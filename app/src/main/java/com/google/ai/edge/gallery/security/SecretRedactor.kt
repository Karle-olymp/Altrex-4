package com.google.ai.edge.gallery.security

/**
 * [ALTREX] Masks secrets (passwords, tokens, API keys, private keys) in text that leaves the app or
 * is written to logs: tool logs, web search queries chosen by the model.
 *
 * It is not applied to what the user shows to the model on purpose (a config to audit, a file
 * with an exposed key): finding those secrets is one of Argora's jobs.
 */
object SecretRedactor {
  private const val MASK = "[secret masqué]"

  private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> =
    listOf(
      // Private key blocks.
      Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to { _ -> MASK },
      // user:password@ in a URL.
      Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@") to { m -> m.groupValues[1] + "$MASK@" },
      // Authorization headers.
      Regex("(?i)\\b(authorization|proxy-authorization)\\s*[:=]\\s*(bearer|basic|token)?\\s*[^\\s,;\"']+") to
        { m -> m.groupValues[1] + ": " + MASK },
      Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{12,}") to { _ -> "Bearer $MASK" },
      // Known token formats.
      Regex("\\bhf_[A-Za-z0-9]{20,}\\b") to { _ -> MASK }, // Hugging Face
      Regex("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b") to { _ -> MASK }, // GitHub
      Regex("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b") to { _ -> MASK },
      Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b") to { _ -> MASK }, // OpenAI-style
      Regex("\\b(AKIA|ASIA)[A-Z0-9]{16}\\b") to { _ -> MASK }, // AWS access key id
      Regex("\\bAIza[0-9A-Za-z_-]{35}\\b") to { _ -> MASK }, // Google API key
      Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b") to { _ -> MASK }, // Slack
      Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b") to { _ -> MASK }, // JWT
      // key=value with a sensitive name (query strings, configs, JSON).
      Regex(
        "(?i)([\"']?\\b(?:password|passwd|pwd|secret|token|access_token|refresh_token|api_?key|" +
          "apikey|client_secret|private_key|session(?:id)?|cookie|signature|sig|auth)\\b[\"']?\\s*[:=]\\s*[\"']?)" +
          "([^\\s&\"',;}]{4,})"
      ) to { m -> m.groupValues[1] + MASK },
    )

  fun redact(text: String): String {
    var out = text
    for ((regex, replace) in PATTERNS) out = regex.replace(out, replace)
    return out
  }

  /** True when [text] contains something that looks like a secret. */
  fun containsSecret(text: String): Boolean = redact(text) != text
}
