package com.google.ai.edge.gallery.ui.navigation

import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.home.DRAWER_SPACE_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each menu entry leads somewhere the navigation graph knows. */
class DrawerRoutesTest {
  // The model tasks still registered (LlmChatTaskModule and LlmSingleTurnTaskModule).
  private val modelTasks = setOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_PROMPT_LAB)

  @Test
  fun fourSpacesInOrder() {
    assertEquals(
      listOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_PROMPT_LAB,
        com.google.ai.edge.gallery.notes.NOTES_ENTRY_ID,
        com.google.ai.edge.gallery.terminal.TERMINAL_ENTRY_ID,
      ),
      DRAWER_SPACE_IDS,
    )
  }

  @Test
  fun everyEntryHasARouteOrATask() {
    for (id in DRAWER_SPACE_IDS) {
      val route = drawerEntryRoute(id)
      if (route == null) {
        assertTrue("$id is neither a route nor a model task", id in modelTasks)
      } else {
        assertTrue("$id opens an unknown route $route", route in DIRECT_ROUTES)
      }
    }
    // The bottom button.
    assertTrue(drawerEntryRoute(com.google.ai.edge.gallery.connectors.CONNECTORS_ENTRY_ID) in DIRECT_ROUTES)
  }

  @Test
  fun modelTasksOpenWithTheirModel() {
    for (id in modelTasks) assertNull(drawerEntryRoute(id))
  }
}
