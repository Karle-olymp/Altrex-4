package com.google.ai.edge.gallery.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The last used model: remembered, proposed first, never chosen when unusable. */
class LastModelTest {
  @Test
  fun remembersAndForgets() {
    val store = LastModelStore(MapKeyValueStore())
    assertNull(store.get())
    store.set("argora-2b.gguf")
    assertEquals("argora-2b.gguf", store.get())
    store.set("")
    assertEquals("argora-2b.gguf", store.get())
    store.clearIf("other")
    assertEquals("argora-2b.gguf", store.get())
    store.clearIf("argora-2b.gguf")
    assertNull(store.get())
  }

  @Test
  fun lastUsedComesFirstOtherwiseUnchanged() {
    val items = listOf("space:argora", "qwen.gguf", "gemma.litertlm")
    assertEquals(listOf("qwen.gguf", "space:argora", "gemma.litertlm"), ModelOrdering.lastUsedFirst(items, "qwen.gguf") { it })
    assertEquals(items, ModelOrdering.lastUsedFirst(items, null) { it })
    assertEquals(items, ModelOrdering.lastUsedFirst(items, "deleted.gguf") { it })
  }

  @Test
  fun preferredIsTheLastUsedWhenUsable() {
    val items = listOf("space:argora", "qwen.gguf")
    val downloaded = setOf("space:argora", "qwen.gguf")
    // Before: always the first one (the Argora Space). Now: the last used.
    assertEquals("qwen.gguf", ModelOrdering.preferred(items, "qwen.gguf", { it }) { it in downloaded })
    // Last used deleted: first usable.
    assertEquals("space:argora", ModelOrdering.preferred(items, "gone", { it }) { it in downloaded })
    assertEquals("qwen.gguf", ModelOrdering.preferred(items, "space:argora", { it }) { it == "qwen.gguf" })
    // Nothing usable: the first one.
    assertEquals("space:argora", ModelOrdering.preferred(items, null, { it }) { false })
    assertNull(ModelOrdering.preferred(emptyList<String>(), "x", { it }) { true })
  }
}
