package com.google.ai.edge.gallery.forge.workspace

import com.google.ai.edge.gallery.forge.engine.Preset

/**
 * [ALTREX] Forge's `/` commands (step 1: the basic ones). Parsed here, run by the screen. A
 * message that starts with "/" but names no command is not sent to the model: the user gets the
 * list instead (a typo must not become a request).
 */
object ForgeCommands {
  data class Command(val name: String, val usage: String, val help: String)

  val ALL =
    listOf(
      Command("aide", "/aide", "Liste des commandes."),
      Command("init", "/init", "L'agent explore le projet et écrit son ALTREX.md (mémoire du projet)."),
      Command("doctor", "/doctor", "Vérifie le Linux, les outils (git, node, python…) et le modèle."),
      Command("plan", "/plan [demande]", "Passe en mode Plan (lecture seule), et envoie la demande s'il y en a une."),
      Command("mode", "/mode demander|auto|plan|tout", "Change de mode de travail."),
      Command("scan", "/scan [page]", "Analyse de sécurité du projet avec SecurCode (après ton accord)."),
      Command("apercu", "/apercu", "Aperçu d'une page du projet ou d'un serveur local."),
      Command("nouvelle", "/nouvelle", "Nouvelle session dans ce projet."),
      Command("sessions", "/sessions", "Retour à l'accueil de Forge (projets et sessions)."),
      Command("confiance", "/confiance", "Fait confiance à ce projet : son ALTREX.md sera lu."),
      Command("limites", "/limites", "Rappelle les limites d'un tour (étapes, jetons, durée)."),
      Command("arreter", "/arreter", "Arrête le tour en cours."),
    )

  sealed class Parsed {
    data class Run(val name: String, val arg: String) : Parsed()

    data class Unknown(val name: String) : Parsed()
  }

  /** Null when [text] is not a command (it goes to the model as it is). */
  fun parse(text: String): Parsed? {
    val t = text.trim()
    if (!t.startsWith("/") || t.length < 2 || t[1].isWhitespace() || t.startsWith("//")) return null
    val name = t.drop(1).substringBefore(' ').substringBefore('\n').lowercase()
    val arg = t.drop(1 + name.length).trim()
    val canonical = ALIASES[name] ?: name
    return if (ALL.any { it.name == canonical }) Parsed.Run(canonical, arg) else Parsed.Unknown(name)
  }

  private val ALIASES =
    mapOf(
      "help" to "aide",
      "aperçu" to "apercu",
      "preview" to "apercu",
      "new" to "nouvelle",
      "clear" to "nouvelle",
      "stop" to "arreter",
      "arrêter" to "arreter",
      "trust" to "confiance",
    )

  /** Commands whose name starts with what follows "/" (suggestions above the input). */
  fun suggestions(text: String): List<Command> {
    val t = text.trim()
    if (!t.startsWith("/") || t.contains(' ')) return emptyList()
    val prefix = t.drop(1).lowercase()
    return ALL.filter { it.name.startsWith(prefix) }
  }

  /** `/mode` argument → preset. */
  fun presetFor(arg: String): Preset? =
    when (arg.trim().lowercase()) {
      "demander", "ask" -> Preset.DEMANDER
      "auto", "editions", "éditions", "editions-auto", "éditions-auto" -> Preset.EDITIONS_AUTO
      "plan" -> Preset.PLAN
      "tout", "tout-autoriser", "all" -> Preset.TOUT_AUTORISER
      else -> null
    }

  fun helpText(): String =
    "Commandes de Forge :\n" + ALL.joinToString("\n") { "- `${it.usage}` : ${it.help}" }

  /** The request /init sends (FROZEN, docs/forge/OUTILS.md). */
  const val INIT_PROMPT =
    "Explore ce projet (structure, langages, commandes pour lancer et tester, conventions) avec Glob, Grep et Read. " +
      "Puis écris à la racine un fichier ALTREX.md court (40 lignes au plus), en français : à quoi sert le projet, " +
      "comment le lancer et le tester, l'organisation des dossiers, les conventions à respecter, les pièges connus. " +
      "S'il existe déjà, lis-le et complète-le au lieu de l'écraser. Ne modifie aucun autre fichier."
}
