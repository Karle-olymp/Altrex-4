package com.google.ai.edge.gallery.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tool progress panel speaks French, while running and once done. */
class ProgressLabelsTest {
  @Test
  fun runningAndDone() {
    assertEquals("Chargement du skill « météo »", ProgressLabels.loadingSkill("météo"))
    assertEquals("Skill « météo » chargé", ProgressLabels.done(ProgressLabels.loadingSkill("météo")))
    assertEquals("Script JS « a/b.js » appelé", ProgressLabels.done(ProgressLabels.callingScript("a", "b.js")))
    assertEquals("Outil MCP « search » appelé", ProgressLabels.done(ProgressLabels.callingMcpTool("search")))
    assertEquals("Action « send_email » exécutée", ProgressLabels.done(ProgressLabels.runningAction("send_email")))
  }

  @Test
  fun otherLabelsUnchanged() {
    assertEquals("Recherche web", ProgressLabels.done("Recherche web"))
  }
}
