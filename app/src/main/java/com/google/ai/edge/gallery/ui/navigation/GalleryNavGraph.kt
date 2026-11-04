/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.navigation

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkScreen
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.home.AssistantGuideDialog
import com.google.ai.edge.gallery.ui.home.AssistantSetup
import com.google.ai.edge.gallery.ui.home.SettingsDialog
import com.google.ai.edge.gallery.ui.home.AltrexDrawer
import com.google.ai.edge.gallery.ui.home.LastExitDialog
import com.google.ai.edge.gallery.ui.home.MODELS_ENTRY_ID
import com.google.ai.edge.gallery.ui.home.StartLoadingScreen
import com.google.ai.edge.gallery.ui.home.WelcomeScreen
import com.google.ai.edge.gallery.ui.modelmanager.MODEL_MANAGER_ACTION_CLOUD
import com.google.ai.edge.gallery.ui.modelmanager.MODEL_MANAGER_ACTION_IMPORT
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_START = "start"
// [ALTREX] Replaces Google's home screen when the chat has no model.
private const val ROUTE_WELCOME = "welcome"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ROUTE_TERMINAL = "terminal"
private const val ROUTE_CONNECTORS = "connectors"
private const val ROUTE_NOTES = "voice_notes"
// [ALTREX] How long the start route waits for the model list before showing the welcome screen.
private const val START_WAIT_LIMIT_MS = 8_000L
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

/**
 * [ALTREX] Route opened by a menu entry that is not a model task (terminal, notes, connectors);
 * null for Discuter and Code, which open with a model (see navigateToTaskScreen).
 */
internal fun drawerEntryRoute(entryId: String): String? =
  when (entryId) {
    com.google.ai.edge.gallery.terminal.TERMINAL_ENTRY_ID -> ROUTE_TERMINAL
    com.google.ai.edge.gallery.connectors.CONNECTORS_ENTRY_ID -> ROUTE_CONNECTORS
    com.google.ai.edge.gallery.notes.NOTES_ENTRY_ID -> ROUTE_NOTES
    else -> null
  }

/** [ALTREX] Routes of the screens the menu opens directly (see [drawerEntryRoute]). */
internal val DIRECT_ROUTES: Set<String> = setOf(ROUTE_TERMINAL, ROUTE_CONNECTORS, ROUTE_NOTES)

/** Navigation routes. */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var enableModelListAnimation by remember { mutableStateOf(true) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var showSettingsDialog by remember { mutableStateOf(false) }

  // Close the menu when back button is pressed.
  BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

  // For the side menu: which space is open, and which model is currently loaded in memory.
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentTaskId =
    navBackStackEntry?.arguments?.getString("taskId")
      ?: com.google.ai.edge.gallery.terminal.TERMINAL_ENTRY_ID.takeIf {
        navBackStackEntry?.destination?.route == ROUTE_TERMINAL
      }
      ?: com.google.ai.edge.gallery.connectors.CONNECTORS_ENTRY_ID.takeIf {
        navBackStackEntry?.destination?.route == ROUTE_CONNECTORS
      }
      ?: MODELS_ENTRY_ID.takeIf {
        navBackStackEntry?.destination?.route?.startsWith(ROUTE_MODEL_MANAGER) == true
      }
      // [ALTREX] Voice notes.
      ?: com.google.ai.edge.gallery.notes.NOTES_ENTRY_ID.takeIf {
        navBackStackEntry?.destination?.route == ROUTE_NOTES
      }
  val loadedModelName =
    modelManagerUiState.tasks
      .flatMap { it.models }
      .firstOrNull { it.instance != null }
      ?.let { it.displayName.ifEmpty { it.name } }

  val navigateToTaskScreen: (Task) -> Unit = { task ->
    val defaultModel =
      task.models.firstOrNull { model ->
        modelManagerUiState.modelDownloadStatus[model.name]?.status ==
          ModelDownloadStatusType.SUCCEEDED
      } ?: task.models.firstOrNull()
    if (defaultModel != null) {
      navController.navigate("$ROUTE_MODEL/${task.id}/${defaultModel.name}")
    } else {
      // [ALTREX] No model yet: the welcome screen says how to add one.
      navController.navigate(ROUTE_WELCOME) { launchSingleTop = true }
    }
  }

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Tells the user, once, why the previous run ended abnormally (memory kill, crash...).
  LastExitDialog()

  // [ALTREX] Once a model is loaded, offer (once) to make Altrex the phone's assistant.
  val guideContext = LocalContext.current
  var showAssistantGuide by remember { mutableStateOf(false) }
  LaunchedEffect(loadedModelName != null) {
    if (loadedModelName != null && AssistantSetup.shouldOfferGuide(guideContext)) {
      delay(1500)
      AssistantSetup.markGuideOffered(guideContext)
      showAssistantGuide = true
    }
  }
  if (showAssistantGuide) {
    AssistantGuideDialog(onDismiss = { showAssistantGuide = false })
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      AltrexDrawer(
        isOpen = drawerState.targetValue == DrawerValue.Open,
        currentTaskId = currentTaskId,
        loadedModelName = loadedModelName,
        onOpenTask = { taskId ->
          scope.launch { drawerState.close() }
          val route = drawerEntryRoute(taskId)
          if (route != null) {
            navController.navigate(route) { launchSingleTop = true }
          } else {
            val task = modelManagerUiState.tasks.find { it.id == taskId }
            if (task != null) {
              navigateToTaskScreen(task)
            } else {
              // [ALTREX] The model list is not there yet: the welcome screen waits for it.
              navController.navigate(ROUTE_WELCOME) { launchSingleTop = true }
            }
          }
        },
        onOpenModels = {
          scope.launch { drawerState.close() }
          scope.launch {
            delay(50)
            navController.navigate(ROUTE_MODEL_MANAGER) { launchSingleTop = true }
          }
        },
        // [ALTREX] Connectors are configuration: a bottom button, no longer a space.
        onOpenConnectors = {
          scope.launch { drawerState.close() }
          navController.navigate(ROUTE_CONNECTORS) { launchSingleTop = true }
        },
        onOpenSettings = {
          showSettingsDialog = true
          scope.launch { drawerState.close() }
        },
        onOpenAssistantGuide = {
          showAssistantGuide = true
          scope.launch { drawerState.close() }
        },
      )
    },
    gesturesEnabled = drawerState.isOpen,
  ) {
    NavHost(
      navController = navController,
      startDestination = ROUTE_START,
      enterTransition = { EnterTransition.None },
      exitTransition = { ExitTransition.None },
    ) {
    // [ALTREX] Start route: Discuter with its first model, or the welcome screen when there is no
    // model (or no model list: offline first launch). A loader shows while the list loads, and the
    // wait is bounded, so this route never stays blank.
    composable(route = ROUTE_START) {
      var gaveUpWaiting by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) {
        delay(START_WAIT_LIMIT_MS)
        gaveUpWaiting = true
      }
      val destination =
        chooseStartDestination(
          tasks = modelManagerUiState.tasks,
          downloadStatus = modelManagerUiState.modelDownloadStatus,
          loadFailed = modelManagerUiState.loadingModelAllowlistError.isNotEmpty(),
          gaveUpWaiting = gaveUpWaiting,
        )
      StartLoadingScreen(modifier = modifier)
      LaunchedEffect(destination) {
        when (destination) {
          StartDestination.Waiting -> {}
          StartDestination.Welcome ->
            navController.navigate(ROUTE_WELCOME) { popUpTo(ROUTE_START) { inclusive = true } }
          is StartDestination.Chat ->
            navController.navigate("$ROUTE_MODEL/${destination.taskId}/${destination.modelName}") {
              popUpTo(ROUTE_START) { inclusive = true }
            }
        }
      }
    }

    // [ALTREX] Welcome screen: no model to chat with yet. As soon as one is there (downloaded,
    // added from the cloud, imported, or the list arrived), it opens Discuter in its place.
    composable(route = ROUTE_WELCOME) {
      val destination =
        chooseStartDestination(
          tasks = modelManagerUiState.tasks,
          downloadStatus = modelManagerUiState.modelDownloadStatus,
          loadFailed = modelManagerUiState.loadingModelAllowlistError.isNotEmpty(),
          gaveUpWaiting = true,
        )
      LaunchedEffect(destination) {
        if (destination is StartDestination.Chat) {
          navController.navigate("$ROUTE_MODEL/${destination.taskId}/${destination.modelName}") {
            popUpTo(ROUTE_WELCOME) { inclusive = true }
          }
        }
      }
      WelcomeScreen(
        modifier = modifier,
        loading = modelManagerUiState.tasks.isEmpty() && modelManagerUiState.loadingModelAllowlist,
        catalogUnavailable = modelManagerUiState.loadingModelAllowlistError.isNotEmpty(),
        onRetry = { modelManagerViewModel.loadModelAllowlist() },
        onOpenMenu = { scope.launch { drawerState.open() } },
        onOpenModels = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onAddCloudModel = {
          navController.navigate("$ROUTE_MODEL_MANAGER?action=$MODEL_MANAGER_ACTION_CLOUD")
        },
        onImportModel = {
          navController.navigate("$ROUTE_MODEL_MANAGER?action=$MODEL_MANAGER_ACTION_IMPORT")
        },
      )
    }

    // Model page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
          navArgument("query") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val queryParam = backStackEntry.arguments?.getString("query")
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
        LaunchedEffect(modelName) { modelManagerViewModel.selectModel(initialModel) }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          if (isLegacyTasks(customTask.task.id)) {
            customTask.MainScreen(
              data =
                CustomTaskDataForBuiltinTask(
                  modelManagerViewModel = modelManagerViewModel,
                  onNavUp = {
                    if (navController.previousBackStackEntry != null) {
                      enableModelListAnimation = false
                      navController.navigateUp()
                    } else {
                      scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                    }
                  },
                  initialQuery = queryParam,
                  onMenuClicked = {
                    scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                  },
                )
            )
          } else {
            var disableAppBarControls by remember { mutableStateOf(false) }
            var hideTopBar by remember { mutableStateOf(false) }
            var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            CustomTaskScreen(
              task = customTask.task,
              modelManagerViewModel = modelManagerViewModel,
              onNavigateUp = {
                if (customNavigateUpCallback != null) {
                  customNavigateUpCallback?.invoke()
                } else {
                  enableModelListAnimation = false
                  navController.navigateUp()

                  // clean up all models.
                  for (curModel in customTask.task.models) {
                    val instanceToCleanUp = curModel.instance
                    scope.launch(Dispatchers.Default) {
                      modelManagerViewModel.cleanupModel(
                        context = context,
                        task = customTask.task,
                        model = curModel,
                        instanceToCleanUp = instanceToCleanUp,
                      )
                    }
                  }
                }
              },
              onMenuClicked = {
                scope.launch { drawerState.apply { if (isClosed) open() else close() } }
              },
              disableAppBarControls = disableAppBarControls,
              hideTopBar = hideTopBar,
              useThemeColor = customTask.task.useThemeColor,
            ) { bottomPadding ->
              customTask.MainScreen(
                data =
                  CustomTaskData(
                    modelManagerViewModel = modelManagerViewModel,
                    bottomPadding = bottomPadding,
                    setAppBarControlsDisabled = { disableAppBarControls = it },
                    setTopBarVisible = { hideTopBar = !it },
                    setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                  )
              )
            }
          }
        }
      }
    }

    // Global model manager page.
    composable(
      route = "$ROUTE_MODEL_MANAGER?action={action}",
      arguments =
        listOf(
          navArgument("action") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          }
        ),
      enterTransition = {
        if (
          initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            initialState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideUpEnter()
        }
      },
      exitTransition = {
        if (
          targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            targetState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideDownExit()
        }
      },
    ) { backStackEntry ->
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        initialAction = backStackEntry.arguments?.getString("action"),
        navigateUp = { navController.navigateUp() },
        onModelSelected = { task, model ->
          navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
        },
        onBenchmarkClicked = { model ->
          navController.navigate("$ROUTE_BENCHMARK/${model.name}")
        },
      )
    }

    // [ALTREX] Linux terminal (Alpine through PRoot).
    composable(
      route = ROUTE_TERMINAL,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      com.google.ai.edge.gallery.terminal.TerminalScreen(navigateUp = { navController.navigateUp() })
    }

    // [ALTREX] Connectors (remote MCP servers).
    composable(
      route = ROUTE_CONNECTORS,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      com.google.ai.edge.gallery.connectors.ConnectorsScreen(navigateUp = { navController.navigateUp() })
    }

    // [ALTREX] Voice notes: dictation, list and graph.
    composable(
      route = ROUTE_NOTES,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      com.google.ai.edge.gallery.notes.NotesScreen(
        navigateUp = { navController.navigateUp() },
        modelManagerViewModel = modelManagerViewModel,
      )
    }

    // Benchmark creation page.
    composable(
      route = "$ROUTE_BENCHMARK/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

      modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
        BenchmarkScreen(
          initialModel = model,
          modelManagerViewModel = modelManagerViewModel,
          onBackClicked = {
            enableModelListAnimation = false
            navController.navigateUp()
          },
        )
      }
    }
  }
  }

  // Settings dialog
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  // [ALTREX] Content sent from another app ("Demander à Altrex", share sheet): open the chat with a
  // downloaded model unless it is already on screen. The chat input then takes the request.
  val sharedRequest by com.google.ai.edge.gallery.ui.common.chat.SharedInbox.pending.collectAsState()
  val shareContext = LocalContext.current
  LaunchedEffect(sharedRequest, modelManagerUiState.tasks.isNotEmpty()) {
    if (sharedRequest == null || modelManagerUiState.tasks.isEmpty()) return@LaunchedEffect
    val entry = navController.currentBackStackEntry
    val onChat =
      entry?.destination?.route?.startsWith(ROUTE_MODEL) == true &&
        entry.arguments?.getString("taskId") == BuiltInTaskId.LLM_CHAT
    if (onChat) return@LaunchedEffect
    val chatTask = modelManagerUiState.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }
    fun downloaded(name: String) =
      modelManagerUiState.modelDownloadStatus[name]?.status == ModelDownloadStatusType.SUCCEEDED
    val model =
      chatTask?.models?.firstOrNull {
        it.name == modelManagerUiState.selectedModel.name && downloaded(it.name)
      } ?: chatTask?.models?.firstOrNull { downloaded(it.name) }
    if (chatTask == null || model == null) {
      com.google.ai.edge.gallery.ui.common.chat.SharedInbox.take()
      android.widget.Toast.makeText(
          shareContext,
          "Télécharge ou importe d'abord un modèle pour discuter avec Altrex.",
          android.widget.Toast.LENGTH_LONG,
        )
        .show()
      return@LaunchedEffect
    }
    navController.navigate("$ROUTE_MODEL/${chatTask.id}/${model.name}")
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  // Wait until the model manager has been initialized and the tasks are available.
  if (data != null && modelManagerUiState.tasks.isNotEmpty()) {
    intent.data = null
    val uriStr = data.toString()
    // [ALTREX] Never log the link itself: its query carries the user's text.
    Log.d(TAG, "navigation link clicked: ${data.scheme}://${data.host}")
    // 1. Precise model deep links: com.google.ai.edge.gallery://model/<taskId>/<modelName>
    if (uriStr.startsWith("com.google.ai.edge.gallery://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = resolveTaskAlias(data.pathSegments.get(data.pathSegments.size - 2))
        val modelName = data.pathSegments.last()
        val queryStr = data.getQueryParameter("query")
        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          val route =
            if (!queryStr.isNullOrEmpty()) {
              "$ROUTE_MODEL/${taskId}/${model.name}?query=${Uri.encode(queryStr)}"
            } else {
              "$ROUTE_MODEL/${taskId}/${model.name}"
            }
          navController.navigate(route)
        }
      } else {
        Log.e(TAG, "Malformed model deep link received")
      }
    } else if (uriStr == "com.google.ai.edge.gallery://global_model_manager") {
      navController.navigate(ROUTE_MODEL_MANAGER)
    } else {
      // 2. Dynamic task-level deep links: com.google.ai.edge.gallery://<taskId>
      // [ALTREX] Removed tasks (llm_agent_chat from older reminders…) open their replacement.
      val host = data.host?.let { resolveTaskAlias(it) }
      if (host != null) {
        val queryStr = data.getQueryParameter("query")
        val task = modelManagerUiState.tasks.find { it.id == host }
        if (task != null) {
          // Pick the first successfully downloaded model or the default active model for this task
          val defaultModel =
            task.models.firstOrNull { model ->
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            } ?: task.models.firstOrNull()

          if (defaultModel != null) {
            val route =
              if (!queryStr.isNullOrEmpty()) {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}?query=${Uri.encode(queryStr)}"
              } else {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}"
              }
            navController.navigate(route)
          } else {
            Log.e(TAG, "No available model found for task: $host")
          }
        }
      }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  onMenuClicked: (() -> Unit)? = null,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          shouldShowHistoryButton = false,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onMenuClicked = onMenuClicked,
          onModelSelected = { prevModel, newSelectedModel ->
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
