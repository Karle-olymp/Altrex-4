package org.nehuatl.llamacpp

/**
 * [ALTREX] Diagnostic options read when the native library is first loaded. Kept out of
 * [LlamaContext] so that setting them does not load the library first. Set at app start.
 */
object NativeCpuOptions {
    /** Load the plain ARMv8 build (no dotprod/i8mm kernels): slower, for diagnosing bad output. */
    @Volatile var forceBasicCpu: Boolean = false

    const val PREFS = "altrex_native"
    const val KEY_FORCE_BASIC_CPU = "force_basic_cpu"
}
