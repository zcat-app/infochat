---
id: M1-909
title: "Wizard GPU-class spec-decode head delivery + clone recovery"
status: done
created: 2026-08-23
last_updated: 2026-08-24
flow: tick
reproduction: >-
  LlamacppWiringTest.gpuClassSpecDecodeOfferFetchesHeadAndMintsSecrets
  and RestoreWiringTest.restoreRecoversSpecDraftHeadFromPersistedUrl
  — written at start 2026-08-24 and run RED before any fix code (adapter:
  5 offer drives RED on the exact-fit-stdin EOF shift; provider: the
  source pin RED on the absent recovery leg), green after the fix.
  Verified on
  this checkout (2026-08-23): prod/scripts/4-llm.sh:503-720 (the llamacpp
  branch read end to end) contains no spec-decode prompt and writes no
  INFOCHAT_LLAMACPP_SPEC_* secret, and restore.sh's rehydrate_models
  (restore.sh:878-956) recovers only the generative + embeddings GGUFs — so
  a draft head enabling the M1-908 compose keys can be delivered only by
  hand, and a pack.sh/restore.sh clone (pack.sh:258-268 bundles only
  prod/runtime) silently loses the feature: the restored secrets.env would
  carry the spec keys but the head file is nowhere on the fresh host and
  nothing fetches it.
analysis_ref: docs/plan/m1/tick-analysis/llamacpp-spec-decode-serving-param.md
blocked_by:
  - M1-908
files_scope:
  - prod/scripts/4-llm.sh
  - prod/scripts/restore.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The compose keys themselves — sibling M1-908 (blocker) owns
    docker-compose.yml. This ticket mints the secrets.env values those keys
    interpolate; it does not edit either compose file.
  - >-
    A pinned-default draft head — binding user steer: the shipped surface
    carries NO default head, URL, or SHA (contrast LLAMACPP_GEN_GGUF_*). The
    wizard offers; the operator supplies. The spec-type prompt default
    `draft-mtp` is a prompt default the operator can overtype, not a shipped
    constant (P3).
  - >-
    CPU-class, ollama, and remote wizard paths — the offer fires only on the
    GPU-class llamacpp branch (gpu_on=1, the M1-905 probe seam). Off by
    default everywhere else; a CPU operator can still hand-write the keys
    (M1-908 docs).
  - >-
    The llamacpp-embeddings service and any embeddings draft head (no nomic
    head exists; brief out-of-scope).
  - >-
    prod/scripts/pack.sh — secrets.env is bundled verbatim (pack.sh:258-268),
    so the persisted URL/SHA ride the bundle with no pack.sh change (the
    M1-571 out-of-scope shape).
  - >-
    prod/scripts/8-verify.sh — WARN-only posture and exit contract untouched
    (P9); no freshness-leg change.
  - >-
    The optional-SHA posture for operator-supplied head URLs — operator-trusted
    TLS fetch (M1-394), preserved exactly as fetch_gguf implements it; this
    ticket does NOT make the head SHA mandatory (the M1-571 out-of-scope
    shape, redteam-CLEAN).
  - >-
    Removing prod's untracked docker-compose.mtp.yml overlay — ops action
    riding rollout (analysis P6 dry-run), not this diff.
  - >-
    Any acceptance/quality re-run — speculative decoding is main-model-verified
    output (probe verdict); one live bot sanity turn post-rollout is the
    ceiling (P10).
acceptance:
  - "REPRODUCTION closed (wizard half): LlamacppWiringTest.gpuClassSpecDecodeOfferFetchesHeadAndMintsSecrets (test_plan.adds) passes — on a GPU-class drive (INFOCHAT_LLAMACPP_GPU=on) answering the offer with a head URL + SHA + Enter (spec-type default draft-mtp) + Enter (n-max default 4), the wizard HEAD-preflights the URL (fake-curl log asserted), downloads via fetch_gguf BEFORE `up -d llamacpp` (fake-docker argv order asserted — P8), and mints exactly five secrets via set_secret: INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF=<basename>, _URL=<url>, _SHA=<sha>, INFOCHAT_LLAMACPP_SPEC_TYPE=draft-mtp, INFOCHAT_LLAMACPP_SPEC_N_MAX=4. The head answer is operator-supplied in the drive (a models.example.test URL) — no shipped default exists (P3)."
  - "OFF-DEFAULT (P3, brief Q3): LlamacppWiringTest.gpuClassSpecDecodeDeclinedWritesNoSecrets (test_plan.adds) passes — a GPU-class drive answering the offer with bare Enter writes NO INFOCHAT_LLAMACPP_SPEC_* line to secrets.env and records no head download in the fake-docker log; the test asserts the printed offer names \"off\" as the default. The shipped repo picks no head."
  - "FAILURE-MODE (CPU-side absence twin, P5): LlamacppWiringTest.forcedGpuOffWritesNoSpecDecodeSecrets (test_plan.adds) passes — an INFOCHAT_LLAMACPP_GPU=off drive asserts secrets.env contains no INFOCHAT_LLAMACPP_SPEC_* line at all. REQUIRED because the offer's stdin position sits among all-Enter answers: an offer that leaked onto the CPU branch would shift reads without tripping any pre-existing drive (every answer is an Enter) — only this explicit absence assertion catches that mutation."
  - "STAGED HEAD (P7): LlamacppWiringTest.specDecodeHeadStagedFromLocalPathPersistsEmptyUrl (test_plan.adds) passes — an absolute-path answer stages via stage_gguf (the drive asserts the argv-only cp from a read-only /stage mount, -u 0:0, pinned CURL_IMAGE), persists an EMPTY INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_URL, never persists the host path, and prints the staged-source not-re-fetchable disclosure (the M1-824/M1-825 shape)."
  - "FAILURE-MODE (integrity + validation, P11/P12): LlamacppWiringTest.specDecodeHeadShaMismatchFailsAndRemoves and LlamacppWiringTest.specDecodeNMaxRejectsNonInteger (test_plan.adds) pass — (a) a non-empty SHA mismatch fails the wizard and removes the head from the volume (the drive asserts the SAME fetch_gguf path ran — never a second download helper); (b) a non-integer n-max answer fails loud (the prompt_timing positive-integer shape, 4-llm.sh:127) and a junk head answer (not URL, not an existing absolute file) fails AT THE PROMPT with no curl/docker invocation recorded (the nonUrlNonFileAnswerFailsAtThePrompt shape)."
  - "REPRODUCTION closed (restore half, P7/P8): RestoreWiringTest.restoreRecoversSpecDraftHeadFromPersistedUrl (test_plan.adds) passes — restore.sh's rehydrate_models generative llamacpp leg, when the restored secrets.env carries a non-empty INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF, calls ensure_gguf with ALL THREE persisted args (read_dotenv_value INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_URL / _SHA — the M1-585 full-invocation pin shape) BEFORE `up -d llamacpp`, and skips the recovery entirely when the key is absent (pre-M1-909 bundles restore unchanged). The real download stays HOST validation (the file's gate-only scope)."
  - "DRIVE-LAYER DISCIPLINE (P5, corrected census per the 2026-08-24 hurdle refine; §8 test-modification authorization): the offer's reads land after the GPU ACL gate and serving-class writes, before the generative up — inside the GPU branch only. Authorized edits, exhaustively: (i) the FIVE pre-existing GPU-forced test methods (eight call sites) gain exactly ONE extra Enter in their stdin (the decline answer) and NOTHING else changes: gpuCapableHostMergesTheVulkanOverlayForBothLlamacppServices, gpuHostGetsBenchmarkServingClassAndTiming, ollamaEmbeddingsUpNeverMergesTheGpuOverlay, rootfulOrAclPresentGpuHostProceeds (two runWizard calls), gpuUpFailsLoudWhenTheContainerSeesNoDevice (three runWizardCapture calls). (ii) The 19 un-forced full-flow llamacpp methods gain exactly one INFOCHAT_LLAMACPP_GPU=off entry in their drive env map (stdin byte-untouched — the M1-905 carve-off comment on llamacppEmbeddingsShape... generalized: auto is GPU-class on any /dev/dri host, and these drives were written for the CPU class): customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery, localGgufPathIsStagedIntoTheVolumeWithoutDownload, locallyStagedGgufRunDisclosesTheRestoreConsequence, stagedEmbeddingsGgufDisclosesAndPersistsNoRefetchUrl, pinnedDefaultRunPrintsNoRefetchDisclosure, stagedGgufSkipsWhenAlreadyInTheVolume, stagedEmbeddingsGgufKeepsTheDimensionGate (the accepted arm; the declined arm dies at the confirm gate before the GPU branch), stagedGgufPathWithHashInNameKeepsFullBasename, fetchGgufWritesToTheVolumeComposeMounts, oneShotDownloadContainersUseTheHostNetworkPath, llamacppPreflightHeadChecksEveryGgufUrlBeforeDownload, ollamaEmbeddingsShapePreflightsOnlyTheGenerativeGguf, preflightDistinguishesNetworkFailureFromHeadRefusal (the refusal arm; the abort arms die at preflight), switchingAwayFromRemoteToLlamacppClearsStaleRemoteApiKeys, wizardAsksAndWritesChatReplyMode, replyModeOverrideWritesNative, replyModeInvalidAnswerFailsAndWritesNothing, replyModeRerunDefaultsToRecommendationAndDisclosesCurrent, ollamaEmbeddingsShapePointsAtOllamaNomicEndpoint (a llamacpp-GENERATIVE drive with ollama embeddings — the offer rides the generative GPU branch; missed in the hurdle census, caught by the first green run). Every other drive stays byte-untouched: llamacppEmbeddingsShapePointsAtEmbeddingsServiceNeverGenerativeGguf and forcedGpuOffKeepsCpuServingClass (already GPU=off), the two rootless-ACL-gate drives (fail at the gate, before the offer), the early-death drives (nonUrlNonFileAnswerFailsAtThePrompt, stagedGgufShaMismatchFailsAndRemoves, llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload, preflightFailsHardOnMalformedUrl, hostlessSchemeValidUrlAbortsLikeALocalPath — all exit before the GPU branch), and every CPU/ollama/remote drive. Verification: `git diff` on the test file shows only the new methods, the (i) single-Enter stdin inserts, the (ii) env-map entries, and the clarity_check-authorized draft-mtp pin narrowing, and `./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest'` is green."
  - "DOCS (docs/design/07-deployment.md §7.7.2, §7.10.1): the §7.7.2 step-4 row records the GPU-class offer (operator-supplied head URL/path + SHA + spec-type + n-max, default off; CPU class and other backends are never offered); the §7.10.1 restore bullet records that a spec-decode draft head is recovered from its persisted URL+SHA like a custom GGUF, and that a staged head (empty persisted URL) fails loud with the manual-staging recipe. Verification: `git diff --stat docs/` shows exactly docs/design/07-deployment.md."
  - "Controls preserved (§10): fetch_gguf/stage_gguf's argv-only, no-shell, -u 0:0, host-network, SHA-enforced-when-non-empty posture is REUSED, not re-implemented (P12 — the acceptance-5 drive's reuse assertion fails if a second download helper appears); set_secret's dotenv escaping + 0600 carries the new secrets; the write-before-boot order (M1-907 census) holds on both scripts; every pre-existing wiring-test assertion outside the authorized stdin inserts, the GPU=off env-map entries, and the clarity_check-authorized pin narrowing is byte-untouched — verified by `git diff` on both test files plus the green module runs."
  - "`./mvnw -B -pl infochat-llm-adapter,infochat-provider test -Dtest='LlamacppWiringTest,RestoreWiringTest'` is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — gpuClassSpecDecodeOfferFetchesHeadAndMintsSecrets, gpuClassSpecDecodeDeclinedWritesNoSecrets, forcedGpuOffWritesNoSpecDecodeSecrets, specDecodeHeadStagedFromLocalPathPersistsEmptyUrl, specDecodeHeadShaMismatchFailsAndRemoves, specDecodeNMaxRejectsNonInteger (+ junk-answer leg)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java — restoreRecoversSpecDraftHeadFromPersistedUrl
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (acceptance 7.i): one decline-Enter inserted into the stdin of the five GPU-forced pre-existing methods (eight call sites); nothing else in those methods changes
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (acceptance 7.ii, 2026-08-24 hurdle refine): one INFOCHAT_LLAMACPP_GPU=off entry added to the drive env map of the 19 un-forced full-flow llamacpp methods enumerated in acceptance 7; stdin byte-untouched
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (clarity_check 2026-08-24, user decision): composeExposesSpecDecodeKeysWithOffDefaults' draft-mtp absence assertion narrowed from prod/scripts/** to docker-compose*.yml only (mtp-gemma stays absent everywhere) — defect repair of M1-908's over-strict pin to the analysis P3 intent; nothing else in that method changes
  preserves:
    - all tests currently green on main
    - every pre-existing LlamacppWiringTest / RestoreWiringTest assertion outside the three authorized modifies rows, the fake docker/curl scripts, and every pre-existing drive's STDIN outside the five GPU-forced methods' single decline-Enter, byte-untouched
    - >-
      M1-908's composeExposesSpecDecodeKeysWithOffDefaults minus the one
      narrowed assertion authorized in modifies (clarity_check 2026-08-24):
      every other assertion in that pin — key forms, off defaults,
      embeddings absence, wrong-name absences, mtp-gemma absence — reads the
      same end state; this ticket edits no compose file.
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.7.2
  - docs/design/07-deployment.md §7.10.1
decision_refs:
  - D49
reviews:
  - round: 1
    date: 2026-08-24
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: FAIL; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS. One low finding: the offer's decline arm leaves previously-minted INFOCHAT_LLAMACPP_SPEC_* keys in secrets.env on a re-run, so the printed 'press Enter for off' is untrue on a host that earlier accepted a head (the decline must clear the five keys + print a confirmation). Reviewer falsified seven candidate findings (off-branch leakage; second download helper; staged host-path leak; restore ordering/helper; M1-908 pin-narrowing over-weakening; n-max-before-fetch; stdin shift outside the authorized census — all defeated with citations)"
    diff_stats: "round 1: 7 files, +405/−56 (log of record tick-test-M1-909-r1.log, full mvn verify 1990 tests / 1 pre-existing main-red ComfyUI failure, verified twice on clean main fb08d4aa; no source file newer than the log)"
  - round: 2
    date: 2026-08-24
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: WARN; SCOPE: PASS. Round-1 item 1 SATISFIED (decline arm sed+echo at 4-llm.sh:699-705 before the up; probe drive green, class 56→57, no other module counts changed). Reviewer falsified five candidates (off-branch key-wipe; CPU stale-keys promise; design-doc wording vs decline-clear; sed -i mode widening; drive vacuity). One LOW comment-only fix: drop the 'Round-1 rework: ' provenance prefix at LlamacppWiringTest.java:1484 — applied by driver; probes: grep 'Round-1 rework' no matches, ./mvnw -B -pl infochat-llm-adapter -am test-compile exit 0; fixed-tree snapshot .scratch/tick-fixes-M1-909.tree (8d4f4ae3)"
    diff_stats: "round 2 fix delta vs round 1: 153-line fix diff (+61/−1 full-diff growth, exactly the REWORK fix + ticket/board bookkeeping). Log of record tick-test-M1-909-r2.log: full mvn verify BUILD SUCCESS, 1990 tests, 0 failures, 0 errors (two earlier r2 attempts aborted on roaming rootless-docker port-bind testcontainers flakes, each victim green in isolation; final run after cool-down)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  question: >-
    2026-08-24 start self-check falsified test_plan.preserves: M1-908's
    composeExposesSpecDecodeKeysWithOffDefaults (LlamacppWiringTest) asserts
    draft-mtp appears NOWHERE under prod/scripts/, while this ticket's
    acceptance 1 prescribes an overtypable [draft-mtp] spec-type prompt
    default in 4-llm.sh — mutually exclusive. The analysis is internally
    inconsistent (P3's guard: "nowhere in prod/scripts/ outside comments";
    chosen approach: "spec-type (`[draft-mtp]`)"); M1-908 landed the stricter
    half.
  answer: >-
    User decision (option A): keep the [draft-mtp] prompt default; narrow
    M1-908's draft-mtp absence assertion to docker-compose*.yml only
    (mtp-gemma stays absent everywhere). The narrowing is a defect repair of
    M1-908's over-strict pin to the analysis's P3 intent — the guard targets
    the shipped compose surface pinning an active type, not GPU-branch opt-in
    overtypable prompt text. Authorized as an additional test_plan.modifies
    row below; acceptances 7 and 9 carry the carve-out.
  hurdle_2026_08_24: >-
    Hurdle trigger 1 (premise wrong), resolved via escalate->refine. The P5
    drive census counted only forced INFOCHAT_LLAMACPP_GPU=on drives as
    GPU-reaching, assuming un-forced drives (GPU unset -> auto) resolve
    CPU-class. Falsified empirically on this checkout's host (/dev/dri/
    renderD128 present): auto resolves gpu_on=1, and every un-forced
    full-flow drive's stdin is exact-fit, so the offer's read kills ~18
    further pre-existing methods, not zero (one fewer stdin line -> rc=1 at
    the next read). M1-905's own carve-out comment (LlamacppWiringTest,
    llamacppEmbeddingsShape...: "auto is GPU-class on a /dev/dri host ... the
    env pins the CPU class it was written for") documented the model but
    applied it to one drive only. User decision (option a, refine):
    authorize an INFOCHAT_LLAMACPP_GPU=off env entry (stdin byte-untouched)
    in each un-forced full-flow llamacpp drive enumerated in acceptance 7 —
    the M1-905 carve-out generalized — alongside the five forced methods'
    decline-Enters. Rejected alternatives: blanket decline-Enters (edits
    stdin the acceptance holds byte-untouched and couples 19 drives to the
    new prompt's position), offer only under explicit GPU=on (auto hosts
    incl. prod would silently never see the offer — false-green),
    EOF-tolerant read (the shifted read cascades into the reply-mode read
    anyway, and it papers over the M1-895 stdin contract).
---

# M1-909: Wizard GPU-class spec-decode head delivery + clone recovery

## Context

M1-908 (blocker) puts the tracked spec-decode keys on the generative
llamacpp service. This ticket teaches the wizard to DELIVER a draft head and
mint the keys' values — GPU-class opt-in only, default OFF, no shipped
default head (binding user steer) — and teaches restore.sh to recover the
head on a fresh-host clone from the URL+SHA the wizard persists (the M1-571
shape). Without it, the head is a 462-MB hand-staged file pack.sh cannot see
(pack.sh bundles only prod/runtime, pack.sh:258-268) and every deployment
re-invents the fetch. Analysis:
`docs/plan/m1/tick-analysis/llamacpp-spec-decode-serving-param.md`.

## Root cause

4-llm.sh's llamacpp branch (:503-720) provisions the generative + embeddings
GGUFs with fetch/stage/URL+SHA-persistence machinery that simply has no
spec-decode leg: no prompt, no fetch, no secrets. restore.sh's
rehydrate_models (:878-956) recovers exactly two GGUFs. Both gaps are
absences, not bugs — the machinery to close them already exists and is
reused, not duplicated.

## Pitfalls

Numbered with the analysis document:

- P3: **gemma-specific leakage** — no pinned-default head constant; the
  drive supplies its own URL. The `draft-mtp` spec-type prompt default is an
  overtypable prompt default, not a shipped constant.
- P5: **prompt-surface growth** — the offer's reads shift positional stdin
  for GPU-reaching drives; acceptance 7 (corrected by the 2026-08-24 hurdle
  refine: `auto` is GPU-class on any /dev/dri host, so the GPU-reaching set
  is the five forced methods PLUS every un-forced full-flow drive)
  authorizes one decline-Enter per forced call site and one GPU=off env
  entry per un-forced full-flow method. The CPU leak is invisible to
  answer-shifting (all Enters) — acceptance 3's explicit absence drive is
  the load-bearing guard.
- P7: **restore/clone parity** — head into the models volume + URL/SHA
  persisted + ensure_gguf recovery; staged heads persist empty URL with the
  disclosure and fail loud on restore (existing ensure_gguf branch).
- P8: **write/fetch-before-boot** — head fetched and secrets minted before
  `up -d llamacpp` in both scripts (M1-907 census).
- P9: **8-verify.sh untouched.**
- P10: **scope fence** — config-shape tests only; no throughput pins; one
  live bot sanity turn post-rollout.
- P11: **input validation posture** — URL/path classifier reuse, optional
  SHA, positive-integer n-max, fail-at-prompt on junk.
- P12: **security surface** — fetch_gguf/stage_gguf reuse keeps the
  argv-only/no-shell/SHA-enforced posture (M1-394); URL+SHA in secrets.env
  ride pack.sh bundles (M1-571 redteam CLEAN: operator-trusted TLS fetch,
  bundle tampering out of model, §SSRF binds the app not operator scripts).
  security_relevant: true so review runs SECURITY at full force.

## Approach

Derived from docs/design/07-deployment.md §7.7.2 (the wizard owns LLM
provisioning; GPU-class posture is probe-driven) and §7.10.1 (clone recovery
from persisted URLs), under docs/spec/deployment.md §Deployment scenarios
(D49: the operator owns local-backend serving choices).

- **Files to touch:** `prod/scripts/4-llm.sh`, `prod/scripts/restore.sh`,
  the two wiring-test files, `docs/design/07-deployment.md`.
- **Steps, in order:**
  1. 4-llm.sh, GPU branch (gpu_on=1), AFTER `gpu_rootless_acl_gate` and the
     serving-class set_secrets (:648-655), BEFORE the generative up (:664):
     the offer prompt (URL | absolute path | Enter=off). On an answer:
     classify with the existing URL/absolute-path/junk rule; SHA prompt
     (blank skips); spec-type prompt `[draft-mtp]` (non-empty token);
     n-max prompt `[4]` (positive-integer). preflight_gguf_url for URLs,
     then fetch_gguf/stage_gguf, then the five set_secret writes.
     staged_source_disclosure for staged heads. CPU class, ollama, remote:
     no prompt, no writes.
  2. restore.sh rehydrate_models generative llamacpp leg: after the
     generative ensure_gguf (:909-911), before `up -d llamacpp` (:913) —
     read INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF; when non-empty, ensure_gguf
     with the persisted _URL/_SHA (three-arg invocation). Absent key: skip.
  3. Tests (RED first per workflow §0): the new drives; the authorized
     single-Enter stdin inserts (five GPU-forced methods) and GPU=off env
     entries (18 un-forced full-flow methods); the M1-908 pin narrowing.
  4. Docs (§7.7.2 step-4 row, §7.10.1 bullet), then module tests +
     `mvn verify`.
- **Controls to preserve (§10):** enumerated in acceptance 9 — helper reuse
  (no second download path), set_secret channel, write-before-boot order,
  pre-existing assertions byte-untouched outside the authorized inserts.
- **Pitfall→mitigation:** P3 → no constants + operator-supplied drive URL;
  P5 → step 1 placement + acceptance 7 + acceptance 3's absence drive;
  P7 → steps 1+2 + the restore pin; P8 → step ordering + the argv-order
  assertion; P11 → classifier/prompt_timing reuse + acceptance 5; P12 →
  fetch_gguf reuse assertion + the mismatch-removal drive.

## Definition of done

Every acceptance item verified by its named drive: the GPU-class offer
fetching/staging/minting all five secrets in order; the declined and
CPU-forced absence drives; the staged-head disclosure drive; the
integrity/validation failure-modes; the restore three-arg recovery pin; the
authorized stdin inserts with everything else byte-untouched; the two doc
sections; module tests + `mvn verify` green.

## Verification

- P3 → `gpuClassSpecDecodeOfferFetchesHeadAndMintsSecrets` (operator URL in
  the drive) + `gpuClassSpecDecodeDeclinedWritesNoSecrets` (no default).
- P5 → acceptance 7's diff shape + `forcedGpuOffWritesNoSpecDecodeSecrets`
  (the CPU-leak mutation's only catcher — fails if the offer fires off the
  GPU branch).
- P7 → `specDecodeHeadStagedFromLocalPathPersistsEmptyUrl` +
  `restoreRecoversSpecDraftHeadFromPersistedUrl`.
- P8 → the offer drive's fake-docker argv order (download/cp before
  `up -d llamacpp`) + the restore pin's call-site assertion.
- P9 → `git diff --name-only` shows no 8-verify.sh hunk.
- P10 → no test asserts tok/s; config-shape pins only.
- P11 → `specDecodeNMaxRejectsNonInteger` (+ junk-answer leg: no curl/docker
  invocation recorded).
- P12 → `specDecodeHeadShaMismatchFailsAndRemoves` — mismatch removes the
  head and fails the wizard; the drive asserts the SAME fetch_gguf path (no
  second download helper).
- Failure-mode (negative, beyond the reproduction) → acceptances 3 and 5:
  a CPU-branch offer leak, a SHA-mismatched head, and a non-integer n-max
  each turn a named drive RED.
- acceptance 8 → `git diff --stat docs/` shows exactly
  docs/design/07-deployment.md (plus this ticket file + STATUS-TICK.md, the
  flow's own artifacts).
- acceptance 10 → the named module test run + `mvn verify`.
- Pre-existing main-red (recorded per workflow §4, untouched by this diff):
  ComfyUIClientTest.overCapResponseBodyIsRefusedBeforeAnyBytesAreRetained
  fails deterministically on clean main at fb08d4aa on this host (verified
  twice in isolation, 2026-08-24) — the stub pushes all 65536 bytes without
  observing the cap cut, an environment-sensitive shape last touched by
  M1-854. The r1 full verify ran 1990 tests with exactly this 1 failure;
  every module up to infochat-provider SUCCESS, both this ticket's test
  classes green inside the run. Filing a fix ticket stays the user's call.

## Round 1 rework

REWORK ITEMS (verbatim from `.scratch/tick-review-M1-909-r1.txt`):

1. Finding 1: make the offer's decline truthful on re-runs — add the decline
   arm's `sed -i '/^INFOCHAT_LLAMACPP_SPEC_/d' "$SECRETS_FILE"` (+ one echo
   line) to the `if [[ -n "$spec_override" ]]` at prod/scripts/4-llm.sh:661,
   and add the re-run decline drive, evaluated via EVALUATED-AS: the new
   LlamacppWiringTest.specDecodeDeclineOnRerunClearsStaleSpecKeys drive
   (pre-staged five keys, GPU=on, bare-Enter offer answer, rc == 0, zero
   INFOCHAT_LLAMACPP_SPEC_* lines afterward) plus the existing decline/CPU
   absence drives staying green in
   `./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest'`.

## Review observations

- (Round 1, RECOMMENDED-NEW-TICKET) ComfyUIClientTest.
  overCapResponseBodyIsRefusedBeforeAnyBytesAreRetained fails on unmodified
  main on this host (deterministic, twice in isolation at fb08d4aa): the
  stub pushes all 65536 bytes without observing the client's cap cut.
  TOUCHED-BY-THIS-DIFF: no; no DECIDE-BEFORE ordering. Filing a repair
  ticket stays the user's call.
  Classification update (round 2): the test ran GREEN in both round-2 full
  verifies on the same host — the r1 failure belongs to the host
  testcontainers flake class, not a deterministic main defect; the entry
  stands as filed with that caveat.

## Out-of-scope

The compose keys (M1-908); any shipped default head; CPU/ollama/remote
wizard paths; the embeddings service; pack.sh; 8-verify.sh; mandatory head
SHA; the prod overlay removal (ops, rides rollout — the analysis's P6
dry-run: stage the head into the models volume SHA-verified, move the three
values into prod secrets.env, drop the overlay from the tuple, and confirm
`docker compose config` shows only the admissible llamacpp deltas);
acceptance/quality re-runs. Test modifications are limited to acceptance 7's
authorized single-Enter stdin inserts (five GPU-forced methods), acceptance
7's authorized GPU=off env-map entries (18 un-forced full-flow methods), and
the clarity_check-authorized M1-908 pin narrowing; any OTHER
pre-existing-drive conflict is a start-hurdle escalation, not a silent edit
(§8).
