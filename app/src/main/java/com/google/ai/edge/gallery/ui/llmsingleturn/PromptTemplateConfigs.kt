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
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.R

enum class PromptTemplateInputEditorType {
  SINGLE_SELECT
}

enum class LanguageType(val key: String) {
  CPP(key = "C++"),
  JAVA(key = "Java"),
  JAVASCRIPT(key = "JavaScript"),
  KOTLIN(key = "Kotlin"),
  PYTHON(key = "Python"),
  SWIFT(key = "Swift"),
  TYPESCRIPT(key = "TypeScript");

  fun toSelectOption() = SelectOption(key = key, labelFallback = key)
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

private val GEMINI_GRADIENT_STYLE =
  SpanStyle(
    brush = linearGradient(colors = listOf(Color(0xFF4285f4), Color(0xFF9b72cb), Color(0xFFd96570)))
  )

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
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              key = "language",
              labelRes = R.string.prompt_lab_label_language,
              options = LanguageType.entries.map { it.toSelectOption() },
              defaultOptionKey = LanguageType.JAVASCRIPT.key,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val language = inputEditorValues["language"] as String
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) { append("Write a $language code snippet to ") }
        append(userInput)
      }
    },
    examplePrompts =
      listOf(
        "Create an alert box that says \"Hello, World!\"",
        "Declare an immutable variable named 'appName' with the value \"AI Gallery\"",
        "Print the numbers from 1 to 5 using a for loop.",
        "Write a function that returns the square of an integer input.",
      ),
  ),
  FREE_FORM(
    labelRes = R.string.prompt_lab_tab_free_form,
    config = PromptTemplateConfig(),
    genFullPrompt = { userInput, _ -> AnnotatedString(userInput) },
    examplePrompts =
      listOf(
        "Suggest 3 topics for a podcast about \"Friendships in your 20s\".",
        "Outline the key sections needed in a basic logo design brief.",
        "List 3 pros and 3 cons to consider before buying a smart watch.",
        "Write a short, optimistic quote about the future of technology.",
        "Generate 3 potential names for a mobile app that helps users identify plants.",
        "Explain the difference between AI and machine learning in 2 sentences.",
        "Create a simple haiku about a cat sleeping in the sun.",
        "List 3 ways to make instant noodles taste better using common kitchen ingredients.",
      ),
  ),
}