package com.google.ai.edge.gallery.compute

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which compute choices each engine offers, and how a stored choice is read back. */
class ComputeOptionsTest {
  @Test
  fun ggufModelsOnlyOfferTheCpu() {
    // The native llama.cpp build has the CPU backend only, whatever the import declared.
    val backends =
      ComputeOptions.backendsFor(
        RuntimeType.LLAMA_CPP,
        listOf(Accelerator.GPU, Accelerator.NPU, Accelerator.CPU),
      )
    assertEquals(listOf(ComputeChoice.CPU), backends)
    val options = ComputeOptions.optionsFor(RuntimeType.LLAMA_CPP, emptyList(), SocKind.QUALCOMM)
    assertEquals(listOf(ComputeChoice.AUTO, ComputeChoice.CPU), options.map { it.choice })
    assertTrue(options[0].detail.contains("Un seul calcul"))
  }

  @Test
  fun liteRtModelsOfferWhatTheyDeclareInAFixedOrder() {
    val backends =
      ComputeOptions.backendsFor(
        RuntimeType.LITERT_LM,
        listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
      )
    assertEquals(listOf(ComputeChoice.CPU, ComputeChoice.GPU, ComputeChoice.NPU), backends)
  }

  @Test
  fun liteRtWithoutDeclarationOffersCpuAndGpu() {
    assertEquals(
      listOf(ComputeChoice.CPU, ComputeChoice.GPU),
      ComputeOptions.backendsFor(RuntimeType.LITERT_LM, emptyList()),
    )
  }

  @Test
  fun theModelFileNarrowsTheListButNeverEmptiesIt() {
    val declared = listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
    assertEquals(
      listOf(ComputeChoice.CPU, ComputeChoice.GPU),
      ComputeOptions.backendsFor(RuntimeType.LITERT_LM, declared, setOf(ComputeChoice.CPU, ComputeChoice.GPU)),
    )
    assertEquals(
      listOf(ComputeChoice.CPU, ComputeChoice.GPU, ComputeChoice.NPU),
      ComputeOptions.backendsFor(RuntimeType.LITERT_LM, declared, setOf(ComputeChoice.AUTO)),
    )
  }

  @Test
  fun npuIsHexagonOnQualcommAndAlwaysExperimental() {
    val options =
      ComputeOptions.optionsFor(
        RuntimeType.LITERT_LM,
        listOf(Accelerator.CPU, Accelerator.NPU),
        SocKind.QUALCOMM,
      )
    val npu = options.first { it.choice == ComputeChoice.NPU }
    assertEquals("NPU Hexagon", npu.title)
    assertTrue(npu.experimental)
    assertFalse(options.first { it.choice == ComputeChoice.CPU }.experimental)
    assertEquals("NPU", ComputeOptions.title(ComputeChoice.NPU, SocKind.OTHER))
  }

  @Test
  fun remoteModelsHaveNoComputeChoice() {
    assertTrue(ComputeOptions.optionsFor(RuntimeType.REMOTE, listOf(Accelerator.CPU), SocKind.QUALCOMM).isEmpty())
    assertTrue(ComputeOptions.optionsFor(RuntimeType.AICORE, emptyList(), SocKind.TENSOR).isEmpty())
  }

  @Test
  fun aStoredChoiceThatIsNotOfferedReadsAsAuto() {
    val gguf = listOf(ComputeChoice.CPU)
    assertEquals(ComputeChoice.AUTO, ComputeOptions.resolve("GPU", gguf))
    assertEquals(ComputeChoice.CPU, ComputeOptions.resolve("CPU", gguf))
    assertEquals(ComputeChoice.AUTO, ComputeOptions.resolve("", gguf))
    assertEquals(ComputeChoice.AUTO, ComputeOptions.resolve(null, gguf))
    assertEquals(ComputeChoice.NPU, ComputeOptions.resolve("TPU", listOf(ComputeChoice.CPU, ComputeChoice.NPU)))
  }

  @Test
  fun socKindFromBuildFields() {
    assertEquals(SocKind.QUALCOMM, SocKind.from("QTI", "SM8450"))
    assertEquals(SocKind.QUALCOMM, SocKind.from("", "sm8450"))
    assertEquals(SocKind.TENSOR, SocKind.from("Google", "Tensor G3"))
    assertEquals(SocKind.OTHER, SocKind.from("Mediatek", "MT6983"))
  }
}
