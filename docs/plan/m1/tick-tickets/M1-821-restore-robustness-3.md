---
id: M1-821
title: restore.sh failure paths print verify steps + exact commands
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  RestoreWiringTest#partialStateNoteNamesHowToVerifyAndFinishCommands
  (written at start, run RED 2026-08-15 via `./mvnw -pl infochat-provider
  -am verify`: 23 tests ran, the new case failed on the missing verify
  block — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/restore-robustness.md). Probe against the
  current tree: sed -n '411,425p' prod/scripts/restore.sh — the PARTIAL
  RESTORE banner lists placed items and the teardown recipe only; no
  verification step. Observed live (2026-08-11, .scratch/setup-hurdles.md
  items 2+3): after a partial restore the operator hand-started compose
  WITHOUT --env-file prod/runtime/secrets.env, the
  ${INFOCHAT_*_PASSWORD:-} pass-throughs (docker-compose.yml:55-57,
  112-113, 179) resolved to blank, and the Collector died with
  "SCRAM-based authentication, but no password was provided" — an
  auth-shaped error for an env-file-shaped mistake, found only by log
  archaeology.
analysis_ref: docs/plan/m1/tick-analysis/restore-robustness.md
blocked_by:
  - M1-819
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    An APP-SIDE startup guard distinguishing "password empty" from
    "password wrong" (Collector code — the brief scopes restore.sh and the
    scripts it drives). Recorded in the analysis as option C / follow-up
    interface; do not touch app code here.
  - >-
    The M1-819 Flyway-history gate and the M1-822 inherited-state probe —
    sibling tickets; this ticket's Collector-failure log diagnosis covers
    the failure shapes pre-validation cannot see, not a reimplementation of
    the gate.
  - >-
    restore.sh's own compose invocations — they already all carry
    --env-file (verified: restore.sh:116, 518, 551, 600); there is no
    in-script env-file bug to fix.
  - >-
    8-verify.sh, pack.sh, 4-llm.sh, apps.sh, and the restart-policy /
    lifecycle surface (batch D) and wizard scripts (batch B).
acceptance:
  - "RestoreWiringTest.partialStateNoteNamesHowToVerifyAndFinishCommands (the reproduction, written and run RED at start) passes — a driven post-mutation failure prints, after the placed-items list and the return-to-fresh recipe, a short 'how to verify' block naming 8-verify.sh and the two Collector log signatures (FlywayValidateException = dump-vs-checkout migration drift; 'no password was provided' on a MANUAL bring-up = compose started without --env-file prod/runtime/secrets.env, because the ${...:-} pass-throughs blank out); the SCRAM line names the manual-bring-up context and never implies restore.sh observed it (P5)."
  - "RestoreWiringTest.customGgufFailurePrintsExactEnvFileBearingComposeCommands passes — the ensure_gguf no-persisted-URL fail-loud message (restore.sh:278-291) replaces its prose step list with the exact commands, each carrying -f, --env-file, and the profiles compose() uses (restore.sh:116), and the manual GGUF-fetch docker command it already prints stays intact (the M1-571 control)."
  - "Failure-mode case: RestoreWiringTest.collectorWaitFailurePrintsLogExcerptAndSignatures passes — the fake docker made to fail 'compose up -d --wait infochat-collector' (with a canned FlywayValidateException in the modeled log output) yields a bounded Collector log excerpt plus the named-signature guidance, then the partial-state note exactly once (P6, P10)."
  - "No secrets in printed output (P5): RestoreWiringTest asserts the driven run's output contains no expanded INFOCHAT_*_PASSWORD value from the fake secrets.env — the new text references the secrets.env PATH only."
  - "docs/design/07-deployment.md §7.10.1's partial-restore paragraph gains the verify-steps sentence (how to check what landed before teardown or retry). Verify: grep -n 'partial' docs/design/07-deployment.md shows it."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing RestoreWiringTest case unmodified."
test_plan:
  adds:
    - RestoreWiringTest.partialStateNoteNamesHowToVerifyAndFinishCommands
    - RestoreWiringTest.customGgufFailurePrintsExactEnvFileBearingComposeCommands
    - RestoreWiringTest.collectorWaitFailurePrintsLogExcerptAndSignatures
  preserves:
    - all tests currently green on main
    - >-
      the M1-581 partial-state cases and the M1-582 consent-gate cases in
      RestoreWiringTest — this ticket EXTENDS the partial note and the
      ensure_gguf failure message; the placed-items list, the
      nothing-was-deleted line, and the return-to-fresh recipe keep their
      wording and their first position (P6).
spec_refs:
  - docs/design/07-deployment.md §7.10.1
decision_refs: []
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-15
    verdict: REWORK
    checks: SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS
    diff_stats: "5 files changed, 293 insertions(+), 19 deletions(-)"
    rework_items: 2
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS
    diff_stats: "fix hunks: 4 files changed, 33 insertions(+), 4 deletions(-); cumulative vs merge-base: 5 files changed, 322 insertions(+), 19 deletions(-)"
    rework_dispositions: "item 1 SATISFIED, item 2 SATISFIED (probes re-run; full-reactor verify green, RestoreWiringTest 26/26 surefire, provider failsafe 344/0/0/8)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {
  line-drift-after-M1-819: >
    All file:line citations verified true against the current tree; several
    line numbers drifted since authoring (M1-819 landed the Flyway gate,
    shifting later regions): the Collector --wait call is at restore.sh:820
    (ticket says :714-715), docker-compose.yml pass-throughs at :59-61/117-118/185
    (ticket says :55-57/112-113/179), ensure_gguf message at :284-297 (ticket says
    :278-291). No claim is false — positions only. The implementation used the
    current numbers.
  }
escalation_reason:
---

# M1-821: restore.sh failure paths print verify steps + exact commands

## Context

Live session 2026-08-11 (`.scratch/setup-hurdles.md` items 2 and 3): a
restore run failed post-mutation; the PARTIAL RESTORE banner enumerated
what was placed but said nothing about how to verify it, and the operator's
hand-rolled bring-up omitted `--env-file prod/runtime/secrets.env` — the
compose file's `${INFOCHAT_*_PASSWORD:-}` pass-throughs blanked out and the
Collector died with "SCRAM-based authentication, but no password was
provided", an error pointing at DB/auth rather than the missing env-file.
Both failures in that session required manual log archaeology. Analysis:
`docs/plan/m1/tick-analysis/restore-robustness.md`. Do not restate the
contract — cite `spec_refs:`.

## Root cause

Proven by read: print_partial_state_note (restore.sh:411-425) prints the
PLACED list + "nothing was deleted" + print_fresh_host_recipe (teardown
only, :177-193) — designed in M1-581 to answer "what landed / how do I
retry", never extended to "how do I check what landed". The one prose
manual-finish recipe (ensure_gguf's no-persisted-URL message, :278-291)
names the bring-up steps without literal commands; only
print_provider_withheld_note (:199-210) prints an exact `--env-file`
command. **Verified non-cause:** restore.sh's own compose calls all carry
--env-file (:116, 518, 551, 600) — the observed blank-password failure
came from the operator's manual command, so the fix surface is the printed
guidance, not the script's invocations.

## Pitfalls

Numbered per the analysis document; this ticket carries P5, P6, P9, P10.

- P5: Printed guidance must not over-claim or leak. The SCRAM/no-password
  signature cannot occur on restore.sh's own compose calls (all carry
  --env-file, verified) — the verify block names it strictly as a
  manual-bring-up diagnosis (§11: messages state current truth). Printed
  text references the secrets.env path, never expanded secret values
  (security.md §Secrets handling).
- P6: The partial note's placed-items list and return-to-fresh recipe stay
  FIRST; the verify block is short and last. Burying the destructive-action
  recipe under prose is the evidence's item-9 wall-of-text trap.
- P9: Wiring-test sandbox — new coreutils the edited paths execute join
  REAL_TOOLS (:66-69); the Collector-failure case needs the fake docker to
  model a failing `--wait` and to replay canned log content, with a
  distinguishing argv marker (the fake matches substrings of "$*",
  :121-136).
- P10: The log-excerpt capture runs under set -e with the ERR trap armed —
  it must be bounded (a tail, not a full dump) and must tolerate `compose
  logs` itself failing (|| true + skip line), never masking the original
  --wait failure or double-printing the partial note (the :410-412
  single-print flag already exists — reuse it).

## Approach

Derived from `spec_refs:` — §7.10.1's partial-restore recovery contract
("restore.sh prints this exact recipe on any post-mutation failure",
07-deployment.md:1279-1280) is extended, not bent: the recipe stays; the
verify block and exact finish commands join it.

- **Files to touch** (plan, not allowlist): `prod/scripts/restore.sh`
  (print_partial_state_note gains the verify block; the ensure_gguf failure
  message gains exact commands; the Collector --wait call site gains a
  failure leg); `RestoreWiringTest.java`; `docs/design/07-deployment.md`
  §7.10.1 (one sentence).
- **Steps in order** (each green before the next):
  1. The verify block in print_partial_state_note — after the recipe,
     bounded: run 8-verify.sh; the two named Collector log signatures with
     their one-line meanings (P5's framing for the SCRAM line).
  2. The ensure_gguf message: replace the prose step list with the exact
     compose commands (-f / --env-file / --profile per compose() at :116
     plus the llamacpp profiles the rehydration used), keeping the printed
     manual GGUF-fetch docker command intact.
  3. The Collector --wait failure leg: on non-zero, capture a bounded
     `compose logs --tail` excerpt (|| true, P10), print it with the
     named-signature guidance, then let the standard failure path print the
     partial note once.
  4. RestoreWiringTest cases + harness additions (P9).
  5. The §7.10.1 sentence — last, it records the landed shape.
- **Controls to preserve (§10):** the placed-items list, the
  nothing-was-deleted line, the return-to-fresh recipe (wording and first
  position — test_plan.preserves pins them); the single-print flag;
  print_provider_withheld_note's existing exact command (untouched);
  ensure_gguf's recovery BEHAVIOR (M1-571: pinned-branch auto-recovery,
  persisted-URL re-fetch, the manual-fetch docker command) — only the
  failure MESSAGE text changes; the trap discipline (:426-428, :548-557,
  :724-726).
- **Pitfall→mitigation:** P5→steps 1-2 wording + the acceptance grep
  assertion; P6→step 1's placement + the preserves entry; P9→step 4;
  P10→step 3's || true + bounded tail.

## Definition of done

The reproduction test passes (partial note carries the verify block after
the recipe, SCRAM line framed as manual-bring-up); the ensure_gguf message
prints exact env-file-bearing compose commands with the manual-fetch
command intact; the Collector-wait failure case prints the bounded log
excerpt + signatures + one partial note; no expanded secret values in any
printed block; §7.10.1 records the verify steps; `mvn verify` green with
all pre-existing RestoreWiringTest cases unmodified.

## Verification

- P5 → RestoreWiringTest.partialStateNoteNamesHowToVerifyAndFinishCommands
  asserts the SCRAM line's manual-bring-up framing, plus the no-expanded-
  secrets assertion (feeds the run a fake secrets.env with sentinel values
  and asserts they never appear in output).
- P6 → the same test asserts ordering: placed items and recipe precede the
  verify block (a mutation moving the block first fails it).
- P9 → the harness additions fail loudly under the restricted PATH when a
  tool/marker is missing.
- P10 → RestoreWiringTest.collectorWaitFailurePrintsLogExcerptAndSignatures
  — the failure-mode pair: the fake docker fails --wait with canned
  FlywayValidateException log content, and a second variant where `compose
  logs` itself fails; asserts excerpt+signatures in the first, skip-line +
  a single partial note in the second (a full log dump or a double-printed
  note fails it).
- acceptance item 2 → RestoreWiringTest.customGgufFailurePrintsExactEnvFileBearingComposeCommands
  — asserts each printed bring-up command contains `--env-file` and the
  manual-fetch docker command is still present.
- acceptance item 5 → grep probe on docs/design/07-deployment.md.
- acceptance item 6 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. No app-side empty-vs-wrong-password guard
(analysis option C — Collector code, outside the brief's scope; the
misdirecting SCRAM message survives for non-restore manual bring-ups and is
recorded as a follow-up interface). The M1-819 gate and M1-822 probe are
siblings — do not reimplement either here; this ticket's log diagnosis is
the residual-failure net. restore.sh's own compose calls need no fix
(verified). 8-verify.sh, pack.sh, 4-llm.sh, apps.sh, batch D (lifecycle)
and batch B (wizards) surfaces are untouched. This ticket modifies NO
pre-existing test.

## Census

Class guarded: every operator-directed command/recipe restore.sh prints on
a failure or withheld path. Enumeration —
`grep -n 'echo\|cat <<\|cat >&' prod/scripts/restore.sh` over the message
helpers; disposed rows:

- print_fresh_host_recipe (:177-193) — teardown-only by design (M1-581:
  deleting identity material is the operator's deliberate act); no bring-up
  command belongs there. Disposed: unchanged.
- print_provider_withheld_note (:199-210) — already prints the exact
  --env-file command (:205). Disposed: unchanged.
- ensure_gguf no-persisted-URL message (:278-291) — prose steps, no exact
  commands. Disposed: FIXED (step 2).
- print_partial_state_note (:411-425) — no verify guidance. Disposed:
  FIXED (step 1).
- Collector --wait call site (:714-715) — no diagnosis on failure.
  Disposed: FIXED (step 3).
- Final DONE banner (:779-806) — already lists the manual bot-chat
  verification (:783-787); the automated health result is printed by
  8-verify.sh itself. Disposed: unchanged.
- M1-819's new gate message (sibling, blocked_by) — carries its own
  recovery options. Disposed: sibling owns it; this ticket must not edit it.

## Round 1 rework

Verdict file: `.scratch/tick-review-M1-821-r1.txt` (REWORK, 2 items, 0
critical/high). REWORK ITEMS verbatim:

1. (Finding 1) Delete the stray leading backtick at docs/design/07-deployment.md:1286
   so the cross-line `prod/setup.sh --reset --hard` code span pairs as before,
   evaluated via `grep -n '^`--reset' docs/design/07-deployment.md` → no matches.
2. (Finding 2) Reword the parenthetical at prod/scripts/restore.sh:437 to drop the
   transcript-dependent "see the Flyway-history check above" reference, evaluated
   via `grep -n 'check above' prod/scripts/restore.sh` → no matches plus
   `./mvnw -pl infochat-provider -am verify` green (RestoreWiringTest 26/26).

## Review observations

Round-1 reviewer recommended-new-ticket entry (TOUCHED-BY-THIS-DIFF: yes, no
DECIDE-BEFORE — recorded, not relayed; filing is the user's call): the
ensure_gguf manual-finish recipe covers only llama.cpp backends — for a
restored config pairing the custom GGUF with an OLLAMA embeddings backend,
the printed exact commands start both llama.cpp services and never start
the ollama daemon or pull its models (dead embeddings backend, degraded
verify). The omission predates this diff; this diff rewrote the message.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-821`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-821-restore-robustness-3.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
