package com.google.ai.edge.gallery.ui.security

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnScreen
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** The "Sécurité" space: read-only agent with SecurCode scanning, sharing the Code Mode screen. */
class SecurityTask @Inject constructor(@ApplicationContext private val context: Context) :
  CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_SECURITY,
      label = "Sécurité",
      category = Category.LLM,
      icon = Icons.Outlined.Shield,
      models = mutableListOf(),
      description = "Analyse de sécurité de ton code avec SecurCode, en lecture seule.",
      shortDescription = "Analyse de code avec SecurCode",
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

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    LlmSingleTurnScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      onMenuClicked = myData.onMenuClicked,
      taskId = BuiltInTaskId.LLM_SECURITY,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SecurityTaskModule {
  @Provides
  @IntoSet
  fun provideTask(@ApplicationContext context: Context): CustomTask {
    return SecurityTask(context)
  }
}
