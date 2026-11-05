package com.google.ai.edge.gallery.tools

/**
 * [ALTREX] How long a foreground command is waited for (Forge's Bash).
 *
 * Not a fixed total delay any more: a command is followed as long as it writes something. When it
 * stays silent for [Policy.idleMs] (longer for installs and builds, shorter for servers), or past
 * the very large [Policy.capMs], it is not killed: it goes on as a background job and the model
 * gets its output so far and the job id (BashOutput to follow it, KillShell to stop it).
 */
object CommandWait {
  data class Policy(val idleMs: Long, val capMs: Long)

  enum class Verdict { WAIT, IDLE, CAP }

  const val DEFAULT_IDLE_S = 120
  /** apk, npm, pip, git clone, make…: minutes of silence are normal on a phone (download, unpack). */
  const val SLOW_IDLE_S = 300
  /** A server says it is listening, then nothing more: no need to wait two minutes for it. */
  const val SERVER_IDLE_S = 20
  const val MAX_IDLE_S = 600
  const val CAP_MS = 30 * 60_000L

  /** Installs and builds that take minutes on a phone. */
  private val SLOW =
    Regex(
      """(^|[;&|(]\s*|\bsudo\s+)(altrex-setup|pkg\s+(install|upgrade|update)|apk\s+(add|upgrade|update|fix)|apt(-get)?\s+(install|upgrade|update)|npm\s+(i|install|ci|update)\b|pnpm\s|yarn\b|pip3?\s+install|python3?\s+-m\s+pip\s+install|cargo\s+(build|install)|go\s+(build|install|mod)|make\b|cmake\b|ninja\b|gradle|\./gradlew|git\s+(clone|fetch|pull)|npx\s)"""
    )

  /** Servers and watchers: they never end by themselves. */
  private val SERVER =
    Regex(
      """(http\.server|SimpleHTTPServer|\b(npm|pnpm|yarn)\s+(run\s+)?(dev|start|serve|preview|watch)\b|\bvite\b|\bnext\s+(dev|start)|\bserve\b|live-server|http-server|flask\s+run|\buvicorn\b|\bgunicorn\b|\bnodemon\b|php\s+-S|hugo\s+server|jekyll\s+serve|manage\.py\s+runserver|\bnode\s+\S*(server|app|index)\.m?js)"""
    )

  fun isSlow(command: String): Boolean = SLOW.containsMatchIn(command)

  fun isServer(command: String): Boolean = SERVER.containsMatchIn(command)

  /**
   * The wait for [command]: [requestedIdleS] (the model's `timeout`) when given, else by kind of
   * command; [capMs] is the absolute ceiling (the turn's remaining time, 30 min at most).
   */
  fun policy(command: String, requestedIdleS: Int?, capMs: Long = CAP_MS): Policy {
    val idleS =
      when {
        requestedIdleS != null && requestedIdleS > 0 -> requestedIdleS.coerceIn(5, MAX_IDLE_S)
        isServer(command) -> SERVER_IDLE_S
        isSlow(command) -> SLOW_IDLE_S
        else -> DEFAULT_IDLE_S
      }
    return Policy(idleS * 1000L, capMs.coerceIn(10_000L, CAP_MS))
  }

  fun verdict(now: Long, startedAt: Long, lastOutputAt: Long, p: Policy): Verdict =
    when {
      now - startedAt >= p.capMs -> Verdict.CAP
      now - maxOf(lastOutputAt, startedAt) >= p.idleMs -> Verdict.IDLE
      else -> Verdict.WAIT
    }

  /** Commands that run Alpine's package manager (its database lock matters). */
  fun usesApk(command: String): Boolean = Regex("""(^|[\s;&|(])(apk|pkg|apt|apt-get|altrex-setup)(\s|$)""").containsMatchIn(command)
}
