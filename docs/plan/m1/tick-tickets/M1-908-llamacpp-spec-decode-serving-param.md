---
id: M1-908
title: "Tracked llamacpp speculative-decoding compose keys, off by default"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  LlamacppWiringTest.composeExposesSpecDecodeKeysWithOffDefaults
  (to-be-written — converted at start: written first, run RED; the keys do
  not exist). Verified absence probe on this checkout (2026-08-23): `grep -n
  'SPEC|spec-draft|speculative' docker-compose.yml docker-compose.gpu.yml
  prod/scripts/*` returns ZERO matches — speculative decoding (probe-proven
  on the pinned image: solo 47.0 → ~75 tok/s at n-max 4,
  .scratch/VULKAN-MTP-PROBE-2026-08-23.md) is enableable only via the
  UNTRACKED overlay docker-compose.mtp.yml (PROD-UPGRADE-RUNBOOK-2026-08-23
  step 9), invisible to the wizard, pack.sh, and restore/clone flows.
analysis_ref: docs/plan/m1/tick-analysis/llamacpp-spec-decode-serving-param.md
blocked_by: []
files_scope:
  - docker-compose.yml
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    prod/scripts/4-llm.sh and any wizard prompt/write for spec decode — that
    is sibling M1-909 (blocked by this ticket). This ticket adds the compose
    surface only; an operator enables the feature by hand-writing the three
    keys into secrets.env (exactly what prod's rollout does).
  - >-
    prod/scripts/restore.sh, prod/scripts/pack.sh, prod/scripts/8-verify.sh —
    restore-side head recovery is M1-909; pack.sh needs no change (secrets.env
    is bundled verbatim); 8-verify.sh's WARN-only posture and exit contract
    are untouched (P9).
  - >-
    The llamacpp-embeddings service — no draft head exists for the nomic
    embedder (brief out-of-scope); the wiring test pins the spec keys'
    ABSENCE there (P4).
  - >-
    docker-compose.gpu.yml — spec decode is not GPU-bound; the overlay stays
    image/devices/group_add/ngl only (analysis Option C rejection). The test
    pins the overlay free of spec keys.
  - >-
    The ollama backend — ollama exposes no speculative-decoding knobs
    (binding user steer); nothing on the ollama surface changes.
  - >-
    Removing prod's untracked docker-compose.mtp.yml overlay — an ops action
    that rides this ticket's rollout (P6 dry-run), not this diff.
  - >-
    Any bot-side change (the thinking-template default, chat-template-kwargs)
    — the shipped surface already pins LLAMA_ARG_REASONING: "off"
    (docker-compose.yml:324); analysis Q4 found no shipped-surface
    obligation.
acceptance:
  - "REPRODUCTION closed: LlamacppWiringTest.composeExposesSpecDecodeKeysWithOffDefaults (test_plan.adds) passes — the generative `llamacpp` service in docker-compose.yml gains exactly three interpolated keys: `LLAMA_ARG_SPEC_TYPE: \"${INFOCHAT_LLAMACPP_SPEC_TYPE:-<off>}\"`, `LLAMA_ARG_SPEC_DRAFT_MODEL: /models/${INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF:-}`, `LLAMA_ARG_SPEC_DRAFT_N_MAX: \"${INFOCHAT_LLAMACPP_SPEC_N_MAX:-4}\"`. The test asserts all three forms on the generative service, their ABSENCE on llamacpp-embeddings (P4), and the absence of the plausible wrong names `LLAMA_ARG_SPEC_N_MAX` / `LLAMA_ARG_SPEC_MODEL` / `LLAMA_ARG_DRAFT_MODEL` across docker-compose.yml, docker-compose.gpu.yml and prod/scripts/ (P1; the surface-scan pattern of composeExposesParallelAndCtxKeysWithSafeDefaults, LlamacppWiringTest:263-270)."
  - "OFF-DEFAULT VERIFICATION (P2, ASSUMPTION the implementor verifies and records BEFORE landing the default): on the pinned image `ghcr.io/ggml-org/llama.cpp:server-vulkan-b9776`, a `llama-server --help` probe documents `--spec-type` / `--spec-draft-model` / `--spec-draft-n-max` (names + defaults), AND a boot probe with the off render (SPEC_TYPE at its off value, DRAFT_MODEL rendering `/models/`, N_MAX 4) starts the server with NO speculative init in the log and baseline decode; the recorded probe output rides the commit message. `<off>` is `none` if --help lists it as a --spec-type value (preferred — explicit), else the empty interpolation form. If verification disagrees with the drafted encoding, the default follows the verified server behavior and the correction is recorded in the commit message; the acceptance-1 test asserts the landed literal so a later edit flipping the default to an ACTIVE spec type fails the build (the non-vacuity mutation)."
  - "GENERIC SURFACE (P3, binding user steer): nothing model-specific ships — the acceptance-1 test asserts the literals `mtp-gemma` and `draft-mtp` appear nowhere in docker-compose.yml / docker-compose.gpu.yml / prod/scripts/ assignment lines, and a `grep -n 'LLAMACPP_SPEC' prod/scripts/4-llm.sh` probe confirms no head URL/SHA/filename constant is added anywhere. There is deliberately NO pinned-default head (contrast the LLAMACPP_GEN_GGUF_* constants): the operator owns the head choice."
  - "FAILURE-MODE (P2, negative): the acceptance-1 test asserts the OFF render is the unset-default render — a deployment that sets none of the three keys resolves SPEC_TYPE to the off value, so upgrading an existing llamacpp deployment cannot silently change its serving shape; the test fails if the `:-<off>` default is deleted or defaulted to an active type. Existing deployments gain three inert env entries only."
  - "FAILURE-MODE (P1, negative): the acceptance-1 wrong-name scan fails the build if an edit introduces the silently-ignored `LLAMA_ARG_SPEC_N_MAX` (without DRAFT) anywhere on the compose/wizard surface — llama-server itself would say nothing."
  - "DOCS (docs/design/07-deployment.md §7.8.3, §7.5): §7.8.3's operator-key table gains rows for INFOCHAT_LLAMACPP_SPEC_TYPE (off default; maps to LLAMA_ARG_SPEC_TYPE; names live-verified on the pinned image, P1 trap note), INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF (draft-head FILENAME in the shared models volume; maps to LLAMA_ARG_SPEC_DRAFT_MODEL at /models/<file>; empty = no head), and INFOCHAT_LLAMACPP_SPEC_N_MAX (default 4, the probe-recommended draft depth; inert when off); the §7.5 env table gains the matching row noting the keys are llamacpp-only (ollama has no spec-decode knobs) and off-by-default. Verification: `git diff --stat docs/` shows exactly docs/design/07-deployment.md."
  - "Controls preserved (§10): every pre-existing LlamacppWiringTest drive and static pin is byte-untouched — `git diff` on the test file shows only the new test method — and the M1-905 serving-key pins, M1-744 render pins, REASONING=off pin, and anti-downgrade image pins stay green (`./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest'`)."
  - "`./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest'` is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — composeExposesSpecDecodeKeysWithOffDefaults (static compose layer: three key forms on the generative service, embeddings-service absence, wrong-name surface scan, gemma/draft-mtp literal absence, off-default render pin)
  preserves:
    - all tests currently green on main
    - every pre-existing LlamacppWiringTest method, byte-untouched (the M1-905 drive-layer discipline)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.8.3
  - docs/design/07-deployment.md §7.5
decision_refs:
  - D49
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-908: Tracked llamacpp speculative-decoding compose keys, off by default

## Context

The 2026-08-23 probe proved draft-MTP speculative decoding on the pinned
Vulkan image (solo 47.0 → ~75 tok/s at n-max 4; prod-shape aggregate ~94 →
~104) and verified the pure-env mechanism live (`LLAMA_ARG_SPEC_TYPE` /
`LLAMA_ARG_SPEC_DRAFT_MODEL` / `LLAMA_ARG_SPEC_DRAFT_N_MAX`, 74.7 tok/s —
`.scratch/PROD-UPGRADE-RUNBOOK-2026-08-23.md` step 9). Today the feature is
reachable only through an UNTRACKED prod overlay; the shipped compose
carries zero spec keys (verified grep, reproduction above). This ticket adds
the tracked, generic, operator-settable surface — three env keys on the
generative llamacpp service, OFF by default — so the feature is a secrets.env
edit, not a hand-maintained overlay. Wizard automation and restore-side head
recovery are sibling M1-909. Analysis:
`docs/plan/m1/tick-analysis/llamacpp-spec-decode-serving-param.md`.

## Root cause

M1-905 exposed serving shape (parallel/ctx/caps) but not speculative
decoding; the probe post-dates it and its MTP rollout lived in the untracked
overlay as an explicit interim ("until a tracked ticket folds them into
gpu.yml/wizard", runbook step 9). The base generative service
(docker-compose.yml:288-361) declares MODEL/HOST/PORT/REASONING/N_PARALLEL/
CTX_SIZE and nothing else; the GPU overlay is image/devices/ngl only
(docker-compose.gpu.yml:29-52). No defect in existing code — an absent
config surface, reproduced by the RED wiring test.

## Pitfalls

Numbered with the analysis document:

- P1: **env-name trap** — a misspelled `LLAMA_ARG_*` name is silently
  ignored (the M1-905 `LLAMA_ARG_PARALLEL` lesson). Names here are
  live-verified by the probe; the test still pins exact names + wrong-name
  absence.
- P2: **the OFF encoding is unverified** — whether b9776 accepts
  `--spec-type none` (or tolerates an empty env value) is not in the
  evidence; acceptance 2 gates the default on a --help + boot probe on the
  pinned image, and the test pins the landed literal.
- P3: **gemma-specific leakage** — binding user steer: no shipped default
  head. The test greps the literals out of the shipped surface.
- P4: **keys on the embeddings service** — no nomic draft head exists;
  absence pinned.
- P6: **prod-parity dry-run** — the tracked design serves the head from
  `/models/<file>`; prod's overlay uses a `/heads` bind mount. The rollout
  dry-run (ops, post-implementation) must show only the admissible llamacpp
  deltas (env source, head path, dropped bind); every other service renders
  byte-identical. This ticket's design enables that migration; the migration
  itself is out of scope.
- P9: **8-verify.sh untouched** — zero diff there.
- P10: **scope fence** — NOT a model change; no acceptance re-run; the tests
  pin config shape, never throughput; one live bot sanity turn post-rollout
  is the ceiling.

## Approach

Derived from docs/design/07-deployment.md §7.8.3 (operator-settable
llama.cpp keys with pinned defaults — the M1-905 pattern) and
docs/spec/deployment.md §Deployment scenarios (D49: the operator owns
local-backend serving choices).

- **Files to touch:** `docker-compose.yml`,
  `infochat-llm-adapter/.../LlamacppWiringTest.java`,
  `docs/design/07-deployment.md`.
- **Steps, in order:**
  1. Run the acceptance-2 verification on the pinned image (--help value
     list/defaults; off-render boot probe) and record the output in the
     commit message. This decides the `<off>` literal BEFORE any file edit.
  2. Add the three interpolated keys to the generative `llamacpp` service,
     beside the M1-905 serving keys, with a comment in the M1-905 style
     (off-by-default rationale + the env-name trap pointer). Nothing else in
     the file changes.
  3. Write `composeExposesSpecDecodeKeysWithOffDefaults` (RED before step 2
     lands, per workflow §0 — the ticket's reproduction), asserting: the
     three exact forms; embeddings-service absence; wrong-name absence
     across both compose files + prod/scripts/; `mtp-gemma`/`draft-mtp`
     literal absence; the off-default literal.
  4. Docs: §7.8.3 table rows + §7.5 env-table row (llamacpp-only,
     off-by-default, generic — operator chooses the head).
  5. Module test run + `mvn verify`.
- **Controls to preserve (§10):** the M1-905 serving-key pins and their
  default renders, the M1-744 base/overlay separation (no spec keys in
  gpu.yml — pinned), the `LLAMA_ARG_REASONING: "off"` pin, the
  anti-downgrade image pins, and every pre-existing drive — the test file
  diff is one added method.
- **Pitfall→mitigation:** P1 → step 3's exact-name + wrong-name assertions;
  P2 → step 1 + acceptance 2 + the landed-literal pin; P3 → step 3's literal
  grep + no constants; P4 → step 3's embeddings-absence assertions; P6 →
  step 2's `/models/${...}` filename shape (restore-compatible, P7 for
  M1-909); P9/P10 → out_of_scope + the zero-diff guards.

## Definition of done

Every acceptance item verified by its named test/probe: the three keys on
the generative service with the verified off default; the wiring test green
with its absence scans; the recorded pinned-image verification in the commit
message; the two doc sections updated; pre-existing tests byte-untouched;
module tests + `mvn verify` green.

## Verification

- P1 → `composeExposesSpecDecodeKeysWithOffDefaults` — asserts the exact
  names; scans all compose/wizard surfaces for `LLAMA_ARG_SPEC_N_MAX`,
  `LLAMA_ARG_SPEC_MODEL`, `LLAMA_ARG_DRAFT_MODEL`.
- P2 → acceptance 2's recorded --help + boot probe; the test's off-literal
  pin catches the "default becomes active" mutation.
- P3 → the same test's `mtp-gemma` / `draft-mtp` absence assertions.
- P4 → the same test's embeddings-service absence assertions.
- P6 → rollout dry-run (`docker compose config` diff, old tuple vs new
  tuple): only the admissible llamacpp deltas; host-side, post-implementation,
  recorded in the rollout notes — not a build test.
- P9 → `git diff --name-only` shows no 8-verify.sh hunk.
- P10 → no test asserts tok/s; acceptance names config-shape tests only.
- Failure-mode (negative, beyond the reproduction) → the off-literal pin
  fails if the default flips to an active spec type, and the wrong-name
  surface scan fails if the silently-ignored `LLAMA_ARG_SPEC_N_MAX` spelling
  ever appears (acceptances 4 and 5).
- acceptance 6 → `git diff --stat docs/` shows exactly
  docs/design/07-deployment.md.
- acceptance 7 → the test-file diff is one added method; the named module
  run is green.

## Out-of-scope

The wizard prompt/writes and restore-side head recovery (M1-909, blocked on
this ticket); the embeddings service; the GPU overlay; ollama; the prod
overlay's removal (ops action riding rollout); bot-side thinking-template
behavior (the shipped `LLAMA_ARG_REASONING: "off"` pin already covers the
shipped-surface obligation — analysis Q4). No pre-existing test is modified;
any genuinely conflicting drive is a start-hurdle escalation, not a silent
edit (§8).
