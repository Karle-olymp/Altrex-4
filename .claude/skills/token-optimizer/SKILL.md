---
name: token-optimizer
description: Minimize token and context use when executing tasks, answering questions, writing code, or calling tools. Use to avoid redundant tool calls, bound file/command/image reads, avoid polling loops and needless subagents, and keep responses concise and high-signal. Also when the user asks to save tokens, be economical, reduce cost or context ("économiser les tokens", "moins de contexte", "réponses courtes"). Applies to every task; it governs how you work, not what you decide.
---

# Token Optimizer

Spend the fewest tokens that still fully and correctly answer the request. Optimize four places: tool calls, context reads, long-running work, final output. Never trade correctness, required verification, or a faithful report for brevity.

## 1. Before calling a tool, decide if you need one
- If existing context or general knowledge answers it, answer directly — no search, bash, or file read.
- Don't re-fetch what's already in this conversation (file contents, command output, a prior result).
- Pick the single most direct tool; don't fan out exploratory tools "just in case".

## 2. Call tools efficiently
- Run independent calls in one turn (parallel), not in a sequential chain.
- Prefer dedicated tools over shell: `Grep`/`Glob`/`Read` instead of `grep`/`find`/`cat`. They integrate and are easier to bound.
- Go to the known path/symbol directly; avoid broad directory scans or speculative searches.
- After a failed call, read the error and change the approach — never blind-retry the same parameters.
- Batch related edits: one `Write` for a new or fully rewritten file, one script for many mechanical replacements, rather than dozens of single `Edit` calls.

## 3. Bound what enters context
- `Read` with `offset`/`limit` for the region you need; search large files instead of reading them whole.
- Cap search output (`head_limit`); in shell use line ranges, `head`/`tail`, `grep`, and quiet flags (`-q`, `--quiet`) to suppress logs and progress bars.
- Don't re-read a file right after you edited it — Edit/Write already confirm success and the harness tracks state.
- **Images are expensive.** Inspect one contact sheet or a downscaled thumbnail rather than many full-size screenshots or frames; don't re-open an image you already looked at.
- **Connector/MCP results:** ask for minimal output, paginate in small batches, and filter lists server-side when the tool allows it.

## 4. Long-running and delegated work
- Run long jobs (builds, renders, test suites) in the background and wait for the completion notification — no `sleep` loops or repeated status polls.
- When a job finishes, read only the tail or the summary of its log, not the whole log.
- Don't spawn subagents unless asked or clearly cheaper overall: each one starts cold and re-reads context. Do small and medium work inline.

## 5. Keep output concise
- Lead with the answer, code, or result. No preamble, no restating the prompt, no closing recap.
- Show a targeted diff or the changed lines, not whole unchanged files.
- Don't narrate routine steps; report what matters (what changed, what failed, what's left).

## Gotchas
- **Don't under-verify.** Saving tokens never justifies guessing file contents or skipping a check before a destructive or hard-to-reverse edit. Read the exact lines around the change — minimal, not zero.
- **Unbounded output floods context.** `cat` a big file, `find /`, or an unfiltered test runner can dump thousands of tokens. Always restrict output when inspecting unfamiliar files, directories, or logs.
- **Concise ≠ incomplete.** Still answer every part of the request and still surface failures, caveats, and skipped steps honestly.
- **Cheap ≠ fewer checks on visuals.** For video, UI or design work, still look at the result — just through one contact sheet instead of every frame.
