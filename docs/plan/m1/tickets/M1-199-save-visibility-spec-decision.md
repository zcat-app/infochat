---
id: M1-199
title: "/save visibility sentence: adjudicate the scope-visibility reading"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 4
files_scope:
  - docs/spec/commands.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the save-cap atomicity machinery (SELECT … FOR UPDATE counter) — implemented and tested, untouched
  - /saved retrieval filters and /unsave — unchanged either way
  - the chat tools' per-(user, scope) visibility implementation (searchPosts/getPost) — referenced as the definitional contrast only; their code is M1-193/M1-194/M1-197 territory
  - re-litigating D13 (saves are per-user-globally) — the carve-out stands; only the save-TIME visibility check is in question
acceptance:
  - "A decision is recorded and applied: EITHER (a) the sentence in docs/spec/commands.md §Content — \"The `/save` flow never lets a user bookmark content they cannot see.\" — is amended to bind exactly the enumerated status rules that precede it (READY visible-and-snapshotted; QUARANTINED \"treated as an unknown UID\"; NEEDS_REVIEW \"treated as an unknown UID\"), pinning the current implementation; OR (b) /save gains the wider per-(user, scope) visibility filter, with the filter's definition written into the spec sentence and implemented in SaveCommandHandler with named tests"
  - "Whichever direction: the three already-implemented status legs stay intact — the existing named tests asserting QUARANTINED → error.save.unknown_uid, NEEDS_REVIEW → error.save.unknown_uid, and unknown UID → error.save.unknown_uid stay green"
  - "The choice and its argument (the D13 per-user-globally carve-out, schema.md §Invariants Invariant 1's documented saved_post exception, and the chat tools' \"visible in the calling (user, scope)\" definition in security.md §Prompt-injection defenses) are recorded in the commit message"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D13
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-199: /save visibility sentence: adjudicate the scope-visibility reading

## Context

Unified finding P10 (`deep-code-review/v2/UNIFIED.md` §2): SaveCommandHandler
selects `WHERE p.uid = ? AND p.status = 'READY'` (:99-104) — any
registered DM user can bookmark any READY post by uid, regardless of
subscriptions or scope.

**Re-anchoring result (the batch-2 drafting prompt flagged this as the
canonical trap):** the opus-47 quote DOES exist verbatim —
commands.md §Content closes the `/save` visibility-of-target rules with
"The `/save` flow never lets a user bookmark content they cannot see."
However, every rule that sentence summarizes is **status-based** (READY /
QUARANTINED / NEEDS_REVIEW), and all three legs are already implemented
and covered by named tests in SaveCommandHandlerTest (verified on main
2026-06-07: tests at :133-170 pin QUARANTINED, NEEDS_REVIEW, and
unknown-UID all → error.save.unknown_uid with no row written).

What remains is a genuine **spec ambiguity**, not a confirmed code bug:
does "content they cannot see" also mean the per-(user, scope)
visibility that security.md's tool catalogue defines for getPost
("returns null for a UID not visible in the calling scope")? Points
against the wider reading: saves are per-user-globally by design (D13;
schema.md Invariant 1 names saved_post as the only documented carve-out
from per-(user, scope) isolation), so a save deliberately transcends the
scope it was made in — a save-time subscription filter sits oddly beside
that. Points for: a UID obtained out-of-band names content no retrieval
surface would show the caller.

This is a decision-tier ticket (user call on direction at start), per
the same pattern as the audit's other decision groupings.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T22 under `deep-code-review/v2/` (opus-47
  prov F5; re-anchored against the current spec text at draft time).
- If direction (a) is chosen this is a near-pure spec edit bundled with
  (at most) a pinning test — the code change footprint is zero.
