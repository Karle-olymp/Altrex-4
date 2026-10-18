package com.google.ai.edge.gallery.ui.home

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.ui.common.AltrexAvatar
import com.google.ai.edge.gallery.ui.common.AltrexLogoLoader

/** [ALTREX] Whether Altrex is the phone's assistant, and the way to the setting that makes it so. */
object AssistantSetup {
  private const val PREFS = "altrex_assistant"
  private const val KEY_GUIDE_OFFERED = "guide_offered"

  fun isDefaultAssistant(context: Context): Boolean {
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
  }

  /** The guide opens by itself once, and only while Altrex is not the assistant yet. */
  fun shouldOfferGuide(context: Context): Boolean =
    !isDefaultAssistant(context) &&
      !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUIDE_OFFERED, false)

  fun markGuideOffered(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GUIDE_OFFERED, true).apply()
  }

  /**
   * Apps cannot take the assistant role themselves: Android asks the user in its settings. This
   * opens the closest page ("Assistant numérique"), or the default apps list, or the settings.
   */
  fun openAssistantSettings(context: Context) {
    val actions =
      listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
      )
    for (action in actions) {
      try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
      } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
  }

  val isSamsung: Boolean
    get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}

private data class GuidePage(val title: String, val body: String, val illustration: @Composable () -> Unit)

private const val PAGE_DONE = -1

/**
 * Guide shown once after a model is loaded (and from the side menu): what Altrex can do as the
 * phone's assistant, one animated page at a time, then a button that opens the right setting.
 * Back from the settings with Altrex chosen, the guide says it is ready.
 */
@Composable
fun AssistantGuideDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val pages = remember {
    listOf(
      GuidePage(
        "Altrex, l'assistant de ton téléphone",
        "Appelle Altrex depuis n'importe quelle application, sans quitter ce que tu fais. " +
          "Tout reste sur ton téléphone : rien n'est envoyé sur Internet.",
      ) {
        WelcomeIllustration()
      },
      GuidePage(
        "Un appui, et Altrex est là",
        if (AssistantSetup.isSamsung) {
          "Maintiens la touche latérale : Altrex s'ouvre par-dessus l'app en cours et peut lire " +
            "le texte affiché pour te l'expliquer ou le résumer."
        } else {
          "Maintiens le bouton d'alimentation ou balaie depuis un coin de l'écran : Altrex s'ouvre " +
            "par-dessus l'app en cours et peut lire le texte affiché."
        },
      ) {
        SideKeyIllustration()
      },
      GuidePage(
        "Sélectionne, demande",
        "Sur un texte sélectionné, choisis « Demander à Altrex » : expliquer, résumer, traduire, " +
          "ou vérifier qu'un message n'est pas une arnaque.",
      ) {
        SelectionIllustration()
      },
      GuidePage(
        "Partage tes fichiers",
        "PDF, fichiers texte, configurations, journaux, images : partage-les vers Altrex pour les " +
          "faire analyser.",
      ) {
        ShareIllustration()
      },
    )
  }
  val activatePage = pages.size
  var page by rememberSaveable { mutableIntStateOf(0) }
  var forward by remember { mutableStateOf(true) }
  var askedSettings by rememberSaveable { mutableStateOf(false) }

  // Back from the settings: done if Altrex was chosen.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && AssistantSetup.isDefaultAssistant(context)) {
        forward = true
        page = PAGE_DONE
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun goTo(target: Int) {
    forward = target > page || target == PAGE_DONE
    page = target
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        AnimatedContent(
          targetState = page,
          transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) {
              dir * it / 3
            } + fadeIn(tween(250))) togetherWith
              (slideOutHorizontally(tween(220)) { -dir * it / 3 } + fadeOut(tween(160)))
          },
          label = "guidePage",
          modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { current ->
          when (current) {
            PAGE_DONE -> DonePage()
            activatePage -> ActivatePage(askedSettings = askedSettings)
            else -> IllustratedPage(pages[current])
          }
        }

        if (page != PAGE_DONE) {
          PageDots(count = pages.size + 1, current = page)
          Spacer(Modifier.height(20.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          when (page) {
            PAGE_DONE -> {
              Spacer(Modifier.weight(1f))
              Button(onClick = onDismiss) { Text("Terminer") }
            }
            activatePage -> {
              TextButton(onClick = onDismiss) { Text("Plus tard") }
              Spacer(Modifier.weight(1f))
              Button(
                onClick = {
                  askedSettings = true
                  AssistantSetup.openAssistantSettings(context)
                }
              ) {
                Text(if (askedSettings) "Rouvrir les réglages" else "Activer")
              }
            }
            else -> {
              TextButton(onClick = if (page == 0) onDismiss else ({ goTo(page - 1) })) {
                Text(if (page == 0) "Passer" else "Retour")
              }
              Spacer(Modifier.weight(1f))
              Button(onClick = { goTo(page + 1) }) { Text("Suivant") }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun IllustratedPage(page: GuidePage) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(modifier = Modifier.height(300.dp), contentAlignment = Alignment.Center) { page.illustration() }
    Spacer(Modifier.height(28.dp))
    Text(
      page.title,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      page.body,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ActivatePage(askedSettings: Boolean) {
  val steps = buildList {
    add("Touche « Activer » : les réglages d'Android s'ouvrent.")
    add("Choisis « Application d'assistant numérique » (ou « Assistance de l'appareil »), puis Altrex.")
    if (AssistantSetup.isSamsung) {
      add(
        "Pour la touche latérale : Paramètres > Fonctions avancées > Touche latérale > " +
          "Appui prolongé > « Assistant numérique »."
      )
    }
  }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    AltrexLogoLoader(size = 88.dp)
    Spacer(Modifier.height(24.dp))
    Text(
      "Active Altrex",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Android demande ton accord dans ses réglages : Altrex ne peut pas se choisir lui-même.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    steps.forEachIndexed { index, step ->
      Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
          modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
        Spacer(Modifier.width(14.dp))
        Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 3.dp))
      }
    }
    if (askedSettings) {
      Spacer(Modifier.height(16.dp))
      Text(
        "Altrex n'est pas encore choisi comme assistant.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Rounded.Lock,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(6.dp))
      Text(
        "Altrex ne lit l'écran que quand tu l'appelles.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DonePage() {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
      visible = shown,
      enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) + fadeIn(),
    ) {
      Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(96.dp),
      )
    }
    Spacer(Modifier.height(24.dp))
    Text("Altrex est ton assistant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
      if (AssistantSetup.isSamsung) {
        "Dernière étape pour la touche latérale : Paramètres > Fonctions avancées > Touche latérale > " +
          "Appui prolongé > « Assistant numérique »."
      } else {
        "Maintiens le bouton d'alimentation, ou balaie depuis un coin bas de l'écran, pour l'appeler."
      },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun PageDots(count: Int, current: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { index ->
      val width by animateDpAsState(if (index == current) 22.dp else 8.dp, spring(), label = "dot")
      Box(
        modifier =
          Modifier.size(width = width, height = 8.dp)
            .clip(CircleShape)
            .background(
              if (index == current) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outlineVariant
            )
      )
    }
  }
}

// ---- Illustrations: a small phone whose content plays the gesture in a loop. ----

/** Eased progress of [t] between [start] and [end], from 0 to 1. */
private fun phase(t: Float, start: Float, end: Float): Float =
  FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

@Composable
private fun loopProgress(durationMs: Int): Float {
  val transition = rememberInfiniteTransition(label = "guideLoop")
  val t by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
      label = "guideT",
    )
  return t
}

private val PHONE_W = 150.dp
private val PHONE_H = 280.dp

@Composable
private fun PhoneMock(sideKeyPress: Float = 0f, content: @Composable BoxScope.() -> Unit) {
  Box {
    Box(
      modifier =
        Modifier.size(PHONE_W, PHONE_H)
          .clip(RoundedCornerShape(26.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh)
          .border(3.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(26.dp))
          .padding(10.dp)
    ) {
      content()
    }
    // Side key, on the right edge.
    Box(
      modifier =
        Modifier.offset(x = PHONE_W - 1.dp - (sideKeyPress * 3f).dp, y = 70.dp)
          .size(5.dp, 34.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(
            if (sideKeyPress > 0.01f) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
          )
    )
  }
}

@Composable
private fun TextLines(count: Int, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(count) { i ->
      Box(
        modifier =
          Modifier.fillMaxWidth(if (i % 3 == 2) 0.6f else 0.95f)
            .height(7.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f))
      )
    }
  }
}

/** The Altrex sheet rising from the bottom of the mock screen ([rise] from 0 to 1). */
@Composable
private fun BoxScope.MockSheet(rise: Float) {
  if (rise <= 0f) return
  Box(
    modifier =
      Modifier.align(Alignment.BottomCenter)
        .graphicsLayer {
          translationY = (1f - rise) * 120.dp.toPx()
          alpha = rise.coerceAtMost(1f)
        }
        .fillMaxWidth()
        .height(108.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .padding(10.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        AltrexAvatar(size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text("Altrex", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(30.dp, 38.dp, 26.dp).forEach { w ->
          Box(
            Modifier.size(w, 14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
          )
        }
      }
      Box(
        Modifier.fillMaxWidth().height(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
      )
    }
  }
}

@Composable
private fun WelcomeIllustration() {
  val t = loopProgress(2600)
  val glow = 0.5f + 0.5f * kotlin.math.sin(t * 2f * Math.PI.toFloat())
  Box(contentAlignment = Alignment.Center) {
    Box(
      modifier =
        Modifier.size(200.dp)
          .graphicsLayer {
            scaleX = 0.9f + 0.1f * glow
            scaleY = 0.9f + 0.1f * glow
            alpha = 0.25f + 0.25f * glow
          }
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer)
    )
    AltrexLogoLoader(size = 120.dp)
  }
}

@Composable
private fun SideKeyIllustration() {
  val t = loopProgress(3600)
  val press = phase(t, 0.08f, 0.16f) * (1f - phase(t, 0.24f, 0.3f))
  val rise = phase(t, 0.3f, 0.48f) * (1f - phase(t, 0.88f, 0.98f))
  PhoneMock(sideKeyPress = press) {
    TextLines(9, Modifier.padding(top = 18.dp))
    // Dim of the app behind the sheet.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f * rise)))
    MockSheet(rise)
  }
}

@Composable
private fun SelectionIllustration() {
  val t = loopProgress(4200)
  val select = phase(t, 0.06f, 0.3f)
  val menu = phase(t, 0.34f, 0.42f) * (1f - phase(t, 0.56f, 0.6f))
  val tap = phase(t, 0.48f, 0.56f)
  val rise = phase(t, 0.6f, 0.74f) * (1f - phase(t, 0.92f, 0.99f))
  val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
  PhoneMock {
    Box(modifier = Modifier.padding(top = 18.dp)) {
      TextLines(9)
      // Selection highlight over lines 3 and 4.
      Column(modifier = Modifier.padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth(0.95f * phase(select, 0f, 0.5f)).height(11.dp).background(highlight))
        Box(Modifier.fillMaxWidth(0.95f * phase(select, 0.5f, 1f)).height(11.dp).background(highlight))
      }
    }
    if (menu > 0f) {
      Box(
        modifier =
          Modifier.offset(y = 4.dp)
            .align(Alignment.TopCenter)
            .graphicsLayer {
              scaleX = 0.8f + 0.2f * menu
              scaleY = 0.8f + 0.2f * menu
              alpha = menu
            }
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp)
      ) {
        Text("Demander à Altrex", fontSize = 10.sp, color = MaterialTheme.colorScheme.inverseOnSurface)
        // Finger tap.
        Box(
          Modifier.align(Alignment.Center)
            .size(26.dp)
            .graphicsLayer {
              scaleX = 0.6f + 0.6f * tap
              scaleY = 0.6f + 0.6f * tap
              alpha = if (tap > 0f) (1f - tap) * 0.8f else 0f
            }
            .clip(CircleShape)
            .background(Color.White)
        )
      }
    }
    MockSheet(rise)
  }
}

@Composable
private fun ShareIllustration() {
  val t = loopProgress(3800)
  val panel = phase(t, 0.08f, 0.22f) * (1f - phase(t, 0.62f, 0.7f))
  val pulse = phase(t, 0.3f, 0.42f) * (1f - phase(t, 0.52f, 0.6f))
  val fly = phase(t, 0.42f, 0.62f)
  val rise = phase(t, 0.68f, 0.8f) * (1f - phase(t, 0.93f, 0.99f))
  PhoneMock {
    // A file in the file manager.
    Row(
      modifier =
        Modifier.padding(top = 20.dp)
          .graphicsLayer {
            translationY = fly * 150.dp.toPx()
            scaleX = 1f - 0.5f * fly
            scaleY = 1f - 0.5f * fly
            alpha = 1f - fly
          }
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerLowest)
          .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Rounded.Description,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Spacer(Modifier.width(6.dp))
      Text("rapport.pdf", fontSize = 10.sp)
    }
    // Share panel with app targets.
    if (panel > 0f) {
      Row(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .graphicsLayer {
              translationY = (1f - panel) * 80.dp.toPx()
              alpha = panel
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        ShareTarget()
        Box(
          modifier = Modifier.graphicsLayer {
            scaleX = 1f + 0.25f * pulse
            scaleY = 1f + 0.25f * pulse
          }
        ) {
          AltrexAvatar(size = 28.dp)
        }
        ShareTarget()
      }
    }
    MockSheet(rise)
  }
}

@Composable
private fun ShareTarget(size: Dp = 28.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}
