package com.google.ai.edge.gallery.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpOAuthTest {
  @Test
  fun challengeParam_readsRealChallenges() {
    // Linear's challenge, as served.
    val linear =
      "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.linear.app/.well-known/oauth-protected-resource/mcp\", scope=\"read write\""
    assertEquals(
      "https://mcp.linear.app/.well-known/oauth-protected-resource/mcp",
      McpOAuth.challengeParam(linear, "resource_metadata"),
    )
    assertEquals("read write", McpOAuth.challengeParam(linear, "scope"))
    // Atlassian's: no resource_metadata.
    assertNull(
      McpOAuth.challengeParam("Bearer realm=\"OAuth\", error=\"invalid_token\"", "resource_metadata")
    )
    // Unquoted value.
    assertEquals("x", McpOAuth.challengeParam("Bearer scope=x, realm=r", "scope"))
  }

  @Test
  fun origin_keepsSchemeHostPort() {
    assertEquals("https://mcp.notion.com", McpOAuth.origin("https://mcp.notion.com/mcp"))
    assertEquals("https://h.example:8443", McpOAuth.origin("https://h.example:8443/a/b"))
  }
}
