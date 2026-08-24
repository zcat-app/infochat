---
id: M1-921
title: "Make GPU context and memory configurable"
status: done
created: 2026-08-23
last_updated: 2026-08-24
flow: tick
reproduction: >-
  LlamacppWiringTest.gpuClassServingShapeFitsTheDocumentedPromptFloor
  (written at /tick start and run RED against the inherited 3/32768
  wizard behavior; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md).
  The wrong behavior it states: the GPU wizard has no operator override for
  its serving context and unconditionally writes the compiled-in value.
  Verified: prod/scripts/4-llm.sh:649-650 writes
  INFOCHAT_LLAMACPP_PARALLEL=3 / INFOCHAT_LLAMACPP_CTX=32768, while the
  proposed fit-derived default is 24576 and an explicit operator value must
  be honored instead of overwritten. The underlying fit evidence remains
  the 11,477-token HTTP 400 recorded in
  .agents/memory-local/prod-state-post-upgrade-20260823.md; the v2.0.0 chat
  legs used the test instance's P1 pin (single slot, ctx 8192, small prompts
  — .scratch/V2.0.0-FIX-VERIFICATION-FINAL-REPORT-2026-08-22.md:94-111).
analysis_ref: docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md
clarity_check: >-
  start 2026-08-24 pass — tick-lint 0 findings; M1-918 and M1-920 are done;
  the only in-flight tick ticket, M1-925, is measurement-only and disjoint.
  The serving-shape census re-run finds one GPU write site and one CPU write
  site, both covered by this ticket. All acceptance items are implementable
  without guessing. Corrected stale inherited citation: the GPU chat reply
  cap is at prod/scripts/4-llm.sh:976 (not :918); the value is 600.
  User-directed refinement: the fit-derived defaults are CTX=24576 and
  MEMORY=40g; both are operator overrides and are never unconditionally
  replaced when set. M1-920's seam tests are traced: the
  cache-key static test, GPU cache assertion, CPU absence assertion, and
  GPU-to-CPU stale-key drive remain covered; M1-918's provider tests are in a
  different Maven module and do not touch this seam.
blocked_by:
  - M1-918
  - M1-920
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
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
    evidence stands; this ticket makes the fit-derived default configurable,
    not the measurement records.
  - >-
    docker-compose.yml / docker-compose.gpu.yml — the wizard write is
    the change; the compose interpolation surface is untouched.
acceptance:
  - "REPRODUCTION closed: LlamacppWiringTest.gpuClassServingShapeFitsTheDocumentedPromptFloor passes — the GPU-class drive asserts the fit-derived defaults PARALLEL=3 / CTX=24576 (8,192/slot) and MEMORY=40g, covering the 6,144 prompt floor + 600 chat reply budget + ~1.4k template/estimate headroom; a paired INFOCHAT_LLAMACPP_CTX=32768 and INFOCHAT_LLAMACPP_MEMORY override is also asserted."
  - "DEFAULT DERIVATION RECORDED (P13): the 4-llm.sh write site and docs/design/07-deployment.md §7.8.3 carry the default derivation — inputs are M1-918's prompt floor 6,144, the GPU-class chat reply budget 600 (4-llm.sh:976), ~1.4k template/estimate headroom, the 2.7x stopping-point lesson, and unchanged default memory arithmetic (40g cap + Q6_K_XL weights + KV + 16 GiB cache + ~2 GB spec-decode head). Probe: grep -n '6144\\|prompt-token-budget\\|floor' prod/scripts/4-llm.sh returns the default derivation comment."
  - "CONFIGURATION COHERENCE (P12/P13): the defaults are CTX=24576 and MEMORY=40g, but explicit INFOCHAT_LLAMACPP_CTX and INFOCHAT_LLAMACPP_MEMORY values win as a paired operator resource choice; gpuHostGetsBenchmarkServingClassAndTiming's default memory/cpus/cache assertions stay byte-untouched."
  - "AUTHORIZED TEST MOVE (§8): the pre-existing M1-905 drive gpuHostGetsBenchmarkServingClassAndTiming's stale ctx expectation (comment and assertion, 32768) is updated to the default 24576; no other hunk in any pre-existing drive changes. Verification: git diff on the test file shows only the new default/override method plus that one expectation update."
  - "FAILURE-MODE (P13, negative): the reproduction test fails if the wizard ignores either paired override and falls back to CTX=24576/MEMORY=40g; its formula arm also fails a default below floor + reply + headroom."
  - "DOCS: docs/design/07-deployment.md §7.8.3's GPU-class paragraph and §7.7.2's step-4 row record the configurable CTX/MEMORY defaults, paired override, and derivation with citations; SETUP_GUIDE.md's llama.cpp section gives the same operator-facing defaults and override command; the CPU-class sentence notes that ctx 4096 is below the product's chat prompt floor. Verification: git diff --stat -- SETUP_GUIDE.md docs/design/07-deployment.md shows exactly those two docs."
  - "./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest' is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — gpuClassServingShapeFitsTheDocumentedPromptFloor (default literals + floor-formula arm + paired explicit override)
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (acceptance item 4): the stale ctx comment and single assertion in gpuHostGetsBenchmarkServingClassAndTiming move 32768 → the configurable default; default memory/cpus/cache assertions and every other pre-existing drive remain byte-untouched
  preserves:
    - all tests currently green on main
    - every other pre-existing LlamacppWiringTest assertion byte-untouched, including the M1-905 timing/cap pins, the M1-908 spec-key pins, M1-909's wizard-offer drives, and M1-920's cache pins (all read the same end state)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.8.3
  - docs/design/07-deployment.md §7.7.2
decision_refs:
  - D49
reviews:
  - round: 1
    date: 2026-08-24
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: WARN. One low fix item, commit-composition only (no code): exclude .agents/memory/rootless-docker-port-split.md from the M1-921 commit — it is a parallel-session (M1-923/924/925 stream, mtime 12:54) rootless-Docker memory correction tracing to no M1-921 acceptance item; it waits for its own process: commit. Commit stages exactly six files: the four files_scope files + this ticket file + STATUS-TICK.md; untracked parallel ticket files (M1-924/925/926) already excluded. APPLY NOTE: the fix edits zero working-tree lines — the parity check (comment/javadoc-only) and the test-compile guard are vacuous; the r1 green verify (tick-test-M1-921-r1.log) remains the log of record, no compiled source is newer than it. The item's EVALUATED-AS probe is commit-time by construction (git show --stat of the M1-921 commit lists no .agents/memory/ path and exactly the six expected files) — deferred to /tick commit, binding there. Reviewer falsified five candidates (7.7.2 row rewrite deleting wizard facts — all survive elsewhere in the doc set; formula arm vacuous — guarded by the >= floor assertion at LlamacppWiringTest.java:1102; CTX/MEMORY override not atomic — per-key honoring IS the ticket contract, SETUP_GUIDE.md:312-313 instructs both, operator env trusted per threat model; stale M1-905 comment head — relabeled on the next line; clarity_check :976 vs :981 line-number nit). RECOMMENDED-NEW-TICKET recorded under Review observations (CPU-class 4096 below the 6,144 floor), TOUCHED-BY-THIS-DIFF: no, no DECIDE-BEFORE — recorded only, no decision requested."
    diff_stats: "round 1: 7 files, +202/-115 over merge-base 48f4be3 (log of record .scratch/tick-test-M1-921-r1.log: full mvn verify BUILD SUCCESS, 7/7 reactor, 384 provider tests / 0 failures / 0 errors / 10 skipped, LlamacppWiringTest 60/60 green incl. gpuClassServingShapeFitsTheDocumentedPromptFloor; SETUP_GUIDE.md and this ticket file have mtimes newer than the log — doc/flow files only, no compiled source newer). Fixes applied post-verdict: none to the tree; .scratch/tick-fixes-M1-921.tree = reviewed tree da8f11c (copied from the r1 tree — the fix changes no lines; the procedure's git add -A before stash create was deliberately skipped: a parallel session is live with untracked M1-924/926 files in the shared tree and must not be staged)."
---

# M1-921: Make GPU context and memory configurable

## Context

The GPU wizard currently writes parallel=3, ctx=32768, and memory=40g
unconditionally (4-llm.sh:649-650), even though the Compose surface already
accepts INFOCHAT_LLAMACPP_CTX and INFOCHAT_LLAMACPP_MEMORY. The fit-derived
safe defaults proposed by the analysis are ctx=24576 (8,192 tokens/slot) and
memory=40g, while an operator may need to retain a larger context such as 32k
with a correspondingly larger memory cap. The original fit incident remains
the evidence: prod's 11,477-token turn exceeded the 11,008-token slot.
Analysis is recorded in analysis_ref.

## Root cause

M1-905's acceptance-1 discipline pinned the serving-key surface but
4-llm.sh writes the GPU-class context and memory as literals, so the existing
INFOCHAT_LLAMACPP_CTX and INFOCHAT_LLAMACPP_MEMORY overrides are not honored
by the wizard. The proposed defaults were derived from the prompt floor,
reply budget, headroom, and the existing memory arithmetic, but the paired
operator override behavior was not included in the original ticket.

## Pitfalls

Numbered with the analysis document; this ticket carries P12, P13, P14.

- P12: the default memory arithmetic must close inside the UNCHANGED 40g
  cap — weights ≈ 21 GB + KV(default ctx × slots) + 16 GiB cache (M1-920)
  + ~2 GB spec head (M1-909); an operator override may be larger and is
  intentionally the operator's resource decision.
- P13: never re-derive from throughput alone again — the default derivation
  names the floor, reply budget, headroom, and prod stopping-point lesson,
  while the wiring test proves the paired context/memory override is honored
  and the default formula cannot undersize the fit-derived context. Docs sync:
  §7.8.3 + §7.7.2; measurement records are append-only (cite, never edit).
- P14: sibling calibration — this ticket lands after M1-918 (the floor)
  and M1-920 (same files, same test); its authorized test move is the
  single ctx literal in the M1-905 drive, plus the new explicit-override
  arm, pre-authorized here so fixture calibration is honored.

## Approach

Derived from `spec_refs:`: §7.8.3's operator-key pattern and D49 (the
operator owns local-backend serving choices); the wizard keeps its
printed-never-prompted posture and accepts existing
INFOCHAT_LLAMACPP_CTX and INFOCHAT_LLAMACPP_MEMORY overrides.

- **Files to touch:** `files_scope`.
- **Pre-decided defaults:** per-slot floor = prompt floor (6,144) + chat
  reply (600) + template/estimate headroom (~1.4k) ≈ 8.1k, rounded to
  slot 8,192, so the default CTX is 3 × 8,192 = **24,576** and PARALLEL
  stays **3**. The safe GPU memory default stays **40g**, matching the
  existing Q6_K_XL + KV + cache + spec-decode-head arithmetic. Explicit
  INFOCHAT_LLAMACPP_CTX and INFOCHAT_LLAMACPP_MEMORY values win together over
  those defaults; the wizard remains printed-never-prompted.
- **Steps, in order:**
  1. Extend gpuClassServingShapeFitsTheDocumentedPromptFloor with the
     explicit INFOCHAT_LLAMACPP_CTX=32768 + INFOCHAT_LLAMACPP_MEMORY=48g
     override arm and keep the fit-derived default arm RED against today's
     wizard behavior.
  2. Replace the unconditional GPU ctx and memory writes with configurable
     default/override resolution and land the derivation comment (P13).
  3. Update the single authorized ctx literal in the M1-905 drive to the
     configurable default.
  4. Docs (§7.8.3 paragraph + §7.7.2 row + SETUP_GUIDE.md operator recipe +
     the CPU-class floor note).
  5. Module run + `mvn verify`.
- **Controls to preserve (§10):** every other GPU-class write (timing,
  caps, cache per M1-920), the CPU-class write and its drives, the
  M1-908/M1-909 surfaces, and the printed-never-prompted posture.
- **Pitfall→mitigation:** P12→step 2's paired memory default/override + acceptance item 3;
  P13→the default formula and override arms + derivation greps;
  P14→blocked_by + the single-literal authorization.

## Definition of done

  The GPU-class wizard uses configurable CTX and MEMORY with defaults
  24576 and 40g, plus PARALLEL=3; the floor-formula wiring pin and paired
  override drive guard both paths; the one authorized pre-existing literal
  moves and every other drive is byte-untouched; the docs carry the default
  derivation, operator recipe, and CPU-class floor note; module tests +
  `mvn verify` are green.

## Verification

- P12 → acceptance item 3 (paired default memory arithmetic, override
  assertions, and cap assertions byte-untouched).
- P13 → gpuClassServingShapeFitsTheDocumentedPromptFloor's default formula
  arm and explicit override arm + item 2's grep probe.
- P14 → the test-file diff shape (acceptance item 4).
- FAILURE-MODE coverage (beyond the reproduction) → acceptance item 5
  (the ignored-override and undersized-default mutations).
- acceptance items 2, 6, 7 → the grep probe, the docs diff-stat probe,
  the named module run + `mvn verify`.

## Out-of-scope

  Named in `out_of_scope`: the floor itself (M1-918), the cache key
  (M1-920), any CPU-class change (unmeasured; honestly documented instead),
  the spec-decode surface (M1-908/909), measurement-record edits
  (append-only), and compose-file changes. The existing Compose interpolation
  remains the transport surface; this ticket changes the wizard's default and
  override handling for context and GPU memory. Pre-existing test modification
  (§8): exactly the one ctx
  literal in gpuHostGetsBenchmarkServingClassAndTiming, authorized by
  acceptance item 4; any other conflicting drive is a start-hurdle
  escalation, not a silent edit.

## Review observations

- Round 1 (2026-08-24, reviewer RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF:
  no, no DECIDE-BEFORE): CPU-class llama.cpp serving shape is unmeasured —
  the wizard writes INFOCHAT_LLAMACPP_CTX="4096" (prod/scripts/4-llm.sh:718)
  while the product's prompt floor is 6,144, so a budgeted CPU-class chat
  turn cannot fit the slot; either measure a CPU-class shape that fits the
  floor or adopt an explicit wizard/doc refusal posture for chat on that
  class. Pre-existing and deliberately out of scope here (honest doc note
  landed in SETUP_GUIDE.md and §7.8.3); filing a ticket is the user's call.
  One-line version carried into the commit body.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-921-gpu-default-ctx-derivation.md
```
