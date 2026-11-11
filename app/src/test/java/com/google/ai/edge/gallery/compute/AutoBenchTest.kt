package com.google.ai.edge.gallery.compute

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ALTREX] Background Auto measurement: order, time limits, blacklist, recovery after a crash. */
class AutoBenchTest {
  private val cpu = ComputeChoice.CPU
  private val gpu = ComputeChoice.GPU
  private val npu = ComputeChoice.NPU
  private val all = listOf(cpu, gpu, npu)
  private val fp = ModelFingerprint("gemma", 4_000, 10, "1")

  private fun memory(store: KeyValueStore = MapKeyValueStore()) = AutoMemory(store, "phone#1")

  /** Results per backend: a speed, null (failure), or NEVER (the native call does not return). */
  private class Runs(val results: Map<ComputeChoice, Any?>, val memory: AutoMemory, val fp: ModelFingerprint) {
    val started = ArrayList<ComputeChoice>()
    val pending = HashMap<ComputeChoice, CompletableDeferred<Double?>>()
    var maxInFlight = 0

    fun start(b: ComputeChoice): Deferred<Double?> {
      started.add(b)
      val d = CompletableDeferred<Double?>()
      when (val r = results[b]) {
        NEVER -> pending[b] = d
        else -> d.complete(r as Double?)
      }
      maxInFlight = maxOf(maxInFlight, pending.values.count { !it.isCompleted } + 0)
      return d
    }
  }

  private companion object {
    val NEVER = Any()
  }

  // ------------------------------------------------------------------------- immediate choice

  @Test
  fun immediateChoice_neverMeasures_andPrefersWhatWorked() {
    // Nothing known: the model's recommendation, else the CPU.
    assertEquals(gpu, AutoSelector.immediate(all, null, null, gpu, emptySet()).backend)
    assertEquals(cpu, AutoSelector.immediate(all, null, null, null, emptySet()).backend)
    assertEquals("choix sûr", AutoSelector.immediate(all, null, null, null, emptySet()).reason)
    // The last backend that started well wins over the recommendation.
    assertEquals(cpu, AutoSelector.immediate(all, null, cpu, gpu, emptySet()).backend)
    // A measured choice wins over everything, with its speed.
    val use = AutoSelector.immediate(all, AutoRecord(npu, 21.0, 1), cpu, gpu, emptySet())
    assertEquals(npu, use.backend)
    assertEquals(21.0, use.tokensPerSecond!!, 0.0)
    // A blacklisted backend is never chosen, even remembered or recommended.
    assertEquals(cpu, AutoSelector.immediate(all, AutoRecord(gpu, 30.0, 1), gpu, gpu, setOf(gpu)).backend)
    assertEquals(cpu, AutoSelector.immediate(listOf(cpu, gpu), null, null, gpu, setOf(cpu, gpu)).backend)
  }

  @Test
  fun lastSuccess_isPerFileAndPhone_andDroppedWhenItFails() {
    val store = MapKeyValueStore()
    val m = memory(store)
    assertNull(m.lastSuccess(fp))
    m.rememberSuccess(fp, gpu)
    assertEquals(gpu, m.lastSuccess(fp))
    assertNull(AutoMemory(store, "phone#2").lastSuccess(fp))
    assertNull(m.lastSuccess(fp.copy(fileBytes = 5_000)))
    m.markFailed(fp, gpu)
    assertNull(m.lastSuccess(fp))
  }

  // ------------------------------------------------------------------------------- scheduling

  @Test
  fun order_loadedBackendFirst_thenTheOthers_withoutTheExcluded() {
    assertEquals(listOf(gpu, cpu, npu), AutoBenchmark.order(all, gpu, emptySet()))
    assertEquals(listOf(cpu, npu), AutoBenchmark.order(all, cpu, setOf(gpu)))
    // The loaded backend is measured even if it was once too slow in a test.
    assertEquals(listOf(gpu, cpu), AutoBenchmark.order(all, gpu, setOf(gpu, npu)))
  }

  @Test
  fun measuresOneAtATime_remembersTheFastest_andSuggestsWithoutReloading() = runBlocking {
    val m = memory()
    val runs = Runs(mapOf(cpu to 6.0, gpu to 14.0, npu to null), m, fp)
    val progress = ArrayList<String>()
    val out = AutoBenchmark(m, runs::start, timeoutMs = 200, graceMs = 200).run(fp, all, cpu) { progress.add(it) }
    assertEquals(listOf(cpu, gpu, npu), runs.started)
    assertEquals(listOf("Auto : test du CPU…", "Auto : test du GPU…", "Auto : test du NPU…"), progress)
    val done = out as AutoBenchmark.Outcome.Done
    assertEquals(gpu, done.best)
    assertEquals(gpu, done.switchTo)
    // Only remembered: applied by the next load.
    assertEquals(gpu, m.recall(fp)!!.backend)
    assertEquals(gpu, AutoSelector.immediate(all, m.recall(fp), cpu, null, m.excludedOf(fp)).backend)
    // The backend that failed its test is blacklisted; the mark « test en cours » is gone.
    assertEquals(setOf(npu), m.failedOf(fp))
    assertNull(m.takeCrashedAttempt(fp))
    assertTrue(AutoBenchmark.summary(done, { it.label }).contains("GPU est plus rapide (14 tok/s, contre 6,0 pour CPU)"))
    // A second run has nothing new to try besides CPU and GPU.
    assertEquals(listOf(cpu, gpu), AutoBenchmark.order(all, cpu, m.excludedOf(fp)))
  }

  @Test
  fun cpuWithinTenPercent_isConfirmed_noSwitch() = runBlocking {
    val m = memory()
    val runs = Runs(mapOf(cpu to 9.5, gpu to 10.0), m, fp)
    val done = AutoBenchmark(m, runs::start, 200, 200).run(fp, listOf(cpu, gpu), cpu) as AutoBenchmark.Outcome.Done
    assertEquals(cpu, done.best)
    assertNull(done.switchTo)
    assertTrue(AutoBenchmark.summary(done, { it.label }).startsWith("Auto : CPU confirmé"))
  }

  @Test
  fun oneUsableBackend_nothingToMeasure() = runBlocking {
    val m = memory()
    m.markFailed(fp, gpu)
    val runs = Runs(emptyMap(), m, fp)
    assertEquals(AutoBenchmark.Outcome.Nothing, AutoBenchmark(m, runs::start, 200, 200).run(fp, listOf(cpu, gpu), cpu))
    assertTrue(runs.started.isEmpty())
  }

  @Test
  fun cancelled_betweenTwoBackends_remembersNothing() = runBlocking {
    val m = memory()
    val runs = Runs(mapOf(cpu to 6.0, gpu to 14.0), m, fp)
    var stop = false
    val out = AutoBenchmark(m, { b -> runs.start(b).also { stop = true } }, 200, 200).run(fp, all, cpu, cancelled = { stop })
    assertTrue(out is AutoBenchmark.Outcome.Cancelled)
    assertEquals(listOf(cpu), runs.started)
    assertNull(m.recall(fp))
    assertNull(m.takeCrashedAttempt(fp))
  }

  // ----------------------------------------------------------------------------------- delays

  @Test
  fun aBackendOverItsTimeLimit_isMarkedSlow_andTheNextWaitsForItToEnd() = runBlocking {
    val m = memory()
    val runs = Runs(mapOf(cpu to 6.0, gpu to NEVER, npu to 9.0), m, fp)
    val t0 = System.currentTimeMillis()
    val bench =
      AutoBenchmark(
        m,
        { b ->
          // The stuck GPU call returns during the grace time.
          runs.start(b).also { d -> if (b == gpu) Thread { Thread.sleep(150); (d as CompletableDeferred<Double?>).complete(40.0) }.start() }
        },
        timeoutMs = 60,
        graceMs = 2_000,
      )
    val done = bench.run(fp, all, cpu) as AutoBenchmark.Outcome.Done
    // The late answer of the GPU is not counted: it went over its limit.
    assertEquals(listOf(cpu, gpu, npu), runs.started)
    assertEquals(npu, done.best)
    assertEquals(listOf(gpu), done.slow)
    assertEquals(setOf(gpu), m.slowOf(fp))
    assertTrue(m.failedOf(fp).isEmpty())
    assertTrue(System.currentTimeMillis() - t0 >= 140)
    // Not retried by itself; « Relancer la mesure » gives it a new chance.
    assertEquals(listOf(cpu, npu), AutoBenchmark.order(all, cpu, m.excludedOf(fp)))
    m.clearSlow(fp.modelName)
    assertEquals(all, AutoBenchmark.order(all, cpu, m.excludedOf(fp)))
  }

  @Test
  fun aBackendThatNeverReturns_stopsTheMeasurement_andKeepsItsMark() = runBlocking {
    val store = MapKeyValueStore()
    val m = memory(store)
    val runs = Runs(mapOf(cpu to 6.0, gpu to NEVER, npu to 9.0), m, fp)
    val out = AutoBenchmark(m, runs::start, timeoutMs = 50, graceMs = 80).run(fp, all, cpu)
    val aborted = out as AutoBenchmark.Outcome.Aborted
    assertEquals(gpu, aborted.stuck)
    // The NPU was not started while the GPU call was still running: one at a time.
    assertEquals(listOf(cpu, gpu), runs.started)
    assertNull(m.recall(fp))
    assertEquals(setOf(gpu), m.slowOf(fp))
    // The app is killed while the call is stuck: the next launch blacklists the GPU.
    val next = memory(store)
    assertEquals(gpu, next.takeCrashedAttempt(fp))
    assertEquals(setOf(gpu), next.failedOf(fp))
    // If instead the call ends later, the mark is removed.
    val store2 = MapKeyValueStore()
    val m2 = memory(store2)
    val runs2 = Runs(mapOf(cpu to 6.0, gpu to NEVER), m2, fp)
    AutoBenchmark(m2, runs2::start, 50, 80).run(fp, listOf(cpu, gpu), cpu)
    runs2.pending[gpu]!!.complete(3.0)
    assertNull(memory(store2).takeCrashedAttempt(fp))
  }

  // ------------------------------------------------------------------ crash guard and recovery

  @Test
  fun crashDuringATest_isReadAtTheNextLaunch_andNeverRetried() = runBlocking {
    val store = MapKeyValueStore()
    val m = memory(store)
    // The driver kills the process inside the GPU test: nothing after start() runs.
    class Killed : Error()
    val seenMark = ArrayList<String?>()
    try {
      AutoBenchmark(
        m,
        { b ->
          if (b == gpu) {
            // The mark was written BEFORE the try.
            seenMark.add(store.get("pending|gemma"))
            throw Killed()
          }
          CompletableDeferred(6.0)
        },
        200,
        200,
      ).run(fp, all, cpu)
    } catch (e: Throwable) {
      // start() failures are handled; simulate the kill by dropping the process state below.
    }
    assertTrue(seenMark.single()!!.contains("b=GPU"))
    // A start() that throws is a plain failure (blacklisted, mark cleared, measurement goes on).
    assertEquals(setOf(gpu), m.failedOf(fp))

    // Real kill: the mark stays on disk. Simulated with a store where it was left.
    val disk = MapKeyValueStore()
    val before = memory(disk)
    before.rememberSuccess(fp, cpu)
    before.beginAttempt(fp, gpu)
    // Next launch, next load of the model.
    val after = memory(disk)
    assertEquals(gpu, after.takeCrashedAttempt(fp))
    assertNull(after.takeCrashedAttempt(fp))
    assertEquals(setOf(gpu), after.excludedOf(fp))
    // The load uses the safe choice at once, and the background measurement skips the GPU for good.
    assertEquals(cpu, AutoSelector.immediate(all, after.recall(fp), after.lastSuccess(fp), gpu, after.excludedOf(fp)).backend)
    val runs = Runs(mapOf(cpu to 6.0, npu to 5.0), after, fp)
    val done = AutoBenchmark(after, runs::start, 200, 200).run(fp, all, cpu) as AutoBenchmark.Outcome.Done
    assertEquals(listOf(cpu, npu), runs.started)
    assertEquals(cpu, done.best)
    // Another phone or app version starts clean (the blacklist is per model, phone and version).
    assertTrue(AutoMemory(disk, "phone#1-v2").excludedOf(fp).isEmpty())
    // Launch after launch, the GPU is not tried again: no loop.
    repeat(3) {
      val again = memory(disk)
      assertNull(again.takeCrashedAttempt(fp))
      assertEquals(listOf(cpu, npu), AutoBenchmark.order(all, cpu, again.excludedOf(fp)))
    }
  }

  @Test
  fun gate_memoryAndConversation() {
    val gb = 1024L * 1024 * 1024
    assertNull(BenchmarkGate.memoryRefusal(availBytes = 6 * gb, fileBytes = 3 * gb))
    assertEquals("mesure reportée : mémoire libre insuffisante", BenchmarkGate.memoryRefusal(availBytes = 3 * gb, fileBytes = 3 * gb))
    assertEquals("mesure reportée : conversation en cours", BenchmarkGate.refusal(DeviceState(80, false, false), conversationActive = true, explicitLoad = true))
  }
}
