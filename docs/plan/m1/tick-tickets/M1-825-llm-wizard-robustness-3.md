---
id: M1-825
title: "Disclose the no-refetch status of locally-staged GGUFs"
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  Probe (RED on main + M1-824): drive the staged-local-path flow and read the
  run output — nothing tells the operator that a locally-staged model is NOT
  re-fetchable on a fresh-host restore; and
  `sed -n '1156,1159p' docs/design/07-deployment.md` (the §7.10.1 restore
  bullet) covers re-fetch from the persisted URL but says nothing about a
  model that has none. The underlying contract hazard is verified in code:
  restore.sh:268-272 re-fetches whatever INFOCHAT_LLAMACPP_GGUF_URL holds, so
  a host path there fails a fresh-host restore mid-run (evidence item 7,
  live 2026-08-11). Test:
  LlamacppWiringTest.locallyStagedGgufRunDisclosesTheRestoreConsequence
  (written at `start`, ran RED on the M1-824 tree before any fix code:
  "the staged drive must disclose the restore consequence").
analysis_ref: docs/plan/m1/tick-analysis/llm-wizard-robustness.md
blocked_by: [M1-824]
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - restore.sh itself (batch A): its empty-persisted-URL branch already fails
    loud with the actionable manual-staging recipe (restore.sh:273-291) and
    needs no change for THIS contract; the "bundle predates M1-571"
    attribution reword is batch A's (analysis §P6 residual).
  - The staging mechanism (M1-824, landed — this ticket consumes its
    empty-URL persistence).
  - pack.sh (bundles secrets.env verbatim; nothing here changes that).
  - Making the SHA mandatory for staged files, or bundle-signing — the
    M1-571 redteam verdict (docs/plan/m1/redteam/M1-571-2026-07-05.md)
    keeps the optional-SHA posture unless the threat model widens.
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.locallyStagedGgufRunDisclosesTheRestoreConsequence — the staged-path drive's output tells the operator, at setup time, that this model cannot be re-fetched on a fresh-host restore, that the source file must be kept, and that restore.sh will print the manual-staging recipe; and secrets.env carries an EMPTY INFOCHAT_LLAMACPP_GGUF_URL with the host path string appearing NOWHERE in the file (the M1-824 day-one behavior, pinned here as the end state). RED at start: the staged flow prints no restore-consequence disclosure."
  - "URL-entered flow untouched (the hard constraint, analysis P6): LlamacppWiringTest.customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery (:271) and RestoreWiringTest.restoreRecoversCustomGgufFromPersistedUrl (:428) stay green UNMODIFIED — a URL override still persists URL+SHA and restore.sh still re-fetches from them."
  - "Embeddings twin (symmetric FAILURE-MODE): LlamacppWiringTest.stagedEmbeddingsGgufDisclosesAndPersistsNoRefetchUrl (to-be-written, test_plan.adds) asserts a staged EMBEDDINGS file drives the same disclosure and an empty INFOCHAT_LLAMACPP_EMBED_GGUF_URL; and LlamacppWiringTest.pinnedDefaultRunPrintsNoRefetchDisclosure (to-be-written, test_plan.adds) asserts the pinned-default run prints NO disclosure — a warning that cries wolf on the default path fails this item."
  - "Design record: docs/design/07-deployment.md §7.10.1's restore bullet (:1156-1159) gains the locally-staged case — a GGUF staged from a local path has no persisted URL, so a fresh-host restore fails loud with the manual-staging recipe exactly like a pre-M1-571 bundle — and §7.7.2's step-4 row sentence about URL/SHA persistence names the local-path caveat. Probes: `grep -n 'staged' docs/design/07-deployment.md` hits the §7.10.1 bullet; the M1-824 row grep still hits."
  - "mvn verify from the repo root is green; bash -n prod/scripts/4-llm.sh passes."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — locallyStagedGgufRunDisclosesTheRestoreConsequence (reproduction),
      stagedEmbeddingsGgufDisclosesAndPersistsNoRefetchUrl (item 3),
      pinnedDefaultRunPrintsNoRefetchDisclosure (item 3 negative).
  preserves:
    - all tests currently green on main
    - >-
      customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery and every
      RestoreWiringTest pin, unmodified — the URL-entered re-fetch contract
      is the constraint this ticket designs against, not a thing it edits.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
clarity_check:
  lint: "tick-lint: 0 findings, 0 BLOCKERs (after copying the gitignored analysis doc into the worktree, the M1-824/M1-829/M1-830/M1-834 convention)"
  self_check: >-
    PASS. No in-flight tick tickets (0 in-progress, 0 in-review) — the
    module-overlap check for worktree operation is vacuous. Citations
    spot-checked, all claims hold with post-M1-824-merge line drift:
    set_secret INFOCHAT_LLAMACPP_GGUF_URL at 4-llm.sh:521 (ticket cited
    :453 pre-merge), restore.sh:268-272 re-fetch branch exact, §7.10.1
    restore bullet at 07-deployment.md:1158, RestoreWiringTest
    restoreRecoversCustomGgufFromPersistedUrl at :453, LlamacppWiringTest
    customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery at :274.
    Analysis pitfalls P3/P6/P13 all landed with consistent numbering.
    blocked_by M1-824 (merged) seam tests traced: the six staged drives
    + the M1-571 pins all stay green under a print-only staged-branch
    change (no output assertion on the disclosure area exists to break).
    No replaces:, no superseded worktree of this surface.
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files, +117/-12"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-825-r1.txt
---

# M1-825: Disclose the no-refetch status of locally-staged GGUFs

## Context

The M1-571 re-fetch contract persists `INFOCHAT_LLAMACPP_GGUF_URL` into
secrets.env so restore.sh can re-fetch a custom model on a fresh host. A
locally-staged model (M1-824) has no re-fetchable URL — M1-824 persists an
EMPTY URL so restore.sh takes its pre-existing actionable fail-loud branch
instead of dying mid-restore on a host path (evidence item 7). What remains:
the operator is never TOLD, at setup time, that this model cannot survive a
fresh-host restore by itself, and the design record (§7.10.1) doesn't cover
the case. Shared analysis: `analysis_ref:`.

## Root cause

`set_secret INFOCHAT_LLAMACPP_GGUF_URL "$gen_url"` (4-llm.sh:453) is
source-agnostic by M1-571's uniform-persistence design; the staged flow's
empty-URL write (M1-824) makes the persistence contract-safe but silent. The
disclosure gap is what's left: an operator who stages a local file and later
restores onto a fresh host without keeping the source file discovers the gap
mid-restore — the same learn-after-the-fact shape as item 4, one layer down.

## Pitfalls

Numbered consistently with the analysis document.

- P6: the contract is the constraint — URL-entered models persist URL+SHA
  exactly as today; both pinning tests stay green unmodified. The staged flow
  writes the empty URL through `set_secret` (escaping + 0600, P13) and prints
  the disclosure; it does not special-case restore.sh (batch A).
- P13: the disclosure print must not echo the full host path into logs the
  run keeps — name the basename, not the directory (operator hygiene;
  mirrors the wizard's existing value-echo posture).
- P3: hermeticity — drives reuse the M1-824 seams; no real containers.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction + twin + negative drives — run RED (workflow §0).
  2. In 4-llm.sh's staged-source branch (M1-824), print the disclosure once
     per staged source: the model (basename) is not re-fetchable on a
     fresh-host restore; keep the source file; restore.sh will print the
     manual-staging recipe. No new prompt (a print, never a read — the
     family's positional-stdin constraint).
  3. §7.10.1 bullet + §7.7.2 row caveat (acceptance item 4).
  4. `bash -n`; `mvn verify` from the repo root.
- **Controls to preserve (§10):** the M1-571 keys and their consumers (both
  pinning tests green), `set_secret` as the only write path, restore.sh
  byte-identical, the optional-SHA posture (out_of_scope).
- **Pitfall→mitigation:** P6→step 2 touches only the staged branch + item 2;
  P13→step 2's basename-only print; P3→step 1.

## Definition of done

An operator staging a local GGUF is told at setup that the model is not
re-fetchable on a fresh-host restore and what to keep; secrets.env carries an
empty re-fetch URL for staged sources and the path string nowhere; the
URL-entered contract and every pin over it are unchanged; §7.10.1/§7.7.2
record the semantics; mvn verify green.

## Verification

- P6 → item 2 (both contract pins green, unmodified) + the reproduction
  drive's secrets.env assertions (mutation: persisting the path, or dropping
  the URL key's empty write, fails them).
- P13 → the reproduction drive asserts the output names the basename; review
  probe: `grep` the captured run output for the source directory — it must
  not appear in the disclosure line.
- P3 → the drives run under the existing fake seams; mvn verify green.
- Item 3 (failure-mode) → LlamacppWiringTest.pinnedDefaultRunPrintsNoRefetchDisclosure
  feeds the default path and asserts the disclosure is absent — a mutation
  printing it unconditionally fails this negative drive; the wizard must not
  warn on flows that ARE re-fetchable.
- Item 4 → the doc greps named in the acceptance item.

## Out-of-scope

Named in `out_of_scope`: restore.sh (batch A — its fail-loud branch already
does the right thing with an empty URL; only its cause attribution is stale,
recorded as the analysis §P6 residual), the staging mechanism (M1-824),
pack.sh, mandatory-SHA/bundle-signing hardening. No pre-existing test is
modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-825-llm-wizard-robustness-3.md
```
