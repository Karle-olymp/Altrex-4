package com.google.ai.edge.gallery.ui.github

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectContextBuilder
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectEvents
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectFolderManager
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ImportState {
  object Form : ImportState()

  data class Downloading(val bytes: Long) : ImportState()

  data class Writing(val files: Int) : ImportState()

  data class Done(val result: ImportResult, val repo: String) : ImportState()
}

/**
 * [ALTREX] "Importer depuis GitHub": downloads a repository into a new folder of the project, then
 * offers to scan it with SecurCode ([onScan] receives the folder, relative to the project).
 */
@Composable
fun GitHubImportDialog(onDismiss: () -> Unit, onScan: ((String) -> Unit)?) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var repoInput by remember { mutableStateOf("") }
  var branchInput by remember { mutableStateOf("") }
  var state by remember { mutableStateOf<ImportState>(ImportState.Form) }
  var error by remember { mutableStateOf<String?>(null) }
  var hasToken by remember { mutableStateOf(false) }
  var repos by remember { mutableStateOf<List<RepoInfo>?>(null) }
  var job by remember { mutableStateOf<Job?>(null) }

  LaunchedEffect(Unit) {
    val token = withContext(Dispatchers.IO) { GitHubSettings.getToken(context) }
    hasToken = token != null
    if (token != null) {
      try {
        repos = withContext(Dispatchers.IO) { GitHubClient.listRepos(token) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error = e.message
      }
    }
  }

  fun startImport() {
    val repo = GitHubPaths.parseRepo(repoInput)
    if (repo == null) {
      error = "Écris le dépôt sous la forme propriétaire/nom, ou colle son lien github.com."
      return
    }
    val branch = branchInput.trim()
    if (branch.isNotEmpty() && !GitHubPaths.isValidRef(branch)) {
      error = "Nom de branche invalide."
      return
    }
    error = null
    state = ImportState.Downloading(0)
    job =
      scope.launch(Dispatchers.IO) {
        val zip = File(context.cacheDir, "github/import.zip")
        try {
          val root =
            ProjectFolderManager.getSavedProjectFolder(context)
              ?: throw GitHubException("choisis d'abord un dossier de projet.")
          val token = GitHubSettings.getToken(context)
          val info = GitHubClient.getRepo(repo, token)
          zip.parentFile?.mkdirs()
          GitHubClient.download(repo, branch.ifEmpty { info.defaultBranch }, token, zip) { bytes ->
            if (!isActive) throw CancellationException()
            state = ImportState.Downloading(bytes)
          }
          state = ImportState.Writing(0)
          val result =
            GitHubClient.extract(
              context,
              zip,
              root,
              repo,
              onFolderCreated = { GitHubSettings.addImportedFolder(context, it) },
            ) { files ->
              if (!isActive) throw CancellationException()
              state = ImportState.Writing(files)
            }
          ProjectContextBuilder.invalidateCache()
          withContext(Dispatchers.Main) { ProjectEvents.tick.intValue++ }
          state = ImportState.Done(result, info.fullName)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          error = "Import impossible : " + (e.message ?: "erreur réseau")
          state = ImportState.Form
        } finally {
          zip.delete()
        }
      }
  }

  val running = state is ImportState.Downloading || state is ImportState.Writing
  AlertDialog(
    onDismissRequest = { if (!running) onDismiss() },
    title = { Text("Importer depuis GitHub") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = state) {
          is ImportState.Form -> {
            OutlinedTextField(
              value = repoInput,
              onValueChange = { repoInput = it },
              singleLine = true,
              label = { Text("Dépôt") },
              placeholder = { Text("propriétaire/nom ou lien") },
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
              value = branchInput,
              onValueChange = { branchInput = it },
              singleLine = true,
              label = { Text("Branche (facultatif)") },
              modifier = Modifier.fillMaxWidth(),
            )
            val list = repos
            if (list != null && list.isNotEmpty()) {
              Text("Tes dépôts", style = MaterialTheme.typography.labelLarge)
              LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(list) { r ->
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                      Modifier.fillMaxWidth()
                        .clickable {
                          repoInput = r.fullName
                          branchInput = ""
                        }
                        .padding(vertical = 6.dp),
                  ) {
                    Icon(
                      if (r.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Public,
                      contentDescription = if (r.isPrivate) "privé" else "public",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(16.dp),
                    )
                    Text(
                      r.fullName,
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.padding(start = 8.dp),
                    )
                  }
                }
              }
            } else if (!hasToken) {
              Text(
                "Sans jeton, seuls les dépôts publics sont accessibles. Pour tes dépôts privés, " +
                  "ajoute un jeton dans Réglages → Clés d'API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              "Le dépôt est copié dans un nouveau dossier du projet. Son code vient d'ailleurs : " +
                "l'agent le lit comme une donnée non fiable et n'écrit rien après l'avoir lu.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          is ImportState.Downloading -> {
            Text("Téléchargement… ${"%.1f".format(s.bytes / 1_048_576f)} Mo")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          is ImportState.Writing -> {
            Text("Copie dans le projet… ${s.files} fichiers")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          is ImportState.Done -> {
            val r = s.result
            Text("${s.repo} est dans le dossier « ${r.folder} » : ${r.files} fichiers.")
            if (r.skipped > 0) {
              Text(
                "${r.skipped} fichier(s) ignoré(s) (plus de 10 Mo ou impossible à écrire).",
                style = MaterialTheme.typography.bodySmall,
              )
            }
            if (r.truncated) {
              Text(
                "Dépôt très gros : la copie s'est arrêtée à 4 000 fichiers ou 200 Mo.",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      when (val s = state) {
        is ImportState.Form -> Button(enabled = repoInput.isNotBlank(), onClick = { startImport() }) { Text("Importer") }
        is ImportState.Done ->
          if (onScan != null) {
            Button(
              onClick = {
                onDismiss()
                onScan(s.result.folder)
              }
            ) {
              Text("Scanner avec SecurCode")
            }
          }
        else -> {}
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          if (running) {
            job?.cancel()
            error = "Import annulé. Le dossier déjà créé peut contenir une copie partielle."
            state = ImportState.Form
          } else {
            onDismiss()
          }
        }
      ) {
        Text(if (running) "Annuler" else "Fermer")
      }
    },
  )
}
