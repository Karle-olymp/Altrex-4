package com.google.ai.edge.gallery.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The new side menu (4 spaces, 3 bottom buttons) and the settings in 6 blocks, drawn for real. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h820dp-xhdpi")
class DrawerAndSettingsRenderTest {
  @get:Rule val compose = createComposeRule()

  @After
  fun resetTheme() {
    ThemeSettings.themeOverride.value = Theme.THEME_AUTO
  }

  private fun draw(frames: Int = 40) {
    repeat(frames) { compose.mainClock.advanceTimeBy(16) }
    compose.waitForIdle()
    compose.onRoot().captureToImage()
  }

  @Test
  fun drawerShowsFourSpacesAndThreeButtons() {
    val opened = mutableListOf<String>()
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        AltrexDrawer(
          isOpen = true,
          currentTaskId = BuiltInTaskId.LLM_CHAT,
          loadedModelName = "argora-2b",
          onOpenTask = { opened += it },
          onOpenModels = { opened += "models" },
          onOpenConnectors = { opened += "connectors" },
          onOpenSettings = { opened += "settings" },
          onOpenAssistantGuide = { opened += "assistant" },
        )
      }
    }
    draw(80)
    for (label in listOf("Discuter", "Forge", "Notes vocales", "Terminal", "Modèles", "Connecteurs", "Réglages")) {
      compose.onNodeWithText(label).assertIsDisplayed()
    }
    // Google's tools are gone from the menu.
    for (label in listOf("Ask Image", "Audio Scribe", "Agent Skills", "Outils")) {
      assertEquals(0, compose.onAllNodesWithText(label, ignoreCase = true).fetchSemanticsNodes().size)
    }
    compose.onNodeWithText("argora-2b").assertIsDisplayed()

    compose.onNodeWithText("Discuter").performClick()
    compose.onNodeWithText("Terminal").performClick()
    compose.onNodeWithText("Connecteurs").performClick()
    compose.onNodeWithText("Modèles").performClick()
    compose.onNodeWithText("Réglages").performClick()
    assertEquals(
      listOf(
        BuiltInTaskId.LLM_CHAT,
        com.google.ai.edge.gallery.terminal.TERMINAL_ENTRY_ID,
        "connectors",
        "models",
        "settings",
      ),
      opened,
    )
  }

  @Test
  fun drawerWithoutModelInLightTheme() {
    ThemeSettings.themeOverride.value = Theme.THEME_LIGHT
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        AltrexDrawer(
          isOpen = true,
          currentTaskId = MODELS_ENTRY_ID,
          loadedModelName = null,
          onOpenTask = {},
          onOpenModels = {},
          onOpenConnectors = {},
          onOpenSettings = {},
          onOpenAssistantGuide = {},
        )
      }
    }
    draw(80)
    compose.onNodeWithText("Aucun modèle chargé").assertIsDisplayed()
  }

  @Test
  fun settingsOpenWithTheSixBlocks() {
    // Robolectric leaves it null; the model's Linux block reads it (PRoot lives there on a phone).
    RuntimeEnvironment.getApplication().applicationInfo.nativeLibraryDir =
      System.getProperty("java.io.tmpdir")
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        SettingsPage(
          curThemeOverride = Theme.THEME_AUTO,
          readHfToken = { null },
          saveHfToken = {},
          clearHfToken = {},
          saveTheme = {},
          onDismissed = {},
        )
      }
    }
    draw()
    assertEquals(6, SETTINGS_BLOCKS.size)
    for (block in SETTINGS_BLOCKS) {
      assertTrue(
        "missing block $block",
        compose.onAllNodesWithText(block.uppercase()).fetchSemanticsNodes().isNotEmpty(),
      )
    }
    // Every setting is still reachable: the token, the keys, the model's Linux, memory, licences.
    for (text in
      listOf(
        "Jeton Hugging Face",
        "Modèles cloud",
        "Services en ligne",
        "Brave Search",
        "SecurCode",
        "GitHub",
        "Ses commandes",
        "Choisir un dossier",
        "Ce qui sort du téléphone",
        "Version",
      )) {
      assertTrue(
        "missing $text",
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
      )
    }
    // The developer test window is gone.
    assertEquals(0, compose.onAllNodesWithText("Linux (test)").fetchSemanticsNodes().size)
    compose.mainClock.autoAdvance = true
    compose.onNodeWithText("Jeton Hugging Face").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Coller un jeton hf_…", useUnmergedTree = true).assertExists()
  }
}
