package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The logo and its animations, drawn frame after frame: any crash in them fails here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BrandRenderTest {
  @get:Rule val compose = createComposeRule()

  private fun runFrames(ms: Long) {
    compose.mainClock.autoAdvance = false
    var t = 0L
    while (t < ms) {
      compose.mainClock.advanceTimeBy(40)
      compose.waitForIdle()
      // Draws the frame for real (a capture), so drawing code runs, not only composition.
      compose.onRoot().captureToImage()
      t += 40
    }
  }

  @Test
  fun intro() {
    compose.setContent { MaterialTheme { AltrexIntro(onFinished = {}) } }
    runFrames(3200)
  }

  @Test
  fun modelLoading() {
    compose.setContent {
      MaterialTheme { AltrexModelLoading(title = "Chargement", modelName = "argora", hint = "…") }
    }
    runFrames(4000)
  }

  @Test
  fun smallLoadersAndAvatar() {
    compose.setContent {
      MaterialTheme {
        Column {
          AltrexLogoLoader(size = 26.dp)
          AltrexAvatar()
          AltrexAvatar(size = 44.dp)
        }
      }
    }
    runFrames(2000)
  }

  @Test
  fun logoDrawables() {
    compose.setContent {
      Column {
        Image(painterResource(R.drawable.logo), null, Modifier.size(20.dp))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(100.dp))
        Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(100.dp))
        RotationalLoader(size = 160.dp)
      }
    }
    runFrames(1000)
  }
}
