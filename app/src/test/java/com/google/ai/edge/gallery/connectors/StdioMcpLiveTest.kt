package com.google.ai.edge.gallery.connectors

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A real MCP server over stdio, wired exactly like [LocalMcp.start] (stderr apart). Needs node and
 * the network: runs only with MCP_STDIO_TEST=1.
 */
class StdioMcpLiveTest {
  @Test
  fun listsToolsOfARealServer() = runBlocking {
    assumeTrue(System.getenv("MCP_STDIO_TEST") == "1")
    val process =
      ProcessBuilder("sh", "-c", "exec npx -y @modelcontextprotocol/server-memory").redirectErrorStream(false).start()
    try {
      val transport =
        StdioClientTransport(
          input = process.inputStream.asSource().buffered(),
          output = process.outputStream.asSink().buffered(),
          error = process.errorStream.asSource().buffered(),
        )
      val client = Client(clientInfo = Implementation(name = "Altrex", version = "test"))
      val tools = withTimeout(120_000) {
        client.connect(transport)
        client.listTools()?.tools.orEmpty()
      }
      println("tools: " + tools.joinToString { it.name + "(ro=" + it.annotations?.readOnlyHint + ")" })
      assertTrue(tools.any { it.name == "create_entities" })
      client.close()
    } finally {
      process.destroy()
    }
  }
}
