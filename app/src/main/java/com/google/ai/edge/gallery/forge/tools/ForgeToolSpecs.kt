package com.google.ai.edge.gallery.forge.tools

import com.google.ai.edge.gallery.agent.AgentToolParam
import com.google.ai.edge.gallery.agent.AgentToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ALTREX] Forge's tools as the model sees them. FROZEN format (docs/forge/OUTILS.md): names,
 * parameters and order are the ones the Argora dataset is written with. The names are Claude
 * Code's (a public interface convention); descriptions and behaviour are Altrex's own.
 *
 * The list never changes between modes (Plan included): the restrictions are applied when a tool
 * runs, with a reason the model reads, so the prompt stays the same (and cached).
 */
object ForgeToolSpecs {
  const val READ = "Read"
  const val WRITE = "Write"
  const val EDIT = "Edit"
  const val GLOB = "Glob"
  const val GREP = "Grep"
  const val BASH = "Bash"
  const val BASH_OUTPUT = "BashOutput"
  const val KILL_SHELL = "KillShell"
  const val TODO_WRITE = "TodoWrite"
  const val EXIT_PLAN_MODE = "ExitPlanMode"
  const val ASK_USER_QUESTION = "AskUserQuestion"

  private const val TODO_ITEM_SCHEMA =
    """{"type":"object","properties":{"content":{"type":"string","description":"La tâche, à l'impératif."},"status":{"type":"string","enum":["pending","in_progress","completed"]},"activeForm":{"type":"string","description":"La même tâche en cours, ex. « Écriture des tests »."}},"required":["content","status"]}"""

  val specs: List<AgentToolSpec> =
    listOf(
      AgentToolSpec(
        READ,
        "Lit un fichier texte du projet, avec les numéros de ligne. Lis toujours un fichier avant de le modifier.",
        listOf(
          AgentToolParam("file_path", "string", "Chemin du fichier, relatif au projet (src/app.js) ou absolu sous /root/projets/<projet>."),
          AgentToolParam("offset", "integer", "Première ligne à lire (1 par défaut).", required = false),
          AgentToolParam("limit", "integer", "Nombre de lignes à lire.", required = false),
        ),
      ),
      AgentToolSpec(
        WRITE,
        "Crée un fichier ou remplace tout son contenu. Un fichier existant doit d'abord être lu avec Read. Pour un petit changement, préfère Edit.",
        listOf(
          AgentToolParam("file_path", "string", "Chemin du fichier dans le projet."),
          AgentToolParam("content", "string", "Le contenu complet du fichier."),
        ),
      ),
      AgentToolSpec(
        EDIT,
        "Remplace un passage exact d'un fichier déjà lu. old_string est copié tel quel depuis Read (sans les numéros de ligne) et doit n'apparaître qu'une fois, sauf avec replace_all.",
        listOf(
          AgentToolParam("file_path", "string", "Chemin du fichier dans le projet."),
          AgentToolParam("old_string", "string", "Le texte exact à remplacer."),
          AgentToolParam("new_string", "string", "Le nouveau texte."),
          AgentToolParam("replace_all", "boolean", "Remplacer toutes les occurrences (false par défaut).", required = false),
        ),
      ),
      AgentToolSpec(
        GLOB,
        "Trouve des fichiers du projet par motif (**/*.ts, src/**/*.css, *.md). Les plus récents d'abord.",
        listOf(
          AgentToolParam("pattern", "string", "Le motif. Sans « / », il porte sur le nom du fichier à toute profondeur."),
          AgentToolParam("path", "string", "Dossier où chercher (la racine du projet par défaut).", required = false),
        ),
      ),
      AgentToolSpec(
        GREP,
        "Cherche une expression régulière dans les fichiers du projet.",
        listOf(
          AgentToolParam("pattern", "string", "L'expression régulière (syntaxe Java)."),
          AgentToolParam("path", "string", "Fichier ou dossier où chercher (la racine par défaut).", required = false),
          AgentToolParam("glob", "string", "Ne garder que ces fichiers, ex. *.js ou src/**/*.ts.", required = false),
          AgentToolParam(
            "output_mode",
            "string",
            "files_with_matches (par défaut : les fichiers), content (les lignes) ou count (le nombre par fichier).",
            required = false,
            enumValues = listOf("files_with_matches", "content", "count"),
          ),
          AgentToolParam("case_insensitive", "boolean", "Ignorer la casse.", required = false),
          AgentToolParam("context", "integer", "Lignes de contexte autour de chaque résultat (mode content, 10 max).", required = false),
          AgentToolParam("head_limit", "integer", "Nombre maximal de résultats (100 par défaut).", required = false),
        ),
      ),
      AgentToolSpec(
        BASH,
        "Lance une commande shell (sh) dans le Linux d'Altrex (Alpine), depuis le dossier du projet. Le dossier courant et les variables exportées sont gardés d'une commande à l'autre. Pour un serveur ou une tâche longue (installation, compilation), mets run_in_background à true puis lis la sortie avec BashOutput. Une commande restée trop longtemps sans sortie passe d'elle-même en arrière-plan (job_id dans le résultat).",
        listOf(
          AgentToolParam("command", "string", "La commande."),
          AgentToolParam("timeout", "integer", "Secondes sans nouvelle sortie avant de passer la commande en arrière-plan, 600 au plus (120 par défaut, 300 pour une installation, 20 pour un serveur).", required = false),
          AgentToolParam("run_in_background", "boolean", "true pour un serveur ou une tâche longue : renvoie tout de suite un job_id.", required = false),
          AgentToolParam("description", "string", "Ce que fait la commande, en quelques mots.", required = false),
          AgentToolParam("justification", "string", "Seulement pour redemander une commande refusée : pourquoi elle est nécessaire.", required = false),
        ),
      ),
      AgentToolSpec(
        BASH_OUTPUT,
        "Lit la nouvelle sortie d'une tâche de fond lancée par Bash, et dit si elle tourne encore.",
        listOf(AgentToolParam("job_id", "string", "Le job_id donné par Bash.")),
      ),
      AgentToolSpec(
        KILL_SHELL,
        "Arrête une tâche de fond lancée par Bash.",
        listOf(AgentToolParam("job_id", "string", "Le job_id donné par Bash.")),
      ),
      AgentToolSpec(
        TODO_WRITE,
        "Écrit la liste de tâches complète de la demande en cours ; elle remplace la précédente. Une seule tâche in_progress à la fois ; passe-la en completed dès qu'elle est finie.",
        listOf(AgentToolParam("todos", "array", "Toutes les tâches, dans l'ordre.", itemsSchema = TODO_ITEM_SCHEMA)),
      ),
      AgentToolSpec(
        EXIT_PLAN_MODE,
        "En mode Plan : présente ton plan complet, en Markdown, pour que l'utilisateur le valide. Rien n'est modifié avant sa validation.",
        listOf(AgentToolParam("plan", "string", "Le plan en Markdown : titre, étapes numérotées, fichiers touchés, risques.")),
      ),
      AgentToolSpec(
        ASK_USER_QUESTION,
        "Pose une question courte à l'utilisateur, avec 2 à 4 réponses possibles (il peut aussi répondre librement). Sers-t'en quand un choix lui appartient.",
        listOf(
          AgentToolParam("question", "string", "La question."),
          AgentToolParam("options", "array", "Les réponses proposées, courtes.", itemsSchema = """{"type":"string"}"""),
        ),
      ),
    )

  val names: Set<String> = specs.map { it.name }.toSet()

  /** Exact name from what a model wrote ("read", "todo_write", "bash_output"…), or null. */
  fun canonicalName(raw: String): String? {
    val n = raw.trim().lowercase().replace("_", "").replace("-", "")
    return specs.firstOrNull { it.name.lowercase() == n }?.name
      ?: when (n) {
        "readfile" -> READ
        "writefile" -> WRITE
        "editfile" -> EDIT
        "runcommand", "shell" -> BASH
        "checkjob" -> BASH_OUTPUT
        "todo", "todos" -> TODO_WRITE
        else -> null
      }
  }

  // ------------------------------------------------------------------------------------- args

  /** First non-empty string among [names]. */
  fun str(args: JSONObject, vararg names: String): String {
    for (n in names) {
      val v = args.opt(n)
      if (v is String && v.isNotEmpty()) return v
      if (v != null && v != JSONObject.NULL && v !is String) return v.toString()
    }
    return ""
  }

  /** Text that may be empty (new_string, content). */
  fun text(args: JSONObject, vararg names: String): String? {
    for (n in names) if (args.has(n) && args.opt(n) != JSONObject.NULL) return args.opt(n)?.toString()
    return null
  }

  fun int(args: JSONObject, vararg names: String): Int? {
    for (n in names) {
      val v = args.opt(n) ?: continue
      when (v) {
        is Number -> return v.toInt()
        is String -> v.trim().toIntOrNull()?.let { return it }
      }
    }
    return null
  }

  fun bool(args: JSONObject, vararg names: String): Boolean {
    for (n in names) {
      when (val v = args.opt(n)) {
        is Boolean -> return v
        is String -> if (v.equals("true", true)) return true
      }
    }
    return false
  }

  /** An array argument, also accepted as a JSON text (small models often send a string). */
  fun array(args: JSONObject, vararg names: String): JSONArray? {
    for (n in names) {
      when (val v = args.opt(n)) {
        is JSONArray -> return v
        is String -> runCatching { JSONArray(v.trim()) }.getOrNull()?.let { return it }
      }
    }
    return null
  }
}
