/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.annotation.StringRes
import androidx.compose.ui.text.AnnotatedString
import com.google.ai.edge.gallery.R

enum class PromptTemplateInputEditorType {
  SINGLE_SELECT
}

data class SelectOption(
  val key: String,
  @StringRes val labelRes: Int = 0,
  val labelFallback: String = "",
)

open class PromptTemplateInputEditor(
  val key: String,
  @StringRes open val labelRes: Int,
  open val type: PromptTemplateInputEditorType,
  open val defaultOptionKey: String = "",
)

/** Single select that shows options in bottom sheet. */
class PromptTemplateSingleSelectInputEditor(
  key: String,
  @StringRes override val labelRes: Int,
  val options: List<SelectOption> = listOf(),
  override val defaultOptionKey: String = "",
) :
  PromptTemplateInputEditor(
    key = key,
    labelRes = labelRes,
    type = PromptTemplateInputEditorType.SINGLE_SELECT,
    defaultOptionKey = defaultOptionKey,
  )

data class PromptTemplateConfig(val inputEditors: List<PromptTemplateInputEditor> = listOf())

@Suppress("ImmutableEnum")
enum class PromptTemplateType(
  @StringRes val labelRes: Int,
  val config: PromptTemplateConfig,
  val genFullPrompt: (userInput: String, inputEditorValues: Map<String, Any>) -> AnnotatedString =
    { _, _ ->
      AnnotatedString("")
    },
  val examplePrompts: List<String> = listOf(),
) {
  CODE_SNIPPET(
    labelRes = R.string.prompt_lab_tab_code_snippet,
    config = PromptTemplateConfig(),
    genFullPrompt = { userInput, _ -> AnnotatedString(userInput) },
    examplePrompts =
      listOf(
        "Create an alert box that says \"Hello, World!\"",
        "Declare an immutable variable named 'appName' with the value \"AI Gallery\"",
        "Print the numbers from 1 to 5 using a for loop.",
        "Write a function that returns the square of an integer input.",
      ),
  ),
}