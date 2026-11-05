package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.proto.McpPermissionPolicy.MCP_PERMISSION_ALLOW_ALL
import com.google.ai.edge.gallery.proto.McpPermissionPolicy.MCP_PERMISSION_ASK_ALL
import com.google.ai.edge.gallery.proto.McpPermissionPolicy.MCP_PERMISSION_READS_FREE
import com.google.ai.edge.gallery.proto.McpTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpApprovalTest {
  private fun tool(readOnly: Boolean = false, destructive: Boolean = false, always: Boolean = false) =
    McpTool.newBuilder().setName("t").setReadOnly(readOnly).setDestructive(destructive).setAlwaysAllow(always).build()

  @Test
  fun readsFree_readsRunActionsAsk() {
    assertFalse(RunMcpTool.needsApproval(MCP_PERMISSION_READS_FREE, tool(readOnly = true)))
    assertTrue(RunMcpTool.needsApproval(MCP_PERMISSION_READS_FREE, tool()))
  }

  @Test
  fun askAll_asksEvenForReads() {
    assertTrue(RunMcpTool.needsApproval(MCP_PERMISSION_ASK_ALL, tool(readOnly = true)))
  }

  @Test
  fun allowAll_stillConfirmsDestructive() {
    assertFalse(RunMcpTool.needsApproval(MCP_PERMISSION_ALLOW_ALL, tool()))
    assertTrue(RunMcpTool.needsApproval(MCP_PERMISSION_ALLOW_ALL, tool(destructive = true)))
  }

  @Test
  fun alwaysAllowOnTheToolWins_unknownToolAsks() {
    assertFalse(RunMcpTool.needsApproval(MCP_PERMISSION_ASK_ALL, tool(destructive = true, always = true)))
    assertTrue(RunMcpTool.needsApproval(MCP_PERMISSION_ALLOW_ALL, null))
  }
}
