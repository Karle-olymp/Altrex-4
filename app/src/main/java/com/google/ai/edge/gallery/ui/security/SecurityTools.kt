package com.google.ai.edge.gallery.ui.security

import android.content.Context
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentProfile
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolCall
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolParam
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolSpec
import com.google.ai.edge.gallery.ui.llmsingleturn.CodeTools
import com.google.ai.edge.gallery.ui.llmsingleturn.ToolOutcome

/**
 * Tools of the "Sécurité" space. Read-only on purpose: the model can list and read project files and
 * run a SecurCode scan, but it can never write or delete anything.
 */
object SecurityTools {
  private val READ_ONLY_FROM_CODE = setOf("list_dir", "read_file")
  private val WRITE_TOOLS = setOf("write_file", "replace_lines", "delete_file")

  val specs: List<AgentToolSpec> =
    CodeTools.specs.filter { it.name in READ_ONLY_FROM_CODE } +
      AgentToolSpec(
        name = "securcode_scan",
        description =
          "Analyse la sécurité d'un dossier, d'un fichier .zip ou d'un fichier du projet avec " +
            "SecurCode (secrets, dépendances vulnérables, failles). Le code est envoyé à " +
            "SecurCode : l'utilisateur doit l'avoir autorisé. Chaque scan consomme 1 des 20 " +
            "analyses quotidiennes. Pour voir la suite des failles du dernier scan, rappelle " +
            "l'outil avec la même cible et page=2, 3... (sans refaire de scan).",
        params =
          listOf(
            AgentToolParam(
              "path",
              "string",
              "Chemin relatif du dossier, du .zip ou du fichier à analyser, ou . pour tout le projet",
            ),
            AgentToolParam(
              "page",
              "integer",
              "Page de résultats (10 failles par page). 1 par défaut = nouveau scan",
              required = false,
            ),
          ),
      )

  fun execute(context: Context, call: AgentToolCall, readCap: Int): ToolOutcome {
    return when {
      call.name == "securcode_scan" ->
        try {
          SecurCodeClient.scan(context, call.arguments)
        } catch (e: Exception) {
          ToolOutcome(
            "Erreur : ${e.message ?: "erreur inconnue"}",
            "✗ securcode_scan : ${e.message ?: "erreur inconnue"}",
          )
        }
      call.name in READ_ONLY_FROM_CODE -> CodeTools.execute(context, call, readCap)
      call.name in WRITE_TOOLS ->
        ToolOutcome(
          "Erreur : l'espace Sécurité est en lecture seule, l'outil ${call.name} n'est pas disponible.",
          "✗ ${call.name} : lecture seule dans l'espace Sécurité",
        )
      call.name.isEmpty() ->
        ToolOutcome(
          "Erreur : appel d'outil invalide (JSON incorrect). Réécris-le exactement au format demandé.",
          "✗ Appel d'outil invalide",
        )
      else ->
        ToolOutcome(
          "Erreur : outil inconnu « ${call.name} ». Outils disponibles : list_dir, read_file, securcode_scan.",
          "✗ Outil inconnu : ${call.name}",
        )
    }
  }
}

object SecurityAgent {
  private fun instructions(openedFileName: String?): String {
    val opened =
      if (openedFileName != null) {
        " Fichier ouvert dans l'éditeur : « $openedFileName » (son contenu est dans le message)."
      } else {
        ""
      }
    return "Tu es un assistant de sécurité défensive intégré à Altrex. Réponds toujours en " +
      "français. Tu es en lecture seule : tu peux lister et lire les fichiers du projet, et " +
      "analyser un dossier, un zip ou un fichier avec l'outil securcode_scan. Tu ne modifies " +
      "jamais rien. Pour analyser la sécurité d'un projet, appelle securcode_scan : ne devine " +
      "jamais des failles sans rapport. Distingue clairement les faits (ce que dit le rapport) " +
      "des hypothèses ; les failles XSS et AI_DEEP sont des pistes à vérifier, pas des " +
      "certitudes. Ne prétends jamais avoir analysé ou lu quelque chose si l'outil n'a pas " +
      "renvoyé de résultat. Si un outil répond par une erreur, explique-la simplement à " +
      "l'utilisateur. Réponds en 6 phrases au maximum.$opened"
  }

  val profile =
    AgentProfile(
      id = "security",
      specs = SecurityTools.specs,
      instructions = { name -> instructions(name) },
      execute = { context, call, cap -> SecurityTools.execute(context, call, cap) },
      noToolHint = Regex("scan|analys|audit|vérifi|verifi|zip|faille|sécurit|securit", RegexOption.IGNORE_CASE),
    )
}
