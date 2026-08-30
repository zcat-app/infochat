---
id: M1-959
title: "Re-read width-32 as owner deltas on both legs"
status: pending
created: 2026-08-30
last_updated: 2026-08-30
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/retrieval-campaign-followups.md). Probe (the
  measurement posture — the missing delta IS the defect, the M1-930/M1-944/
  M1-952 posture): `grep -n 'width-32' docs/measurement/
  retrieval-eval-two-leg.md` returns ONLY the not-decided statement ("The
  width-32 lever is NOT product-decided by this record ... before any
  infochat.chat.semantic-limit change, the lever re-reads as owner-run
  deltas on BOTH legs — T1 per leg ... — and the change decision is a
  SEPARATE decision/ticket after this baseline") — the binding
  pre-registration exists, no delta run is recorded, AND the harness cannot
  express one: `grep -n 'static final int K' infochat-provider/src/test/
  java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorer.java`
  returns the HARDCODED cap (:27-28, "the recall cap: the production
  default result count (infochat.chat.semantic-limit)", used at :142 as
  Math.min(|E|, K)) — a limit-32 run scored through it silently reports
  capped recall at 16, contradicting the scorer's own javadoc contract.
  Intended entry (to-be-written per workflow §0 — no k-bearing score seam
  exists to compile against, the M1-950 marker precedent):
  RetrievalEvalScorerTest#capsRecallAtTheRunsEffectiveLimitNotTheHardcodedDefault
  — score(...) over a 32-row return with |E| = 20 caps at min(20, 32) = 20;
  RED today at 16 (denominator 16, observed wrong output).
analysis_ref: docs/plan/m1/tick-analysis/retrieval-campaign-followups.md
blocked_by: [M1-957]
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - docs/measurement/retrieval-eval-two-leg.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY infochat.chat.semantic-limit CHANGE — the flip is a SEPARATE
    decision/ticket after this baseline (the binding pre-registration,
    analysis P17); probe: git diff names no application.properties /
    config path, and no committed file sets the key to 32.
  - >-
    ANY production / main-source / spec / design change — the scorer and its
    test are test-scope; the record is a measurement doc; probe:
    git diff --name-only names no src/main or docs/spec path.
  - >-
    Amending or restating-with-knowledge the pre-registered rules
    T1/G1/N1/D1/TL1/TL2/TL3 or the coverage-comparability clause — cited by
    reference exactly as the two-leg record already cites them (analysis
    P18); any rule edit fails this item.
  - >-
    Editing the two-leg record's landed sections — the delta lands as ONE
    appended dated section (append-only, analysis P19); corrections stay
    visible.
  - >-
    The window arm (M1-957's scope) and any golden-set edit — consumed
    read-only at whatever sha the 16-side runs pinned.
  - >-
    Any world mutation (backfill, re-embed sweep, restore) — both legs'
    fingerprints AND coverage pins must byte-equal their 16-side pins (the
    coverage-comparability clause); live fam and prod containers are never
    targets.
acceptance:
  - "REPRODUCTION closed: RetrievalEvalScorerTest.capsRecallAtTheRunsEffectiveLimitNotTheHardcodedDefault passes — score(records, outcomes, worldNow, k=32) over a 32-row return with |E| = 20 yields capped recall denominator min(20, 32) = 20 (RED today: 16). The static K stays as the DEFAULT overload's value; every pre-existing RetrievalEvalScorerTest pin passes UNMODIFIED (the §8-authorized change is the k-bearing overload + the runner's call site, named in test_plan.modifies)."
  - "The runner passes its EFFECTIVE limit into the scorer (the already-injected @ConfigProperty infochat.chat.semantic-limit, RetrievalEvalRunnerIT.java:155-156) — a limit-32 run's scores.json caps at 32; the manifest's config.semantic_limit already pins the lever (probe: the 32-side manifest artifact under .bench/retrieval-eval/ shows semantic_limit: 32; grep -n 'score(' over the runner shows the k-bearing call)."
  - "BOTH legs' 32-side owner runs recorded: per leg, the documented lane invocation plus the semantic-limit override, run TWICE (two invocations, per-query uid lists byte-identical — the determinism leg, harness-asserted); per-leg pins: DB fingerprint and world_embedding_coverage byte-equal to that leg's 16-side pins, golden_set_sha256 byte-equal, embedder/translator/threshold pins restated — probe: every pin key resolves in the appended section; both run manifests' label_fingerprint_match true."
  - "Per-leg T1 against the POST-M1-957 16-side runs (analysis P16 — single-variable pairing: the 16-side is the window-armed run of M1-957's delta section, named by commit + run timestamps; NEVER M1-952's unwindowed readings): discordant queries = the expected-uid set inside the returned window differs between the limit-16 and limit-32 runs of the same golden set and fingerprint; one-directional discordant counts per leg; the two-sided sign test reported where the floor (6 one-directional) allows, 'fewer than six is never a result' stated otherwise; marks per leg per TL2 (a one-leg result stays leg-scoped); movement per class DESCRIPTIVE; never pooled across legs (TL3); absolute counts only (N1) — probes: grep the section for the per-leg discordant counts; no percentage-point phrasing (reviewer read); grep -n 'leg-scoped' returns the TL2 sentence."
  - "The not-decided statement is RESTATED in the appended section (analysis P17): the lever remains NOT product-decided by these deltas; any infochat.chat.semantic-limit flip is a SEPARATE decision/ticket — probe: grep -n 'NOT product-decided\\|not product-decided' over the record returns both the landed statement and the new section's restatement."
  - "Diff fences (analysis P19/P22): git diff --name-only names exactly the files_scope paths plus board/frontmatter regen; the record diff is pure additions; no URL, no user-derived data, no instance name in the section (only pins, uids where needed, counts) — probes: git diff --name-only; grep the new section for http/instance tokens returns nothing."
  - "mvn verify from the repo root is green (the scorer legs run in the default suite; the runner IT stays CI-excluded — the M1-950 verify-log probe)."
test_plan:
  adds:
    - >-
      RetrievalEvalScorerTest — capsRecallAtTheRunsEffectiveLimitNotTheHardcodedDefault
      (the reproduction; a 32-row return, |E| = 20, denominator 20) and a
      k=16 identity leg (the overload at the default reproduces the static
      pin's numbers byte-identically).
  modifies:
    - >-
      RetrievalEvalScorer (AUTHORIZED: score gains a k-bearing overload; the
      static K remains the default's value — every existing pin's numbers
      unchanged; the javadoc's cap contract now states the parameter is the
      run's effective limit).
    - >-
      RetrievalEvalRunnerIT (AUTHORIZED: the scorer call passes the injected
      effective limit; no fence, key, or arm change — M1-957's arm rides
      through untouched).
  preserves:
    - every existing RetrievalEvalScorerTest pin byte-identical.
    - every runner fence; the golden sets read-only.
    - all tests currently green on main.
spec_refs:
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D19
  - D29
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-959: Re-read width-32 as owner deltas on both legs

## Context

`infochat.chat.semantic-limit` 16→32 is the tech world's best measured move
(the prior campaign's shadow reading — context only: the shadow worktree
`.opencode/worktrees/M1-945-eval` NO LONGER EXISTS on this machine, so the
shadow artifact is unverifiable from this checkout and its magnitude is
never quoted as a leg result) and is explicitly NOT product-decided by the
two-leg record: the binding pre-registration demands the lever re-read as
owner-run deltas on BOTH legs (T1 per leg; TL1 for any product-wide claim)
BEFORE any config change, with the change decision a SEPARATE
decision/ticket. This ticket runs the pre-registered deltas and appends
them. One harvested prerequisite defect rides along: the scorer's recall
cap is hardcoded at 16 against its own contract, so a 32-side run cannot
be scored honestly until it is parameterized. Shared analysis:
`analysis_ref:` (Ground truth, Pitfalls P15-P19, options A-C).

## Root cause

Not a retrieval defect — an un-run pre-registered delta plus one instrument
defect the run exposed at analysis time (the coverage-premise duty):
`RetrievalEvalScorer.K = 16` is documented as "the production default
result count (infochat.chat.semantic-limit)" (RetrievalEvalScorer.java:
27-28) but is a constant — the moment the run's limit differs from 16, the
scorer's capped-recall denominator silently disagrees with its stated
contract (:142, `Math.min(|E|, K)`). Verified: the runner already injects
the effective limit (:155-156) and pins it in the manifest (:502), so the
override invocation (`-Dinfochat.chat.semantic-limit=32`) needs no new
plumbing — only the scorer seam and honest recording.

## Pitfalls

Carried from the analysis, numbered identically; this ticket carries
P15-P19 (P16 realized as the blocked_by sequencing).

- P15: scorer K — a 32-side run scored at K=16 silently reports R@16-of-32;
  the delta's headline metric would not constrain what the run claims
  (assertion adequacy).
- P16: single-variable pairing — the 16-side of each leg's T1 pair must be
  the POST-M1-957 window-armed run; pairing against M1-952's unwindowed
  readings conflates two changes in one sign test (T1's same-set/
  matching-fingerprint rule plus the single-variable reading). Hence
  blocked_by [M1-957].
- P17: the decision is separate — no flip anywhere in this family's diffs;
  the not-decided statement stays true and is restated.
- P18: comparability — per leg, fingerprint AND coverage pin byte-equal;
  frozen corpora only; no backfill or search-space mutation.
- P19: append-only — one appended dated section; landed sections and rules
  never edited or restated-with-knowledge.

## Approach

Derived from `spec_refs:` — llm.md §Determinism boundary is the pinned-
world premise both legs' determinism legs restate (same DB state → same
rows and order; the limit is SQL-decided, reproducible); security.md
§Prompt-injection defenses is the lane's fence charter (the runs execute
the production tool bean under the runner's refusals).

- **Files to touch** — `files_scope`: the scorer (+ its test) and the
  runner's call site (test-scope), the record's appended section.
- **Steps in order:**
  1. The scorer legs RED (workflow §0: the k=32 denominator leg), then the
     k-bearing overload; `mvn verify` green (the §8-authorized modify is
     exactly the overload + call site).
  2. Owner runs, per leg: the documented invocation + the
     `infochat.chat.semantic-limit=32` override, two invocations each;
     capture manifests/queries/scores under `.bench/` (operator-local).
  3. Author the appended section: per-leg pins (fingerprint, coverage,
     sha, models, threshold, semantic_limit=32, run timestamps), per-leg
     T1 vs the post-M1-957 16-side runs (discordant counts, sign test or
     the fewer-than-six statement), descriptive per-class movement, the
     leg-scoped marks (TL2), the do-not-settle restatement.
  4. Diff fences; board regen.
- **Controls to preserve (§10):** every runner fence runs as-is (no
  harness line beyond the scorer call changes; M1-957's arm rides through
  untouched); the golden sets consumed read-only; the record's rules cited
  by reference; no spec row cites any of this.
- **Pitfall→mitigation:** P15→step 1; P16→blocked_by + step 3's named
  16-side runs; P17→the restatement + the config grep fence; P18→step 2's
  byte-equal pins (harness-refused otherwise); P19→step 3's append-only
  probes.
- **Alternatives considered (rejected; the commit message cites them):**
  pairing against M1-952's unwindowed 16-side readings (B — an illegal
  two-change sign test); flipping the config in the same ticket (C —
  explicitly forbidden by the pre-registration).

## Definition of done

The scorer caps at the run's effective limit with every existing pin
green; the runner passes the limit and the manifest pins it; both legs'
32-side runs are recorded twice each with byte-equal fingerprints and
coverage pins; the per-leg T1 comparisons name their post-M1-957 16-side
runs, report discordant counts and the sign test (or the fewer-than-six
statement), keep marks leg-scoped, and phrase movement descriptively; the
not-decided statement is restated; the record diff is pure additions; no
config change anywhere; repo-root `mvn verify` is green.

## Verification

- P15 → the reproduction leg (denominator 20 at k=32) + the k=16 identity
  leg (the default overload reproduces the static pin's numbers).
- P16 → the appended section names its 16-side runs (commit + timestamps);
  the frontmatter blocked_by; reviewer read: no T1 row pairs a 32-side run
  with an unwindowed reading.
- P17 → the restatement probe (both statements greppable) + the config
  grep fence (acceptance item 6).
- P18 → both 32-side manifests' fingerprint/coverage byte-equal to the
  legs' pins (harness-refused otherwise; restated in the section).
- P19 → git diff over the record is pure additions; the rules block
  untouched (grep the section cites T1/TL1/TL2/TL3 by reference only).
- FAILURE-MODE coverage → the reproduction leg IS the failure mode (the
  hardcoded cap observed wrong: denominator 16 where 20 is contracted);
  plus the determinism legs (harness-asserted) refuse any drift.
- acceptance items 6-7 → the diff/verify probes.

## Out-of-scope

Named in `out_of_scope`: any semantic-limit change; any production/spec/
design change; any rule edit or restatement-with-knowledge; editing the
landed record sections; the window arm and the golden sets (consumed
read-only); any world mutation or live-instance target. The two modified
test files are authorized in `test_plan.modifies` with the overload shape
stated in plain language (engineering-rules §8); every other pre-existing
test passes unmodified.

## Census

The seam this ticket guards: **committed sites that fix or assume the
retrieval cap.** Re-runnable:
`grep -rn 'K = 16\|Math.min(.*K)\|semantic-limit' infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/`.
Rows (verified at draft time):

- RetrievalEvalScorer.java:27-28/:142 — the cap and its use → **FIX** (the
  k-bearing overload; the default stays 16).
- RetrievalEvalRunnerIT.java:155-156/:502 — the injected limit and the
  manifest pin → **FIX** (the call passes k; the pin already honest).
- RetrievalEvalScorerTest — the pins → **PRESERVED** (byte-identical) +
  two legs added.
- RetrievalEvalCharacterizationIT — reads the limit for its manifest only,
  no cap assumption → **DISPOSED** (untouched).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-959-width-32-both-leg-delta.md
```
