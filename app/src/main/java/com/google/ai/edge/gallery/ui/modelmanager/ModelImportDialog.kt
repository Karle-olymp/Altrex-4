/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.modelmanager

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.HardwareOptimizer
import com.google.ai.edge.gallery.data.LlmHardwareProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelImportDialog(
    modelUri: Uri,
    initialModelName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, maxTokens: Int, topK: Int, topP: Float, temperature: Float, supportImage: Boolean, supportAudio: Boolean, supportThinking: Boolean, supportSpeculative: Boolean, hardwareProfile: LlmHardwareProfile) -> Unit
) {
    var modelName by remember { mutableStateOf(initialModelName) }
    var maxTokens by remember { mutableStateOf("8192") }
    var topK by remember { mutableStateOf("64") }
    var topP by remember { mutableStateOf("0.95") }
    var temperature by remember { mutableStateOf("1.00") }
    var supportImage by remember { mutableStateOf(false) }
    var supportAudio by remember { mutableStateOf(false) }
    var supportThinking by remember { mutableStateOf(false) }
    var supportSpeculative by remember { mutableStateOf(false) }
    
    // [ALTREX] Nouveau sélecteur de profil matériel intelligent (CPU/GPU/NPU hybride)
    var selectedProfile by remember { mutableStateOf(LlmHardwareProfile.HYBRID_AUTO) }
    var expandedDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Model") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("Default max tokens (100-16384)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Default TopK (1-100)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Default TopP (0.00-1.00)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Default temperature (0.00-2.00)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Support image")
                    Switch(checked = supportImage, onCheckedChange = { supportImage = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Support audio")
                    Switch(checked = supportAudio, onCheckedChange = { supportAudio = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Support thinking")
                    Switch(checked = supportThinking, onCheckedChange = { supportThinking = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Support speculative decoding")
                    Switch(checked = supportSpeculative, onCheckedChange = { supportSpeculative = it })
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Profil d'accélération matérielle", style = MaterialTheme.typography.labelLarge)

                // [ALTREX] Dropdown pour choisir le profil d'optimisation unifié (CPU + GPU + NPU asynchrone)
                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { expandedDropdown = !expandedDropdown }
                ) {
                    OutlinedTextField(
                        value = selectedProfile.label,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) }
                    )

                    ExposedDropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false }
                    ) {
                        LlmHardwareProfile.values().forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.label) },
                                onClick = {
                                    selectedProfile = profile
                                    expandedDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        modelName,
                        maxTokens.toIntOrNull() ?: 8192,
                        topK.toIntOrNull() ?: 64,
                        topP.toFloatOrNull() ?: 0.95f,
                        temperature.toFloatOrNull() ?: 1.00f,
                        supportImage,
                        supportAudio,
                        supportThinking,
                        supportSpeculative,
                        selectedProfile
                    )
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ModelImportingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Importing Model...") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {}
    )
}
