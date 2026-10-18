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

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGWebSearchTool"

private const val MAX_RESULTS = 6
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000
private const val USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0 Mobile Safari/537.36"

/** A single web search result. */
private data class WebResult(val title: String, val url: String, val snippet: String)

/**
 * Native, key-less web search tool. It combines two public, CORS-enabled and API-key-free sources —
 * DuckDuckGo Instant Answers (direct answers / abstracts) and the Wikipedia search API (general
 * results) — into a single result set.
 *
 * It works with both runtimes: LiteRT-LM models call it through their native tool calling, local
 * GGUF models through [com.google.ai.edge.gallery.agent.LlamaCppToolBridge]. The exact same logic is
 * also available as the JavaScript "web-search" skill, so results are consistent across modes.
 */
class WebSearchTool : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /** Search the web. */
  @Tool(description = "Search the web for up-to-date information and return a list of results.")
  fun webSearch(
    @ToolParam(description = "The search query.") query: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.IO) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) {
        return@runBlocking mapOf(
          "error" to "The search query is empty.",
          "status" to "failed",
        )
      }
      // [ALTREX] A secret (key, token, password) must never leave the phone in a search query.
      if (com.google.ai.edge.gallery.security.SecretRedactor.containsSecret(trimmed)) {
        return@runBlocking mapOf(
          "error" to
            "The query contains a secret (key, token or password) and was not sent. Search again without it.",
          "status" to "failed",
        )
      }

      executionContext?.actionChannel?.trySend(
        SkillProgressToolAction(
          label = "Searching the web for \"$trimmed\"...",
          inProgress = true,
          addItemTitle = "Web search",
          addItemDescription = "Query: $trimmed",
        )
      )

      try {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val results = LinkedHashMap<String, WebResult>()

        // 1) DuckDuckGo Instant Answer API: direct answers / abstracts for entity & factual queries.
        val summary = runCatching { queryInstantAnswer(trimmed) }.getOrNull()
        summary?.abstractText?.let { text ->
          val url = summary.url
          if (url.isNotEmpty()) {
            results[url] = WebResult(title = summary.heading, url = url, snippet = text)
          }
        }
        summary?.related?.forEach { r -> results.putIfAbsent(r.url, r) }

        // 2) Wikipedia search API: broad, reliable, language aware.
        runCatching { queryWikipedia(trimmed, lang) }
          .getOrNull()
          ?.forEach { r -> results.putIfAbsent(r.url, r) }

        val list = results.values.take(MAX_RESULTS).toList()
        executionContext?.actionChannel?.trySend(
          SkillProgressToolAction(
            label = "Web search finished (${list.size} result(s))",
            inProgress = false,
          )
        )

        if (list.isEmpty()) {
          mapOf("result" to "No results found for \"$trimmed\".", "status" to "succeeded")
        } else {
          mapOf(
            "result" to resultsToJson(trimmed, summary?.abstractText.orEmpty(), summary?.answer.orEmpty(), list),
            "status" to "succeeded",
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Web search failed for \"$trimmed\"", e)
        executionContext?.actionChannel?.trySend(
          SkillProgressToolAction(label = "Web search failed", inProgress = false)
        )
        mapOf(
          "error" to "Web search failed: ${e.message ?: "network error"}",
          "status" to "failed",
        )
      }
    }
  }

  private data class InstantAnswer(
    val heading: String,
    val abstractText: String,
    val answer: String,
    val url: String,
    val related: List<WebResult>,
  )

  /** DuckDuckGo Instant Answer API (CORS-enabled, no key). */
  private fun queryInstantAnswer(query: String): InstantAnswer {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val json =
      httpGet(
        "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1&t=altrex"
      )
    if (json.isBlank()) return InstantAnswer("", "", "", "", emptyList())

    val obj = JSONObject(json)
    val heading = obj.optString("Heading")
    val answer = obj.optString("Answer")
    val abstractText = obj.optString("AbstractText").ifEmpty { obj.optString("Definition") }
    val url = obj.optString("AbstractURL")

    val related = ArrayList<WebResult>()
    obj.optJSONArray("RelatedTopics")?.let { collectRelated(it, related) }
    return InstantAnswer(heading.ifEmpty { query }, abstractText, answer, url, related.take(3))
  }

  private fun collectRelated(array: JSONArray, out: MutableList<WebResult>) {
    for (i in 0 until array.length()) {
      if (out.size >= MAX_RESULTS) return
      val item = array.optJSONObject(i) ?: continue
      val nested = item.optJSONArray("Topics")
      if (nested != null) {
        collectRelated(nested, out)
        continue
      }
      val text = item.optString("Text")
      val url = item.optString("FirstURL")
      if (text.isNotEmpty() && url.isNotEmpty()) {
        val title = text.substringBefore(" - ", text).take(80)
        out.add(WebResult(title = title, url = url, snippet = stripHtml(text)))
      }
    }
  }

  /** Wikipedia search API (CORS-enabled via origin=*, no key). */
  private fun queryWikipedia(query: String, lang: String): List<WebResult> {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val json =
      httpGet("https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=$encoded&limit=$MAX_RESULTS")
    if (json.isBlank()) return emptyList()

    val root = JSONObject(json)
    val pages = root.optJSONArray("pages") ?: return emptyList()
    val out = ArrayList<WebResult>()
    for (i in 0 until pages.length()) {
      val page = pages.optJSONObject(i) ?: continue
      val key = page.optString("key")
      if (key.isEmpty()) continue
      val title = page.optString("title").ifEmpty { key }
      val url = "https://$lang.wikipedia.org/wiki/$key"
      val description = page.optString("description")
      val excerpt = stripHtml(page.optString("excerpt"))
      val snippet = listOf(description, excerpt).filter { it.isNotEmpty() }.joinToString(" — ")
      out.add(WebResult(title = title, url = url, snippet = snippet))
    }
    return out
  }

  private fun resultsToJson(
    query: String,
    abstractText: String,
    answer: String,
    results: List<WebResult>,
  ): String {
    val array = JSONArray()
    for (r in results) {
      array.put(JSONObject().put("title", r.title).put("url", r.url).put("snippet", r.snippet))
    }
    val root = JSONObject().put("query", query).put("results", array)
    if (abstractText.isNotEmpty()) root.put("summary", abstractText)
    if (answer.isNotEmpty()) root.put("answer", answer)
    return root.toString()
  }

  private fun httpGet(urlString: String): String {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    return try {
      conn.requestMethod = "GET"
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.instanceFollowRedirects = true
      conn.setRequestProperty("User-Agent", USER_AGENT)
      conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,fr;q=0.8")
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      stream?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
    } finally {
      conn.disconnect()
    }
  }

  private fun stripHtml(input: String): String =
    unescapeHtml(input.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

  private fun unescapeHtml(input: String): String {
    var text = input
    text = text.replace("&amp;", "&")
    text = text.replace("&lt;", "<")
    text = text.replace("&gt;", ">")
    text = text.replace("&quot;", "\"")
    text = text.replace("&#x27;", "'")
    text = text.replace("&#39;", "'")
    text = text.replace("&nbsp;", " ")
    text =
      Regex("&#(\\d+);").replace(text) { m ->
        m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
      }
    return text
  }
}
