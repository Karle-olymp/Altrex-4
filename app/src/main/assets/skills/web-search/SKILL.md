---
name: web-search
description: Search the web for up-to-date information and return a list of results.
---

# Web Search

## Instructions

Call the `run_js` tool using `index.html` and a JSON string for `data` with the following fields:
- **query**: Required. The search keywords. Extract the core entities or question from the user's request. Keep it short and focused (2-8 words), and remove conversational filler.
- **lang**: Optional. The 2-letter Wikipedia language code matching the query language (e.g. "en", "fr", "es", "de", "it", "pt", "ja", "zh"). If omitted, the language is detected automatically.

## When to use
- Use this skill whenever the answer depends on current events, recent facts, prices, sports results, or anything that may have changed after your training data.
- Prefer this skill over guessing. If the user asks for the latest information and you are unsure, search first.

## Constraints
- Base your answer only on the returned results. Do not invent facts or URLs.
- Cite the source URLs that support your answer.
- Keep the final answer concise (2-4 sentences) and respond in the SAME language as the user's original prompt.
- If the results do not contain the answer, say so briefly and share the closest relevant information found.
