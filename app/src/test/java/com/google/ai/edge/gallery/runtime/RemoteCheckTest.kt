package com.google.ai.edge.gallery.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ALTREX] « Vérifier la connexion » of cloud models and Spaces: what each answer means. */
class RemoteCheckTest {
  private val groq = CloudApi.Provider.GROQ

  @Test
  fun names() {
    assertTrue(RemoteCheck.isRemoteModel("cloud:groq:openai~gpt-oss-120b"))
    assertTrue(RemoteCheck.isRemoteModel("space:autaris:argora"))
    assertTrue(!RemoteCheck.isRemoteModel("gemma-4-e4b"))
  }

  @Test
  fun cloud_keyAndModel() {
    assertEquals(RemoteState.READY, RemoteCheck.cloudVerdict(groq, "openai/gpt-oss-120b", listOf("a", "openai/gpt-oss-120b")).state)
    val missing = RemoteCheck.cloudVerdict(groq, "old-model", listOf("a", "b"))
    assertEquals(RemoteState.ERROR, missing.state)
    assertTrue(missing.detail.contains("old-model"))
    // A provider that returns no list still proved the key.
    assertEquals(RemoteState.READY, RemoteCheck.cloudVerdict(groq, "x", emptyList()).state)
    assertEquals(RemoteState.KEY_REFUSED, RemoteCheck.cloudError(groq, 401, "").state)
    assertEquals(RemoteState.KEY_REFUSED, RemoteCheck.cloudError(groq, 403, "").state)
    assertEquals(RemoteState.ERROR, RemoteCheck.cloudError(groq, 429, "").state)
    assertTrue(RemoteCheck.cloudError(groq, 503, "").detail.contains("indisponible"))
    assertEquals("Clé refusée", RemoteState.KEY_REFUSED.label)
    assertEquals("Clé manquante", RemoteState.NO_KEY.label)
    assertEquals("Prêt", RemoteState.READY.label)
    assertEquals("Hors ligne", RemoteState.OFFLINE.label)
    assertEquals("Connexion…", RemoteState.CHECKING.label)
  }

  @Test
  fun offline() {
    assertTrue(RemoteCheck.offlineDetail(java.net.UnknownHostException("api.groq.com")).contains("Pas de connexion"))
    assertTrue(RemoteCheck.offlineDetail(java.net.SocketTimeoutException()).contains("Pas de réponse"))
  }

  @Test
  fun space_answers() {
    val info = """{"named_endpoints":{"/generate":{"parameters":[]}},"unnamed_endpoints":{}}"""
    assertEquals(RemoteState.READY, RemoteCheck.spaceVerdict(200, info, false).first.state)
    assertEquals(RemoteState.ERROR, RemoteCheck.spaceVerdict(200, """{"named_endpoints":{"/predict":{}}}""", false).first.state)
    for (code in listOf(502, 503, 504)) {
      val (st, again) = RemoteCheck.spaceVerdict(code, "", false)
      assertEquals(RemoteState.WAKING, st.state)
      assertEquals("Space en veille, réveil en cours…", st.detail)
      assertTrue(again)
    }
    // The « Space is starting » page instead of the API description.
    assertTrue(RemoteCheck.spaceVerdict(200, "<html>Preparing Space</html>", false).second)
    assertEquals(RemoteState.NO_KEY, RemoteCheck.spaceVerdict(404, "", hasToken = false).first.state)
    assertEquals(RemoteState.ERROR, RemoteCheck.spaceVerdict(404, "", hasToken = true).first.state)
    assertEquals(RemoteState.KEY_REFUSED, RemoteCheck.spaceVerdict(401, "", true).first.state)
  }

  @Test
  fun space_wakesUp_thenReady_orGivesUp() {
    val info = """{"named_endpoints":{"/generate":{}}}"""
    val answers = ArrayDeque(listOf(503 to "", 503 to "", 200 to info))
    val states = ArrayList<RemoteState>()
    var clock = 0L
    val slept = ArrayList<Long>()
    val r = RemoteCheck.spaceLoop(false, { answers.removeFirst() }, { states.add(it.state) }, maxWaitMs = 60_000, now = { clock }, sleep = { slept.add(it); clock += it })
    assertEquals(RemoteState.READY, r.state)
    assertEquals("Space réveillé : il est prêt.", r.detail)
    assertEquals(listOf(RemoteState.WAKING, RemoteState.WAKING), states)
    assertEquals(listOf(3_000L, 4_500L), slept)
    // Already up: one request, no wait.
    var calls = 0
    assertEquals(RemoteState.READY, RemoteCheck.spaceLoop(false, { calls++; 200 to info }, {}, now = { 0 }, sleep = { error("no wait") }).state)
    assertEquals(1, calls)
    // Paused or broken: it never starts, the check gives up after the limit.
    clock = 0
    val stuck = RemoteCheck.spaceLoop(false, { 503 to "" }, {}, maxWaitMs = 30_000, now = { clock }, sleep = { clock += it })
    assertEquals(RemoteState.ERROR, stuck.state)
    assertTrue(stuck.detail.contains("ne démarre pas"))
  }
}
