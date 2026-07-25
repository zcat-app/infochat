---
id: M1-691
title: "Decide whether degraded-digest assembly operands need sanitizing"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - docs/spec/security.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The non-degraded render path (DigestRenderer.renderSections). Its
    output is LLM prose that already passes through LlmOutputSanitizer in
    full; only the degraded single-message branch assembles sanitizer
    output with operands the sanitizer never saw.
  - >-
    LlmOutputSanitizer itself — its CLOSED_LIST, its flatten pass, and
    its `](` adjacency-breaking mechanism. This ticket decides WHAT is
    fed to the sanitizer, not how the sanitizer behaves.
  - >-
    The ingest-side question of whether source.display_name should be
    constrained at /add-source time. Tightening the write boundary is a
    separate surface with its own command handler and audit row; this
    ticket looks only at the render boundary.
  - >-
    Translation of degraded output. The degraded branch deliberately
    skips translation and that is unchanged here — the spec already
    records it.
  - >-
    /summary, /retry --digest, and the categorized-digest section
    renderers. They do not use DegradedDigestRenderer's assembly.
acceptance:
  - >-
    An investigation outcome is recorded in the ticket body: whether a
    hostile or malformed source.display_name can place `](` (or a
    closed-list admin-command token) into delivered degraded-digest
    bytes, established by reading DegradedDigestRenderer's assembly and
    the source.display_name write path — not by assumption.
  - >-
    If the answer is yes, DegradedDigestRenderer sanitizes the offending
    operand(s) and DegradedDigestRendererTest gains a case asserting a
    hostile display name cannot produce `](` in the assembled line. If
    the answer is no, the ticket closes with the reasoning written down
    and no code change.
  - >-
    docs/spec/security.md §"Sanitizer output never contains `](`" is
    reconciled with whatever the investigation concludes, and its
    forward reference to M1-691 is removed or replaced with the outcome.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D17
  - D30
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-691: Decide whether degraded-digest assembly operands need sanitizing

## Context

Found on 2026-07-25 while falsifying an out-of-model observation from the
M1-688 red-team audit (`docs/plan/m1/redteam/M1-688-2026-07-25.md`, third
item). The audit item itself was dismissed — it claimed only that M1-688
raises the *probability* of the degraded render firing, which is a
probability shift on an already-accepted bypass surface. Checking it
surfaced something else.

`docs/spec/security.md` §"Sanitizer output never contains `](`" used to
justify the degraded branch's exemption by saying it "ships feed-derived
headlines without sanitizing or translating". The sanitizing half has
been false since M1-675 (`e3815eca`): `DegradedDigestRenderer.java:38`
calls `llmOutputSanitizer.sanitize(p.title())` on every headline.

The exemption's *conclusion* survives, but for a different reason. The
line is assembled as:

    sanitize(p.title()) + " — " + p.sourceDisplayName() + <url>

so a **sanitized** headline is joined with a `sourceDisplayName` and a
URL that never pass through the sanitizer. The spec has been corrected to
state that reason instead. What has NOT been decided is whether those
unsanitized operands are acceptable — that is this ticket.

The question matters because the stated reason changes what a reader
would do about it. Under the old text, sanitizing the headline looked
like the fix; it is already done and closes nothing. The live gap, if
there is one, is in the assembly.

## Acceptance

See the frontmatter. This is an investigation first: establish whether a
hostile `source.display_name` can actually reach delivered bytes and
carry `](` or a closed-list token. Only then decide between a code change
and a written-down no-change, and reconcile the spec either way.

## Out-of-scope

The non-degraded render path, `LlmOutputSanitizer`'s own internals, the
`/add-source` write boundary, degraded-output translation, and the other
digest render surfaces. See the frontmatter.

## Notes

- **Establish reachability before designing a fix.** `source.display_name`
  is operator-supplied via `bootstrap-sources.json` or `/add-source`
  (bot-admin only), not attacker-supplied over the wire. If every write
  path to that column is already bot-admin-gated, the realistic threat is
  a malformed name rather than a hostile one, and the no-change arm with
  written reasoning may be the correct outcome. Check the write paths
  before assuming a fix is owed — the D30 plain-text rule means a stray
  `](` degrades rendering, it does not grant privilege.
- The URL operand is worth a separate look from the display name: URLs
  are feed-supplied rather than operator-supplied, so their reachability
  argument is different and probably weaker.
- `DegradedDigestRendererTest` currently has 3 cases; none covers a
  hostile operand. Whatever the outcome, the reasoning belongs in this
  ticket's body so the next auditor does not re-derive it — that
  re-derivation cost is exactly what the M1-688 audit was trying to
  avoid when it recorded its disproved timezone item.
- Adjacent code: `DegradedDigestRenderer.render`,
  `LlmOutputSanitizer.sanitize`, `SourceUpsertService` (the
  `display_name` write path).
