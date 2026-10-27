package com.google.ai.edge.gallery.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSpaceTest {
  @Test
  fun parseSpaceId_acceptsIdsAndLinks() {
    assertEquals("Autaris/argora-api", RemoteSpace.parseSpaceId("Autaris/argora-api"))
    assertEquals("Autaris/argora-api", RemoteSpace.parseSpaceId(" https://huggingface.co/spaces/Autaris/argora-api/ "))
    assertEquals("Autaris/argora-api", RemoteSpace.parseSpaceId("huggingface.co/spaces/Autaris/argora-api?logs=container"))
  }

  @Test
  fun parseSpaceId_rejectsOthers() {
    assertNull(RemoteSpace.parseSpaceId(""))
    assertNull(RemoteSpace.parseSpaceId("argora-api"))
    assertNull(RemoteSpace.parseSpaceId("https://autaris-argora-api.hf.space"))
    assertNull(RemoteSpace.parseSpaceId("evil.com/x@y"))
  }

  @Test
  fun baseUrl_followsHuggingFaceRule() {
    assertEquals("https://autaris-argora-api.hf.space", RemoteSpace.baseUrl("Autaris/argora-api"))
    assertEquals("https://my-org-my-space-v1-2.hf.space", RemoteSpace.baseUrl("My_Org/my.space-v1_2"))
  }

  @Test
  fun modelName_roundTrip_withoutSlash() {
    val name = RemoteSpace.modelName("Autaris/argora-api")
    // A "/" in a model name breaks the chat's navigation route (crash).
    assertFalse(name.contains('/'))
    assertTrue(RemoteSpace.isRemote(name))
    assertEquals("Autaris/argora-api", RemoteSpace.spaceId(name))
    // Old form saved by the first version.
    assertEquals("Autaris/argora-api", RemoteSpace.spaceId("space:Autaris/argora-api"))
  }

  @Test
  fun parseOutput_readsFirstValue() {
    assertEquals("<think>\nok\n</think>\n\nBonjour", RemoteSpace.parseOutput("[\"<think>\\nok\\n</think>\\n\\nBonjour\"]"))
  }
}
