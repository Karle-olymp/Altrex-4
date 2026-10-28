package com.google.ai.edge.gallery.connectors

/**
 * [ALTREX] Connectors offered in one tap: official remote MCP servers (Streamable HTTP) of each
 * service. Every OAuth entry was checked on 2026-10-28: its server announces dynamic registration
 * and PKCE S256, which is what [McpOAuth] needs. No logos (trademarks): a coloured initial instead.
 */
object ConnectorCatalog {
  enum class Auth {
    /** Public server, nothing to enter. */
    NONE,
    /** Sign-in page of the service (OAuth, [McpOAuth]). */
    OAUTH,
    /** A key or token the user creates on the service, sent as a bearer token. */
    KEY,
  }

  data class Connector(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val url: String,
    val auth: Auth,
    /** Colour of the initial (ARGB). */
    val color: Long,
    /** For [Auth.KEY]: where to create the key, and what it looks like. */
    val keyPage: String = "",
    val keyHint: String = "",
  )

  const val DEV = "Développement"
  const val WORK = "Travail et productivité"
  const val WEB = "Design et sites web"
  const val PAY = "Paiements"
  const val DOCS = "Documentation (sans compte)"

  val ALL: List<Connector> =
    listOf(
      // Development.
      Connector(
        "github", "GitHub", "Dépôts, issues, pull requests et actions", DEV,
        "https://api.githubcopilot.com/mcp/", Auth.KEY, 0xFF24292F,
        keyPage = "https://github.com/settings/personal-access-tokens",
        keyHint = "Jeton d'accès personnel (github_pat_… ou ghp_…)",
      ),
      Connector(
        "huggingface", "Hugging Face", "Modèles, datasets, Spaces et articles", DEV,
        "https://huggingface.co/mcp", Auth.OAUTH, 0xFFFFB000,
      ),
      Connector("sentry", "Sentry", "Erreurs et performances de tes applis", DEV, "https://mcp.sentry.dev/mcp", Auth.OAUTH, 0xFF362D59),
      Connector("supabase", "Supabase", "Bases de données, fonctions et stockage", DEV, "https://mcp.supabase.com/mcp", Auth.OAUTH, 0xFF3ECF8E),
      // Work.
      Connector("linear", "Linear", "Tickets, projets et cycles", WORK, "https://mcp.linear.app/mcp", Auth.OAUTH, 0xFF5E6AD2),
      Connector("notion", "Notion", "Pages, bases et notes", WORK, "https://mcp.notion.com/mcp", Auth.OAUTH, 0xFF191919),
      Connector("atlassian", "Atlassian", "Jira et Confluence", WORK, "https://mcp.atlassian.com/v1/mcp", Auth.OAUTH, 0xFF0052CC),
      Connector("monday", "monday.com", "Tableaux, tâches et projets", WORK, "https://mcp.monday.com/mcp", Auth.OAUTH, 0xFFFF3D57),
      Connector("intercom", "Intercom", "Conversations et support client", WORK, "https://mcp.intercom.com/mcp", Auth.OAUTH, 0xFF1F8DED),
      Connector("zapier", "Zapier", "Des milliers d'applis via tes actions Zapier", WORK, "https://mcp.zapier.com/api/mcp/mcp", Auth.OAUTH, 0xFFFF4F00),
      // Design and web.
      Connector("canva", "Canva", "Designs, modèles et exports", WEB, "https://mcp.canva.com/mcp", Auth.OAUTH, 0xFF00C4CC),
      Connector("webflow", "Webflow", "Sites, pages et contenus CMS", WEB, "https://mcp.webflow.com/mcp", Auth.OAUTH, 0xFF146EF5),
      Connector("wix", "Wix", "Sites, boutique et contenus", WEB, "https://mcp.wix.com/mcp", Auth.OAUTH, 0xFF0C6EFC),
      // Payments.
      Connector("stripe", "Stripe", "Paiements, clients et factures", PAY, "https://mcp.stripe.com", Auth.OAUTH, 0xFF635BFF),
      Connector("paypal", "PayPal", "Factures, commandes et litiges", PAY, "https://mcp.paypal.com/mcp", Auth.OAUTH, 0xFF003087),
      // Public documentation.
      Connector("deepwiki", "DeepWiki", "Explique n'importe quel dépôt GitHub public", DOCS, "https://mcp.deepwiki.com/mcp", Auth.NONE, 0xFF4B5563),
      Connector("context7", "Context7", "Documentation à jour des bibliothèques de code", DOCS, "https://mcp.context7.com/mcp", Auth.NONE, 0xFF059669),
      Connector("cloudflare-docs", "Cloudflare Docs", "Documentation de Cloudflare", DOCS, "https://docs.mcp.cloudflare.com/mcp", Auth.NONE, 0xFFF38020),
    )

  val CATEGORIES: List<String> = listOf(DEV, WORK, WEB, PAY, DOCS)

  fun byId(id: String): Connector? = ALL.find { it.id == id }

  fun byUrl(url: String): Connector? = ALL.find { it.url.trimEnd('/') == url.trimEnd('/') }

  /** The header value sent for a [Auth.KEY] connector: the key as a bearer token. */
  fun bearer(key: String): String {
    val k = key.trim()
    return if (k.startsWith("Bearer ", ignoreCase = true)) k else "Bearer $k"
  }
}
