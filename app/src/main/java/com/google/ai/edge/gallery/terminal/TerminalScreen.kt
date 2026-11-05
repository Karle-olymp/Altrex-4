package com.google.ai.edge.gallery.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

private const val TAG = "AGTerminal"

/** Id of the Terminal entry in the side menu (not a model task). */
const val TERMINAL_ENTRY_ID = "altrex_terminal"

/**
 * [ALTREX] The user's terminal session (Alpine shell through PRoot, in a real pseudo-terminal). It
 * outlives the screen: leaving and coming back finds the same shell, until it exits or the app is
 * closed.
 */
object UserTerminal {
  var session: TerminalSession? = null
    private set

  /** The running session, or a new shell. Call on the main thread. */
  fun sessionFor(context: Context, client: TerminalSessionClient): TerminalSession {
    session?.takeIf { it.isRunning }?.let {
      it.updateTerminalSessionClient(client)
      return it
    }
    val env = LinuxEnv.of(context, LinuxEnv.USER)
    // A login shell reads /etc/profile (prompt, PATH): bash once altrex-setup installed it.
    val launch =
      env.launch(listOf("/bin/sh", "-c", "[ -x /bin/bash ] && exec /bin/bash -l; exec /bin/sh -l"))
    val s =
      TerminalSession(
        launch.executable,
        env.rootfs.absolutePath,
        (listOf("proot") + launch.args).toTypedArray(),
        launch.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
        5_000,
        client,
      )
    session = s
    return s
  }

  /** Ends the shell; the next visit starts a new one. */
  fun close() {
    session?.finishIfRunning()
    session = null
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(navigateUp: () -> Unit) {
  val context = LocalContext.current
  val env = remember { LinuxEnv.of(context, LinuxEnv.USER) }
  var installed by remember { mutableStateOf(env.isInstalled) }
  // Bumped to rebuild the view on a new session.
  var generation by remember { mutableIntStateOf(0) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Terminal") },
        navigationIcon = {
          IconButton(onClick = navigateUp) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
          }
        },
        actions = {
          if (installed) {
            IconButton(
              onClick = {
                UserTerminal.close()
                generation++
              }
            ) {
              Icon(Icons.Rounded.Refresh, contentDescription = "Nouvelle session")
            }
          }
        },
      )
    }
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding()) {
      when {
        !env.isSupported ->
          Text(
            "Le terminal n'est pas disponible sur cet appareil (processeur arm64 requis).",
            modifier = Modifier.padding(16.dp),
          )
        !installed -> InstallPanel(env = env, onInstalled = { installed = true })
        else -> TerminalPanel(generation = generation)
      }
    }
  }
}

@Composable
private fun InstallPanel(env: LinuxEnv, onInstalled: () -> Unit) {
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }
  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Le terminal fait tourner Alpine Linux ${LinuxEnv.ALPINE_VERSION} dans Altrex, sans root : " +
        "installe Python, git, curl… avec « apk add ». Environ 4 Mo à télécharger, puis tout reste " +
        "sur le téléphone.",
      style = MaterialTheme.typography.bodyMedium,
    )
    Button(
      enabled = !busy,
      onClick = {
        busy = true
        scope.launch {
          val r = env.install { status = it }
          busy = false
          if (r.isSuccess && env.isInstalled) onInstalled()
          else status = "Échec : ${r.exceptionOrNull()?.message}"
        }
      },
    ) {
      Text(if (busy) "Installation…" else "Installer Alpine Linux")
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
  }
}

/** Modifier keys of the extra-keys row, read by the terminal view on the next key. */
private class ExtraKeys {
  var ctrl by mutableStateOf(false)
  var alt by mutableStateOf(false)
}

@Composable
private fun TerminalPanel(generation: Int) {
  val context = LocalContext.current
  val extra = remember { ExtraKeys() }
  var view by remember { mutableStateOf<TerminalView?>(null) }
  var finished by remember(generation) { mutableStateOf(false) }

  val sessionClient =
    remember(generation) {
      object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
          view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
          finished = true
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Terminal", text ?: ""))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
          session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {
          view?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) {
          Log.e(TAG, "$message")
        }

        override fun logWarn(tag: String?, message: String?) {
          Log.w(TAG, "$message")
        }

        override fun logInfo(tag: String?, message: String?) {}

        override fun logDebug(tag: String?, message: String?) {}

        override fun logVerbose(tag: String?, message: String?) {}

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
          Log.e(TAG, "$message", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
          Log.e(TAG, "terminal", e)
        }
      }
    }

  Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B1118))) {
      AndroidView(
        modifier = Modifier.fillMaxSize().padding(4.dp),
        factory = { ctx ->
          TerminalView(ctx, null).apply {
            setTextSize((14 * ctx.resources.displayMetrics.scaledDensity).toInt())
            // The app's own monospace font: Typeface.MONOSPACE follows the phone's font setting on
            // Samsung, which can be a proportional handwriting font.
            setTypeface(
              androidx.core.content.res.ResourcesCompat.getFont(
                ctx,
                com.google.ai.edge.gallery.R.font.jetbrains_mono_regular,
              ) ?: Typeface.MONOSPACE
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(ViewClient(this, extra))
            attachSession(UserTerminal.sessionFor(ctx, sessionClient))
            tag = generation
            view = this
            requestFocus()
          }
        },
        update = { v ->
          // Only on ↻ (new generation): a finished shell stays on screen until the user asks.
          if (v.tag != generation) {
            v.tag = generation
            v.attachSession(UserTerminal.sessionFor(v.context, sessionClient))
            v.onScreenUpdated()
          }
        },
      )
      if (finished) {
        Surface(
          modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
          Text(
            "Session terminée. Touche ↻ en haut pour en ouvrir une nouvelle.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    ExtraKeysRow(extra = extra, view = { view })
  }
}

/** Esc, Tab, Ctrl, Alt, arrows and the symbols a phone keyboard hides. */
@Composable
private fun ExtraKeysRow(extra: ExtraKeys, view: () -> TerminalView?) {
  fun sendKey(keyCode: Int) {
    val v = view() ?: return
    val session = v.currentSession ?: return
    val emulator = session.emulator ?: return
    var mod = 0
    if (extra.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
    if (extra.alt) mod = mod or KeyHandler.KEYMOD_ALT
    val code =
      KeyHandler.getCode(keyCode, mod, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        ?: return
    session.write(code)
    extra.ctrl = false
    extra.alt = false
  }

  fun sendText(text: String) {
    val session = view()?.currentSession ?: return
    if (extra.ctrl && text.length == 1) {
      // Ctrl + key: the control character (Ctrl-C = 3…).
      val c = text[0].uppercaseChar()
      if (c in '@'..'_') session.write(byteArrayOf((c.code - 64).toByte()), 0, 1) else session.write(text)
    } else {
      session.write(text)
    }
    extra.ctrl = false
    extra.alt = false
  }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    KeyButton("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
    KeyButton("TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
    KeyButton("CTRL", active = extra.ctrl) { extra.ctrl = !extra.ctrl }
    KeyButton("ALT", active = extra.alt) { extra.alt = !extra.alt }
    KeyButton("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
    KeyButton("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    KeyButton("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
    KeyButton("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
    for (s in listOf("-", "/", "|", "~", "$", "&", "*", ">", "<")) KeyButton(s) { sendText(s) }
  }
}

@Composable
private fun KeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
  TextButton(onClick = onClick) {
    Text(
      label,
      fontFamily = FontFamily(androidx.compose.ui.text.font.Font(com.google.ai.edge.gallery.R.font.jetbrains_mono_regular)),
      color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
  }
}

/** Keyboard and gestures of the terminal view. */
private class ViewClient(private val view: TerminalView, private val extra: ExtraKeys) : TerminalViewClient {
  private var fontScale = 1f

  override fun onScale(scale: Float): Float {
    if (scale < 0.9f || scale > 1.1f) {
      fontScale = (fontScale * scale).coerceIn(0.6f, 2.5f)
      view.setTextSize((14 * fontScale * view.resources.displayMetrics.scaledDensity).toInt())
      return 1f
    }
    return scale
  }

  override fun onSingleTapUp(e: MotionEvent?) {
    view.requestFocus()
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
  }

  override fun shouldBackButtonBeMappedToEscape() = false

  override fun shouldEnforceCharBasedInput() = true

  override fun shouldUseCtrlSpaceWorkaround() = false

  override fun isTerminalViewSelected() = true

  override fun copyModeChanged(copyMode: Boolean) {}

  override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false

  override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false

  override fun onLongPress(event: MotionEvent?) = false

  override fun readControlKey(): Boolean = extra.ctrl.also { if (it) extra.ctrl = false }

  override fun readAltKey(): Boolean = extra.alt.also { if (it) extra.alt = false }

  override fun readShiftKey() = false

  override fun readFnKey() = false

  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false

  override fun onEmulatorSet() {}

  override fun logError(tag: String?, message: String?) {
    Log.e(TAG, "$message")
  }

  override fun logWarn(tag: String?, message: String?) {}

  override fun logInfo(tag: String?, message: String?) {}

  override fun logDebug(tag: String?, message: String?) {}

  override fun logVerbose(tag: String?, message: String?) {}

  override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
    Log.e(TAG, "$message", e)
  }

  override fun logStackTrace(tag: String?, e: Exception?) {
    Log.e(TAG, "terminal view", e)
  }
}
