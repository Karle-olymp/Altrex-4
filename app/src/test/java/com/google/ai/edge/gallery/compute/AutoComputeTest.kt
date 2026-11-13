package com.google.ai.edge.gallery.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto mode: choice of the fastest backend, CPU preference, memory, crash guard, gate. */
class AutoComputeTest {
  private val cpu = ComputeChoice.CPU
  private val gpu = ComputeChoice.GPU
  private val npu = ComputeChoice.NPU

  @Test
  fun keepsTheFastestBackend() {
    val pick = AutoSelector.pickFastest(listOf(BenchSample(cpu, 8.0), BenchSample(gpu, 14.0)))
    assertEquals(gpu, pick)
  }

  @Test
  fun prefersTheCpuWithinTenPercent() {
    assertEquals(cpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, 9.5), BenchSample(gpu, 10.0))))
    assertEquals(cpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, 10.0), BenchSample(gpu, 10.0))))
    // Exactly 10 % slower is not "within" 10 %: the GPU stays.
    assertEquals(gpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, 9.0), BenchSample(gpu, 10.0))))
    // The CPU is the fastest: kept.
    assertEquals(cpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, 12.0), BenchSample(npu, 6.0))))
  }

  @Test
  fun failedSamplesAreIgnored() {
    assertEquals(cpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, 5.0), BenchSample(gpu, null))))
    assertEquals(gpu, AutoSelector.pickFastest(listOf(BenchSample(cpu, null), BenchSample(gpu, 3.0))))
    assertNull(AutoSelector.pickFastest(listOf(BenchSample(cpu, null), BenchSample(gpu, 0.0))))
    assertNull(AutoSelector.pickFastest(emptyList()))
  }

  @Test
  fun planMeasuresOnlyWhenNeededAndAllowed() {
    val candidates = listOf(cpu, gpu, npu)
    assertEquals(AutoPlan.Measure(candidates), AutoSelector.plan(candidates, null, emptySet(), null))
    // A crashed backend is never measured again.
    assertEquals(AutoPlan.Measure(listOf(cpu, gpu)), AutoSelector.plan(candidates, null, setOf(npu), null))
    // A valid memory is used as is.
    val remembered = AutoRecord(gpu, 14.0, 1L)
    val use = AutoSelector.plan(candidates, remembered, emptySet(), null) as AutoPlan.Use
    assertEquals(gpu, use.backend)
    assertEquals(14.0, use.tokensPerSecond!!, 0.001)
    // Remembered backend failed since: measure the others.
    assertEquals(AutoPlan.Measure(listOf(cpu, npu)), AutoSelector.plan(candidates, remembered, setOf(gpu), null))
  }

  @Test
  fun planFallsBackToTheCpuWhenMeasuringIsRefused() {
    val use =
      AutoSelector.plan(listOf(cpu, gpu), null, emptySet(), "mesure reportée : batterie faible") as AutoPlan.Use
    assertEquals(cpu, use.backend)
    assertEquals("mesure reportée : batterie faible", use.reason)
  }

  @Test
  fun planWithASingleBackendOrNothingLeft() {
    assertEquals(cpu, (AutoSelector.plan(listOf(cpu), null, emptySet(), null) as AutoPlan.Use).backend)
    // Everything failed: CPU, never a loop.
    assertEquals(cpu, (AutoSelector.plan(listOf(cpu, gpu), null, setOf(cpu, gpu), null) as AutoPlan.Use).backend)
    // NPU-only model whose NPU failed: still that backend (nothing else exists).
    assertEquals(npu, (AutoSelector.plan(listOf(npu), null, setOf(npu), null) as AutoPlan.Use).backend)
  }

  @Test
  fun gateRefusesLowBatteryPowerSaveAndConversations() {
    val ok = DeviceState(batteryPercent = 80, charging = false, powerSave = false)
    assertNull(BenchmarkGate.refusal(ok, conversationActive = false, explicitLoad = true))
    assertNotNull(BenchmarkGate.refusal(ok, conversationActive = true, explicitLoad = true))
    assertNotNull(BenchmarkGate.refusal(ok, conversationActive = false, explicitLoad = false))
    assertNotNull(BenchmarkGate.refusal(ok.copy(powerSave = true), false, true))
    assertNotNull(BenchmarkGate.refusal(ok.copy(batteryPercent = 15), false, true))
    // Plugged in: a low battery does not stop it.
    assertNull(BenchmarkGate.refusal(ok.copy(batteryPercent = 15, charging = true), false, true))
    // Unknown battery level: allowed.
    assertNull(BenchmarkGate.refusal(ok.copy(batteryPercent = null), false, true))
  }

  @Test
  fun explanationLine() {
    assertEquals("Auto : GPU, 14 tok/s", AutoSelector.explain("GPU", 14.2))
    assertEquals("Auto : CPU, 7,5 tok/s", AutoSelector.explain("CPU", 7.46))
    assertEquals("Auto : CPU (seul calcul disponible)", AutoSelector.explain("CPU", null, "seul calcul disponible"))
  }

  private val fp = ModelFingerprint("argora-2b.gguf", 1_600_000_000L, 1000L, "v1")

  @Test
  fun memoryIsKeptPerModelAndPhone() {
    val store = MapKeyValueStore()
    val memory = AutoMemory(store, deviceId = "phone-A|41")
    assertNull(memory.recall(fp))
    memory.remember(fp, gpu, 14.0, nowMs = 5L)
    val rec = memory.recall(fp)!!
    assertEquals(gpu, rec.backend)
    assertEquals(14.0, rec.tokensPerSecond!!, 0.001)
    assertEquals(5L, rec.measuredAtMs)
    // Another model is not concerned.
    assertNull(memory.recall(fp.copy(modelName = "other.gguf")))
  }

  @Test
  fun memoryIsInvalidatedWhenTheModelTheAppOrThePhoneChanges() {
    val store = MapKeyValueStore()
    AutoMemory(store, "phone-A|41").remember(fp, gpu, 14.0, 5L)
    val memory = AutoMemory(store, "phone-A|41")
    assertNull(memory.recall(fp.copy(fileBytes = fp.fileBytes + 1)))
    assertNull(memory.recall(fp.copy(fileModifiedMs = 2000L)))
    assertNull(memory.recall(fp.copy(modelVersion = "v2")))
    // App update (version code in the device id) or system update.
    assertNull(AutoMemory(store, "phone-A|42").recall(fp))
    assertNotNull(memory.recall(fp))
  }

  @Test
  fun forgetDropsTheMeasurementOnly() {
    val store = MapKeyValueStore()
    val memory = AutoMemory(store, "d")
    memory.remember(fp, gpu, 14.0, 5L)
    memory.markFailed(fp, npu)
    memory.forget(fp.modelName)
    assertNull(memory.recall(fp))
    assertEquals(setOf(npu), memory.failedOf(fp))
  }

  @Test
  fun aFailureInvalidatesTheRememberedChoice() {
    val memory = AutoMemory(MapKeyValueStore(), "d")
    memory.remember(fp, gpu, 14.0, 5L)
    memory.markFailed(fp, gpu)
    assertNull(memory.recall(fp))
    assertEquals(setOf(gpu), memory.failedOf(fp))
    memory.clearFailures(fp.modelName)
    assertTrue(memory.failedOf(fp).isEmpty())
  }

  @Test
  fun anAttemptThatNeverEndedIsACrashReportedOnce() {
    val store = MapKeyValueStore()
    // The process dies while the GPU starts: endAttempt is never called.
    AutoMemory(store, "d").beginAttempt(fp, gpu)
    val next = AutoMemory(store, "d")
    assertEquals(gpu, next.takeCrashedAttempt(fp))
    assertEquals(setOf(gpu), next.failedOf(fp))
    assertNull(next.takeCrashedAttempt(fp))
    // The next Auto load does not try the GPU again.
    val plan = AutoSelector.plan(listOf(cpu, gpu), next.recall(fp), next.failedOf(fp), null) as AutoPlan.Use
    assertEquals(cpu, plan.backend)
  }

  @Test
  fun anAttemptThatEndedIsNotACrash() {
    val memory = AutoMemory(MapKeyValueStore(), "d")
    memory.beginAttempt(fp, gpu)
    memory.endAttempt(fp)
    assertNull(memory.takeCrashedAttempt(fp))
    assertTrue(memory.failedOf(fp).isEmpty())
  }
}
