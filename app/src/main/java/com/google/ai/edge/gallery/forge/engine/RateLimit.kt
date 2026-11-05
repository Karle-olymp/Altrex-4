package com.google.ai.edge.gallery.forge.engine

/**
 * [ALTREX] A cloud provider said « too many requests » (HTTP 429: the free plans of Groq,
 * OpenRouter, Gemini… allow a few requests or tokens per minute). [retryAfterMs] is the wait it
 * asked for (Retry-After header, or « try again in 7.5s » in its message), null when it did not say.
 */
class RateLimitedException(message: String, val retryAfterMs: Long?, val provider: String) : Exception(message)

/**
 * [ALTREX] Forge's answer to a rate limit: wait and try again, [MAX_RETRIES] times at most, the
 * wait shown in the feed and cut short by Stop. The provider's wait when it gives one, else an
 * exponential backoff (2, 4, 8 s); never more than [MAX_WAIT_MS] (a longer wait means a daily
 * quota: no point in waiting, the turn ends with a clear message).
 */
object RateLimit {
  const val MAX_RETRIES = 3
  const val MAX_WAIT_MS = 90_000L
  private const val MIN_WAIT_MS = 1_000L

  /** The wait before retry number [attempt] (1, 2, 3), or null when it is too long to wait. */
  fun delayMs(retryAfterMs: Long?, attempt: Int): Long? {
    if (retryAfterMs != null && retryAfterMs > MAX_WAIT_MS) return null
    val backoff = 2_000L shl (attempt - 1).coerceIn(0, 5)
    // The provider's wait, plus a little margin; never less than the backoff for later attempts.
    val wait = if (retryAfterMs != null) maxOf(retryAfterMs + 500, if (attempt > 1) backoff else 0) else backoff
    return wait.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
  }

  fun waiting(provider: String, waitMs: Long, attempt: Int): String =
    "Limite du fournisseur ${provider.ifEmpty { "cloud" }}, nouvel essai dans ${(waitMs + 999) / 1000} s (essai ${attempt + 1} sur ${MAX_RETRIES + 1}). " +
      "Arrêter pour annuler."

  fun giveUp(provider: String, retryAfterMs: Long?): String =
    if (retryAfterMs != null && retryAfterMs > MAX_WAIT_MS) {
      "Limite du fournisseur ${provider.ifEmpty { "cloud" }} : il demande d'attendre ${retryAfterMs / 60_000 + 1} min ou plus (quota de l'offre gratuite épuisé ?). " +
        "Réessaie plus tard ou choisis un autre modèle."
    } else {
      "Limite du fournisseur ${provider.ifEmpty { "cloud" }} toujours atteinte après $MAX_RETRIES nouveaux essais. " +
        "Attends une minute puis renvoie ta demande, ou choisis un autre modèle."
    }

  /**
   * The wait a 429 answer asks for: the Retry-After header (seconds, or an HTTP date), else a
   * « try again in 7.5s / 450ms / 1m30s » in the body (Groq, OpenAI). Null when neither says.
   */
  fun parseRetryAfter(header: String?, body: String, now: Long = System.currentTimeMillis()): Long? {
    header?.trim()?.takeIf { it.isNotEmpty() }?.let { h ->
      h.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
      runCatching {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        return (fmt.parse(h)!!.time - now).coerceAtLeast(0)
      }
    }
    val m = Regex("""(?i)(?:try again in|retry in|retry after|réessaie dans)\s*((?:\d+(?:\.\d+)?\s*(?:ms|h|m|s)\s*)+)""").find(body) ?: return null
    var total = 0.0
    for (part in Regex("""(\d+(?:\.\d+)?)\s*(ms|h|m|s)""").findAll(m.groupValues[1])) {
      val v = part.groupValues[1].toDouble()
      total +=
        when (part.groupValues[2].lowercase()) {
          "ms" -> v
          "s" -> v * 1000
          "m" -> v * 60_000
          else -> v * 3_600_000
        }
    }
    return total.toLong()
  }
}
