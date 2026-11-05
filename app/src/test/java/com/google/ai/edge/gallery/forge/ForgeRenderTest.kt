package com.google.ai.edge.gallery.forge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.ui.ApprovalCard
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.ui.ForgeHome
import com.google.ai.edge.gallery.forge.ui.ForgeSessionView
import com.google.ai.edge.gallery.forge.ui.SessionActions
import com.google.ai.edge.gallery.forge.ui.SessionUi
import com.google.ai.edge.gallery.forge.ui.relativeTime
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Forge's screens drawn on the computer (Robolectric): a crash in drawing fails here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ForgeRenderTest {
  @get:Rule val compose = createComposeRule()

  private val now = 1_800_000_000_000L

  /** Nothing animates forever while no turn runs: the default clock is enough. */
  private fun frozen() {}

  /** Draws the frame for real (a capture), so drawing code runs, not only composition. */
  private fun frames() {
    compose.waitForIdle()
    compose.onRoot().captureToImage()
  }

  private fun events(mode: ForgeMode): List<ForgeEvent> =
    listOf(
      ForgeEvent.SessionStart("s", "monsite", "Qwen", mode.key(), "", now),
      ForgeEvent.TurnStart(1, now),
      ForgeEvent.User(1, "ajoute un formulaire de contact avec validation", now),
      ForgeEvent.Todos(1, listOf(ForgeEvent.Todo("Lire la page", "completed"), ForgeEvent.Todo("Formulaire", "in_progress"), ForgeEvent.Todo("Tests", "pending")), now),
      ForgeEvent.ToolCall(1, "c1-1", "Read", "{\"file_path\":\"src/app/contact/page.tsx\"}", now),
      ForgeEvent.ToolResult(1, "c1-1", "Read", "ok", "{}", "48 l.", path = "src/app/contact/page.tsx", ts = now),
      ForgeEvent.ToolCall(1, "c1-2", "Edit", "{\"file_path\":\"page.tsx\"}", now),
      ForgeEvent.ToolResult(1, "c1-2", "Edit", "ok", "{}", "+2 −1", "12\t-\treturn <form>\n12\t+\tconst [e, setE] = useState({});\n13\t+\tfunction valider() {}", 2, 1, "page.tsx", now),
      ForgeEvent.ToolCall(1, "c1-3", "Bash", "{\"command\":\"npm test\"}", now),
      ForgeEvent.ToolResult(1, "c1-3", "Bash", "ok", "{}", "code 0", "12 tests passés", ts = now),
      ForgeEvent.Assistant(1, "x", "Nom, e-mail et message sont vérifiés.", ts = now),
      ForgeEvent.Plan(1, "c1-4", "# Plan\n1. NextAuth\n2. Table membres", now),
      ForgeEvent.Question(1, "c1-5", "Quel framework ?", listOf("React", "Vue"), now),
      ForgeEvent.Note(1, "blocage", "Rappel de Forge : tu sembles bloqué", forModel = true, ts = now),
      ForgeEvent.TurnEnd(1, "interrupted", "L'appli a été fermée.", now),
    )

  private fun ui(mode: ForgeMode, approval: ApprovalRequest? = null) =
    SessionUi(
      project = "monsite-vitrine",
      branch = "main",
      model = "Qwen3.5-4B",
      local = true,
      state = SessionState.fold(events(mode)),
      live = ForgeEngine.Live(running = false, tokensPerSecond = 9.5f),
      running = false,
      contextRatio = 0.38f,
      trusted = false,
      approval = approval,
      questionId = "c1-5",
      planId = "c1-4",
    )

  @Test
  fun session_lookB_withApproval() {
    val req = ApprovalRequest("c1-6", "Bash", "Forge veut lancer une commande", "npm install zod", grantLabel = "« npm »")
    frozen()
    compose.setContent { MaterialTheme { ForgeSessionView(ui(Preset.DEMANDER.mode, req), SessionActions()) } }
    frames()
    compose.onNodeWithText("Tâches").assertIsDisplayed()
  }

  @Test
  fun approvalCard_answers() {
    var answer: ApprovalAnswer? = null
    val req = ApprovalRequest("c1-6", "Edit", "Forge veut modifier a.js", "3\t-\tvieux\n3\t+\tneuf", isDiff = true, risky = true, grantLabel = "les modifications de fichiers")
    compose.setContent { MaterialTheme { ApprovalCard(req) { answer = it } } }
    frames()
    compose.onNodeWithText("Non, plutôt…").performClick()
    compose.onNodeWithText("Refuser").performClick()
    assertEquals(ApprovalAnswer.Choice.NON, answer!!.choice)
  }

  @Test
  fun session_planLookC() {
    frozen()
    frozen()
    compose.setContent { MaterialTheme { ForgeSessionView(ui(Preset.PLAN.mode), SessionActions()) } }
    frames()
  }

  @Test
  fun home() {
    val projects =
      listOf(
        ForgeWorkspace.ProjectInfo("monsite-vitrine", File("/x"), "Next.js", "main", now - 7_200_000, true, "créé"),
        ForgeWorkspace.ProjectInfo("api-notes", File("/y"), "Python", "feat/auth", now - 90_000_000, false, "cloné"),
      )
    val sessions =
      listOf(
        ForgeWorkspace.SessionInfo("monsite-vitrine", "s1", "Formulaire de contact", now - 7_200_000, null, 2, 5, File("/s1")),
        ForgeWorkspace.SessionInfo("api-notes", "s2", "Authentification JWT", now - 90_000_000, "done", 3, 3, File("/s2")),
        ForgeWorkspace.SessionInfo("bot", "s3", "Rappels", now - 300_000_000, "interrupted", 0, 0, File("/s3")),
      )
    frozen()
    compose.setContent {
      MaterialTheme {
        ForgeHome(projects, sessions, "ancien-projet", null, now, {}, {}, {}, {}, {}, {}, {}, {}, {})
      }
    }
    frames()
    compose.onNodeWithText("Build").assertIsDisplayed()
  }

  @Test
  fun relativeTimes() {
    assertEquals("à l'instant", relativeTime(now - 10_000, now))
    assertEquals("il y a 2 h", relativeTime(now - 7_200_000, now))
    assertEquals("hier", relativeTime(now - 90_000_000, now))
  }
}
