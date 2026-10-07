package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context

/**
 * Result of a tool: text sent back to the model + a line shown to the user. When [endTurn] is true
 * the agent stops there and shows [log] as the answer (no further model generation needed).
 */
data class ToolOutcome(val result: String, val log: String, val endTurn: Boolean = false)

/** Project file tools exposed to the model (list, read, write, edit lines, delete). */
object CodeTools {
  val specs: List<AgentToolSpec> =
    listOf(
      AgentToolSpec(
        name = "list_dir",
        description = "Liste les fichiers et dossiers d'un dossier du projet. Le point . désigne la racine.",
        params = listOf(AgentToolParam("path", "string", "Chemin relatif du dossier, ou . pour la racine")),
      ),
      AgentToolSpec(
        name = "read_file",
        description = "Lit un fichier du projet. Chaque ligne est préfixée par son numéro.",
        params = listOf(AgentToolParam("path", "string", "Chemin relatif du fichier")),
      ),
      AgentToolSpec(
        name = "write_file",
        description = "Crée un fichier, ou remplace tout son contenu.",
        params =
          listOf(
            AgentToolParam("path", "string", "Chemin relatif du fichier"),
            AgentToolParam("content", "string", "Contenu COMPLET du fichier, sans numéros de ligne"),
          ),
      ),
      AgentToolSpec(
        name = "replace_lines",
        description =
          "Remplace les lignes start_line à end_line (incluses) d'un fichier par new_text. " +
            "Pour insérer sans rien supprimer, mets end_line = start_line - 1. " +
            "Un new_text vide supprime les lignes.",
        params =
          listOf(
            AgentToolParam("path", "string", "Chemin relatif du fichier"),
            AgentToolParam("start_line", "integer", "Première ligne à remplacer (à partir de 1)"),
            AgentToolParam("end_line", "integer", "Dernière ligne à remplacer"),
            AgentToolParam("new_text", "string", "Nouveau texte, sans numéros de ligne"),
          ),
      ),
      AgentToolSpec(
        name = "delete_file",
        description = "Supprime un fichier du projet (l'utilisateur pourra annuler).",
        params = listOf(AgentToolParam("path", "string", "Chemin relatif du fichier")),
      ),
    )

  private fun cleanPath(raw: String): String {
    var p = raw.trim()
    for (prefix in listOf("file:", "文件:", "fichier:", "path:")) {
      if (p.startsWith(prefix, ignoreCase = true)) p = p.substring(prefix.length).trim()
    }
    return p
  }

  private fun rootListing(context: Context): String {
    val root = FileActions.listDir(context, "") ?: return ""
    return "\nRacine du projet :\n" + root.joinToString("\n")
  }

  private fun fail(message: String, log: String) = ToolOutcome("Erreur : $message", "✗ $log")

  fun execute(context: Context, call: AgentToolCall, readCap: Int): ToolOutcome {
    if (call.name.isEmpty()) {
      return fail(
        "appel d'outil invalide (JSON incorrect). Réécris-le exactement au format demandé.",
        "Appel d'outil invalide",
      )
    }
    return try {
      val args = call.arguments
      when (call.name) {
        "list_dir" -> {
          val path = FileActions.normalizeDir(cleanPath(args.optString("path", "."))) ?: return fail("chemin invalide", "list_dir : chemin invalide")
          val names = FileActions.listDir(context, path)
          val shown = if (path.isEmpty()) "(racine)" else path
          if (names == null) {
            fail("dossier introuvable : $shown.${rootListing(context)}", "Dossier introuvable : $shown")
          } else {
            ToolOutcome(
              "Contenu de $shown :\n" + names.joinToString("\n"),
              "📂 Listé : $shown (${names.size} éléments)",
            )
          }
        }
        "read_file" -> {
          val path = FileActions.normalize(cleanPath(args.optString("path", ""))) ?: return fail("chemin invalide", "read_file : chemin invalide")
          var realPath = path
          var text = FileActions.readNumbered(context, realPath, readCap)
          if (text == null) {
            val alt = ProjectContextBuilder.findByName(context, path.substringAfterLast('/'))
            if (alt != null) {
              realPath = alt
              text = FileActions.readNumbered(context, realPath, readCap)
            }
          }
          if (text == null) {
            fail("fichier introuvable : $path.${rootListing(context)}", "Introuvable : $path")
          } else {
            ToolOutcome("Contenu de $realPath :\n$text", "📄 Lu : $realPath")
          }
        }
        "write_file" -> {
          val path = FileActions.normalize(cleanPath(args.optString("path", ""))) ?: return fail("chemin invalide", "write_file : chemin invalide")
          val content = args.optString("content", "")
          if (content.isBlank() && FileActions.exists(context, path)) {
            return fail(
              "contenu vide refusé pour un fichier existant : fournis le contenu complet, ou utilise delete_file.",
              "$path : contenu vide refusé",
            )
          }
          val r = FileActions.apply(context, FileAction(path, FileActions.cleanCode(content)))
          if (r.ok) {
            ToolOutcome("OK : ${r.message.lowercase()} : $path", "✓ ${r.message} : $path")
          } else {
            fail(r.message, "$path : ${r.message}")
          }
        }
        "replace_lines" -> {
          val path = FileActions.normalize(cleanPath(args.optString("path", ""))) ?: return fail("chemin invalide", "replace_lines : chemin invalide")
          val start = args.optInt("start_line", Int.MIN_VALUE)
          val end = args.optInt("end_line", Int.MIN_VALUE)
          if (start == Int.MIN_VALUE || end == Int.MIN_VALUE) {
            return fail("start_line et end_line sont obligatoires (entiers).", "replace_lines : paramètres manquants")
          }
          val r = FileActions.replaceLines(context, path, start, end, args.optString("new_text", ""))
          if (r.ok) {
            ToolOutcome(
              "OK : lignes $start à $end modifiées dans $path (relis le fichier si tu dois encore le modifier).",
              "✓ Modifié : $path (lignes $start à $end)",
            )
          } else {
            fail(r.message, "$path : ${r.message}")
          }
        }
        "delete_file" -> {
          val path = FileActions.normalize(cleanPath(args.optString("path", ""))) ?: return fail("chemin invalide", "delete_file : chemin invalide")
          val r = FileActions.delete(context, path)
          if (r.ok) {
            ToolOutcome("OK : supprimé : $path", "🗑 Supprimé : $path (annulable avec ↩)")
          } else {
            fail(r.message, "$path : ${r.message}")
          }
        }
        else ->
          fail(
            "outil inconnu « ${call.name} ». Outils disponibles : list_dir, read_file, write_file, replace_lines, delete_file.",
            "Outil inconnu : ${call.name}",
          )
      }
    } catch (e: Exception) {
      fail(e.message ?: "erreur inconnue", "${call.name} : ${e.message}")
    }
  }
}
