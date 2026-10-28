package com.google.ai.edge.gallery.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * [ALTREX] First step of the Linux terminal, for testing on a phone: installs Alpine in the user's
 * environment and runs one command at a time (no interactive programs yet, that is the terminal
 * screen of the next step).
 */
@Composable
fun LinuxTestDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val env = remember { LinuxEnv.of(context, LinuxEnv.USER) }
  val scope = rememberCoroutineScope()
  var installed by remember { mutableStateOf(env.isInstalled) }
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }
  var command by remember { mutableStateOf("uname -a && cat /etc/alpine-release") }
  var output by remember { mutableStateOf("") }
  val outputScroll = rememberScrollState()
  LaunchedEffect(output.length) { outputScroll.scrollTo(outputScroll.maxValue) }

  fun runCommand() {
    if (busy || command.isBlank()) return
    busy = true
    output += "$ $command\n"
    val cmd = command
    scope.launch {
      try {
        val result =
          env.run(cmd) { chunk -> scope.launch { output = (output + chunk).takeLast(60_000) } }
        output +=
          when {
            result.timedOut -> "\n[arrêté : plus de ${LinuxEnv.DEFAULT_TIMEOUT_MS / 1000} s]\n"
            result.exitCode != 0 -> "\n[code de sortie ${result.exitCode}]\n"
            else -> "\n"
          }
      } catch (e: Exception) {
        output += "[erreur : ${e.message ?: e.javaClass.simpleName}]\n"
      }
      busy = false
    }
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Linux (test)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
          TextButton(onClick = onDismiss) { Text("Fermer") }
        }
        when {
          !env.isSupported ->
            Text("Linux n'est pas disponible sur cet appareil (processeur arm64 requis).")
          !installed -> {
            Text(
              "Installe Alpine Linux ${LinuxEnv.ALPINE_VERSION} (≈ 4 Mo à télécharger, ≈ 10 Mo installé) " +
                "dans l'environnement de l'utilisateur. Tout reste dans Altrex.",
              style = MaterialTheme.typography.bodyMedium,
            )
            Button(
              enabled = !busy,
              onClick = {
                busy = true
                scope.launch {
                  val r = env.install { status = it }
                  installed = env.isInstalled
                  if (r.isFailure) status = "Échec : ${r.exceptionOrNull()?.message}"
                  busy = false
                }
              },
            ) {
              Text(if (busy) "Installation…" else "Installer Alpine")
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
          }
          else -> {
            Text(
              "Une commande à la fois (ex. apk add python3, python3 -c \"print(2+2)\"). " +
                "Les programmes interactifs viendront avec l'écran terminal.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
              modifier = Modifier.fillMaxWidth().weight(1f),
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
              Text(
                text = output.ifEmpty { "Prêt." },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier =
                  Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .verticalScroll(outputScroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
              )
            }
            OutlinedTextField(
              value = command,
              onValueChange = { command = it },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              label = { Text("Commande") },
              textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
              keyboardActions = KeyboardActions(onGo = { runCommand() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(enabled = !busy, onClick = { runCommand() }) { Text(if (busy) "En cours…" else "Exécuter") }
              TextButton(enabled = !busy, onClick = { output = "" }) { Text("Effacer") }
              TextButton(
                enabled = !busy,
                onClick = {
                  busy = true
                  scope.launch {
                    env.uninstall()
                    installed = false
                    output = ""
                    status = ""
                    busy = false
                  }
                },
              ) {
                Text("Désinstaller")
              }
            }
          }
        }
      }
    }
  }
}
