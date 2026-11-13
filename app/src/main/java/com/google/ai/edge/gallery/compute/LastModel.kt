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

package com.google.ai.edge.gallery.compute

/** [ALTREX] The last model the user loaded: proposed first, never loaded by itself. */
class LastModelStore(private val store: KeyValueStore) {
  fun get(): String? = store.get(KEY)?.takeIf { it.isNotBlank() }

  fun set(modelName: String) {
    if (modelName.isNotBlank()) store.put(KEY, modelName)
  }

  /** The model was deleted: forget it. */
  fun clearIf(modelName: String) {
    if (get() == modelName) store.put(KEY, null)
  }

  private companion object {
    const val KEY = "last_model"
  }
}

object ModelOrdering {
  /** The same list with the last used item first (unchanged order otherwise). */
  fun <T> lastUsedFirst(items: List<T>, lastName: String?, nameOf: (T) -> String): List<T> {
    if (lastName == null) return items
    val last = items.firstOrNull { nameOf(it) == lastName } ?: return items
    return listOf(last) + items.filter { it !== last }
  }

  /**
   * The model to open: the last used one if it is still usable, else the first usable one, else
   * the first one.
   */
  fun <T> preferred(
    items: List<T>,
    lastName: String?,
    nameOf: (T) -> String,
    usable: (T) -> Boolean,
  ): T? =
    items.firstOrNull { nameOf(it) == lastName && usable(it) }
      ?: items.firstOrNull(usable)
      ?: items.firstOrNull()
}
