/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent

import com.google.ai.edge.gallery.tools.LoadSkillTool
import com.google.ai.edge.gallery.tools.RunIntentTool
import com.google.ai.edge.gallery.tools.RunJsTool
import com.google.ai.edge.gallery.tools.RunMcpTool
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.gallery.tools.WebSearchTool
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolCall
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolParam
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolSpec
import com.google.gson.Gson
import org.json.JSONObject

/**
 * Bridges the Gallery [ToolDefinition]s (skills, intents, MCP) to the native tool-calling protocol
 * used by local llama.cpp models (ChatML / Hermes format).
 *
 * The LiteRT-LM runtime executes tool calls automatically from the [ToolDefinition] metadata. Local
 * GGUF models run through [com.google.ai.edge.gallery.runtime.LlamaCppModelHelper], which has no
 * such support. This bridge re-declares the Skills tools in the JSON format those models understand
 * and routes each parsed call back to the very same [ToolDefinition] instances, so the behaviour is
 * identical whether the model is a LiteRT-LM one or a local GGUF one.
 */
object LlamaCppToolBridge {
  /** Tool definitions rendered in the system prompt for local models. */
  val specs: List<AgentToolSpec> =
    listOf(
      AgentToolSpec(
        name = "load_skill",
        description =
          "Loads a skill's instructions by name. Always call this before using a skill so you " +
            "learn exactly how to use it.",
        params =
          listOf(
            AgentToolParam("skillName", "string", "The name of the skill to load."),
          ),
      ),
      AgentToolSpec(
        name = "run_js",
        description = "Runs a JavaScript skill script.",
        params =
          listOf(
            AgentToolParam("skillName", "string", "The name of the skill."),
            AgentToolParam(
              "scriptName",
              "string",
              "The script name to run. Use 'index.html' if not provided by the user.",
              required = false,
            ),
            AgentToolParam(
              "data",
              "string",
              "A JSON string to pass to the script. Use an empty string if not provided.",
              required = false,
            ),
          ),
      ),
      AgentToolSpec(
        name = "run_intent",
        description =
          "Run an Android intent. It is used to interact with the app to perform certain actions.",
        params =
          listOf(
            AgentToolParam("intent", "string", "The intent to run."),
            AgentToolParam(
              "parameters",
              "string",
              "A JSON string containing the parameter values required for the intent.",
              required = false,
            ),
          ),
      ),
      AgentToolSpec(
        name = "runMcpTool",
        description = "Run an MCP tool.",
        params =
          listOf(
            AgentToolParam("toolName", "string", "The name of the tool to run."),
            AgentToolParam("input", "string", "The parameters passed to the tool as JSON input."),
          ),
      ),
      AgentToolSpec(
        name = "webSearch",
        description = "Search the web for up-to-date information and return a list of results.",
        params =
          listOf(
            AgentToolParam("query", "string", "The search query."),
          ),
      ),
      // [ALTREX] The model's own Alpine Linux (separate from the user's terminal).
      AgentToolSpec(
        name = "run_command",
        description =
          "Run a shell command in your own Alpine Linux environment (apk add, python3, git, curl…). " +
            "The user sees and approves every command. Returns the exit code and the output.",
        params =
          listOf(
            AgentToolParam("command", "string", "The shell command to run (sh syntax), from /root."),
          ),
      ),
    )

  private val gson = Gson()

  private fun normalize(name: String): String = name.lowercase().replace("_", "")

  private fun arg(args: JSONObject, vararg names: String): String {
    for (name in names) {
      val value = args.optString(name, "")
      if (value.isNotEmpty()) return value
    }
    return ""
  }

  private fun ok(value: Any?): String = gson.toJson(value)

  private fun err(message: String): String =
    gson.toJson(mapOf("error" to message, "status" to "failed"))

  /**
   * [ALTREX] Only the tools that really exist in this screen. Advertising tools that are not
   * registered (plain chat has none) made small models call them in a loop and filled every prompt
   * with ~600 tokens of tool boilerplate.
   */
  fun availableSpecs(toolsProvider: ToolsProvider): List<AgentToolSpec> {
    val tools = toolsProvider.getAvailableTools()
    fun has(name: String): Boolean =
      when (name) {
        "load_skill" -> tools.any { it is LoadSkillTool }
        "run_js" -> tools.any { it is RunJsTool }
        "run_intent" -> tools.any { it is RunIntentTool }
        "runMcpTool" -> tools.any { it is RunMcpTool }
        "webSearch" -> tools.any { it is WebSearchTool }
        "run_command" -> tools.any { it is com.google.ai.edge.gallery.tools.RunCommandTool }
        else -> false
      }
    return specs.filter { has(it.name) }
  }

  /**
   * Executes a parsed tool call against the active [ToolDefinition]s and returns the JSON string
   * sent back to the model inside a `<tool_response>` block.
   */
  suspend fun dispatch(call: AgentToolCall, toolsProvider: ToolsProvider): String {
    if (call.name.isEmpty()) {
      return err("Invalid tool call (malformed JSON). Rewrite it exactly in the expected format.")
    }

    val tools = toolsProvider.getAvailableTools()
    val args = call.arguments
    return try {
      when (normalize(call.name)) {
        "loadskill" -> {
          val tool = tools.filterIsInstance<LoadSkillTool>().firstOrNull()
            ?: return err("Tool load_skill is not available.")
          val skillName = arg(args, "skillName", "skill_name", "name")
          ok(tool.loadSkill(skillName))
        }
        "runjs" -> {
          val tool = tools.filterIsInstance<RunJsTool>().firstOrNull()
            ?: return err("Tool run_js is not available.")
          val skillName = arg(args, "skillName", "skill_name", "skill")
          val scriptName =
            arg(args, "scriptName", "script_name", "script").ifEmpty { "index.html" }
          val data = arg(args, "data")
          ok(tool.runJs(skillName, scriptName, data))
        }
        "runintent" -> {
          val tool = tools.filterIsInstance<RunIntentTool>().firstOrNull()
            ?: return err("Tool run_intent is not available.")
          val intent = arg(args, "intent")
          val parameters = arg(args, "parameters", "params").ifEmpty { "{}" }
          ok(tool.runIntent(intent, parameters))
        }
        "runmcptool" -> {
          val tool = tools.filterIsInstance<RunMcpTool>().firstOrNull()
            ?: return err("Tool runMcpTool is not available.")
          val toolName = arg(args, "toolName", "tool_name", "name")
          val input = arg(args, "input", "arguments").ifEmpty { "{}" }
          ok(tool.runMcpTool(toolName, input))
        }
        "websearch" -> {
          val tool = tools.filterIsInstance<WebSearchTool>().firstOrNull()
            ?: return err("Tool webSearch is not available.")
          val query = arg(args, "query", "q", "search")
          if (query.isEmpty()) {
            return err("The webSearch tool requires a non-empty \"query\" argument.")
          }
          ok(tool.webSearch(query))
        }
        "runcommand" -> {
          val tool = tools.filterIsInstance<com.google.ai.edge.gallery.tools.RunCommandTool>().firstOrNull()
            ?: return err("Tool run_command is not available.")
          val command = arg(args, "command", "cmd", "shell")
          if (command.isEmpty()) {
            return err("The run_command tool requires a non-empty \"command\" argument.")
          }
          ok(tool.runCommand(command))
        }
        else -> err("Unknown tool \"${call.name}\".")
      }
    } catch (e: Exception) {
      err(e.message ?: "Tool execution failed.")
    }
  }
}
