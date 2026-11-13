package com.google.ai.edge.gallery.compute

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ALTREX] The Auto mode's measurement, run in the BACKGROUND after the model is loaded on a safe
 * choice ([AutoSelector.immediate]). It never blocks a load and never reloads the model:
 * - one backend at a time, the loaded one first (a known-good reference), then the others ;
 * - a tiny fixed benchmark, and at most [timeoutMs] per backend: a backend over the limit is
 *   noted « trop lent » and not tried again by itself ;
 * - cancellable between two backends (a conversation starts, the model is unloaded) ;
 * - crash guard: the « test en cours » mark ([AutoMemory.beginAttempt]) is written BEFORE each
 *   try; if a driver kills the app there, the next load reads it and blacklists that backend
 *   for this model, phone and app version ([AutoMemory.takeCrashedAttempt]) ;
 * - the result is only remembered: it applies at the next load (or when the user accepts
 *   « Basculer sur … »).
 *
 * @param start starts the native measurement of one backend and returns at once; the call itself
 *   cannot be interrupted, only waited for (tokens per second, null on failure).
 */
class AutoBenchmark(
  private val memory: AutoMemory,
  private val start: (ComputeChoice) -> Deferred<Double?>,
  private val timeoutMs: Long = BACKEND_TIMEOUT_MS,
  private val graceMs: Long = GRACE_MS,
  private val title: (ComputeChoice) -> String = { it.label },
  private val now: () -> Long = { System.currentTimeMillis() },
) {
  sealed class Outcome {
    /** Nothing to compare (one usable backend). */
    data object Nothing : Outcome()

    /** [best] is remembered for the next load; [switchTo] is set when it is not the loaded one. */
    data class Done(val best: ComputeChoice, val tokensPerSecond: Double?, val current: ComputeChoice, val samples: List<BenchSample>, val slow: List<ComputeChoice>) : Outcome() {
      val switchTo: ComputeChoice?
        get() = best.takeIf { it != current }
    }

    /** Stopped between two backends; nothing is remembered, the next manual load measures again. */
    data class Cancelled(val samples: List<BenchSample>) : Outcome()

    /** [stuck] did not come back even after the grace time: the measurement stops there. */
    data class Aborted(val stuck: ComputeChoice, val samples: List<BenchSample>) : Outcome()
  }

  suspend fun run(
    fp: ModelFingerprint,
    candidates: List<ComputeChoice>,
    current: ComputeChoice,
    cancelled: () -> Boolean = { false },
    onProgress: (String) -> Unit = {},
  ): Outcome {
    val order = order(candidates, current, memory.excludedOf(fp))
    if (order.size < 2) return Outcome.Nothing
    val samples = ArrayList<BenchSample>()
    val slow = ArrayList<ComputeChoice>()
    for (backend in order) {
      if (cancelled()) return Outcome.Cancelled(samples)
      onProgress("Auto : test du ${title(backend)}…")
      // On disk before the driver gets a chance to kill the process.
      memory.beginAttempt(fp, backend)
      val run =
        try {
          start(backend)
        } catch (e: Throwable) {
          memory.endAttempt(fp)
          if (backend != current) memory.markFailed(fp, backend)
          samples.add(BenchSample(backend, null))
          continue
        }
      var timedOut = false
      val tps: Double? =
        try {
          withTimeout(timeoutMs) { run.await() }
        } catch (e: TimeoutCancellationException) {
          timedOut = true
          null
        } catch (e: CancellationException) {
          if (!currentCoroutineContext().isActive) {
            // The whole measurement is cancelled; the mark goes when the native call returns.
            run.invokeOnCompletion { memory.endAttempt(fp) }
            throw e
          }
          null
        } catch (e: Throwable) {
          null
        }
      if (timedOut) {
        if (backend != current) memory.markSlow(fp, backend)
        slow.add(backend)
        samples.add(BenchSample(backend, null))
        onProgress("Auto : ${title(backend)} trop lent, arrêt du test…")
        // One backend at a time: the next one waits for this native call to end.
        val ended = withTimeoutOrNull(graceMs) { run.join(); true } ?: false
        if (!ended) {
          run.invokeOnCompletion { memory.endAttempt(fp) }
          return Outcome.Aborted(backend, samples)
        }
        memory.endAttempt(fp)
        continue
      }
      memory.endAttempt(fp)
      val ok = tps?.takeIf { it > 0.0 }
      if (ok == null && backend != current) memory.markFailed(fp, backend)
      samples.add(BenchSample(backend, ok))
    }
    val best = AutoSelector.pickFastest(samples) ?: current
    val tps = samples.firstOrNull { it.backend == best }?.tokensPerSecond
    memory.remember(fp, best, tps, now())
    return Outcome.Done(best, tps, current, samples, slow)
  }

  companion object {
    /** At most this long for one backend (engine start + a few tokens). */
    const val BACKEND_TIMEOUT_MS = 18_000L

    /** After a timeout, how long the next backend waits for the native call to return. */
    const val GRACE_MS = 45_000L

    /** Tiny fixed benchmark: tokens read, tokens written. */
    const val PREFILL_TOKENS = 16
    const val DECODE_TOKENS = 12

    /** The loaded backend first, then CPU, GPU, NPU; never one that failed, crashed or was too slow. */
    fun order(candidates: List<ComputeChoice>, current: ComputeChoice, excluded: Set<ComputeChoice>): List<ComputeChoice> =
      (listOf(current) + listOf(ComputeChoice.CPU, ComputeChoice.GPU, ComputeChoice.NPU).filter { it in candidates })
        .distinct()
        .filter { it != ComputeChoice.AUTO && (it == current || it !in excluded) }

    /** The line shown when the measurement ends. */
    fun summary(outcome: Outcome, title: (ComputeChoice) -> String): String =
      when (outcome) {
        is Outcome.Nothing -> ""
        is Outcome.Cancelled -> "Auto : mesure annulée (elle reprendra au prochain chargement manuel)."
        is Outcome.Aborted -> "Auto : ${title(outcome.stuck)} ne répond pas, mesure arrêtée. Il ne sera plus essayé automatiquement."
        is Outcome.Done -> {
          val cur = outcome.samples.firstOrNull { it.backend == outcome.current }?.tokensPerSecond?.let { ", contre ${AutoSelector.formatSpeed(it)} pour ${title(outcome.current)}" }.orEmpty()
          val speed = outcome.tokensPerSecond?.let { " (${AutoSelector.formatSpeed(it)} tok/s" + (if (outcome.switchTo != null) cur else "") + ")" }.orEmpty()
          val slow = if (outcome.slow.isEmpty()) "" else " Trop lent au test : ${outcome.slow.joinToString(", ") { title(it) }}."
          if (outcome.switchTo != null) "Auto : ${title(outcome.best)} est plus rapide$speed. Il servira au prochain chargement.$slow"
          else "Auto : ${title(outcome.best)} confirmé$speed.$slow"
        }
      }
  }
}
