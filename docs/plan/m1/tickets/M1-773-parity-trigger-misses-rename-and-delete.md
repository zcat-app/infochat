---
id: M1-773
title: "Parity-gate trigger misses a rename or delete"
status: done
created: 2026-08-05
last_updated: 2026-08-05
blocked_by: []
files_budget: 3
files_scope:
  - CLAUDE.md
  - docs/process/workflow.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE PARITY TESTS THEMSELVES. `DocumentedConfigKeyParityTest`,
    `CommandCatalogueParityTest`, `ChatToolAllowlistSpecParityTest` and
    `LlmOutputSanitizerTest` are working as designed. This ticket changes
    the DETECTION one-liner in the commit rule, never a test.
  - >-
    THE CONTENT-KEYED DESIGN ITSELF. M1-770 settled that the trigger keys
    off what the diff CONTAINS, not the directory it sits in, and that
    prose edits pay nothing. This ticket closes one hole in the
    detection, it does not reopen the path-vs-content decision.
  - >-
    THE GATE COMMAND. `./mvnw -B -pl infochat-provider -am test` and the
    reasons no `-Dtest`-filtered form is offered are settled by M1-770.
acceptance:
  - >-
    A RENAME OR DELETE OF EITHER SINGLE-FILE FIXTURE IS DETECTED. The
    one-liner in `CLAUDE.md` §"Commit prefixes" and
    `docs/process/workflow.md` §Non-ticket commits rule 10 flags a staged
    commit that renames or deletes `docs/spec/commands.md` or
    `docs/spec/security.md`. The current form detects those two files
    only via a `+++ b/<path>` hunk header, which git does not emit for a
    pure rename (it emits `rename from`/`rename to`) or for a delete (it
    emits `+++ /dev/null`), so both slip the trigger while genuinely
    breaking the three tests that read those files by name.
  - >-
    THE EXISTING FIVE REFERENCE CASES STILL BEHAVE. Whatever form
    replaces the current one is re-validated against the cases M1-770
    pinned: `835ab637` flags on three added lines; a real
    `docs/spec/security.md` content touch flags; a config key added at
    column 0 flags; a file header whose own filename contains the token
    does NOT flag; the prose-only amendment `75ef6d29` finds nothing.
    A fix that closes the rename hole by widening the trigger into
    "any touch of docs/spec/**" has failed this item, not passed it.
  - >-
    THE WHY IS RECORDED. Both documents state why the hunk-header form
    was insufficient, so the next reader does not "simplify" the
    detection back to it.
  - >-
    `mvn verify` is green from the repo root. (`CLAUDE.md` is scanned by
    `DocumentedConfigKeyParityTest`, so this diff is NOT inert despite
    touching no Java — which is the very fact M1-770 exists to record.)
test_plan:
  adds: []
  preserves:
    - >-
      M1-770's cost profile: an ordinary prose edit under `docs/spec/**`,
      `docs/design/**` or a root guide must still cost nothing. The
      no-output-means-commit-freely property is the reason the rule is
      followable at all.
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 21
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-05
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-773: Parity-gate trigger misses a rename or delete

## Context

Filed 2026-08-05 from M1-770's round-2 review, which recorded this as a
non-blocking observation rather than a rework item — the gap is outside
the trigger definition M1-770's acceptance asked for, so the reviewer
correctly declined to charge it. The user chose "file a follow-up" over
folding it in or leaving it unrecorded.

M1-770 published this one-liner as the deciding check for whether a
non-ticket doc commit must run the parity gates:

```
git diff --cached -U0 -- docs/spec docs/design ':(top,glob)*.md' \
  | grep -E '^\+\+\+ b/docs/spec/(commands|security)\.md|^\+([^+].*)?infochat\.'
```

The first alternation is how the two single-file fixtures —
`docs/spec/commands.md` (read by `CommandCatalogueParityTest` and by
`LlmOutputSanitizerTest.matchSetEqualsSpecClosedList()`) and
`docs/spec/security.md` (read by `ChatToolAllowlistSpecParityTest`) — are
detected. It matches the `+++ b/<path>` hunk header.

## The gap

Git does not emit a `+++ b/<path>` line for every change to a file:

- **Pure rename**, no content change: the diff is `similarity index 100%`
  plus `rename from` / `rename to`, with no `---`/`+++` pair at all.
- **Delete**: the pair is `--- a/<path>` and `+++ /dev/null`, so the
  `+++ b/docs/spec/…` form never appears.

Either would leave the one-liner silent, the author would commit under
the zero-verify bypass, and `main` would go red — the three tests locate
those files by literal path and would fail to find them. That is the same
end state as `835ab637`, reached through a different door.

## Census

The class is **doc-reading tests that locate their fixture by literal
filename**, since only those break on a rename or delete; a test that
walks a tree does not. Enumerate with M1-770's handle (which alternates
over every spelling of the repo-root-relative idiom, after
`Path\.of\("\.\.` alone missed one), then classify each hit:

```
grep -rlE 'Path\.of\("\.\.|Paths\.get\("\.\.|new File\("\.\.|"\.\./' \
  --include=*.java infochat-*/src/test/java
```

| Test | Locates its fixture by | Breaks on rename/delete | Disposition |
|---|---|---|---|
| `CommandCatalogueParityTest` | literal `docs/spec/commands.md` | YES | IN CLASS — trigger must detect |
| `LlmOutputSanitizerTest` (`locateSpec()`) | literal `docs/spec/commands.md` | YES | IN CLASS — same file, same exposure |
| `ChatToolAllowlistSpecParityTest` | literal `docs/spec/security.md` | YES | IN CLASS — trigger must detect |
| `DocumentedConfigKeyParityTest` | walks `docs/spec`, `docs/design`, root `*.md` | NO | OUT — a deleted doc removes documented keys rather than breaking a lookup; floors are 50/50, far below the repo's counts |
| `LiveSimpleXScenarioSuiteIT`, `LiveSimpleXRoundTripIT` | `../prod/runtime/simplex-clients` | n/a | OUT — matches the handle but reads no `docs/` fixture |

So the trigger's rename/delete leg needs to cover exactly two paths —
`docs/spec/commands.md` and `docs/spec/security.md` — which are already
the two the existing `+++ b/…` alternation names. This ticket adds a
detection mode for them, not a new path set.

## Why it was not folded into M1-770

Renaming or deleting a spec file is a deliberate, conspicuous act, not
something done by accident mid-prose-edit; and M1-770's acceptance fixed
the trigger at three legs, so widening it there would have required a
third `escalate → refine` on an already-approved diff. Recording it is
cheap; the risk of forgetting it is the real cost.

## Notes

- The obvious fix is a second, path-only probe alongside the content
  probe — e.g. `git diff --cached --name-only --diff-filter=RD` limited
  to the two files — rather than loosening the existing regex. Not
  pre-judged here: whoever implements it should check that the added
  probe cannot re-broaden the trigger into "any touch of `docs/spec/**`",
  which acceptance item 2 explicitly fails.
- `DocumentedConfigKeyParityTest` has no equivalent exposure: it walks
  whole trees rather than naming files, so deleting a doc removes
  documented keys rather than breaking a lookup, and its vacuity floors
  (50 real / 50 documented) sit far below the repo's counts.
