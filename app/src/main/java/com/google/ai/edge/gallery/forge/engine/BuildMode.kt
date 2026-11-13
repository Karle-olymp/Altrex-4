package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.tools.CommandWait
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ALTREX] The recipe of a Build (docs/forge/FORGE.md §5, §13): what « finished » means, written
 * by the model in the « ## Recette » section of its plan and validated by the user with the plan.
 * Forge checks it itself ([CompletionJudge]): the model's word is never a proof.
 */
data class BuildRecipe(val objective: String, val files: List<String>, val checks: List<String>, val preview: String?) {
  val hasProof: Boolean
    get() = files.isNotEmpty() || checks.isNotEmpty() || preview != null

  fun toJson(): String =
    JSONObject().put("objective", objective).put("files", JSONArray(files)).put("checks", JSONArray(checks)).also { o -> preview?.let { o.put("preview", it) } }.toString()

  /** One line per proof, for the feed and for the model. */
  fun describe(): String =
    buildString {
      append("Recette du Build · objectif : ").append(objective.ifBlank { "(non précisé)" })
      if (files.isNotEmpty()) append("\nFichiers attendus : ").append(files.joinToString(", "))
      if (checks.isNotEmpty()) append("\nVérifications : ").append(checks.joinToString(" ; ") { "`$it`" })
      preview?.let { append("\nAperçu : ").append(it) }
    }

  companion object {
    const val MAX_FILES = 12
    const val MAX_CHECKS = 5
    const val MAX_COMMAND_CHARS = 300

    private val HEADING = Regex("""^(#{1,6}\s*|\*\*)recette\b.*$""", RegexOption.IGNORE_CASE)
    private val KEY = Regex("""^(objectif|fichiers?|v[ée]rifications?|tests?|commandes?|aper[çc]u)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val NONE = setOf("aucun", "aucune", "néant", "neant", "-", "—", "non")
    private val TICKED = Regex("`([^`]+)`")

    fun fromJson(json: String): BuildRecipe? =
      runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        BuildRecipe(o.optString("objective"), list("files"), list("checks"), if (o.has("preview")) o.optString("preview") else null)
      }.getOrNull()

    private fun clean(s: String) = s.trim().trim('`', '"', '\'', '«', '»', ' ', '.').trim()

    private fun isNone(s: String) = clean(s).lowercase().let { it.isEmpty() || it in NONE || it.startsWith("aucun") }

    /** Reads the « ## Recette » section of a plan; null when there is none. */
    fun parse(markdown: String): BuildRecipe? {
      val lines = markdown.lines()
      val start = lines.indexOfFirst { HEADING.matches(it.trim()) }
      if (start < 0) return null
      val values = LinkedHashMap<String, StringBuilder>()
      var key = ""
      for (line in lines.drop(start + 1)) {
        val t = line.trim()
        if (t.startsWith("#")) break
        if (t.isEmpty()) continue
        val item = t.trimStart('-', '*', '•', ' ').replace("**", "").trim()
        val m = KEY.find(item)
        if (m != null) {
          key =
            when (m.groupValues[1].lowercase().take(3)) {
              "obj" -> "objectif"
              "fic" -> "fichiers"
              "ape" -> "apercu"
              else -> "verifications"
            }
          values.getOrPut(key) { StringBuilder() }.append(m.groupValues[2]).append('\n')
        } else if (key.isNotEmpty()) {
          values[key]!!.append(item).append('\n')
        }
      }
      val objective = values["objectif"]?.toString()?.trim()?.lineSequence()?.joinToString(" ")?.take(300).orEmpty()
      val files =
        values["fichiers"]?.toString().orEmpty().split(',', ';', '\n').filterNot { isNone(it) }.map { clean(it) }
          .filter { it.isNotEmpty() && !it.contains(' ') }.distinct().take(MAX_FILES)
      val rawChecks = values["verifications"]?.toString().orEmpty()
      val checks =
        (if (rawChecks.contains('`')) TICKED.findAll(rawChecks).map { it.groupValues[1] }.toList() else rawChecks.split(';', '\n'))
          .map { it.trim() }.filterNot { isNone(it) }.filter { it.length <= MAX_COMMAND_CHARS }.distinct().take(MAX_CHECKS)
      val preview = values["apercu"]?.toString()?.lineSequence()?.firstOrNull()?.takeIf { !isNone(it) }?.let { clean(it).substringBefore(' ') }?.ifEmpty { null }
      return BuildRecipe(objective, files, checks, preview)
    }
  }
}

/**
 * [ALTREX] The completion judge of a Build. Deterministic, run by the app: the files exist and are
 * not empty, the test commands exit with 0 in the project's Linux, the preview page exists with
 * every local file it refers to (or the local server answers). Nothing here asks the model.
 */
class CompletionJudge(private val paths: ProjectPaths, private val shell: ShellPort) {
  data class Proof(val label: String, val ok: Boolean, val detail: String = "")

  data class Verdict(val proofs: List<Proof>) {
    val ok: Boolean
      get() = proofs.isNotEmpty() && proofs.all { it.ok }

    /** What fails, as a stable key: the same key twice means « no progress ». */
    val failing: String
      get() = proofs.filter { !it.ok }.joinToString("|") { it.label }

    fun report(): String = proofs.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.label + (if (it.detail.isNotEmpty()) " : ${it.detail}" else "") }
  }

  suspend fun check(recipe: BuildRecipe, onProgress: (String) -> Unit = {}): Verdict {
    val out = ArrayList<Proof>()
    for (f in recipe.files) out.add(file(f))
    for (c in recipe.checks) {
      onProgress("Build : vérification `${c.take(60)}`…")
      out.add(command(c))
    }
    recipe.preview?.let {
      onProgress("Build : vérification de l'aperçu…")
      out.add(preview(it))
    }
    if (out.isEmpty()) out.add(Proof("recette", false, "aucune preuve vérifiable dans la recette"))
    return Verdict(out)
  }

  private fun file(rel: String): Proof {
    val label = "fichier $rel"
    val r = paths.resolve(rel) ?: return Proof(label, false, "hors du projet")
    return when {
      !r.file.isFile -> Proof(label, false, "absent")
      r.file.length() == 0L -> Proof(label, false, "vide")
      else -> Proof(label, true)
    }
  }

  private suspend fun command(cmd: String): Proof {
    val label = "commande `$cmd`"
    if (PermissionPolicy.isDangerous(cmd)) return Proof(label, false, "commande dangereuse, non lancée")
    shell.ensureReady {}?.let { return Proof(label, false, "Linux indisponible ($it)") }
    val r = shell.run(cmd, CommandWait.Policy(CHECK_IDLE_MS, CHECK_CAP_MS)) {}
    if (r.jobId != null) {
      runCatching { shell.checkJob(r.jobId, stop = true, maxChars = 0) }
      return Proof(label, false, "pas terminée après l'attente (une vérification doit se terminer seule)")
    }
    if (r.timedOut) return Proof(label, false, "délai dépassé")
    return if (r.exitCode == 0) Proof(label, true) else Proof(label, false, "code ${r.exitCode}\n" + r.output.trim().takeLast(TAIL_CHARS))
  }

  private suspend fun preview(target: String): Proof {
    val label = "aperçu $target"
    if (LOCAL_URL.matches(target)) {
      shell.ensureReady {}?.let { return Proof(label, false, "Linux indisponible ($it)") }
      val r = shell.run("wget -q -T 5 -O /dev/null '${target.replace("'", "")}'", CommandWait.Policy(15_000, 20_000)) {}
      return if (r.jobId == null && r.exitCode == 0) Proof(label, true, "le serveur répond") else Proof(label, false, "le serveur local ne répond pas")
    }
    val r = paths.resolve(target) ?: return Proof(label, false, "hors du projet")
    if (!r.file.isFile || r.file.length() == 0L) return Proof(label, false, "page absente ou vide")
    val ext = r.file.extension.lowercase()
    if (ext != "html" && ext != "htm") return Proof(label, true, "fichier présent")
    val html = runCatching { r.file.readText() }.getOrElse { return Proof(label, false, "illisible") }
    if (!html.contains('<')) return Proof(label, false, "ce n'est pas du HTML")
    val dir = r.file.parentFile ?: return Proof(label, false, "illisible")
    val missing = ArrayList<String>()
    var refs = 0
    for (m in REF.findAll(html.take(MAX_HTML_CHARS))) {
      val ref = m.groupValues[1].trim().substringBefore('#').substringBefore('?')
      if (ref.isEmpty() || EXTERNAL.containsMatchIn(ref) || ref.contains('{') || ref.contains('$')) continue
      refs++
      val f = if (ref.startsWith("/")) paths.resolve(ref.trimStart('/'))?.file else File(dir, ref)
      if (f == null || !f.exists()) missing.add(ref)
    }
    return if (missing.isEmpty()) Proof(label, true, "page et $refs fichier(s) liés présents")
    else Proof(label, false, "fichiers liés introuvables : " + missing.distinct().take(6).joinToString(", "))
  }

  companion object {
    const val CHECK_IDLE_MS = 120_000L
    const val CHECK_CAP_MS = 300_000L
    private const val TAIL_CHARS = 700
    private const val MAX_HTML_CHARS = 400_000
    private val LOCAL_URL = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?([/?#].*)?$")
    private val REF = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val EXTERNAL = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*:|//)""")
  }
}

/**
 * [ALTREX] The Build mode's loop around the engine's turns. One user message = one [run]:
 * 1. no validated plan yet: a turn in Plan mode (questions, then the plan with its recipe) ;
 * 2. once the plan is validated the same turn builds ; then, without the user, the judge checks
 *    the recipe and a failed proof starts a correction turn, up to [maxTurns] turns and
 *    [maxTries] corrections of the same failure ;
 * 3. when every proof passes: one read-only turn of proposals, once, and the Build is over.
 * Stop cancels the whole loop (it runs inside the turn's coroutine).
 */
class BuildRunner(
  private val engine: ForgeEngine,
  private val judge: CompletionJudge,
  private val maxTurns: Int = DEFAULT_MAX_TURNS,
  private val maxTries: Int = DEFAULT_MAX_TRIES,
) {
  suspend fun run(request: String): String {
    if (engine.state.value.build?.active != true) engine.openBuild()
    if (engine.state.value.build?.state == ForgeEvent.Build.OPEN && !engine.mode.isPlan) engine.setMode(Preset.PLAN.mode)
    var end = engine.runTurn(request)
    var tour = 1
    var essai = 0
    var lastFailing: String? = null
    while (true) {
      val st = engine.state.value.build ?: return end
      val recipe = st.recipe
      if (st.state == ForgeEvent.Build.OPEN || recipe == null || !st.active) return end
      if (end != "done" && end != "limit") {
        engine.buildEvent(ForgeEvent.Build.PAUSE, tour, essai, "Build en pause (tour non terminé). Écris un message pour le reprendre, ou /build fin pour quitter le mode Build.")
        return end
      }
      val verdict = try {
        judge.check(recipe) { engine.progress(it) }
      } finally {
        engine.progress("")
      }
      if (verdict.ok) {
        engine.buildEvent(ForgeEvent.Build.PROOF_OK, tour, essai, "Build vérifié par Forge (tour $tour) :\n" + verdict.report())
        end = engine.runTurn(ForgePrompts.BUILD_PROPOSALS, auto = true, readOnly = true)
        engine.buildEvent(ForgeEvent.Build.DONE, tour, essai, "Build terminé. Les propositions ci-dessus ne sont pas appliquées : demande celles que tu veux.")
        return end
      }
      essai = if (verdict.failing == lastFailing) essai + 1 else 0
      lastFailing = verdict.failing
      engine.buildEvent(ForgeEvent.Build.PROOF_KO, tour, essai, "Vérification du Build (tour $tour sur $maxTurns) : pas encore terminé.\n" + verdict.report())
      val blocked =
        when {
          essai >= maxTries -> "Build bloqué : la même vérification échoue encore après $maxTries essais de correction."
          tour >= maxTurns -> "Build bloqué : limite de $maxTurns tours atteinte sans que toutes les preuves passent."
          else -> null
        }
      if (blocked != null) {
        engine.buildEvent(ForgeEvent.Build.BLOCKED, tour, essai, "$blocked Dis à Forge comment continuer (ton message relance le Build), ou /build fin.")
        return "blocked"
      }
      tour++
      end = engine.runTurn(ForgePrompts.buildRetry(verdict.report(), tour, maxTurns, essai + 1, maxTries), auto = true)
    }
  }

  companion object {
    const val DEFAULT_MAX_TURNS = 8
    const val DEFAULT_MAX_TRIES = 3
  }
}
