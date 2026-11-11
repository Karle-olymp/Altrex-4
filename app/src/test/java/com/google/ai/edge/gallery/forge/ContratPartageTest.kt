package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.ToolCalling
import com.google.ai.edge.gallery.forge.engine.Decision
import com.google.ai.edge.gallery.forge.engine.ForgeAction
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.PermissionPolicy
import com.google.ai.edge.gallery.forge.engine.SessionGrants
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.tools.AgentShell
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ALTREX] The contract shared with the PC version (Rust, pc/crates/altrex-core): the same vector
 * files of docs/forge/contrat are run by pc/crates/altrex-core/tests/contrat.rs. A behaviour
 * change of Forge starts with a new case there, so the two engines cannot drift apart silently
 * (docs/pc/PLAN.md §5).
 */
class ContratPartageTest {
  private fun vectors(name: String): JSONObject {
    val file = listOf(File("../docs/forge/contrat/$name"), File("docs/forge/contrat/$name")).first { it.exists() }
    return JSONObject(file.readText())
  }

  private fun cases(o: JSONObject, key: String = "cas"): List<JSONObject> {
    val arr = o.getJSONArray(key)
    return (0 until arr.length()).map { arr.getJSONObject(it) }
  }

  private fun strings(o: JSONObject, key: String): List<String> {
    val arr = o.getJSONArray(key)
    return (0 until arr.length()).map { arr.getString(it) }
  }

  /** Same JSON value, whatever the key order (Android's org.json has no `similar`). */
  private fun same(a: Any?, b: Any?): Boolean =
    when {
      a is JSONObject && b is JSONObject ->
        a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { same(a.opt(it), b.opt(it)) }
      a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
      a is Number && b is Number -> a.toDouble() == b.toDouble()
      else -> a == b
    }

  private fun textOrNull(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

  @Test
  fun commandes_shell() {
    val all = cases(vectors("commandes.json"))
    assertTrue(all.size >= 30)
    for (c in all) {
      val cmd = c.getString("commande")
      assertEquals("simple : $cmd", c.getBoolean("simple"), AgentShell.isSimple(cmd))
      assertEquals("lecture seule : $cmd", c.getBoolean("lecture_seule"), AgentShell.isReadOnly(cmd))
      assertEquals("programme : $cmd", textOrNull(c, "programme"), AgentShell.programOf(cmd))
      assertEquals("à risque : $cmd", c.getBoolean("dangereuse"), PermissionPolicy.isDangerous(cmd))
    }
  }

  @Test
  fun permissions() {
    val all = cases(vectors("permissions.json"))
    assertTrue(all.size >= 25)
    for ((n, c) in all.withIndex()) {
      val mode = ForgeMode.parse(c.getString("mode"))!!
      val a = c.getJSONObject("accords")
      val grants = SessionGrants()
      grants.programs.addAll(strings(a, "programmes"))
      grants.edits = a.getBoolean("fichiers")
      val refused = a.getJSONObject("refus")
      for (k in refused.keys()) grants.refused[k] = refused.getString(k)
      grants.escalated.addAll(strings(a, "escalades"))
      val act = c.getJSONObject("action")
      val action =
        if (act.getString("type") == "ecrire") ForgeAction.WriteFile(textOrNull(act, "chemin"), act.getBoolean("nouveau"))
        else ForgeAction.RunCommand(act.getString("commande"), textOrNull(act, "justification"))
      val expected = c.getJSONObject("attendu")
      when (val d = PermissionPolicy.decide(mode, grants, action)) {
        is Decision.Allow -> assertEquals("cas $n", "autoriser", expected.getString("decision"))
        is Decision.Deny -> {
          assertEquals("cas $n", "refuser", expected.getString("decision"))
          assertEquals("cas $n", expected.getString("motif"), d.reason)
        }
        is Decision.Ask -> {
          assertEquals("cas $n", "demander", expected.getString("decision"))
          assertEquals("cas $n", expected.getString("titre"), d.title)
          assertEquals("cas $n", textOrNull(expected, "grant"), d.grant)
          assertEquals("cas $n", expected.getBoolean("risque"), d.risky)
          assertEquals("cas $n", expected.getBoolean("escalade"), d.escalation)
        }
      }
    }
  }

  @Test
  fun appels_d_outils() {
    for (c in cases(vectors("appels.json"))) {
      val text = c.getString("texte")
      val calls = ToolCalling.parseToolCalls(text)
      val expected = c.getJSONArray("appels")
      assertEquals(text, expected.length(), calls.size)
      for ((i, call) in calls.withIndex()) {
        val e = expected.getJSONObject(i)
        assertEquals(text, e.getString("nom"), call.name)
        assertTrue("$text : ${call.arguments}", same(e.getJSONObject("arguments"), call.arguments))
      }
      assertEquals(text, c.getString("affiche"), ToolCalling.displayText(text))
    }
  }

  @Test
  fun journal_relu_a_l_identique_et_replie() {
    val v = vectors("journal.json")
    val events = ArrayList<ForgeEvent>()
    for (line in cases(v, "lignes")) {
      val e = ForgeEvent.fromJson(line) ?: throw AssertionError("ligne non lue : $line")
      val again = e.toJson(line.getLong("ts"))
      assertTrue("réécriture différente : $again au lieu de $line", same(again, line))
      events.add(e)
    }
    val s = SessionState.fold(events)
    val expected = v.getJSONObject("etat")
    assertEquals(expected.getString("mode"), s.mode.key())
    assertEquals(strings(expected, "programmes").toSet(), s.grants.programs.toSet())
    assertEquals(expected.getBoolean("fichiers"), s.grants.edits)
    val refused = expected.getJSONObject("refus")
    assertEquals(refused.keys().asSequence().toSet(), s.grants.refused.keys.toSet())
    for (k in refused.keys()) assertEquals(refused.getString(k), s.grants.refused[k])
    assertEquals(expected.getInt("taches_total"), s.todos.size)
    assertEquals(expected.getInt("taches_terminees"), s.todos.count { it.status == "completed" })
    assertEquals(expected.getString("titre"), s.title)
    assertEquals(expected.getInt("dernier_tour"), s.lastTurn)
    assertEquals(expected.getBoolean("tour_ouvert"), s.turnOpen)
    assertEquals(textOrNull(expected, "derniere_fin"), s.lastEnd)
  }
}
