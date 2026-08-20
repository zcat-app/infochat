---
id: M1-893
title: Restore failed-asset wording + cold-start timeout note
status: done
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  RestoreWiringTest#inheritedFailedAssetPairsSurfaceAsRestoreWarning
  (flipped per acceptance item 2 at start; RED run recorded in
  .scratch/tick-test-M1-893-r1-RED.log before any script change): the
  flipped assertions — the WARN names `/asset-enable zcash coingecko` as
  the recovery — red against the current script text, which prints the
  §10.8b manual UPDATE (prod/scripts/restore.sh:777-779, banner note
  :1000-1001) and never names /asset-enable (grep-verified; the stale
  P8 assertFalse at RestoreWiringTest.java:954-955 pins the pre-M1-836
  world). Live-verified in the 2026-08-19 restore drills and recorded
  as D-9 (.scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md): M1-822's
  interface promise ("when batch F lands the command, it updates this
  warning's wording", M1-822 ticket :39-41) is unhonored since
  /asset-enable shipped (M1-836).
analysis_ref: docs/plan/m1/tick-analysis/small-followup-batch.md
blocked_by: []
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
  - ADMIN_GUIDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any control-flow, exit-path, probe, or gate change in restore.sh
    (P6 — the change is print text inside the M1-822 WARN block and the
    banner note only). WARN-and-continue semantics and the probe's
    skip-note degradation are load-bearing siblings.
  - >-
    Any auto-reset of inherited failed rows (M1-822's boundary: the
    restore reports; the operator decides — clearing ladder state would
    falsify the clone's continuity with the source DB).
  - >-
    Removing the §10.8b UPDATE from the WARN text (P6 — §10.8b keeps it
    as the host-level fallback for a down/unreachable Provider,
    docs/design/10-asset-commands.md:425-432).
  - >-
    The V14 migration comment and its repair runbook — sibling M1-894
    (migration_touch serializes them anyway).
  - >-
    Any code, default, or properties change for the cold-start note
    (P10 — the campaign recorded timeout VARIANCE with the breaker
    proven working; raising infochat.llm.chat.timeout-ms's 120 s
    default or touching breaker config is unevidenced scope creep).
  - >-
    upgrade.sh, 8-verify.sh, pack.sh, and every app-side source file.
acceptance:
  - "RestoreWiringTest.inheritedFailedAssetPairsSurfaceAsRestoreWarning (the reproduction, flipped per item 2 and run RED at start) passes — a fake psql probe returning one failed zcash/coingecko row yields a WARN block naming the pair, `/asset-enable zcash coingecko` as the recovery command, the §10.8b UPDATE retained as the host-level fallback, the /source-enable pointer, and the bare-/zcash dead-surface line when the failed pair is the default — and the restore CONTINUES (the fake-docker argv log reaches model rehydration; exit semantics unchanged, P6)."
  - "§8-AUTHORIZED TEST MODIFICATION (engineering-rules §8 — this item IS the authorization): exactly one pre-existing test method changes — inheritedFailedAssetPairsSurfaceAsRestoreWarning. Its new expected behavior: the WARN names /asset-enable as the primary recovery (the command exists since M1-836, commands.md §Asset commands) AND keeps the §10.8b UPDATE text as the fallback for a down Provider; the stale P8 assertion `assertFalse(restore.sh contains \"/asset-enable\")` (RestoreWiringTest.java:954-955 — true when written, false now) is replaced by the assertion that the script DOES name it. Every other RestoreWiringTest method is unmodified. Verify: `git diff` on the test file touches only that method."
  - "FAILURE-MODE (P6): RestoreWiringTest.inheritedStateProbeFailureDegradesToSkipNote and RestoreWiringTest.cleanInheritedStatePrintsNoAssetWarning pass UNMODIFIED — a failed probe still degrades to the one-line skip note with no partial-state note, and an all-active probe still prints no WARN; plus `git diff -U0 prod/scripts/restore.sh` shows changes only to echo lines inside the WARN block (:765-784) and the banner note (:1000-1001) — a wording change that leaked into control flow fails this."
  - "The final-banner note names the same recovery shape as the WARN (P6 consistency — the operator reads the banner at cutover, possibly long after the WARN scrolled): RestoreWiringTest.finalBannerRepeatsInheritedFailureCount passes (its count assertion is unchanged) and `grep -n 'asset-enable' prod/scripts/restore.sh` hits both the WARN block and the banner note."
  - "§7.10.1 sync: docs/design/07-deployment.md's inherited-state paragraph (:1183-1190) now describes the WARN naming /asset-enable with the §10.8b UPDATE as host-level fallback — Verify: `grep -n 'asset-enable' docs/design/07-deployment.md` hits that paragraph."
  - "ADMIN_GUIDE cold-start note (item 4 of the analysis, P10): ADMIN_GUIDE.md's Advanced (technical reference) section documents the cold-local-model first-chat shape — after a (re)start the model's warm-up can push the FIRST chat past the configured chat timeout, so a one-off unavailable reply on a cold first chat is the timeout working, not an outage (the M1-834 breaker admin-notify is the real outage signal) — and names the tuning knob `infochat.llm.chat.timeout-ms` (default 120 s, application.properties:443; the setup wizard's step-4 timing question writes it, 4-llm.sh:873-877), with the operator's options: raise the value for a slow local model, or warm the model before inviting traffic — Verify: `grep -n 'llm.chat.timeout-ms' ADMIN_GUIDE.md` hits the new note; DocumentedConfigKeyParityTest stays green (the key is real); `git diff --stat` shows no code or properties change."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing RestoreWiringTest case except the one authorized method."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
    - >-
      every RestoreWiringTest case except the one method item 2
      authorizes — the M1-819 gate cases, the M1-821 failure-path cases,
      and the M1-822 probe cases (clean-state, skip-note, banner) run
      unmodified and pin the unchanged controls.
  modifies:
    - >-
      RestoreWiringTest.inheritedFailedAssetPairsSurfaceAsRestoreWarning
      — authorized by acceptance item 2: the WARN now names
      /asset-enable as the primary recovery (it exists since M1-836)
      with the §10.8b UPDATE retained as fallback; the stale P8
      never-name assertion flips to must-name. No assertion is weakened
      — the flipped test is strictly more demanding (it requires both
      recovery pointers, not one).
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/design/10-asset-commands.md §10.8b
  - docs/design/07-deployment.md §7.10.1
decision_refs:
  - D42
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
    date: 2026-08-20
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "6 files changed, 47 insertions(+), 23 deletions(-)"
    verdict_file: .scratch/tick-review-M1-893-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  note: >-
    All citations spot-checked at start (restore.sh:765-784/:1000-1001,
    RestoreWiringTest.java:923-964/:954-955, 07-deployment.md:1183-1190,
    10-asset-commands.md:414-432, application.properties:443,
    4-llm.sh:873-877, M1-822 :39-41). No blocking ambiguity.
escalation_reason:
---

# M1-893: Restore failed-asset wording + cold-start timeout note

## Context

Two operator-facing text corrections from the 2026-08 campaign, folded per
the analysis's Decomposition (both wording-only, same verification style,
same drill provenance). (1) D-9: restore.sh's inherited-failed-asset WARN
still tells the operator to run the §10.8b manual SQL and never names
`/asset-enable` — M1-822 pointed at the SQL deliberately (the command did
not exist) and exported the interface promise that batch F would update
the wording; M1-836 shipped the command 2026-08-15 and the promise is
unhonored, live-verified in the 2026-08-19 restore drills. (2) The
cold-local-model first-chat timeout: the 2026-08-19 breaker re-test showed
the first chat after a (re)start can exceed the default 120 s
`infochat.llm.chat.timeout-ms` while the model warms — variance, not a
defect, with the breaker proven working — but no operator-facing doc names
the knob. Shared analysis: `analysis_ref:`.

## Root cause

Proven, with citations. (1) restore.sh:777-779 prints the per-pair
"recovery UPDATE (docs/design/10-asset-commands.md §10.8b):" block and the
banner note :1000-1001 repeats "(§10.8b UPDATE; /source-enable)";
`/asset-enable` appears nowhere in the script (grep-verified). The wording
is pinned by RestoreWiringTest.java:945-955, including the now-stale P8
assertion that the script must NEVER name /asset-enable (:954-955) —
correct when M1-822 wrote it, false since M1-836. The design surface is
already correct and supplies the target shape: §10.8b
(docs/design/10-asset-commands.md:414-432) names `/asset-enable` first and
keeps the SQL as "the host-level fallback for a Provider that is down or
unreachable". §7.10.1 (07-deployment.md:1183-1190) is a second stale text
site describing the old WARN. (2) The knob is real
(infochat-provider/src/main/resources/application.properties:443) and
wizard-surfaced (prod/scripts/4-llm.sh:873-877), but ADMIN_GUIDE.md has
zero timeout/circuit/breaker mentions (case-insensitive grep, no hits).

## Pitfalls

Numbered per the analysis document; this ticket carries P5, P6, P10.

- P5: the stale guard is a test assertion, and flipping it is a §8
  test-modification — unauthorized flips red TEST-INTEGRITY-CHECK, and
  "fixing" the test by weakening it (e.g. dropping the recovery-pointer
  assertions) is the §8 semantic trap. Acceptance item 2 is the explicit
  authorization and states the new expected behavior; the flipped test is
  strictly more demanding than the old one.
- P6: wording-only — the M1-822 controls are load-bearing and do not
  move: WARN-and-continue (a failed pair is legitimate source-host
  history; a non-zero exit would block a healthy clone), the probe's
  `|| true` skip-note degradation, the bare-command dead-surface line, the
  /source-enable pointer, and the §10.8b SQL as fallback (a restore
  operator can be exactly in the down-Provider state where /asset-enable
  is unrunnable — §10.8b:425-432). The diff is echo lines.
- P10: document the knob, change nothing else — the campaign evidence is
  variance with the breaker proven working (M1-834/M1-835 done); a default
  or breaker-config change would be unevidenced scope creep (§1, §3).
  The note must name the REAL key (DocumentedConfigKeyParityTest reds on a
  mis-named one) and live where operators read (ADMIN_GUIDE's Advanced
  section), pointing at the wizard question that already writes it.

## Approach

Derived from `spec_refs:` — commands.md §Asset commands defines
`/asset-enable`'s reset semantics (the recovery the WARN should name);
design §10.8b supplies the primary-plus-fallback shape to mirror; §7.10.1
is the design record that must agree with the landed wording. The
ADMIN_GUIDE note documents existing behavior against the real config key —
no spec surface moves.

- **Files to touch** (plan, not allowlist): `files_scope` — restore.sh
  (print text only), RestoreWiringTest.java (one method), §7.10.1 (one
  sentence), ADMIN_GUIDE.md (one Advanced-section note).
- **Steps in order** (each green before the next):
  1. The RED flip (workflow §0, P5): rewrite the reproduction method's
     assertions per acceptance item 2; run RED against the current script.
  2. The WARN text (P6): per failed pair, print
     `recovery: /asset-enable <asset> <sub-verb>` as the primary pointer
     and keep the §10.8b UPDATE under a "host-level fallback (Provider
     down)" lead-in; the /source-enable line, the dead-surface line, the
     WARN-and-continue flow, and the probe guard are untouched.
  3. The banner note (P6): same primary/fallback shape in one line.
  4. §7.10.1's one-sentence sync.
  5. The ADMIN_GUIDE note (P10): Advanced (technical reference) gains the
     cold-first-chat shape, the named knob with its default and wizard
     provenance, and the two operator options (raise it; warm the model),
     plus the one-liner distinguishing timeout variance from an outage
     (the breaker admin-notify).
- **Controls to preserve (§10):** the M1-819 Flyway gate's position (the
  probe sits after it); the WARN's continue semantics and the probe's
  skip-note guard (P6); every sibling RestoreWiringTest case unmodified
  (they pin the gate, the failure paths, and the probe degradations this
  ticket must not disturb); no secret material in the WARN (asset/sub_verb
  names and counts only, as today).
- **Pitfall→mitigation:** P5→step 1 + acceptance item 2; P6→steps 2-3 +
  acceptance items 1/3/4; P10→step 5 + acceptance item 6.

## Definition of done

The flipped reproduction test passes (WARN names /asset-enable, keeps the
SQL fallback and /source-enable, restore continues); exactly one
pre-existing test method changed, per the item-2 authorization; the
skip-note and clean-state cases pass unmodified; the banner note agrees
with the WARN; §7.10.1 is synced; ADMIN_GUIDE names
`infochat.llm.chat.timeout-ms` with the cold-start guidance and
DocumentedConfigKeyParityTest stays green; `mvn verify` green with no code
or properties change in the diff.

## Verification

- P5 → acceptance item 2 (the authorization) + `git diff` on
  RestoreWiringTest.java touching only the one method; the flipped test
  run RED at start against the unmodified script is the reproduction log.
- P6 → acceptance item 1 (the reproduction: fake psql returns the failed
  zcash/coingecko row; asserts both recovery pointers, the dead-surface
  line, and via the argv log that model rehydration ran and exit semantics
  are unchanged), item 3 (FAILURE-MODE: probe-failure skip-note and
  clean-state cases unmodified; echo-only script diff), and item 4 (banner
  consistency + grep probe).
- P10 → acceptance item 6: `grep -n 'llm.chat.timeout-ms' ADMIN_GUIDE.md`
  hits the note; DocumentedConfigKeyParityTest green; the diff-stat shows
  docs only.
- acceptance item 5 → `grep -n 'asset-enable' docs/design/07-deployment.md`
  inside the §7.10.1 paragraph.
- acceptance item 7 → `mvn verify` from repo root (engineering-rules §5).
- Non-vacuity: a WARN that drops the SQL fallback fails item 1's fallback
  assertion; a wording change that also alters control flow fails item 3's
  unmodified cases or its echo-only diff probe; a mis-named config key in
  the note reds DocumentedConfigKeyParityTest.

## Out-of-scope

Prose mirror of the YAML list. No control-flow, exit-path, probe, or gate
change in restore.sh — the ticket is print text inside the M1-822 WARN
block and banner note, and the siblings that pin the controls run
unmodified. No auto-reset of inherited rows (the restore reports; the
operator decides). The §10.8b UPDATE stays in the text as the documented
host-level fallback — dropping it would strand the down-Provider operator
(P6). The V14 comment and its repair runbook are sibling M1-894's.
Item 4's note is documentation only: no default change, no breaker-config
change, no wizard change. upgrade.sh, 8-verify.sh, pack.sh, and all
app-side sources are untouched.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-893`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-893-restore-asset-enable-wording.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
