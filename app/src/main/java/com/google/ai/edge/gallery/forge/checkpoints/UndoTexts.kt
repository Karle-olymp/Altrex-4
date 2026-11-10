package com.google.ai.edge.gallery.forge.checkpoints

/** [ALTREX] What /undo tells the user (feed) and the agent (its next request). */
object UndoTexts {
  fun forUser(turn: Int, r: Checkpoints.UndoResult): String =
    buildString {
      append("Tour ").append(turn).append(" annulé : ").append(r.restored.size).append(" fichier(s) remis comme avant")
      if (r.restored.isNotEmpty()) append(" (").append(r.restored.take(8).joinToString(", ")).append(if (r.restored.size > 8) ", …)" else ")")
      append('.')
      if (r.skipped.isNotEmpty()) {
        append("\nLaissés tels quels, car tu les as modifiés depuis : ").append(r.skipped.take(8).joinToString(", ")).append('.')
      }
      append("\nUn instantané de l'état d'avant l'annulation est gardé (/checkpoints).")
    }

  fun forModel(turn: Int, r: Checkpoints.UndoResult): String =
    "<rappel-forge>L'utilisateur a annulé les modifications de ton tour $turn (/undo)" +
      (if (r.restored.isNotEmpty()) " : ${r.restored.take(12).joinToString(", ")} remis comme avant" else "") +
      (if (r.skipped.isNotEmpty()) " ; laissés tels quels car modifiés par lui depuis : ${r.skipped.take(12).joinToString(", ")}" else "") +
      ". Relis ces fichiers avant de les modifier.</rappel-forge>"
}
