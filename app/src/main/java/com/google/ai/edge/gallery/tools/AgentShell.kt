package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.terminal.LinuxEnv

/**
 * [ALTREX] The model's shell, in the spirit of Claude Code's: the working directory and exported
 * variables carry over from one command to the next, files are read with line numbers and written
 * or edited exactly, long tasks run in the background, and approvals follow rules the user sets
 * (read-only commands free, "always" for one program, everything).
 *
 * Everything runs inside the model's own Alpine ([LinuxEnv.AGENT]) through PRoot. Commands and file
 * contents go in through stdin and paths as arguments, never pasted into a shell line, and the app
 * never writes into the Linux files itself (a symlink made by the model could point it outside).
 */
object AgentShell {
  /** Where the shell keeps its state, inside the Linux. */
  private const val STATE = "/root/.altrex"

  /** Marks the line that reports the working directory at the end of a command's output. */
  private const val CWD_MARK = "\u001EALTREX_CWD="

  private const val RESTORE =
    "S=$STATE; mkdir -p \"\$S\"\n" +
      "[ -f \"\$S/env\" ] && . \"\$S/env\" >/dev/null 2>&1\n" +
      "cd \"\$(cat \"\$S/cwd\" 2>/dev/null)\" 2>/dev/null || cd /root\n"

  /** Runs the command read from stdin where the previous one left off, then saves cwd and env. */
  internal const val WRAPPER =
    RESTORE +
      "__altrex_cmd=\$(cat)\n" +
      "eval \"\$__altrex_cmd\" </dev/null\n" +
      "__altrex_rc=\$?\n" +
      "pwd > \"\$S/cwd\"\n" +
      "export -p | grep -v -e ' PWD=' -e ' OLDPWD=' -e ' SHLVL=' -e ' _=' > \"\$S/env\" 2>/dev/null\n" +
      "printf '\\n$CWD_MARK%s\\n' \"\$(pwd)\"\n" +
      "exit \$__altrex_rc\n"

  /** Background job: same starting point, nothing saved after. */
  internal const val WRAPPER_BG = RESTORE + "__altrex_cmd=\$(cat)\nexec /bin/sh -c \"\$__altrex_cmd\" </dev/null\n"

  /** Numbered lines [$2, $2+$3) of file $1 (lines cut at 500 characters), after a total line. */
  internal const val READ_SCRIPT =
    RESTORE +
      "[ -f \"\$1\" ] || { echo \"not a file: \$1\"; exit 2; }\n" +
      "echo \"TOTAL=\$(wc -l < \"\$1\")\"\n" +
      "awk -v s=\"\$2\" -v n=\"\$3\" 'NR>=s && NR<s+n { l=\$0; if (length(l)>500) l=substr(l,1,500) \" […]\"; printf \"%6d\\t%s\\n\", NR, l }' \"\$1\"\n"

  /** Writes stdin to file $1 (folders created), then prints its size. */
  internal const val WRITE_SCRIPT =
    RESTORE + "mkdir -p -- \"\$(dirname -- \"\$1\")\" && cat > \"\$1\" && wc -c < \"\$1\"\n"

  /** Prints file $1 as it is. */
  internal const val CAT_SCRIPT = RESTORE + "[ -f \"\$1\" ] || { echo \"not a file: \$1\" >&2; exit 2; }\ncat -- \"\$1\"\n"

  data class Result(val exitCode: Int, val output: String, val cwd: String?, val timedOut: Boolean)

  /** Splits the cwd report off a command's output. */
  internal fun splitCwd(raw: String): Pair<String, String?> {
    val at = raw.lastIndexOf(CWD_MARK)
    if (at < 0) return raw to null
    val cwd = raw.substring(at + CWD_MARK.length).trim().lineSequence().firstOrNull()
    return raw.substring(0, at).trimEnd('\n') to cwd
  }

  suspend fun run(context: Context, command: String, timeoutMs: Long, onOutput: (String) -> Unit = {}): Result {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    val r = env.exec(listOf("/bin/sh", "-lc", WRAPPER), command, timeoutMs, onOutput)
    val (output, cwd) = splitCwd(r.output)
    return Result(r.exitCode, output, cwd, r.timedOut)
  }

  suspend fun script(context: Context, script: String, args: List<String>, stdin: String? = null): LinuxEnv.CommandResult =
    LinuxEnv.of(context, LinuxEnv.AGENT).exec(listOf("/bin/sh", "-lc", script, "sh") + args, stdin, 60_000)

  // ---------------------------------------------------------------- edits

  sealed class Edit {
    data class Ok(val text: String, val count: Int) : Edit()
    data class Fail(val reason: String) : Edit()
  }

  /** Claude Code's rule: [old] must be found, and only once unless [all]. */
  fun applyEdit(text: String, old: String, new: String, all: Boolean): Edit {
    if (old.isEmpty()) return Edit.Fail("old_string is empty.")
    if (old == new) return Edit.Fail("old_string and new_string are the same.")
    var count = 0
    var i = text.indexOf(old)
    while (i >= 0) {
      count++
      i = text.indexOf(old, i + old.length)
    }
    return when {
      count == 0 -> Edit.Fail("old_string was not found in the file. Read the file again and copy the text exactly.")
      count > 1 && !all ->
        Edit.Fail("old_string was found $count times. Add surrounding lines to make it unique, or set replace_all.")
      else -> Edit.Ok(text.replace(old, new), count)
    }
  }

  // ---------------------------------------------------------------- approvals

  enum class Mode { ASK_ALL, READS_FREE, ALLOW_ALL }

  private const val PREFS = "altrex_run_command"
  private const val KEY_MODE = "mode"
  private const val KEY_OLD_ALWAYS = "always_allow"
  private const val KEY_PREFIXES = "allowed_prefixes"
  private const val KEY_EDITS = "edits_allowed"

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Default: read-only commands run freely, the rest is asked (the older "always" switch is kept). */
  fun mode(context: Context): Mode {
    val p = prefs(context)
    p.getString(KEY_MODE, null)?.let { saved -> return Mode.entries.find { it.name == saved } ?: Mode.READS_FREE }
    return if (p.getBoolean(KEY_OLD_ALWAYS, false)) Mode.ALLOW_ALL else Mode.READS_FREE
  }

  fun setMode(context: Context, mode: Mode) {
    prefs(context).edit().putString(KEY_MODE, mode.name).putBoolean(KEY_OLD_ALWAYS, mode == Mode.ALLOW_ALL).apply()
  }

  /** Programs the user said "always" for. */
  fun allowedPrefixes(context: Context): Set<String> = prefs(context).getStringSet(KEY_PREFIXES, emptySet()).orEmpty().toSet()

  fun allowPrefix(context: Context, prefix: String) {
    prefs(context).edit().putStringSet(KEY_PREFIXES, allowedPrefixes(context) + prefix).apply()
  }

  fun removePrefix(context: Context, prefix: String) {
    prefs(context).edit().putStringSet(KEY_PREFIXES, allowedPrefixes(context) - prefix).apply()
  }

  /** File writes and edits without asking (like Claude Code's "accept edits"). */
  fun editsAllowed(context: Context): Boolean = mode(context) == Mode.ALLOW_ALL || prefs(context).getBoolean(KEY_EDITS, false)

  fun setEditsAllowed(context: Context, allowed: Boolean) {
    prefs(context).edit().putBoolean(KEY_EDITS, allowed).apply()
  }

  /** Commands that only read (and their read-only sub-commands). */
  private val READ_ONLY =
    setOf(
      "ls", "cat", "head", "tail", "wc", "pwd", "echo", "grep", "egrep", "fgrep", "find", "which",
      "whoami", "uname", "date", "file", "stat", "du", "df", "tree", "id", "hostname", "sort", "uniq",
      "cut", "tr", "basename", "dirname", "realpath", "readlink", "md5sum", "sha1sum", "sha256sum",
      "printenv", "nproc", "free", "uptime", "ps", "jq", "diff", "cmp", "column", "nl", "true",
    )
  private val READ_ONLY_SUB =
    mapOf(
      "git" to setOf("status", "log", "diff", "show", "branch", "remote", "rev-parse", "ls-files", "blame"),
      "apk" to setOf("info", "search", "list", "policy"),
      "npm" to setOf("ls", "list", "view", "outdated"),
      "pip" to setOf("list", "show", "freeze"),
    )

  /** Options that make a reader run another program (rg --pre, sort --compress-program, tar…). */
  private val RUNS_PROGRAMS =
    listOf("--pre", "--compress-program", "--use-compress-program", "--to-command", "--checkpoint-action", "--exec", "--shell")

  /**
   * The program a command word names, only when it is a plain name or lives in a system folder: a
   * path like ./ls or /root/ls could be anything the model wrote, so it never counts as "ls".
   */
  internal fun bareProgram(word: String?): String? {
    if (word.isNullOrEmpty()) return null
    if (!word.contains('/')) return word
    val dir = word.substringBeforeLast('/') + "/"
    return word.substringAfterLast('/').takeIf { it.isNotEmpty() && dir in SYSTEM_DIRS }
  }

  private val SYSTEM_DIRS = setOf("/bin/", "/usr/bin/", "/sbin/", "/usr/sbin/", "/usr/local/bin/")

  private val FIND_WRITES = setOf("-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
  private val LIST_FLAGS = setOf("-a", "-r", "-v", "-vv", "--list", "--all", "--verbose")

  /** No chaining, redirection, background or substitution: only a pipeline of plain commands. */
  internal fun isSimple(command: String): Boolean =
    command.isNotBlank() &&
      command.none { it == ';' || it == '&' || it == '>' || it == '<' || it == '`' || it == '\n' } &&
      !command.contains("$(")

  private fun segments(command: String): List<List<String>> =
    command.split('|').map { seg -> seg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }

  /** The program a command starts with ("npm install x" → "npm"). */
  fun programOf(command: String): String? =
    bareProgram(command.trim().split(Regex("\\s+")).firstOrNull())?.takeIf { isSimple(it) }

  internal fun isReadOnly(command: String): Boolean {
    if (!isSimple(command)) return false
    return segments(command).all { words ->
      val prog = bareProgram(words.firstOrNull()) ?: return@all false
      val args = words.drop(1)
      when {
        args.any { a -> RUNS_PROGRAMS.any { a.startsWith(it) } } -> false
        // Options that make these readers write or delete.
        args.any { it.startsWith("--output") } -> false
        prog == "find" -> args.none { it in FIND_WRITES }
        (prog == "sort" || prog == "tree") -> args.none { it.startsWith("-o") }
        prog == "uniq" -> args.count { !it.startsWith("-") } <= 1
        prog in READ_ONLY -> true
        prog == "git" && args.firstOrNull() in setOf("branch", "remote") -> args.drop(1).all { it in LIST_FLAGS }
        else -> READ_ONLY_SUB[prog]?.contains(args.firstOrNull()) == true
      }
    }
  }

  /** Whether [command] must be shown to the user first, under [mode] and the "always" programs. */
  fun needsApproval(mode: Mode, prefixes: Set<String>, command: String): Boolean {
    if (mode == Mode.ALLOW_ALL) return false
    if (!isSimple(command)) return true
    // Free when every program of the pipeline is an "always" one, or (default mode) only reads.
    return !segments(command).all { words ->
      bareProgram(words.firstOrNull())?.let { it in prefixes } == true ||
        (mode == Mode.READS_FREE && isReadOnly(words.joinToString(" ")))
    }
  }
}
