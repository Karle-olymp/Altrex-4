package com.google.ai.edge.gallery.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The welcome screen that replaces Google's home screen: drawn for real, and its buttons work. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h820dp-xhdpi")
class WelcomeRenderTest {
  @get:Rule val compose = createComposeRule()

  @After
  fun resetTheme() {
    ThemeSettings.themeOverride.value = Theme.THEME_AUTO
  }

  private fun draw(frames: Int = 30) {
    repeat(frames) { compose.mainClock.advanceTimeBy(16) }
    compose.waitForIdle()
    compose.onRoot().captureToImage()
  }

  @Test
  fun buttonsAndMenu() {
    val clicks = mutableListOf<String>()
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        WelcomeScreen(
          onOpenMenu = { clicks += "menu" },
          onOpenModels = { clicks += "models" },
          onAddCloudModel = { clicks += "cloud" },
          onImportModel = { clicks += "import" },
        )
      }
    }
    draw()
    compose.onNodeWithText("Bienvenue dans Altrex").assertIsDisplayed()
    compose.onNodeWithText("Choisir un modèle à télécharger").performClick()
    compose.onNodeWithText("Ajouter un modèle cloud").performClick()
    compose.onNodeWithText("Importer un modèle").performClick()
    compose.onNodeWithContentDescription("Ouvrir le menu").performClick()
    assertEquals(listOf("models", "cloud", "import", "menu"), clicks)
  }

  @Test
  fun offlineOffersRetry() {
    var retried = false
    ThemeSettings.themeOverride.value = Theme.THEME_DARK
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        WelcomeScreen(
          onOpenMenu = {},
          onOpenModels = {},
          onAddCloudModel = {},
          onImportModel = {},
          catalogUnavailable = true,
          onRetry = { retried = true },
        )
      }
    }
    draw()
    compose.onNodeWithText("Réessayer").performClick()
    assertEquals(true, retried)
  }

  @Test
  fun loadingStates() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      GalleryTheme {
        WelcomeScreen(onOpenMenu = {}, onOpenModels = {}, onAddCloudModel = {}, onImportModel = {}, loading = true)
      }
    }
    draw(60)
    compose.onNodeWithText("Chargement de la liste des modèles…").assertIsDisplayed()
  }

  @Test
  fun startLoader() {
    compose.mainClock.autoAdvance = false
    compose.setContent { GalleryTheme { StartLoadingScreen() } }
    draw(60)
  }
}
