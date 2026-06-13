---
id: M1-311
title: "Comment policy: carve out stable decision-record anchors"
status: pending
created: 2026-06-11
last_updated: 2026-06-13
blocked_by: []
files_budget: 1
files_scope:
  - CLAUDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any source-comment edit. The prior 7-module sweep is dropped; this ticket changes ONE policy paragraph and touches no src/ comment, no executable line.
  - docs/process/engineering-rules-verbatim.md. Verified at refine (grep 2026-06-13) the comment policy is a §Coding style preference living only in CLAUDE.md; it is NOT a reviewer-enforced rule and is NOT mirrored in the verbatim file.
  - The ban on genuinely-rotting references. Caller/usage refs ("used by X", "added for the Y flow", "handles the case from issue #123") and ref-only comments with no inline WHY stay forbidden; only stable in-repo decision-record anchors are carved out.
acceptance:
  - "CLAUDE.md §Coding style → 'Comment important, crucial, or complex code' → the WHY-not-WHAT bullet (line 148, currently: 'Don't reference the current ticket, fix, or callers (\"used by X\", \"added for the Y flow\", \"handles the case from issue #123\") — that belongs in the commit message and rots as the codebase evolves') is amended so it PERMITS a stable decision-record anchor — a ticket ID (M1-NNN), a redteam/audit finding ID, a decision ID (D-NN), or an in-repo docs/plan/... path — inside a comment as a SUPPLEMENT to an inline WHY, never as a substitute. The amended bullet states the reason: those identifiers are immutable and resolve within the repo, so the 'rots as the codebase evolves' rationale does not apply to them (unlike caller refs, which do rot)."
  - "The same bullet KEEPS banning the references that genuinely rot: caller/usage refs ('used by X', 'added for the Y flow', 'handles the case from issue #123') and any comment whose ONLY content is a reference with no inline WHY. A reader can tell from the bullet which references are allowed (stable, in-repo, supplementary) and which are not (rotting, or load-bearing-by-themselves)."
  - "The diff touches CLAUDE.md only: git diff --name-only on the implementing commit lists exactly one path, and grep -rEn 'M1-[0-9]+|acceptance item|this ticket|redteam' over infochat-*/src is UNCHANGED from main (this ticket does not sweep source)."
  - "mvn -B clean verify from the repo root exits 0 (no code is touched; this is a no-op confirmation that the doc edit broke nothing)."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-13
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — the ticket's mandated ⚠ start-time decision ("strip vs codify a
      carve-out") resolved to the carve-out. That supersedes the ticket's
      premise that a code sweep is needed: the sweep is dropped and the
      resolution is a CLAUDE.md policy edit. Lens evaluation at start
      (performance: irrelevant; security: a blanket strip degrades threat-
      control comments and severs resolvable links into docs/plan/m1/redteam/;
      maintenance: the IDs are immutable in-repo anchors that do not rot, so
      the policy's "rots as it evolves" rationale does not apply to them).
revisions:
  - date: 2026-06-13
    reason: |
      premise-fail refine — the ⚠ start-time decision chose the CLAUDE.md
      carve-out over the blanket strip. Ticket rewritten from a 7-module /
      ~105-file / ~356-edit source-comment sweep into a 1-file policy
      amendment. Pre-refine frontmatter snapshot below.
    snapshot: |
      title: "Strip ticket/finding provenance from permanent comments (policy + sweep)"
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
      acceptance (gist): every src/main comment referencing tickets/findings/
        acceptance items rewritten to keep WHY and drop provenance; machine
        check grep over infochat-*/src/main/java returns zero matches; test
        javadocs swept likewise; mvn verify exits 0.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-311: Comment policy — carve out stable decision-record anchors

## Context

Deep-review v5 cross-cut **U-70** (`deep-code-review/v5/UNIFIED-REPORT.md`
§4) flagged ticket/finding-ID provenance woven into permanent comments as a
violation of the CLAUDE.md comment policy. The ticket carried a mandated ⚠
start-time decision: **strip** the provenance from ~105 source files, or
**codify a carve-out** in CLAUDE.md.

The carve-out was chosen, on a lens evaluation grounded in the actual
comments (not the drafting-time guess):

- **Performance** — irrelevant; comments compile away.
- **Security** — a blanket strip is net-negative. The highest-value, hardest-
  to-strip comments are the security controls (SSRF canonicalization, DNS-
  rebinding watchdog, PERM-ESCAL/AUDIT-EVASION closures, DoS bucket sizing,
  prompt-injection sanitizer). Their finding-ID anchors are *resolvable links*
  into `docs/plan/m1/redteam/*`; several are load-bearing ("re-open the
  redteam Finding 2 surface"). A 356-edit mechanical sweep is the worst place
  to invent replacement threat prose.
- **Maintenance** — the project deliberately maintains a rich, append-only,
  in-repo decision log; comment→record anchors are connective tissue. Those
  IDs are immutable and don't rot, so the policy's "rots as it evolves"
  rationale never applied to them — only to caller refs.

U-70 is a *conformance* finding (code ≠ stated policy), resolvable by changing
either side. Changing the policy to match the (sound) reality is the better
fix and ends the recurring finding permanently.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Scope of the carve-out is narrow.** Permit a stable in-repo anchor
  (ticket/finding/decision ID, or `docs/plan/...` path) as a *supplement* to
  an inline WHY — never as a substitute, and never for references that rot
  (callers/usage). The inline-WHY requirement is unchanged; the carve-out only
  stops banning a redundant-but-stable pointer alongside it.
- **Proposed replacement for CLAUDE.md line 148** (developer finalizes wording
  at implementation; this is the intent, not a frozen string):
  > Still WHY-not-WHAT: don't narrate code that named identifiers already
  > explain. Don't reference *rotting* context — callers or usage ("used by
  > X", "added for the Y flow") and bare issue refs as a comment's only
  > content — that belongs in the commit message and rots as the codebase
  > evolves. A *stable decision-record anchor* (a ticket ID, a redteam/audit
  > finding ID, a decision ID, or an in-repo `docs/plan/...` path) MAY appear
  > as a supplement to an inline WHY — never as a substitute for it — because
  > those identifiers are immutable and resolve within the repo, so they do
  > not rot.
- This is a pure-doc change to CLAUDE.md. The implementer/user may elect to
  land it as a plain `process:` commit (which bypasses the ticket gates per
  the CLAUDE.md commit-prefix table) rather than the full ticket flow; either
  way the decision is recorded under this ticket ID.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-311-*.md
```
