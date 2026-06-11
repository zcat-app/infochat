---
id: M1-311
title: "Strip ticket/finding provenance from permanent comments (policy + sweep)"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 30
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any non-comment change — this sweep may not alter a single executable line; javadoc/comment edits only.
  - Commit messages, ticket files, docs/plan — provenance belongs there; only src/ comments are swept.
  - The WHY content of the comments — the invariant/rationale text stays; only the ticket/finding/acceptance-item references go.
acceptance:
  - "⚠ Policy confirmed at start, then: every src/main comment referencing tickets, findings, or acceptance items — the recorded inventory: the ssrf module's M1-xxx references, AuditLogWriter's 'this ticket'/'acceptance item 1', AuditAction's 'M1-068 adds…', the typing codec's 'acceptance item 11' (file may already be deleted by M1-308 — re-grep), plus all other hits of the patterns below — is rewritten to keep its WHY and drop the provenance, per the CLAUDE.md comment policy ('don't reference the current ticket, fix, or callers — that belongs in the commit message')."
  - "Machine check: grep -rEn 'M1-[0-9]+|acceptance item|this ticket|redteam' over infochat-*/src/main/java returns zero matches after the sweep (run it in the diff; quote the empty result in the review notes)."
  - "Test-source javadocs citing tickets/findings (the llm test javadocs the report names, and siblings found by the same grep over src/test) are rewritten the same way where the reference is provenance; a test NAMED after a behaviour keeps its name."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-311: Strip ticket/finding provenance from permanent comments (policy + sweep)

## Context

Deep-review v5 cross-cut **U-70** (`deep-code-review/v5/UNIFIED-REPORT.md`
§4; sources `fable-5/02#F5+03#F4` (CT3), `opus-47/04#F4`, `gpt-55#L-16` —
gitignored; the inventory's named anchors are inlined in acceptance):

Ticket/finding-ID provenance is woven into permanent comments across the
codebase, against the CLAUDE.md comment policy ("Don't reference the
current ticket, fix, or callers … that belongs in the commit message and
rots as the codebase evolves"). The report asks for one policy decision,
then mechanical cleanup.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ User decision at start:** strip (default — it's already the written
  policy) vs codify a carve-out in CLAUDE.md (then this ticket becomes a
  one-paragraph CLAUDE.md edit and the sweep is dropped).
- The risk in this sweep is deleting WHY along with the provenance. The
  rewrite discipline: "M1-023's redteam INFO-LEAK finding showed URLs can
  carry credentials" → "identifier URLs can carry embedded credentials".
  Same fact, no rot.
- Run AFTER the v5 code tickets land — they edit many of the same files
  and some (M1-308) delete files this sweep would otherwise touch.
  Mechanically: regenerate the grep inventory at start, don't trust the
  drafting-time one.
- The grep pattern intentionally catches 'redteam' — those comments cite
  audit findings the same way.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-311-*.md
```
