package com.google.ai.edge.gallery.ui.llamacpp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper

sealed class NativeLlamaState {
  data object Idle : NativeLlamaState()
  data object Loading : NativeLlamaState()
  data class Loaded(val modelPath: String, val contextPtr: Long) : NativeLlamaState()
  data class Generating(val prompt: String, val tokens: Int = 0) : NativeLlamaState()
  data class Completed(val tokens: Int, val durationMs: Long) : NativeLlamaState()
  data class Error(val message: String) : NativeLlamaState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaCppTestScreen(
  onBackClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var modelPath by remember { mutableStateOf("") }
  var prompt by remember { mutableStateOf("Why is the sky blue? Explain in one short sentence.") }
  var generatedText by remember { mutableStateOf("") }
  var state by remember { mutableStateOf<NativeLlamaState>(NativeLlamaState.Idle) }
  var tokenCount by remember { mutableStateOf(0) }
  var durationMs by remember { mutableLongStateOf(0L) }

  val sharedFlow = remember {
    MutableSharedFlow<LlamaHelper.LLMEvent>(
      replay = 0,
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  }

  val llamaHelper = remember {
    LlamaHelper(
      contentResolver = context.contentResolver,
      scope = scope,
      sharedFlow = sharedFlow,
    )
  }

  // Listen to LLM events
  LaunchedEffect(sharedFlow) {
    sharedFlow.collect { event ->
      when (event) {
        is LlamaHelper.LLMEvent.Started -> {
          generatedText = ""
          tokenCount = 0
          state = NativeLlamaState.Generating(prompt = event.prompt, tokens = 0)
        }
        is LlamaHelper.LLMEvent.Ongoing -> {
          generatedText += event.word
          tokenCount = event.tokenCount
          state = NativeLlamaState.Generating(prompt = prompt, tokens = event.tokenCount)
        }
        is LlamaHelper.LLMEvent.Done -> {
          generatedText = event.fullText
          tokenCount = event.tokenCount
          durationMs = event.duration
          state = NativeLlamaState.Completed(tokens = event.tokenCount, durationMs = event.duration)
        }
        is LlamaHelper.LLMEvent.Loaded -> {
          // Model loaded
        }
        is LlamaHelper.LLMEvent.Error -> {
          state = NativeLlamaState.Error(event.message)
        }
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      llamaHelper.abort()
      llamaHelper.release()
    }
  }

  val filePickerLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) {
        modelPath = uri.toString()
      }
    }

  val scrollState = rememberScrollState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              "llama.cpp Native Engine",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              "Isolated JNI / GGUF Test Screen",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Navigate back",
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
      )
    },
    modifier = modifier,
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .verticalScroll(scrollState),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Status banner
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
          CardDefaults.cardColors(
            containerColor =
              when (state) {
                is NativeLlamaState.Error -> MaterialTheme.colorScheme.errorContainer
                is NativeLlamaState.Generating -> MaterialTheme.colorScheme.primaryContainer
                is NativeLlamaState.Loading -> MaterialTheme.colorScheme.secondaryContainer
                is NativeLlamaState.Loaded -> MaterialTheme.colorScheme.tertiaryContainer
                is NativeLlamaState.Completed -> MaterialTheme.colorScheme.surfaceVariant
                NativeLlamaState.Idle -> MaterialTheme.colorScheme.surfaceVariant
              }
          ),
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          when (state) {
            is NativeLlamaState.Idle -> {
              Text(
                "Status: Engine ready (no model loaded)",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            is NativeLlamaState.Loading -> {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              Text("Loading GGUF model into memory...", style = MaterialTheme.typography.bodyMedium)
            }
            is NativeLlamaState.Loaded -> {
              Text(
                "✓ Model loaded successfully (Context ready)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
            }
            is NativeLlamaState.Generating -> {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              Text(
                "Generating tokens... ($tokenCount tokens)",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            is NativeLlamaState.Completed -> {
              val speed =
                if (durationMs > 0) String.format("%.1f", (tokenCount * 1000f) / durationMs) else "0"
              Text(
                "✓ Completed: $tokenCount tokens in ${durationMs}ms ($speed tok/s)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
            }
            is NativeLlamaState.Error -> {
              Text(
                "⚠ Error: ${(state as NativeLlamaState.Error).message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      // Section 1: Model Selection
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            "1. GGUF Model Path",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )

          OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model File Path or Content URI") },
            placeholder = { Text("/path/to/model.gguf or content://...") },
            singleLine = true,
            trailingIcon = {
              IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Pick file")
              }
            },
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = {
                if (modelPath.isNotBlank()) {
                  state = NativeLlamaState.Loading
                  llamaHelper.load(
                    path = modelPath,
                    contextLength = 2048,
                  ) { ptr ->
                    state = NativeLlamaState.Loaded(modelPath = modelPath, contextPtr = ptr)
                  }
                }
              },
              enabled = modelPath.isNotBlank() && state !is NativeLlamaState.Loading && state !is NativeLlamaState.Generating,
              modifier = Modifier.weight(1f),
            ) {
              Text("Load Model")
            }

            OutlinedButton(
              onClick = {
                llamaHelper.release()
                state = NativeLlamaState.Idle
                generatedText = ""
              },
              enabled = state is NativeLlamaState.Loaded || state is NativeLlamaState.Completed,
            ) {
              Text("Unload")
            }
          }
        }
      }

      // Section 2: Inference Prompt
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            "2. Inference Prompt",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )

          OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Prompt") },
            minLines = 2,
            maxLines = 4,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = {
                if (prompt.isNotBlank()) {
                  generatedText = ""
                  llamaHelper.predict(prompt = prompt)
                }
              },
              enabled =
                (state is NativeLlamaState.Loaded || state is NativeLlamaState.Completed) &&
                  prompt.isNotBlank() &&
                  state !is NativeLlamaState.Generating,
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Run Generation")
            }

            if (state is NativeLlamaState.Generating) {
              Button(
                onClick = { llamaHelper.stopPrediction() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              ) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop")
              }
            }
          }
        }
      }

      // Section 3: Output Display
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              "3. Native Engine Output",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            if (tokenCount > 0) {
              Text(
                "$tokenCount tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }

          Surface(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          ) {
            Box(modifier = Modifier.padding(12.dp)) {
              if (generatedText.isEmpty()) {
                Text(
                  "Generated text will appear here in real-time...",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
              } else {
                Text(
                  text = generatedText,
                  style =
                    MaterialTheme.typography.bodyMedium.copy(
                      fontFamily = FontFamily.Monospace,
                      fontSize = 13.sp,
                      lineHeight = 18.sp,
                    ),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }
    }
  }
}
