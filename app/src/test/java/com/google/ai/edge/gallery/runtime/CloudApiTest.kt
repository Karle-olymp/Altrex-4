package com.google.ai.edge.gallery.runtime

import com.google.ai.edge.gallery.runtime.CloudApi.Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudApiTest {
  private fun msgs(vararg pairs: Pair<String, String>) =
    JSONArray().apply { for ((r, c) in pairs) put(JSONObject().put("role", r).put("content", c)) }

  @Test
  fun names_roundTrip_withSlashAndColon() {
    val name = CloudApi.modelName(Provider.OPENROUTER, "openai/gpt-oss-120b:free")
    assertFalse(name.contains('/'))
    assertEquals(Provider.OPENROUTER to "openai/gpt-oss-120b:free", CloudApi.parse(name))
    assertEquals(Provider.ANTHROPIC to "claude-opus-5-5", CloudApi.parse("cloud:anthropic:claude-opus-5-5"))
    assertNull(CloudApi.parse("cloud:unknown:x"))
    assertNull(CloudApi.parse("space:Autaris:argora-api"))
  }

  @Test
  fun anthropicBody_systemApart_turnsAlternate() {
    val body =
      CloudApi.anthropicBody(
        "claude-opus-5-5",
        msgs("system" to "S", "assistant" to "skip", "user" to "a", "user" to "tool result", "assistant" to "b"),
        thinking = false,
        maxTokens = 8192,
        fallbacks = true,
      )
    assertEquals("S", body.getString("system"))
    val turns = body.getJSONArray("messages")
    assertEquals(2, turns.length())
    assertEquals("user", turns.getJSONObject(0).getString("role"))
    assertEquals("a\n\ntool result", turns.getJSONObject(0).getString("content"))
    assertEquals("assistant", turns.getJSONObject(1).getString("role"))
    assertFalse(body.has("thinking"))
    assertFalse(body.has("temperature"))
    assertEquals("default", body.getString("fallbacks"))
  }

  @Test
  fun anthropicBody_thinkingSummarized_noFallbackForOtherModels() {
    val body = CloudApi.anthropicBody("claude-haiku-5-5", msgs("user" to "a"), thinking = true, maxTokens = 1000, fallbacks = true)
    assertEquals("summarized", body.getJSONObject("thinking").getString("display"))
    assertFalse(body.has("fallbacks"))
  }

  @Test
  fun openAiBody_maxTokensField() {
    val o = CloudApi.openAiBody(Provider.OPENAI, "gpt-x", msgs("system" to "S", "user" to "a"), 500)
    assertTrue(o.has("max_completion_tokens"))
    val g = CloudApi.openAiBody(Provider.GEMINI, "gemini-x", msgs("user" to "a"), 500)
    assertEquals(500, g.getInt("max_tokens"))
    assertEquals("system", o.getJSONArray("messages").getJSONObject(0).getString("role"))
  }

  @Test
  fun httpError_readsProviderMessage() {
    val e = CloudApi.httpError(Provider.ANTHROPIC, 401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
    assertTrue(e.contains("401"))
    assertTrue(e.contains("invalid x-api-key"))
    assertTrue(e.contains("Clé refusée"))
    assertTrue(CloudApi.httpError(Provider.GROQ, 429, "nope").contains("Limite"))
  }
}
