---
id: M1-846
title: "Spec: live-text streaming display policy"
status: done
created: 2026-08-14
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe: security.md:355-357 promises "Before any LLM-generated text is
  delivered to a user, the candidate output is passed through a
  deterministic outbound regex pass", messaging.md §Progress notifications
  (:240-273) defines a stage-labels-plus-terminal publisher only, and
  messaging.md §Capability flags (:154-224) has no live-text member while
  CapabilityFlags.java:25-27 states adding one is a spec amendment — a
  streamed live reveal of the chat reply (user direction 2026-08-14,
  future-features.md §I1:839-996) contradicts the letter of the first and
  is undescribed by the second and third, so the display-policy amendment
  lands before any streaming code. Observed in-tree: the sanitizer runs
  once per reply at ChatAgent.java:560 and the notifier SPI exposes
  publish/complete/fail only (ProgressNotifier.java:51-90).
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: [M1-845]
files_scope:
  - docs/spec/security.md
  - docs/spec/messaging.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY code change — the SPI, the notifier, the capability flag
    implementations. This ticket is a spec amendment only (the M1-663
    shape); the implementation it authorizes is M1-849, and the user
    approves the exact wording before it lands (engineering-rules §12).
  - >-
    THE TRANSLATION-SWITCH amendment — M1-845 owns it. This ticket
    references the mode pairing (a scope is live-eligible only when the
    reply's generated language is the delivered language) but does not
    define the modes.
  - >-
    WEAKENING any existing sanitizer commitment: the closed-list match-set
    derivation, the canonical-form matching, the pass ordering, the
    counted-never-throttled durability rule, the delivery-ordering
    contract, and the `](` outbound property all survive intact — the
    amendment EXTENDS the regime to a streamed surface; it relaxes nothing.
  - >-
    SIGNAL and GROUP scopes: the amendment records SimpleX-only and
    DM-first as the feature's scope (Signal edits are real edits, 2-hop
    live-proven at best — M1-566/F-live-11; group fan-out economics,
    future-features.md:874-876). Extending either is a future amendment.
acceptance:
  - "docs/spec/security.md §LLM output sanitizer gains the streamed-surface regime (rule-text only; user approves wording): every transmitted live update is the sanitizer's output over the FULL generated prefix, never an incrementally transformed delta, so pass ordering and deletion-join coverage hold on-stream by construction (P1). Verify: grep -n 'stream' docs/spec/security.md shows the regime inside §LLM output sanitizer."
  - "The refusal-prefix window is specified (P2): the first publish is held until the trimmed prefix either matches the structured-refusal marker (the refusal degrade fires; nothing was displayed) or is provably past it — fail-closed, the ChatAgent.java:567-577 posture carried on-stream. Verify: grep -n 'refusal' docs/spec/security.md shows the window rule."
  - "The audit regime is specified (P4): one aggregated LLM_OUTPUT_SANITIZED row-set per TURN — per distinct token, exact occurrence counts, counted never throttled — covering the final text PLUS any token matched only in a transient update, and an audit-write failure aborts the stream to the failure terminal (the LlmOutputSanitizer.java:250-256 durability posture's streamed equivalent). Verify: grep -n 'per turn\\|per-turn' docs/spec/security.md shows the aggregation rule."
  - "The tool-loop display rule is specified (P3): an iteration that ends in a tool call never leaves its text displayed — the placeholder reverts to the localized stage label and the next iteration streams from empty; tool-protocol text is never transmitted. Verify: grep -n 'tool' docs/spec/security.md or docs/spec/messaging.md shows the rule."
  - "The terminal rule is specified (P5): the finalize carries the full post-pipeline text — sanitize, protocol strip, refusal intercept, the translate-mode display leg where it applies, the deterministic help-block and provenance appends, the emptied-reply degrade — byte-identical to the non-streaming path for the same generated text. Verify: grep -n 'finaliz' docs/spec/messaging.md §Progress notifications region shows the terminal rule."
  - "docs/spec/messaging.md §Progress notifications gains the live-text publisher mode: eligibility (the operator flag is on AND the adapter declares the live-text capability AND the scope is a DM AND the reply's generated language is the delivered language — every en scope, and non-en scopes in native mode per the M1-845 amendment), the cadence (the existing max(adapterMin, system floor) coalescing; chunked ~1s updates are the ceiling, token-smoothness is never promised — P10), and the collapse (any condition failing degrades to today's stage-label behavior exactly). Verify: grep -n 'live' docs/spec/messaging.md shows the mode and its collapse."
  - "docs/spec/messaging.md §Capability flags gains the live-text capability member (SimpleX declares it; Signal does not; an unknown flag defaults to not-supported, the section's own rule). Verify: grep -n 'live' docs/spec/messaging.md §Capability flags region shows the member."
  - "The accepted residuals are stated explicitly, neither clause left implied (P16, the M1-663 discipline): transient sanitized intermediate text may differ from the final delivered text; a redaction can appear in an early update while the final text redacts differently; screenshots capture transient states; streamed display shows text the emptied-reply degrade has not yet judged. Verify: grep -n 'residual\\|transient' docs/spec/security.md shows the stated residuals."
  - "The enable flag is recorded as a D73-style capability gate with a security/quality posture — off is today's exact behavior and the supported vps-profile posture — not an engineering-rules §7 feature flag (P14). Verify: grep -n 'flag' docs/spec/messaging.md §Progress notifications region shows the gating rule."
  - "Rule-text only throughout: git diff docs/spec/ shows no dates, ticket IDs, or report citations in spec prose (§12)."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Spec-only ticket (M1-663 precedent): no JUnit surface; mvn verify
      covers the doc gates and the no-regression leg.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs:
  - D21
  - D30
  - D31
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for: docs/spec/security.md §LLM output sanitizer
spec_amend_parent: M1-849
remediates:
reviews:
  - round: 1
    date: 2026-08-16
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "4 files changed, 94 insertions(+), 19 deletions(-) (spec files additions-only)"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-846-r1.txt
  - round: 2
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "fix hunks: 2 files changed (ledger +1 line, ticket bookkeeping); full diff since merge-base: 5 files, 113 insertions(+), 20 deletions(-)"
    rework_disposition: "round-1 item 1 SATISFIED (all three EVALUATED-AS probes verified: ledger :84, messaging.md:286, green DocumentedConfigKeyParityTest + BUILD SUCCESS in .scratch/tick-test-M1-846-r2.log)"
    verdict_file: .scratch/tick-review-M1-846-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: "2026-08-16 start pass — tick-lint 0 findings/0 BLOCKERs; analysis_ref cross-read: acceptance items carry P1/P2/P3/P4/P5/P10/P14/P16 exactly as the analysis's item 3 assigns them (P5 was already in acceptance item 5 but missing from the Pitfalls enumeration and Verification mapping — both fixed, no scope change). Citations spot-checked: ProgressNotifier.java:51-90 (interface :51, publish/complete/fail :68/:79/:89), CapabilityFlags.java:25-27 spec-amendment rule (:25), LlmOutputSanitizer.java:250-256 audit-failure @throws (:251), security.md:355-357 outbound-pass promise and :753-777 pass-ordering block exact; messaging.md §Capability flags :158 and §Progress notifications :244 (ticket said :154-224/:240-273 — section starts only, drift immaterial); future-features.md §I1:839-996, :874-876, :890-893 exact (path is docs/plan/). Line drift since filing from later-landed work: ChatAgent.java sanitize-once :540 now :560, refusal-intercept posture :548-557 now :567-577 — ticket refs updated to the current sites, claims hold. blocked_by M1-845 landed the mode pairing (llm.md §Translation flow, decision D79) with canonical names translate|native ('pivot'/'direct' were placeholders) — acceptance items 5-6 updated to the canonical names per Approach step 1's 'reference it exactly'; M1-845 added NO tests (spec-only, test_plan.adds: []) so the preserves-trace is vacuous. §Census N/A (spec amendment, not a class ticket; template requires Census only for class tickets). replaces: empty and no worktree holds a superseded streaming implementation. Doc-gate check: the only spec-parsing test reads docs/spec/commands.md (LlmOutputSanitizerTest closed-list parity), which this diff does not touch — no test parses security.md or messaging.md, so the diff is docs-only in the verify sense (memory: doc-only-edits-skip-verify); acceptance item 11 rides the most recent still-valid green log unless a testable file enters the diff. No blocking question: the open drafting choices (operator-key name, exact placement) are §12 wording-approval points by design, not ambiguities. 2026-08-16 §12 wording review: user verdict 'sound, recommend approval' with three advisory notes, all dispositioned — (1) the audit bullet reworded to one row per distinct token aggregated into a single row-set per turn; (2) the residual parenthetical dropped to the emptied-reply degrade alone, because the translation sanity checks never judge a stream-eligible reply (en scopes skip the display leg, native mode carries no language net — llm.md §Translation flow), so acceptance item 8 updated to match the landed text; (3) the cadence sentence reads 'a full message at the transport layer, drawing the same budget as any other outbound message' — transport economics (the analysis's P10), never the maxSendsPerSecond flag's letter. Key name infochat.chat.live-text approved. Advisory for M1-849, recorded here since its pre-flight reads this ticket's record: the ]( chokepoint's surface enumeration (security.md §Sanitizer output never contains '](') names progress placeholder/finalize but not intermediate live updates; this amendment's inheritance claim rests on each update being sanitizer output (internal breakLinkAdjacency), and M1-849 must additionally route its update path through the outbound guarantee. Verify disposition: the landed diff is docs-only (no test parses security.md or messaging.md; the only spec-parsing test reads commands.md, unchanged), so per the doc-only-edits-skip-verify posture no mvn verify ran; acceptance item 11 rides .opencode/worktrees/M1-845/.scratch/tick-test-M1-845-r2.log (BUILD SUCCESS 2026-08-16T14:05), still valid — the testable-file diff between that verified tree (9b487c0e) and main is empty outside docs/plan/*.md. 2026-08-16 round 1 REWORK (tick-review-M1-846-r1.txt, 1 medium SPEC-TRUTHNESS): the pre-flight claim 'no test parses security.md or messaging.md' was FALSIFIED — DocumentedConfigKeyParityTest walks every .md under docs/spec for infochat.* keys (DocumentedConfigKeyParityTest.java:267-307), and the new key infochat.chat.live-text had no built site and no ledger entry, so the build was red; my grep found only tests naming individual spec files and missed the directory walk. Fix applied: the GAP entry in documented-config-key-exemptions.txt (same shape as M1-845's reply-mode line, citing M1-849); spec text untouched per §8 (the requirement keeps, the ledger carries it); the docs-only classification and the M1-845-log ride are both withdrawn, and a fresh full mvn verify is this ticket's log of record (tick-test-M1-846-r2)."
escalation_reason:
---

# M1-846: Spec: live-text streaming display policy

## Context

The 2026-08-14 user direction schedules §I1 streaming behind an enable
flag. Streamed text is pre-sanitizer under today's regime (the sanitizer
runs once per reply, ChatAgent.java:560), and a refusal cannot be unseen
once revealed (future-features.md:890-893, hurdle 2) — the pre-sanitize
display policy is a spec/security decision, and the brief allows this slice
to be the batch's named policy gate. The analysis's ruling (option E/G):
the full-prefix re-sanitize design keeps every transmitted byte
sanitizer-passed, so the amendment EXTENDS the regime rather than weakening
it — but the surfaced regime, the audit aggregation, the tool-loop display
rule, the capability flag, and the accepted residuals are new spec text the
user must approve. Shared analysis: `analysis_ref:`.

## Root cause

Not a defect: security.md §LLM output sanitizer was written against
batch delivery ("the candidate output", :355-357) and messaging.md
§Progress notifications against stage labels. Neither document's letter
admits a sequence of candidate prefixes, and `CapabilityFlags` declares
flag additions spec amendments (CapabilityFlags.java:25-27). The amendment
is therefore the first artifact; M1-849's code contradicts no spec text
once it lands.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4,
P5, P10, P14, P16.

- P1: chunk-boundary bypass — the amendment mandates full-prefix
  re-sanitize per update; a delta-based design would contradict pass
  ordering (security.md:753-777) and must not be drafted.
- P2: the refusal window — hold-back until the prefix question is
  decidable; fail-closed.
- P3: the tool-loop flash — revert-to-stage-label between iterations;
  protocol text never displayed.
- P4: audit aggregation — per-turn rows, mid-stream-only matches retained,
  audit failure kills the stream.
- P5: finalize is not the last chunk — the terminal finalize carries the
  full post-pipeline text, byte-identical to the non-streaming path.
- P10: economics — the cadence text honors the 600 ms floor / shared 5/s
  bucket reality and states the ~1 s chunked ceiling; DM-first; SimpleX-only.
- P14: §7 — the flag is a capability gate with a posture, defaults off.
- P16: the residuals are the user's decision — stated explicitly, approved
  with the wording; the analyst recommends, the user decides.

## Approach

- **Files to touch:** `files_scope` — the two spec files only.
- **Steps, in order:**
  1. Confirm M1-845's mode pairing landed (blocked_by) so the eligibility
     sentence references it exactly.
  2. Draft the security.md §LLM output sanitizer streamed-surface regime
     (P1/P2/P4 + the P3 display rule's security half).
  3. Draft the messaging.md §Progress notifications live-text mode and the
     §Capability flags member (P10 cadence text included).
  4. Draft the accepted-residuals paragraph (P16) — every transient-display
     consequence named.
  5. Show the user the exact proposed text with a plain-English account of
     each commitment added/removed/changed (§12); only an explicit yes
     lands it.
- **Controls to preserve (§10):** every existing sanitizer commitment
  (out_of_scope item 3 enumerates them) and the stage-label regime for the
  collapse path — the amendment's own acceptance asserts their survival
  (the M1-663 "no commitment weakened" discipline).
- **Pitfall→mitigation:** P1→step 2's full-prefix rule; P2→step 2's
  hold-back rule; P3→steps 2-3's revert rule; P4→step 2's aggregation
  rule; P5→step 3's terminal rule; P10→step 3's cadence text; P14→
  step 3's gating rationale; P16→step 4.

## Definition of done

The streamed-surface regime (full-prefix updates, refusal window, per-turn
audit aggregation, tool-loop rule, terminal rule), the messaging.md
live-text mode with eligibility + cadence + collapse, the capability-flag
member, the residuals paragraph, and the flag rationale all land as
rule-text with user approval; no existing commitment is weakened; mvn
verify is green.

## Verification

- P1 → acceptance item 1's grep — the full-prefix rule is in the section.
- P2 → item 2's grep — the hold-back/window rule.
- P3 → item 4's grep — the revert rule.
- P4 → item 3's grep — per-turn aggregation incl. transient matches and the
  fail-closed audit posture.
- P5 → item 5's grep — the terminal finalize rule.
- P10 → item 6's grep — cadence ceiling and DM/SimpleX scoping.
- P14 → item 9's grep — the capability-gating rationale.
- P16 → item 8's grep — the residuals paragraph exists and is explicit.
- failure mode → item 3: a turn whose audit write fails mid-stream must end
  at the failure terminal per the amended rule — the amendment text is
  written so M1-849's failing-audit-INSERT test derives from it directly.
- acceptance item 11 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: all code (M1-849), the switch amendment (M1-845),
any weakening of the enumerated sanitizer commitments, and Signal/group
scopes. The diff touches docs/spec/security.md and docs/spec/messaging.md
and nothing else (`git diff --stat` shows exactly two files — the M1-663
item-4 shape). If the user rejects the streamed-surface regime at the
wording review, this ticket ends abandoned (wont-do) and M1-849 is dropped
with it — the switch half of the batch (M1-845/M1-848) stands on its own
(analysis §SPEC-GAP item 2).

## Round 1 rework

1. FINDING 1: add the `infochat.chat.live-text` GAP entry to
   infochat-provider/src/test/resources/documented-config-key-exemptions.txt
   (same block as line 83, citing M1-849), keep the spec text untouched, and run a
   fresh full `mvn verify` from the repo root captured as this ticket's log of
   record — evaluated via the three probes in FINDING 1's EVALUATED-AS (ledger
   grep, spec grep, green DocumentedConfigKeyParityTest + BUILD SUCCESS in the new
   log).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-846-streaming-translation-switch-3.md
```
