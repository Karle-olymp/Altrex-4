package com.google.ai.edge.gallery.ui.home

import kotlinx.coroutines.CompletionHandlerException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class CrashLogTest {
  @Test
  fun ignoresOnlyCleanupErrorsOfTheCoroutineTimer() {
    val handlerError = CompletionHandlerException("Exception in completion handler", RuntimeException("x"))
    assertTrue(CrashLog.isIgnorable("kotlinx.coroutines.DefaultExecutor", handlerError))
    // Wrapped deeper in the chain.
    assertTrue(CrashLog.isIgnorable("kotlinx.coroutines.DefaultExecutor", RuntimeException("w", handlerError)))
    // The same error elsewhere, or another error on that thread, still crashes.
    assertFalse(CrashLog.isIgnorable("main", handlerError))
    assertFalse(CrashLog.isIgnorable("kotlinx.coroutines.DefaultExecutor", IllegalStateException("boom")))
  }

  @Test
  fun summaryKeepsTheRootCause() {
    val trace =
      buildString {
        append("Fil : t\nA: top\n")
        repeat(40) { append("\tat x.Y.f$it(Y.kt:$it)\n") }
        append("Caused by: B: middle\n\tat m.N.g(N.kt:1)\n\tat m.N.h(N.kt:2)\n")
        repeat(30) { append("\tat z.Z.k$it(Z.kt:$it)\n") }
        append("Caused by: java.net.SocketException: the real cause\n\tat r.R.a(R.kt:9)\n")
      }
    val s = CrashLog.summarize(trace)
    assertTrue(s.contains("the real cause"))
    assertTrue(s.contains("B: middle"))
    assertTrue(s.lines().size < 40)
    assertFalse(s.contains("x.Y.f30"))
  }
}
