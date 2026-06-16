---
id: M1-394
title: "4-llm.sh: verify GGUF download integrity (operator-supplied SHA-256) and strip query/fragment from the derived model filename"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by: []
files_budget: 1
files_scope:
  - prod/scripts/4-llm.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The ollama and remote backend branches — unchanged; only the llamacpp GGUF-download branch is touched.
acceptance:
  - "The llamacpp branch optionally prompts for a SHA-256 (blank skips). When supplied, the downloaded GGUF is verified against it (sha256sum -c or equivalent) before the backend is configured, and a mismatch fails the step non-zero and removes the bad file."
  - "The derived GGUF filename is the basename of the URL's path component only, with any ?query or #fragment stripped, so infochat.llm.*.model is a clean filename (a URL like https://h/model.gguf?token=x yields model.gguf)."
  - "prod/scripts/4-llm.sh passes `bash -n`."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-394: GGUF download integrity + clean filename

## Context

Re-verified at source 2026-06-16. `4-llm.sh:179` downloads an operator-supplied
GGUF URL with `curl -fL` into the model volume with **no integrity check**, and
`gguf_file="$(basename "$gguf_url")"` (line 169) keeps any `?query` string in
the filename, which then becomes the `infochat.llm.*.model` id. The URL is
operator-trusted and fetched over TLS, so this is a low-severity hardening:
an optional checksum closes the tamper window and a clean basename avoids a
malformed model id.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-394-*.md
```
