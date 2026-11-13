package com.google.ai.edge.gallery.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context size and threads chosen from the free memory, the model size and the CPU. */
class AutoParamsTest {
  private val mb = 1024L * 1024L
  private val gb = 1024L * mb
  private val s22Freqs = listOf(1_785_600L, 1_785_600L, 1_785_600L, 1_785_600L, 2_496_000L, 2_496_000L, 2_496_000L, 2_995_200L)

  @Test
  fun smallModelWithPlentyOfRamGetsThePreferredMaximum() {
    // S22 Ultra: 12 GB, 5 GB free, Argora 2B Q5_K_M (~1.5 GB).
    val p =
      AutoParamsCalculator.compute(
        MemorySnapshot(totalBytes = 12 * gb, availBytes = 5 * gb, thresholdBytes = 216 * mb),
        fileBytes = 1_500 * mb,
        maxContext = 16384,
        cpuCores = 8,
        cpuMaxFreqs = s22Freqs,
      )
    assertEquals(8192, p.contextTokens)
    assertEquals(4, p.threads)
    assertTrue(p.fits)
  }

  @Test
  fun neverAboveTheModelMaximum() {
    val p = AutoParamsCalculator.compute(MemorySnapshot(12 * gb, 8 * gb), 500 * mb, maxContext = 4096, cpuCores = 8)
    assertEquals(4096, p.contextTokens)
  }

  @Test
  fun lessFreeRamGivesASmallerContextWithTheSafetyMargin() {
    val mem = MemorySnapshot(totalBytes = 8 * gb, availBytes = 5 * gb)
    val file = 3 * gb
    val p = AutoParamsCalculator.compute(mem, file, maxContext = 16384, cpuCores = 8)
    // Margin: 10 % of 8 GB (> 512 MB). The KV cache gets what is left.
    val margin = AutoParamsCalculator.safetyMargin(mem)
    assertEquals(8 * gb / 10, margin)
    val kv = AutoParamsCalculator.kvBytesPerToken(file)
    val expected = (((5 * gb - margin - file - AutoParamsCalculator.RUNTIME_OVERHEAD_BYTES) / kv) / 1024 * 1024).toInt()
    assertEquals(expected, p.contextTokens)
    assertTrue(p.contextTokens in 1024 until 8192)
    assertEquals(0, p.contextTokens % 1024)
    // With the chosen context, the margin stays free.
    assertTrue(mem.availBytes - p.neededBytes >= margin)
  }

  @Test
  fun tooLittleRamKeepsTheMinimumAndSaysItDoesNotFit() {
    val p = AutoParamsCalculator.compute(MemorySnapshot(6 * gb, 1 * gb), fileBytes = 4 * gb, maxContext = 8192, cpuCores = 8)
    assertEquals(AutoParamsCalculator.MIN_CONTEXT, p.contextTokens)
    assertFalse(p.fits)
  }

  @Test
  fun marginFollowsTheSystemThreshold() {
    assertEquals(512 * mb, AutoParamsCalculator.safetyMargin(MemorySnapshot(4 * gb, 1 * gb, 100 * mb)))
    assertEquals(900 * mb, AutoParamsCalculator.safetyMargin(MemorySnapshot(4 * gb, 1 * gb, 900 * mb)))
  }

  @Test
  fun threadsOnTheFastCores() {
    // 1 + 3 + 4 layout (S22 Ultra): 4 fast cores.
    assertEquals(4, AutoParamsCalculator.autoThreads(8, s22Freqs))
    // 2 fast cores.
    assertEquals(2, AutoParamsCalculator.autoThreads(8, listOf(1L, 1L, 1L, 1L, 1L, 1L, 2L, 2L)))
    // All cores alike or unreadable: native fallback rule.
    assertEquals(4, AutoParamsCalculator.autoThreads(8, List(8) { 2_000_000L }))
    assertEquals(2, AutoParamsCalculator.autoThreads(4, emptyList()))
    assertEquals(3, AutoParamsCalculator.autoThreads(3, emptyList()))
    assertEquals(1, AutoParamsCalculator.autoThreads(0, emptyList()))
  }

  @Test
  fun kvCostIsBounded() {
    assertEquals(32L * 1024L, AutoParamsCalculator.kvBytesPerToken(100 * mb))
    assertEquals(160L * 1024L, AutoParamsCalculator.kvBytesPerToken(20 * gb))
  }

  @Test
  fun bytesInFrench() {
    assertEquals("1,5 Go", AutoParamsCalculator.formatBytes(1_536 * mb))
    assertEquals("820 Mo", AutoParamsCalculator.formatBytes(820 * mb))
  }
}
