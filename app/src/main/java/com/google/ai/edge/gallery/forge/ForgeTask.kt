package com.google.ai.edge.gallery.forge

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.forge.ui.ForgeScreen
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * [ALTREX] Altrex Forge, in the place of the former Code screen (same task id, so the menu, the
 * model lists and the saved last screen keep working). The model is loaded without tools:
 * Forge gives its tools in the prompt (text protocol), the same way for every engine.
 */
class ForgeTask @Inject constructor(@ApplicationContext private val context: Context) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_PROMPT_LAB,
      label = context.getString(R.string.task_label_prompt_lab),
      category = Category.LLM,
      icon = Icons.Outlined.Code,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_prompt_lab),
      shortDescription = context.getString(R.string.task_short_desc_prompt_lab),
      docUrl = "",
      sourceCodeUrl = "",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = systemInstruction,
    )
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val d = data as CustomTaskDataForBuiltinTask
    ForgeScreen(modelManagerViewModel = d.modelManagerViewModel, navigateUp = d.onNavUp, onMenuClicked = d.onMenuClicked)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ForgeTaskModule {
  @Provides
  @IntoSet
  fun provideTask(@ApplicationContext context: Context): CustomTask = ForgeTask(context)
}
