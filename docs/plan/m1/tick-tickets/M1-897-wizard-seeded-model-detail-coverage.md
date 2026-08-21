---
id: M1-897
title: "Wizard wiring: pin the seeded-model reply-mode detail"
status: done
created: 2026-08-20
last_updated: 2026-08-21
flow: tick
reproduction: >-
  RemoteLlmWiringTest.seededModelReplyModePrintsThePerLanguageDetail — GREEN
  on the unmodified script; under the reproduction mutation that changes
  MODEL_REPLYMODE_DETAIL[gemma-4-26b-a4b] at prod/scripts/4-llm.sh:60 to
  "en/es PASS, cs/ru/tr FAIL", the test is RED because the captured wizard
  output no longer contains the committed detail. The driver observed the
  pre-test mutation probe on main @ 4b275e76 as 45 tests, 0 failures, BUILD
  SUCCESS because no drive selected gemma-4-26b-a4b.
analysis_ref: docs/plan/m1/tick-analysis/wizard-ollama-branch-coverage.md
blocked_by: []
files_scope:
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    prod/scripts/4-llm.sh and every other production file — this is a
    coverage gap, not a behavior defect; in particular NO re-seeding of the
    recommendation/detail tables and no fuzzy model-name matching (the
    falsified D79-registry shape, M1-895 P4). A defect spotted while writing
    the drive is a follow-up ticket, not a rider (§1, analysis P6).
  - >-
    LlamacppWiringTest.java — the ollama-branch and --defaults drives are
    the sibling ticket M1-896 (disjoint file; the tickets are independent).
  - >-
    docs/measurement/direct-chat-e2e.md — a frozen historical record; the
    script's table seeds FROM it, the record is never edited (M1-895's
    out_of_scope, carried).
  - >-
    docs/spec/** and docs/design/** — no promise changes: the D79 row
    (decisions.md:98) already carries the wizard-recommendation sentence
    and §7.7.2 already documents the step-4 contract.
acceptance:
  - "REPRODUCTION closed: RemoteLlmWiringTest.seededModelReplyModePrintsThePerLanguageDetail — the remote drive (backend=remote, provider Enter = openai-compatible, base-url, model `gemma-4-26b-a4b` at the free-text model prompt 4-llm.sh:808, API key, ACCEPT_TIMING_DEFAULTS, reply-mode Enter) asserts the output contains the exact fragment `chat reply-mode recommendation for gemma-4-26b-a4b: translate (cs/ru/tr PASS, en/es FAIL)` (the :149 line) AND the written props carry `infochat.chat.reply-mode=translate`. Non-vacuity (§8, analysis P3): the reproduction's mutation (inverted detail at :60) turns the output assertion RED; a mutation recommending `native` for the seeded model (:56) turns the props assertion RED. The recommendation is advice, never a gate — an informed override stays operator-owned (D79), and this drive asserts the advice is TRUTHFUL."
  - "EDGE (the fallback arm of the same print): RemoteLlmWiringTest.unmeasuredRemoteModelPrintsTheUnmeasuredDetail — an arbitrary operator-typed model (the existing REMOTE_MODEL) drives the same prompt and asserts the output carries `(unmeasured)` and the props carry `translate` — pinning the `:-unmeasured` fallback at 4-llm.sh:139, the half of the line the seeded drive cannot see (a mutation that drops the fallback or prints an empty detail fails here)."
  - "Record fidelity (analysis P8): the pinned detail string equals BOTH the script's seed and the committed bar-clearing matrix. Verify: `grep -n 'cs/ru/tr PASS, en/es FAIL' prod/scripts/4-llm.sh` hits the :60 seed, and docs/measurement/direct-chat-e2e.md:547-555 shows gemma × cs/ru/tr PASS, × en/es FAIL. A future re-measurement re-seeds the table deliberately — this test is the intended tripwire, never edited to match a drifted seed without the record moving first."
  - "Test-only (analysis P6): `git diff` is confined to RemoteLlmWiringTest.java and adds whole new methods only — every pre-existing drive's stdin and assertions byte-untouched (no modification of the existing replyModeAskedAndWrittenForRemoteModel; item 2 is a NEW method). Verify: `git diff prod/ docs/` is empty and `git diff infochat-llm-adapter/src/test` shows no hunk inside any existing method body."
  - "`./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest,RemoteLlmWiringTest'` is green AND `mvn verify` from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      RemoteLlmWiringTest.seededModelReplyModePrintsThePerLanguageDetail
      (reproduction closure — the seeded-model detail print),
      RemoteLlmWiringTest.unmeasuredRemoteModelPrintsTheUnmeasuredDetail (the `:-unmeasured`
      fallback arm of the same line).
  preserves:
    - all tests currently green on main
    - >-
      Every pre-existing drive in both wiring classes — stdin strings and
      assertions byte-untouched; both new methods are whole additions.
  modifies: []
  notes:
    - >-
      Coverage-ticket RED semantics (analysis P7): the new tests are
      expected GREEN on unmodified main at `start`; the RED evidence is the
      reproduction's mutation probe. The workflow §0 demonstration is:
      re-apply mutation A (invert the detail string at 4-llm.sh:60), watch
      item 1's test go RED, revert.
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/decisions.md §Decisions log
decision_refs:
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates: M1-895
reviews:
  - round: 1
    date: 2026-08-21
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS; SECURITY PASS; TEST-ADEQUACY PASS; MAINTAINABILITY WARN; SCOPE PASS"
    diff_stats: "3 files changed, 52 insertions(+), 24 deletions(-) (code: RemoteLlmWiringTest.java +28/-0, two whole new methods; rest docs/plan bookkeeping)"
    fixes: "1 comment-only item applied: stdin dialect-Enter trap comment above each new drive's runWizardCapturingOutput call (RemoteLlmWiringTest.java:279,297). Probes: `grep -n 'shifting' RemoteLlmWiringTest.java` → 2 hits (lines 280, 298, one per new method); `./mvnw -B -pl infochat-llm-adapter -am test-compile` → BUILD SUCCESS (45 test sources recompiled, .scratch/tick-fixes-M1-897-testcompile.log). Green log of record: target/tick-test-M1-897-r1.log."
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-21
  lint: pass-with-warnings
  result: pass
  notes: >-
    0 blockers. Live census resolves the seeded detail row and unmeasured
    fallback. All acceptance items are implementable from the existing remote
    helper; P2/P5 are explicitly assigned to sibling M1-896.
escalation_reason:
---

# M1-897: Wizard wiring: pin the seeded-model reply-mode detail

## Context

M1-895 (merged 2026-08-20 as 4b275e76) seeded the D79 reply-mode
recommendation table with the one measured chat model:
`MODEL_REPLYMODE_RECOMMENDED[gemma-4-26b-a4b]=translate`
(prod/scripts/4-llm.sh:55-57) plus the per-language detail printed so an
operator can make an INFORMED override —
`MODEL_REPLYMODE_DETAIL[gemma-4-26b-a4b]="cs/ru/tr PASS, en/es FAIL"`
(:59-61), printed at :149. No wiring drive ever selects that model — the
pinned llamacpp GGUF is gemma-4-E4B, the ollama profile models are fixed
§5.7 llama3 tags, and existing remote drives type other names — so the
detail line is verified by read only. The mutation probe in `reproduction:`
proves the hole: inverting the detail (an operator with en/es scopes would
read "en/es PASS" and override to native against the record's actual
advice) ships with the suite fully green. Shared analysis: `analysis_ref:`.

## Root cause

Coverage hole, not a defect in M1-895: every default wizard path recommends
`translate` for an UNMEASURED model (the conservative fallback working as
intended), so the seeded row's distinguishing output — the per-language
detail — sits on a path no drive reaches. The remote branch closes this
without new plumbing: its openai-compatible dialect reads a free-text model
name (4-llm.sh:808) and sets `reply_mode_model="$remote_model"` (:814), so
an operator-typed `gemma-4-26b-a4b` executes the :149 print exactly as a
real operator run would. Verified harness facts: `RemoteLlmWiringTest`
seeds the remote-llm profile (:292), its drives feed
provider/base-url/model/api-key positionally (:77-79 precedent), and its
`WizardRun` already carries `(output, props)` (:280) — the drive needs no
drive-layer change at all.

## Pitfalls

Numbered consistently with the analysis document; this ticket carries P1,
P3, P4's placement half, P6-P8 (P2 and P5 belong to M1-896).

- P1: positional-stdin discipline — the dialect answer must be Enter
  (openai-compatible): `deepseek` pins its model at 4-llm.sh:805-806 and
  never reads one, shifting every later answer off by one and dying at EOF
  under `set -e` (the M1-553/M1-826 lesson).
- P3: non-vacuity (§8 Assertion adequacy) — the output assertion pins the
  EXACT detail string (kills mutation A); the props assertion pins the
  written `translate` (kills a `native`-recommending seed at :56). Each is
  the end-of-path check for its mutation; neither alone covers both.
- P4: placement — the seeded model is unreachable via the ollama branch
  (fixed profile tags) and the drive belongs in RemoteLlmWiringTest, whose
  seeded profile the remote branch accepts; reaching it via ollama would
  require a production seam existing only for the test (analysis O3,
  rejected).
- P6: test-only scope (§1/§12) — no re-seed, no fuzzy matching, no spec
  edit; the D79 row (decisions.md:98) already carries the rule.
- P7: coverage-ticket RED semantics — the new tests PASS on unmodified
  main; the RED evidence is the reproduction's mutation probe, re-applied
  at `start` (test_plan.notes).
- P8: record-fidelity pinning (§11) — pin the detail string verbatim and
  check it against the committed matrix (direct-chat-e2e.md:547-555); a
  paraphrased assertion lets wording rot through, and editing the assertion
  to match a drifted seed without the record moving is exactly the rot this
  ticket exists to catch.

## Approach

Derived from `spec_refs`: the D79 row (decisions.md:98) commits the wizard
to recommend per chat model from the committed in-language measurement
record — the seeded row and its printed detail ARE that commitment's
operator-visible surface; §7.7.2's step-4 row (:797) commits the wizard to
the model-driven flow the drive exercises. The drives pin the existing
contract; nothing is amended.

- **Files to touch:** `files_scope` — one test class.
- **Steps, in order:**
  1. Write both drives (acceptance items 1-2) as whole new methods —
      stdin shape per the existing remote drives (:77-79) with
      `gemma-4-26b-a4b` / the arbitrary model at the model prompt (P1).
      Run them GREEN on unmodified main, then demonstrate RED: re-apply
      mutation A (invert :60), watch item 1 fail, revert (P7 — capture both
      runs to .scratch and cite in the commit message).
  2. The record-fidelity greps (item 3) and the test-only git-diff probes
     (item 4).
  3. The two mvn runs (item 5).
- **Controls to preserve (§10):** no path rerouted — additive test code.
  Every pre-existing drive's stdin and assertions byte-untouched (item 2 is
  a NEW method; the existing `replyModeAskedAndWrittenForRemoteModel` is
  not modified); no production/spec/doc file touched; no new `infochat.*`
  key written (DocumentedConfigKeyParityTest unaffected).
- **Pitfall→mitigation:** P1→step 1's stdin construction; P3→item 1's dual
  output+props assertions; P4→the remote-branch drive (no plumbing); P6→
  item 4's probes; P7→step 1's RED demonstration; P8→item 3's greps.

Alternatives considered (rejected; analysis §Solution options): driving the
seeded model through the ollama branch via a new profile/override seam (O3);
extending the existing unmeasured-model remote test in place instead of
adding item 2's method (an unauthorized §8 modification — a new method is
cleaner); bundling with the ollama drives (O2 — disjoint file, sibling
M1-896).

## Definition of done

The seeded-model drive passes: the output names
`gemma-4-26b-a4b: translate (cs/ru/tr PASS, en/es FAIL)` verbatim and the
written props carry `infochat.chat.reply-mode=translate` — RED under the
reproduction's inversion. The fallback drive passes: an unmeasured model
prints `(unmeasured)` and writes `translate`. The pinned string matches the
script seed and the committed matrix. The diff is confined to
RemoteLlmWiringTest.java, adds whole methods only, and both mvn runs are
green.

## Verification

- P1 → the drives themselves: a mis-fed stdin dies at EOF with rc != 0,
  failing loudly (the deepseek-skips-the-model-read trap is called out in
  the method comment).
- P3 → item 1's non-vacuity clause: mutation A reds the output assertion;
  a `native` seed reds the props assertion; the commit message cites the
  RED capture (P7).
- P4 → item 1's green run on the remote drive proves the :149 path is
  reached without plumbing.
- P6 → item 4's git-diff probes.
- P7 → test_plan.notes' mutation re-application at `start`.
- P8 → item 3's greps (script seed + record matrix).
- Edge coverage (mandatory class) → item 2: feeds the unmeasured-model edge
  and asserts the conservative fallback print + write.
- acceptance item 5 → the named mvn runs.

## Out-of-scope

Named in `out_of_scope`: any production edit — including re-seeding the
tables and any fuzzy model-name matching (coverage gap, not a behavior
defect — §1; the fuzzy shape is M1-895's falsified D79-registry); the
frozen measurement record (the table seeds FROM it); LlamacppWiringTest
(sibling M1-896's file); all spec/design docs (the D79 row and §7.7.2
already carry the text). No pre-existing test is modified: both drives are
whole new methods (item 4 states this as the §8-relevant bound).

## Census

Class: seeded `MODEL_REPLYMODE_DETAIL` rows and their print coverage
(re-runnable: `grep -n 'MODEL_REPLYMODE_DETAIL\[' prod/scripts/4-llm.sh`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh:60 `[gemma-4-26b-a4b]="cs/ru/tr PASS, en/es FAIL"` | FIXED (this ticket, acceptance item 1) |
| prod/scripts/4-llm.sh:139 `:-unmeasured` fallback | pinned (this ticket, acceptance item 2) — not a seeded row, but the same print's other arm |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-897-wizard-seeded-model-detail-coverage.md
```
