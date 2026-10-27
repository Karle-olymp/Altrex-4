package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.coderunner.CodeRunnerContent
import com.google.ai.edge.gallery.ui.common.coderunner.RunLanguage
import com.google.ai.edge.gallery.ui.common.coderunner.runnableLanguage
import com.google.ai.edge.gallery.ui.theme.codeFontFamily
import kotlinx.coroutines.launch

/** [ALTREX] The three views of the Code space, switched with the bar at the top. */
enum class CodeView(val label: String, val icon: ImageVector) {
  CHAT("Chat", Icons.Outlined.Forum),
  CODE("Code", Icons.Outlined.Code),
  PREVIEW("Aperçu", Icons.Outlined.Visibility),
}

/**
 * Bar at the top of the Code space: Chat / Code / Aperçu, and the SecurCode scan of the opened
 * file (or of the whole project when no file is open).
 */
@Composable
fun CodeViewSelector(
  view: CodeView,
  onViewSelected: (CodeView) -> Unit,
  scanEnabled: Boolean,
  scanLabel: String,
  onScan: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Row(
      modifier =
        Modifier.weight(1f)
          .clip(RoundedCornerShape(50))
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(50))
          .padding(3.dp)
    ) {
      for (v in CodeView.entries) {
        val selected = v == view
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
          modifier =
            Modifier.weight(1f)
              .height(34.dp)
              .clip(RoundedCornerShape(50))
              .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
              )
              .clickable { onViewSelected(v) },
        ) {
          val color =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
          Icon(v.icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
          Text(
            v.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      }
    }
    IconButton(
      enabled = scanEnabled,
      onClick = onScan,
      colors =
        IconButtonDefaults.iconButtonColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          contentColor = MaterialTheme.colorScheme.primary,
          disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
      modifier = Modifier.size(40.dp),
    ) {
      Icon(Icons.Outlined.Shield, contentDescription = scanLabel, modifier = Modifier.size(20.dp))
    }
  }
}

/**
 * Editor of the opened file. The text being edited ([text]) is kept by the screen, so that it
 * survives a switch to the Chat or Aperçu view; [onSaved] receives what was written to disk.
 */
@Composable
fun CodeEditorPanel(
  openedFile: OpenedFile,
  text: String,
  onTextChange: (String) -> Unit,
  onSaved: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  var saveError by remember(openedFile.uri) { mutableStateOf(false) }
  val isDirty = text != openedFile.content

  Box(contentAlignment = Alignment.BottomEnd, modifier = modifier.fillMaxSize()) {
    val codeTextStyle =
      MaterialTheme.typography.bodySmall.copy(
        fontFamily = codeFontFamily,
        color = MaterialTheme.colorScheme.onSurface,
      )
    val lineCount = text.count { it == '\n' } + 1
    Row(
      modifier =
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
    ) {
      Text(
        text = (1..lineCount).joinToString("\n"),
        style = codeTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        textAlign = TextAlign.End,
        modifier = Modifier.padding(top = 8.dp, bottom = 56.dp, end = 8.dp),
      )
      Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
        BasicTextField(
          value = text,
          onValueChange = {
            onTextChange(it)
            saveError = false
          },
          textStyle = codeTextStyle,
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          modifier = Modifier.widthIn(min = 280.dp).padding(top = 8.dp, bottom = 56.dp),
        )
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(bottom = 4.dp, end = 8.dp),
    ) {
      if (saveError) {
        Text(
          text = "Échec de l'enregistrement",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
      IconButton(
        enabled = isDirty,
        onClick = {
          if (ProjectFolderManager.writeTextFile(context, openedFile.uri, text)) {
            saveError = false
            onSaved(text)
          } else {
            // [ALTREX] The failed write may have emptied the file: put the version on disk back
            // (the edited text stays in the editor for another try).
            ProjectFolderManager.writeTextFile(context, openedFile.uri, openedFile.content)
            saveError = true
          }
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
      ) {
        Icon(Icons.Outlined.Save, contentDescription = "Enregistrer", modifier = Modifier.size(20.dp))
      }
      IconButton(
        onClick = {
          scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text))) }
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.primary,
          ),
      ) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = stringResource(R.string.cd_copy_to_clipboard_icon),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

/**
 * Aperçu of the opened file, with the text being edited ([text], saved or not): HTML and SVG drawn
 * as a page, Markdown formatted, JavaScript and Python run on the phone (sandboxed, no network).
 */
@Composable
fun PreviewPanel(openedFile: OpenedFile?, text: String, modifier: Modifier = Modifier) {
  if (openedFile == null) {
    EmptyPaneHint("Ouvre un fichier dans la vue Code pour voir son aperçu.", modifier)
    return
  }
  val ext = openedFile.fileName.substringAfterLast('.', "").lowercase()
  if (ext == "md" || ext == "markdown") {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
      MarkdownText(text = text)
    }
    return
  }
  val language = if (ext == "svg") RunLanguage.HTML else runnableLanguage(ext)
  if (language == null) {
    EmptyPaneHint(
      "Pas d'aperçu pour « ${openedFile.fileName} ». L'aperçu affiche le HTML, le SVG et le " +
        "Markdown, et exécute le JavaScript et le Python.",
      modifier,
    )
    return
  }
  // A new run whenever the file or its text changes.
  key(openedFile.uri, text) {
    CodeRunnerContent(
      language = language,
      code = text,
      modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
      fill = true,
      title = openedFile.fileName,
    )
  }
}

@Composable
fun EmptyPaneHint(text: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
  ) {
    com.google.ai.edge.gallery.ui.common.AltrexAvatar(size = 40.dp)
    Text(
      text,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )
  }
}
