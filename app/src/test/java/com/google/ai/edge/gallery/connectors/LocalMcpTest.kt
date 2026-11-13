package com.google.ai.edge.gallery.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpTest {
  @Test
  fun url_roundTrip() {
    val url = LocalMcp.url("  npx -y rea-agents@latest mcp ")
    assertTrue(LocalMcp.isLocal(url))
    assertEquals("npx -y rea-agents@latest mcp", LocalMcp.command(url))
  }

  @Test
  fun launcherPackages() {
    assertEquals(listOf("nodejs", "npm"), LocalMcp.neededPackages("npx -y x"))
    assertEquals(listOf("uv", "python3"), LocalMcp.neededPackages("uvx mcp-server-fetch"))
    assertEquals(listOf("python3", "py3-pip"), LocalMcp.neededPackages("/usr/bin/python3 -m srv"))
    assertEquals(emptyList<String>(), LocalMcp.neededPackages("./my-server"))
  }

  @Test
  fun catalog_localEntriesLookLikeConnectors() {
    val c = ConnectorCatalog.byUrl(LocalMcp.url(LocalMcp.CATALOG.first().command))
    assertEquals(ConnectorCatalog.Auth.LOCAL, c?.auth)
  }
}
