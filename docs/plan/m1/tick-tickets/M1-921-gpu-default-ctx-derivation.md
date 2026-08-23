---
id: M1-921
title: "Re-derive GPU-class default ctx from the prompt floor"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  LlamacppWiringTest.gpuClassServingShapeFitsTheDocumentedPromptFloor
  (to-be-written — converted at /tick start per workflow §0: written
  first, run RED; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md).
  The wrong behavior it states: the shipped GPU-class default was derived
  from a throughput table and never fit-checked against a real chat turn.
  Verified: prod/scripts/4-llm.sh:649-650 writes
  INFOCHAT_LLAMACPP_PARALLEL=3 / INFOCHAT_LLAMACPP_CTX=32768 → 11,008
  tokens/slot, while the product's own first real chat turn measured
  11,477 tokens → HTTP 400 (llama-server log, quoted in
  .agents/memory-local/prod-state-post-upgrade-20260823.md); the v2.0.0
  chat legs that "validated" chat ran on the test instance's P1 pin
  (single slot, ctx 8192, small prompts —
  .scratch/V2.0.0-FIX-VERIFICATION-FINAL-REPORT-2026-08-22.md:94-111),
  so no shipped-default shape was ever exercised against a real prompt.
analysis_ref: docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md
blocked_by:
  - M1-918
  - M1-920
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The prompt floor itself — sibling M1-918 (blocker) owns the
    infochat.chat.prompt-token-budget key and its 6,144 default; this
    ticket consumes that number, it does not re-derive or change it.
  - >-
    The cache-RAM key — sibling M1-920 (blocker); this ticket's memory
    arithmetic CITES the cache write M1-920 lands but changes no cache
    key or value.
  - >-
    The CPU-class write (1/4096) and any CPU-class chat-fit claim — the
    analysis records that ctx 4096 sits below the product's prompt floor
    entirely; changing the CPU class is unmeasured scope (no CPU-class
    chat-turn measurement exists) and is NOT taken here. The docs note
    the floor relationship honestly.
  - >-
    MTP/spec-decode keys and the head (M1-908/909 own them); the
    ~2 GB spec head enters this ticket ONLY as a cited input to the
    memory arithmetic.
  - >-
    Editing any measurement record (docs/measurement/** is append-only)
    or the 10b campaign throughput numbers — the parallel=3 aggregate
    evidence stands; this ticket re-derives FIT, not throughput.
  - >-
    docker-compose.yml / docker-compose.gpu.yml — the wizard write is
    the change; the compose interpolation surface is untouched.
acceptance:
  - "REPRODUCTION closed: LlamacppWiringTest.gpuClassServingShapeFitsTheDocumentedPromptFloor (test_plan.adds) passes — the GPU-class drive asserts the wizard's written CTX and PARALLEL satisfy the derivation, pinned as literals: per-slot = CTX/PARALLEL >= infochat.chat.prompt-token-budget floor (6,144) + chat reply budget (600) + template/estimate headroom, i.e. the landed write is PARALLEL=3 / CTX=24576 (8,192/slot) — the smallest power-of-two slot covering 6,144 + 600 + ~1.4k headroom — and the test fails if the write drops below the floor formula (the non-vacuity mutation: CTX back to 3x8192=24576 stays green; 3x6144 would not — see the formula assertion in the test)."
  - "DERIVATION RECORDED (P13): the 4-llm.sh write site and docs/design/07-deployment.md §7.8.3 carry the derivation — inputs: M1-918's prompt floor 6,144, the GPU-class chat reply budget 600 (4-llm.sh:918), template/estimate headroom (~1.4k, the chars/4 error band, analysis P2), the stopping-point lesson (2.7x the biggest budgeted turn per slot — the prod memory file's rule applied to the POST-M1-918 turn size, not the pre-budget 11.5k), and the memory arithmetic (40g cap: ~21 GB Q6_K_XL weights + KV for the derived ctx x 3 slots + 16 GiB prompt cache per M1-920 + ~2 GB spec-decode head per M1-909). Probe: grep -n '6144\\|prompt-token-budget\\|floor' prod/scripts/4-llm.sh returns the derivation comment at the write site."
  - "MEMORY-CAP COHERENCE (P12/P13): the derivation comment shows the derived shape fits the UNCHANGED GPU-class cap write (40g/12) — KV SHRINKS vs 3x32768 — so no cap-key value changes; the gpuHostGetsBenchmarkServingClassAndTiming drive's memory/cpus assertions stay byte-untouched."
  - "AUTHORIZED TEST MOVE (§8): the pre-existing M1-905 drive gpuHostGetsBenchmarkServingClassAndTiming's ctx assertion (32768) is updated to the derived literal — this ticket authorizes exactly that change, and no other hunk in any pre-existing drive (stdin, timing pins 60000/600 + 60000/400, cap secrets, serving-key presence, M1-920's cache assertion) changes. Verification: git diff on the test file shows only the new method plus that single literal update."
  - "FAILURE-MODE (P13, negative): the reproduction test's formula arm fails the build if a later edit writes a GPU-class CTX whose per-slot value falls below floor + reply + headroom — a throughput-table-only number can never silently return."
  - "DOCS: docs/design/07-deployment.md §7.8.3's GPU-class paragraph is updated to the derived values WITH the derivation and its citations (the incident, the floor, the campaign throughput evidence for parallel=3), and §7.7.2's step-4 row records the new write; the CPU-class sentence gains the honest note that ctx 4096 is below the product's chat prompt floor (analysis Ground truth). Verification: git diff --stat docs/ shows exactly docs/design/07-deployment.md."
  - "./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest' is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — gpuClassServingShapeFitsTheDocumentedPromptFloor (derived literals + floor-formula arm)
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (acceptance item 4): the single ctx literal in gpuHostGetsBenchmarkServingClassAndTiming moves 32768 → the derived value; nothing else in any pre-existing drive changes
  preserves:
    - all tests currently green on main
    - every other pre-existing LlamacppWiringTest assertion byte-untouched, including the M1-905 timing/cap pins, the M1-908 spec-key pins, M1-909's wizard-offer drives, and M1-920's cache pins (all read the same end state)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.8.3
  - docs/design/07-deployment.md §7.7.2
decision_refs:
  - D49
---

# M1-921: Re-derive GPU-class default ctx from the prompt floor

## Context

The shipped GPU-class wizard default (parallel=3, ctx=32768 →
11,008 tokens/slot, 4-llm.sh:649-650) came from the 10b campaign's
aggregate-throughput table and was never validated against a real chat
turn — the campaign's chat legs ran on the P1 pin (single slot, 8192)
where small prompts fit. Prod's first real turn (11,477 tokens) 400d
against it. Ops carries CTX=92160 untracked. With M1-918 (blocker)
bounding the product's prompt at a documented floor (6,144 estimated
tokens), the shipped default can be re-derived from FIT instead of
throughput — and KV memory drops with the smaller ctx. Analysis:
`analysis_ref:`.

## Root cause

M1-905's acceptance-1 discipline pinned the serving-key SURFACE but its
GPU-class values were the campaign's throughput optimum (94.1 tok/s
aggregate at 3/32768); no campaign leg ever rendered the product's real
prompt against the resulting slot size (the test instance ran P1/8192
with small prompts — the A-series legs). Fit was never an input to the
number.

## Pitfalls

Numbered with the analysis document; this ticket carries P12, P13, P14.

- P12: the memory arithmetic must close inside the UNCHANGED 40g cap —
  weights ≈ 21 GB + KV(ctx × slots) + 16 GiB cache (M1-920) + ~2 GB
  spec head (M1-909); the derived ctx SHRINKS KV vs 3x32768, so the cap
  stands, and the comment cites every input.
- P13: never re-derive from throughput alone again — the derivation
  names the floor, the reply budget, the headroom, and the prod
  stopping-point lesson (2.7× the biggest budgeted turn per slot), and
  the wiring test's formula arm fails any regression to a
  throughput-only number. Docs sync: §7.8.3 + §7.7.2; measurement
  records are append-only (cite, never edit).
- P14: sibling calibration — this ticket lands after M1-918 (the floor)
  and M1-920 (same files, same test); its authorized test move is the
  single ctx literal in the M1-905 drive, pre-authorized here so the
  fixture-calibration rule is honored (M1-920 pinned no ctx value).

## Approach

Derived from `spec_refs:`: §7.8.3's operator-key pattern and D49 (the
operator owns local-backend serving choices); the wizard keeps its
printed-never-prompted posture.

- **Files to touch:** `files_scope`.
- **Pre-decided derivation (implementation is execution):**
  per-slot floor = prompt floor (6,144) + chat reply (600) +
  template/estimate headroom (~1.4k) ≈ 8.1k → smallest power-of-two
  slot 8,192 → CTX = 3 × 8,192 = **24,576**, PARALLEL stays **3** (the
  throughput evidence stands; idle slots cost nothing). Sanity against
  the stopping-point rule: 24,576/3 = 8,192/slot ≈ 1.2× the biggest
  BUDGETED turn (6,144+600) — the pre-budget 2.7× rule applied to the
  11.5k unbounded turn is exactly the failure mode M1-918 removes; the
  bound turn is the correct input, stated openly in the comment.
- **Steps, in order:**
  1. Write gpuClassServingShapeFitsTheDocumentedPromptFloor RED
     (today's 3/32768 fails the floor-formula arm only in that it
     predates the floor — the literals assert the NEW end state, so the
     test is RED on the literal 24576 vs the current 32768).
  2. Change the two set_secret literals in the GPU branch and land the
     derivation comment (P13).
  3. Update the single authorized ctx literal in the M1-905 drive.
  4. Docs (§7.8.3 paragraph + §7.7.2 row + the CPU-class floor note).
  5. Module run + `mvn verify`.
- **Controls to preserve (§10):** every other GPU-class write (timing,
  caps, cache per M1-920), the CPU-class write and its drives, the
  M1-908/M1-909 surfaces, and the printed-never-prompted posture.
- **Pitfall→mitigation:** P12→step 2's comment + acceptance item 3;
  P13→the formula arm + derivation greps; P14→blocked_by + the
  single-literal authorization.

## Definition of done

The GPU-class wizard write is the derived 3/24576 with the recorded
derivation; the floor-formula wiring pin guards it; the one authorized
pre-existing literal moves and every other drive is byte-untouched; the
docs carry the derivation and the CPU-class floor note; module tests +
`mvn verify` green.

## Verification

- P12 → acceptance item 3 (cap assertions byte-untouched + the
  arithmetic comment).
- P13 → gpuClassServingShapeFitsTheDocumentedPromptFloor's formula arm
  (a per-slot below floor+reply+headroom fails) + item 2's grep probe.
- P14 → the test-file diff shape (acceptance item 4).
- FAILURE-MODE coverage (beyond the reproduction) → acceptance item 5
  (the throughput-only regression mutation).
- acceptance items 2, 6, 7 → the grep probe, the docs diff-stat probe,
  the named module run + `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the floor itself (M1-918), the cache key
(M1-920), any CPU-class change (unmeasured; honestly documented
instead), the spec-decode surface (M1-908/909), measurement-record
edits (append-only), and the compose files. Pre-existing test
modification (§8): exactly the one ctx literal in
gpuHostGetsBenchmarkServingClassAndTiming, authorized by acceptance
item 4; any other conflicting drive is a start-hurdle escalation, not a
silent edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-921-gpu-default-ctx-derivation.md
```
