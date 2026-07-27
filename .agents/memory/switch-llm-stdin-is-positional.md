---
name: switch-llm-stdin-is-positional
description: "SwitchLlmWiringTest drives prod/switch-llm.sh through raw positional stdin, so adding one task to LLM_TASKS shifts every later answer by one line — the test then answers the wrong prompts and fails far from the edit."
metadata: 
  type: project
---

`prod/switch-llm.sh` iterates `LLM_TASKS`
(`security tagger entity classifier summarizer chat translator`, pinned at
`prod/switch-llm.sh:51` and mirrored in `prod/scripts/4-llm.sh:74`) to emit one
interactive prompt per task, at eight-plus loop sites.

`SwitchLlmWiringTest` (`infochat-llm-adapter`) runs the real script under
`bash` via `ProcessBuilder` and writes a single raw string to its stdin —
there is no prompt matching, only newline position.

**Why it bites:** adding a task to `LLM_TASKS` inserts a prompt in the middle
of that stream, so every subsequent scripted answer lands one slot early. The
failure surfaces as an unrelated assertion about generated config content, not
as "your new task is unwired", so the edit and the breakage look disconnected.

**How to apply:** any ticket that adds or removes an LLM task must budget an
`+1 Enter` (or removal) per affected stdin fixture in `SwitchLlmWiringTest`,
and the task list edit must land in **both** `prod/switch-llm.sh` and
`prod/scripts/4-llm.sh` — they carry independent copies. The `-am` module build
in [[mvn-dtest-filter-blocked-by-tripwire]] is the fast way to check it.
