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

package com.google.ai.edge.gallery.tools

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.intents.IntentAction
import com.google.ai.edge.gallery.intents.IntentHandler
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

private const val TAG = "AGRunIntentTool"

private const val PERMISSIONS_PREFS = "altrex_action_permissions"

/** [ALTREX] Intents that act directly, with no confirmation screen from Android or another app. */
private val SILENT_ACTIONS =
  mapOf(
    IntentAction.SET_ALARM to "Altrex veut régler une alarme",
    IntentAction.SCHEDULE_NOTIFICATION to "Altrex veut programmer une notification",
    IntentAction.DEVICE_CONTROL to "Altrex veut contrôler l'appareil",
  )

class RunIntentTool(private val context: Context, private val skillsProvider: SkillsProvider) :
  ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /** Run an Android intent */
  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  suspend fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    if (IntentAction.from(intent) == null) {
      Log.w(TAG, "Intent not found: '$intent'")
      return guardMissingEntityWithSkillFallback(name = intent, type = "Intent")
    }

    Log.d(TAG, "Run intent. Intent: '$intent', parameters: '${com.google.ai.edge.gallery.security.SecretRedactor.redact(parameters)}'")

    // [ALTREX] Actions that run without a screen of their own: the user approves them first.
    val action = IntentAction.from(intent)
    val label = SILENT_ACTIONS[action]
    val channel = executionContext?.actionChannel
    if (label != null && channel != null && !isAlwaysAllowed(intent)) {
      val ask = AskActionPermissionAction(label = label, parameters = parameters)
      channel.send(ask)
      when (ask.result.await()) {
        PermissionResult.DENY ->
          return mapOf(
            "action" to intent,
            "result" to "refused: the user did not allow this action. Do not try again; tell the user.",
          )
        PermissionResult.ALWAYS_ALLOW -> setAlwaysAllowed(intent)
        PermissionResult.ALLOW_ONCE -> {}
      }
    }

    executionContext
      ?.actionChannel
      ?.send(
        SkillProgressToolAction(
          label = "Executing intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute intent \"$intent\"",
          addItemDescription = "Parameters: $parameters",
        )
      )

    val res =
      IntentHandler.handleAction(context, intent, parameters) { permission ->
        val permissionAction = RequestPermissionToolAction(permission = permission)
        executionContext?.actionChannel?.send(permissionAction)
        permissionAction.result.await()
      }

    return mapOf("action" to intent, "parameters" to parameters, "result" to res)
  }

  private fun isAlwaysAllowed(intent: String): Boolean =
    context.getSharedPreferences(PERMISSIONS_PREFS, Context.MODE_PRIVATE).getBoolean(intent, false)

  private fun setAlwaysAllowed(intent: String) {
    context.getSharedPreferences(PERMISSIONS_PREFS, Context.MODE_PRIVATE).edit().putBoolean(intent, true).apply()
  }

  /**
   * Guards against missing entities (tools or intents) by checking if they exist as skills. Returns
   * a failure response with a specific hint to the model to try running it as a skill if it is
   * found in the allowed skills list. This helps guide the model when it gets confused and tries to
   * call a skill as a tool or intent.
   */
  private suspend fun guardMissingEntityWithSkillFallback(
    name: String,
    type: String,
  ): Map<String, String> {
    val isSkill = skillsProvider.loadSkill(name) != null
    val error = if (isSkill) "$type not found. Try to run it as a skill" else "Tool not found"
    return mapOf("error" to error, "status" to "failed")
  }
}
