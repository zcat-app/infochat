---
name: simplex-v7-live-drive-ground-truth
description: "SimpleX v7 live-driving facts — /_send takes a JSON ARRAY (a bare object fails 'Failed reading: empty'), --execute-log misses edits and raced replies, the client chat DB chat_items table is the ground truth for delivered text."
metadata:
  type: project
---

Measured driving the bot through headless SimpleX clients (v7.0.0.x line):

- **`/_send` wants an array.** The WS payload must be a JSON array of one
  command; a single bare object returns `Failed reading: empty`.
- **`--execute-log messages` is not ground truth.** It does not replay message
  EDITS — and live-text/streamed replies land as edits — and it misses replies
  that beat its subscription. The client chat database
  (`simplex_v1_chat.db`, table `chat_items`) is the ground truth for what was
  actually delivered; sample from there, use execute-log only for liveness.
- **Prompt shape decides turn length.** Free-form DM prompts often draw an
  instant clarification question or refusal (<1s); corpus-grounded prompts
  (naming real ingested content) reliably produce long generated turns. Design
  acceptance legs around corpus-grounded prompts or the evidence under-samples
  the generative path.

**How to apply:** any SimpleX live leg captures `chat_items` (not the CLI
log) as evidence, sends via the array form, and records the client binary
version — the wire contract above is v7-observed, not a cross-version
guarantee.
