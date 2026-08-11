---
id: M1-818
title: "Surface absent embedding backend on readiness + verify"
status: done
created: 2026-08-11
last_updated: 2026-08-11
flow: tick
reproduction: >-
  HelpCorpusReadinessCheckTest#failedCorpusBuildSurfacesAsInformationalReadinessData
  (written and run RED at start — .scratch/tick-repro-M1-818-red.log:
  the readiness surface did not exist, the test failed against the
  absent HelpCorpusBuildState / HelpCorpusReadinessCheck classes)
  — after a help-corpus build failure the Provider readiness payload must carry
  the per-corpus degraded entry while the check stays UP; today it carries
  nothing (observed live, docs/plan/live-e2e/2026-08-11-m1-784-817-report.md
  §F3: /q/health/ready UP alongside the 'CommandIntentIndexBuilder: failed
  building command_intent corpus' ERROR with the ollama service absent).
  Script-side companion probe: grep -n 'embed' prod/scripts/8-verify.sh
  returns no match — the wizard verify step probes no embedding backend, so
  the same dead-embedder deployment passed the deploy-time smoke gate green.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health/HelpCorpusBuildState.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health/HelpCorpusReadinessCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndexBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/TopicCorpusBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/HelpCorpusReadinessCheckTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/TopicCorpusRetrievalIT.java
  - prod/scripts/8-verify.sh
  - docs/spec/deployment.md
  - docs/spec/security.md
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE PERIODIC LLM PROBE. docs/spec/deployment.md §Health and observability
    promises a periodic ping against each configured LLM provider; this ticket
    is the boot-time down-payment only (corpus-build outcome at startup). The
    periodic leg stays on the observability backlog named in
    AdapterReadinessCheck's javadoc.
  - >-
    RUNTIME (POST-BOOT) EMBEDDING-FAILURE SIGNALS. Chat-time HelpLookupTool
    degrade, the LLM circuit breaker, and Collector-side embedding pipeline
    degrade are unchanged; nothing here observes failures after boot.
  - >-
    COLLECTOR READINESS. The Collector has no help corpora; CollectorReadinessIT
    and the Collector aggregate pin are untouched.
  - >-
    MAKING THE WIZARD FAIL ON A DEAD EMBEDDER. An absent embedding backend is a
    supported degraded mode (deployment.md §Bootstrap behavior on startup); the
    verify probe surfaces, never fails (exit code unchanged — P6). No change to
    restore.sh, apps.sh, or 7-apps.sh.
  - >-
    AdapterReadinessCheck LOGIC (a javadoc sentence only) and the
    messaging-adapters data map (ReadinessPayloadShapeTest untouched).
  - >-
    A SHARED SHELL LIB for the duplicated compose-URL constants — restore.sh
    already names that factor-out as a follow-up; this ticket duplicates with a
    sync note, the house pattern (engineering-rules §3).
acceptance:
  - "HelpCorpusReadinessCheckTest.failedCorpusBuildSurfacesAsInformationalReadinessData (the reproduction, written and run RED at start) passes — a holder snapshot carrying a failed corpus build yields a readiness response named help-corpora whose status is UP and whose data names the failed corpus (docs/spec/deployment.md §Bootstrap behavior on startup: an absent embedding backend is a supported degraded mode, not a readiness failure)."
  - "HelpCorpusReadinessCheckTest.payloadCarriesExactlyTheReportedCorpusOutcomes passes (P2, P8) — the data map carries exactly the reported corpus keys (the CommandIntentIndex.DOC_KIND / HelpTopicCorpus.DOC_KIND constants, command_intent / topic) with boolean values; an empty snapshot yields no data and UP; no exception text or free-form string ever enters the payload."
  - "CommandIntentIndexIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup and TopicCorpusRetrievalIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup pass (P4 failure mode) — a throwing embedder fed through each builder's onStart leaves the holder degraded for that corpus, never propagates out of onStart, and the degrade-don't-abort posture (ERROR log first, startup continues) is preserved (docs/spec/security.md §Failure handling: a complete LLM outage degrades quality, not safety)."
  - "CommandIntentIndexIT.unchangedCorpusWarmRestartReportsBuiltDespiteDeadBackend passes (P3 failure mode) — with a warm (hash-matching) corpus the builder performs zero embedding calls and reports built even though the injected embedder throws when called: the entry states corpus availability, never backend liveness."
  - "ProviderReadinessEndpointIT.readinessAggregateCarriesExactlyTheMessagingAdaptersDatasourceAndHelpCorporaChecks passes — the pre-existing aggregate pin (readinessAggregateCarriesExactlyTheMessagingAdaptersAndDatasourceChecks) is RENAMED and its expected check-name set grows to {messaging-adapters, Database connections health check, help-corpora}. This ticket explicitly authorizes that modification (engineering-rules §8): the new check is the deliberate, reviewed widening the pin exists to make loud (docs/spec/security.md §Trust boundaries item 6)."
  - "ProviderReadinessEndpointIT.readyPayloadCarriesHelpCorpusBuildOutcomesAfterBoot passes (P1, P8; boundary-sited at the HTTP payload) — after a %test boot (StubEmbeddingProvider builds both corpora) GET /q/health/ready returns 200 UP and the help-corpora check carries command_intent=true and topic=true."
  - "prod/scripts/8-verify.sh probes the embedding backend — Verify: bash -n prod/scripts/8-verify.sh green, plus the layer-4 live probe (M1-385 precedent, evidence in the commit message): on the test stack with the ollama service stopped, 8-verify.sh prints a WARN line naming the absent embedding backend and exits 0. Classification mirrors restore.sh rehydrate_models: infochat.embeddings.base-url == http://ollama:11434/v1 → ollama leg (ollama list must carry the infochat.embeddings.model value); == http://llamacpp-embeddings:8080/v1 → llamacpp leg (/v1/models answers from the Provider container's network view); any other value → visible probe-skipped note, never a silent pass (D54: embeddings always run on a local backend)."
  - "The verify summary stays honest (P6) — the WARN leg never changes the exit code, and the final all-components-healthy line is conditional on zero warnings: grep -n 'all components healthy' prod/scripts/8-verify.sh shows the line guarded by the warning count, and the item-7 live probe shows exit 0 with the WARN line printed."
  - "Spec amendments recorded, exact wording approved by the user at implementation time (engineering-rules §12; the M1-779 rides-the-diff shape — these record behavior the existing spec text already supports, they change no promise): (a) docs/spec/deployment.md §Bootstrap behavior on startup names the readiness entry alongside the ERROR log line and states its semantics (boot-time build outcome, covering content-hash-skipped warm restarts; not backend liveness; check stays UP); (b) docs/spec/deployment.md §Health and observability and docs/spec/security.md §Trust boundaries item 6 name the new disclosure. Verify: git diff shows the three spec edits are rule-text only — no dates, ticket IDs, or report citations in spec prose."
  - "The remaining truth-sites follow (P9) — Verify: grep -n 'HelpCorpusReadinessCheck' infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java (deferred-leg sentence restated: the periodic probe remains deferred, the boot-time leg now rides the help-corpora check); grep -n 'help-corpora' docs/design/07-deployment.md (the §7.12.1 payload-pin sentence names the new check and its shape pin; the §7.7.2 step-8 row names the embedding probe); grep -n 'readiness' SETUP_GUIDE.md (the dead-embedding troubleshooting row names the new recognition signals)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health/HelpCorpusReadinessCheckTest.java
    - ProviderReadinessEndpointIT.readyPayloadCarriesHelpCorpusBuildOutcomesAfterBoot
    - CommandIntentIndexIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup
    - CommandIntentIndexIT.unchangedCorpusWarmRestartReportsBuiltDespiteDeadBackend
    - TopicCorpusRetrievalIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup
  preserves:
    - >-
      ReadinessPayloadShapeTest.payloadCarriesExactlyAdapterNamesAndDropCounters —
      the messaging-adapters data map is untouched; the corpus entries live in
      their own check.
    - >-
      CollectorReadinessIT.readinessAggregateCarriesExactlyTheMessagingChannelsAndDatasourceChecks —
      the Collector aggregate is unchanged.
    - >-
      The existing corpus-builder posture tests (restartWithUnchangedCorpusPerformsNoEmbeddingCall,
      changedIntentTextIsReEmbedded, topicBuilderDeleteNeverTouchesCommandRows
      and siblings) — the wired constructor must not change their behavior.
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/deployment.md §Health and observability
  - docs/spec/security.md §Trust boundaries
  - docs/spec/security.md §Failure handling
decision_refs:
  - D49
  - D54
reviews:
  - round: 1
    date: 2026-08-11
    verdict: APPROVE-WITH-FIXES
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: WARN
      SCOPE-CHECK: PASS
    diff_stats: "16 files, 425 insertions(+), 42 deletions(-) (r1); fix delta comment-only (2 pointer prefixes)"
    verdict_file: .scratch/tick-review-M1-818-r1.txt
    test_log: target/tick-test-M1-818-r1.log (BUILD SUCCESS, full suite, 0 failures)
    fixes_applied: >-
      FIX ITEM 1 applied exactly: "(P6)" → "(M1-818 P6)" at
      prod/scripts/8-verify.sh:117 and "(P2, P8)" → "(M1-818 P2, P8)" at
      HelpCorpusReadinessCheckTest.java:45 — comment-only, zero executable
      lines, no docs/spec, docs/design or root *.md touched. Probe outputs:
      grep -nF '(P6)' prod/scripts/8-verify.sh → no match (exit 1);
      grep -n 'M1-818 P6' prod/scripts/8-verify.sh → line 117;
      grep -n 'M1-818 P2' HelpCorpusReadinessCheckTest.java → line 45;
      ./mvnw -B -pl infochat-provider -am test-compile → BUILD SUCCESS
      (.scratch/tick-fixes-compile-M1-818.log). Fixed-tree snapshot:
      .scratch/tick-fixes-M1-818.tree (7fc0e169).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-11
  result: pass
  notes: >-
    Every file:line citation spot-verified (both builders, the health
    pair, both payload pins, 8-verify.sh, restore.sh, 4-llm.sh,
    compose profiles, spec/design/SETUP_GUIDE sites). Census 1 exact:
    3 constructor sites, no production calls. Census 2 disposed: the
    grep also returns two same-phrase rows on unrelated surfaces
    (01-architecture.md:511 last_seen_at, StatusCommandHandler.java:64
    uptime — not corpus-failure signal sites, no edit), and its three
    variant-wording rows (AdapterReadinessCheck.java:42-46,
    SETUP_GUIDE.md:736, TopicCorpusBuilder.java:83-95) were verified
    by direct read. Two claim drifts noted for execution, neither
    changing scope: 8-verify.sh already defines RUNTIME_DIR (only
    CONFIG_FILE is new); CommandIntentIndexIT already carries
    embeddingBackendFailureAtStartupDoesNotAbort, so the new
    failure-mode cases complement it with the holder assertions.
---

# M1-818: Surface absent embedding backend on readiness + verify

## Context

Live E2E verification 2026-08-11
(docs/plan/live-e2e/2026-08-11-m1-784-817-report.md §F3) observed a Provider
that booted READY while its configured embedding endpoint pointed at an absent
`ollama` service: command-intent corpus construction failed at startup (ERROR
log), free-text help matching and topic answers were dead, and nothing on the
readiness payload said so — an operator probing `/q/health/ready` could not
distinguish that boot from a healthy one. The repo audit found the same
blindness on a second surface: the wizard verify step `prod/scripts/8-verify.sh`
polls `/q/health` on Collector and Provider only, so the dead-embedder
deployment also passed the deploy-time smoke gate green. The test-stack cause
was operational (a backup resurrection lost the ollama service), not a repo
defect; the F3 resolution addendum (report lines 144-149) accepted the degraded
mode, documented it, and approved "an informational readiness surface for the
boot-time corpus failure ... pending a tick-flow ticket." This is that ticket.
Per the user's direction both surfaces are ONE ticket: the verify step's
readiness-scan leg consumes exactly the entry the readiness surface adds, and
one spec-amendment wording set covers both.

Prior-art disposition (beyond the pitfalls): redteam M1-201 and M1-302 are
carried as P2; restore.sh's backend detection is carried as P7. Redteam M1-293
(CLEAN, Signal-adapter surface: mention overflow, display-name threading,
capability reconciliation) is falsified and retired — this diff touches no
identity, authorization, or adapter-inbound path. M1-682 (build guard on
transport-adapter `connected()` inheritance) is readiness-truthfulness-adjacent
but a separate surface — its own out_of_scope
(docs/plan/m1/tickets/M1-682-fail-the-build-when-a-transpor.md:25-29) excludes
readiness-payload work; retired. The eight stale worktrees under
.claude/worktrees/ (M1-776, M1-779, M1-795, M1-796, M1-804, M1-813, M1-814,
M1-817) were checked against their ticket subjects — ingest body text,
sanitizer scaffolding, empty-body guards, marker drop, image audit, sweeper,
resolution wording — none on the health or wizard-verify surface, no collision.

## Root cause

Two independent signal-absence defects, both verified:

1. **The boot outcome stops at the ERROR log.** Both help-corpus builders catch
   a failed build into an ERROR line and degrade — the supported degraded mode
   (CommandIntentIndexBuilder.java:126-145, catch at :131-144, log at :136-143;
   TopicCorpusBuilder.java:135-154, catch at :140-153, log at :147-152). Nothing
   carries the outcome further: the only Provider readiness check is
   AdapterReadinessCheck, and its data map is adapter-only —
   ReadinessPayloadShapeTest.java:25-51 pins exactly {adapter names,
   `<name>.dropped-inbound`} and nothing else. The signal that would have
   carried it was promised and never built: deployment.md §Health and
   observability's LLM-probe bullet ("a failing provider surfaces as a degraded
   readiness signal but does not fail readiness outright") is named as deferred
   in AdapterReadinessCheck.java:42-46 ("the degraded-LLM-probe leg remain[s]
   deferred to the observability backlog").
2. **The verify step never looks at the embedding tier.** 8-verify.sh runs
   exactly two `check` calls — Collector 8080, Provider 8081
   (8-verify.sh:86-87) — polling `/q/health` via `docker compose exec`
   (:53-65) and scanning only for DOWN sub-checks (:74). `grep -n 'embed'
   prod/scripts/8-verify.sh` returns nothing: no leg inspects the embedding
   backend, so the F3 deployment reached the green "all components healthy."
   line (:96).

The spec already commits to both halves of the fix: "An absent embedding
backend is a supported degraded mode, not a readiness failure" (deployment.md
§Bootstrap behavior on startup, the "Embedding-backed help corpora" paragraph,
lines 235-246) bounds the readiness STATUS, and the LLM-probe bullet bounds the
SIGNAL ("degraded readiness signal, does not fail readiness outright"). What the
spec text does not yet record — and what the rides-the-diff amendment adds — is
that the boot-time corpus outcome is one of those degraded signals: the
paragraph's sentence "The operator-visible signal is an ERROR log line"
(:243-244) is complete today and becomes incomplete with this change. That is a
record of new behavior the existing promises support (the M1-779 shape), not a
promise change — no SPEC-GAP.

## Pitfalls

- **P1: A degraded corpus must never fail readiness.** Neither the new check's
  own status nor the aggregate may go DOWN on a build failure. Spec: the
  degraded-mode paragraph above; rationale: the builders' javadoc
  (CommandIntentIndexBuilder.java:66-86) — aborting or failing readiness here
  converts a chat-tier convenience outage into a total outage of every security
  control the Provider carries, the blast-radius escalation security.md
  §Failure handling forbids ("A complete LLM outage degrades quality, not
  safety", security.md:1718).
- **P2: The payload widens only deliberately, and with fixed vocabulary.** The
  readiness payload is an unauthenticated disclosure surface (security.md §Trust
  boundaries item 6: it discloses operational topology; redteam M1-201
  out-of-model advisory named the adapter-topology reconnaissance; M1-302
  findings pinned both levels precisely so widening is loud). Hence: a NEW check
  (not entries folded into `messaging-adapters` — that check's data map is
  pinned to adapter semantics), its data map pinned by a new shape test, the
  aggregate pin updated with explicit §8 authorization, and the spec/design
  disclosure text amended in the same diff. And the entries are booleans from a
  fixed vocabulary — never exception text: the builders' failure messages can
  carry endpoint/host detail (e.g. the wrapped connect-refused cause), which
  would widen disclosure past the documented posture. A check contributed with a
  wrong/missing MicroProfile qualifier silently stays out of the aggregate —
  the updated aggregate pin catches that too.
- **P3: The entry means build outcome, never backend liveness.** A warm restart
  with an unchanged corpus performs ZERO embedding calls — the empty-batch
  short-circuit returns before the backend is touched
  (CommandIntentIndexBuilder.java:197-201, TopicCorpusBuilder.java:201-205) — so
  a dead backend and `command_intent=true` legitimately coexist. The readiness
  entry answers "is the corpus available?", and the verify step's DIRECT backend
  probe answers "is the backend serving?" — the two surfaces complement exactly
  here, and neither the spec wording, the javadocs, nor the script output may
  conflate them. (This is also why the verify probe cannot be replaced by
  scanning the readiness payload: in the restore.sh flow — the very flow the
  live incident came through — the DB-restored corpus is warm, so the entry
  reads built while the backend may be gone.)
- **P4: Wiring the report must preserve the degrade-don't-abort control
  (§10).** The diff re-touches both `onStart` bodies. Carried across, enumerated
  per engineering-rules §10: the RuntimeException catch breadth; the ERROR log
  line (the signal the SETUP_GUIDE.md:736 recovery row and the spec paragraph
  assume — it stays FIRST in the catch, the report second); startup-continues
  behavior; and the existing posture tests (test_plan.preserves). The report
  call is a map put that cannot throw, and must sit inside the existing try /
  catch so no new path lets a build failure escape `onStart` (an escaped
  StartupEvent observer failure refuses the service start — the exact abort the
  posture forbids).
- **P5: Pre-existing test modifications need explicit authorization (§8).** The
  diff modifies: (a) the Provider aggregate pin
  (ProviderReadinessEndpointIT.java:87-119) — renamed and its expected set grown
  (acceptance item 5 authorizes); (b) three direct-construction call sites that
  break when the builders' constructors gain the holder argument —
  CommandIntentIndexIT.java:75 and :165, TopicCorpusRetrievalIT.java:90 — each
  passes a throwaway `new HelpCorpusBuildState()` with zero assertion change
  (authorized here). Unauthorized, the reviewer's TEST-INTEGRITY-CHECK fails
  every one of these.
- **P6: The verify step surfaces, never fails.** Absent embedder is a supported
  degraded mode, so a probe failure is a WARN summary line with exit code
  unchanged. Biting points: M1-385's Notes convention ("surface per-adapter
  degradation separately rather than failing outright"); restore.sh propagates a
  non-zero verify exit as a cutover blocker ("resolve before cutover",
  restore.sh:808-811) — a failing probe would wrongly block a legitimate
  degraded clone; and the final "all components healthy." line (8-verify.sh:96)
  becomes a lie if printed under a warning — it must be conditional.
- **P7: The probe classification must stay consistent with restore.sh.**
  restore.sh:632-640 classifies `infochat.embeddings.base-url` against exactly
  `http://ollama:11434/v1` (ollama) and `http://llamacpp-embeddings:8080/v1`
  (llamacpp); per D54 embeddings always run locally, so those two are the only
  legitimate shapes. The verify probe reuses the same classification (constants
  duplicated with a sync note — restore.sh:51-58 documents why these scripts
  duplicate rather than source), the correct compose `--profile` flags per leg
  (the ollama service is `profiles: [dev, ollama]` — 4-llm.sh:330-336 probes it
  with `--profile prod --profile ollama`; a bare `--profile prod` exec would
  false-report), and a visible probe-skipped note for any unrecognized value —
  never a silent pass, never a hard fail.
- **P8: Boot-ordering honesty.** The holder starts empty; the check must be
  honest in that state (no data entries, status UP — no invented "pending"
  keys that would themselves widen the payload). The design relies on readiness
  not going UP before startup completes: design §1.4.3
  (docs/design/01-architecture.md:481-485) states `/q/health/ready` stays 503
  until every priority<500 startup bean is up, and the builders run at 150/151.
  The new IT pins the post-boot payload at the HTTP boundary; if the assumption
  were false the IT would flake and say so. The holder mirrors
  AdapterConnectionState's ConcurrentHashMap (AdapterConnectionState.java:21):
  writers are startup observers, the reader is the health HTTP thread.
- **P9: The "operator-visible signal" sentence lives in several places and must
  move together (§11).** CommandIntentIndexBuilder.java:83-85 ("The
  operator-visible signal is the ERROR log line"), the deployment.md paragraph's
  twin sentence (:243-244), AdapterReadinessCheck.java:42-46 (the deferred-leg
  claim), and the SETUP_GUIDE.md:736 row's recognition story all assert the
  signal surface; editing the code without them leaves stale claims, and editing
  only some of them drifts. The spec sentences ride the §12 amendment; the code
  javadocs and operator docs land in the same diff.

## Approach

Derived from `spec_refs`: the degraded-mode paragraph bounds the readiness
status (check stays UP), the LLM-probe bullet authorizes the degraded readiness
signal, §Failure handling forbids the abort, §Trust boundaries item 6 bounds the
disclosure. User direction groups both surfaces in one ticket.

**Mechanism.** A holder + a check, mirroring the adapter-readiness pair:

- `HelpCorpusBuildState` (new, `provider/health`) — ApplicationScoped,
  ConcurrentHashMap<String, Boolean>, `reportBuilt(corpus)` /
  `reportFailed(corpus)` / `snapshot()`, shaped on AdapterConnectionState
  (:19-47). No reset(): the corpus builders are not documented
  idempotently-re-runnable in the same JVM the way the adapter registry is
  (§7 — no machinery for scenarios that cannot happen).
- `HelpCorpusReadinessCheck` (new, `provider/health`) — `@Readiness
  @ApplicationScoped`, injects the holder; a static pure
  `evaluate(Map<String, Boolean>)` factored for unit testing exactly like
  AdapterReadinessCheck.evaluate (:82-105). Response named `help-corpora`,
  status ALWAYS UP, one boolean data entry per reported corpus keyed by the
  corpus DOC_KIND (`command_intent` — CommandIntentIndex.java:46; `topic` —
  HelpTopicCorpus.java:92). Empty snapshot → no data, UP (P8).
- Wiring: both builders gain the holder as a constructor parameter (their
  `@Inject` constructors resolve it). In each `onStart`: success path →
  `reportBuilt(DOC_KIND)` after `buildCorpus` returns; catch path → the ERROR
  log first (unchanged), then `reportFailed(DOC_KIND)` (P4).

**Rejected options** (the commit message's Alternatives considered):
(a) folding corpus entries into the `messaging-adapters` check — rejected:
corpora are not adapters, that check's data map is pinned to adapter semantics
(ReadinessPayloadShapeTest), and the M1-302 posture makes widening a named,
separate check the deliberate path; (b) making the verify step scan ONLY the
readiness payload instead of probing the backend — rejected at P3: the
restore-flow warm-corpus case reads built while the backend is gone, which is
precisely the incident's flow; (c) failing the wizard on a dead embedder —
rejected at P6 against the spec's supported-degraded-mode commitment;
(d) a periodic runtime probe — rejected as the deferred backlog leg (out of
scope), this ticket is the boot-time down-payment the brief scopes.

**The verify probe (8-verify.sh).** After the two existing `check` calls:
read `infochat.embeddings.base-url` and `infochat.embeddings.model` from the
runtime `application.properties` (a `read_prop` duplicated from
restore.sh:216-220 with the same can't-source rationale; 8-verify.sh gains the
`RUNTIME_DIR`/`CONFIG_FILE` variables it lacks, shaped on 4-llm.sh:29-30).
Classify per P7. Ollama leg: `docker compose ... --profile prod --profile ollama
exec -T ollama ollama list`, grep the model name (the SETUP_GUIDE.md:736
recovery row's own probe). Llamacpp leg: `docker compose ... exec -T
infochat-provider curl -fsS http://llamacpp-embeddings:8080/v1/models` — the
Provider container already carries curl (poll_health uses it, :57-58) and sits
on the compose network, so the probe sees the backend from the app's own view.
Then scan the Provider's already-captured `/q/health` body (check() holds it in
`body`, :59/:73 — hoist the Provider's copy rather than re-probing) for
`"command_intent": *false` / `"topic": *false`: that leg catches the
backend-up-but-boot-failed case the direct probe cannot. Every failure lands as
a WARN summary line naming the backend, the impact (free-text help matching /
topic answers degraded), and the SETUP_GUIDE.md:736 recovery row; exit code
unchanged (P6); "all components healthy." conditional on zero warnings.

**Files to touch** (guidance, not an allowlist):
new `HelpCorpusBuildState.java`, `HelpCorpusReadinessCheck.java`,
`HelpCorpusReadinessCheckTest.java`; edit `CommandIntentIndexBuilder.java`,
`TopicCorpusBuilder.java` (constructor + report calls + javadoc signal
sentences), `AdapterReadinessCheck.java` (deferred-leg javadoc sentence only),
`ProviderReadinessEndpointIT.java` (aggregate pin rename+grow, new boot test),
`CommandIntentIndexIT.java` + `TopicCorpusRetrievalIT.java` (constructor sites
+ new failure-mode cases), `prod/scripts/8-verify.sh`, `docs/spec/deployment.md`
(§Bootstrap paragraph + §Health endpoint-exposure bullet),
`docs/spec/security.md` (§Trust boundaries item 6),
`docs/design/07-deployment.md` (§7.12.1 pin sentence, §7.7.2 step-8 row),
`SETUP_GUIDE.md` (:736 row).

**Steps in implementation order** (each step green before the next):
1. Holder + check + `HelpCorpusReadinessCheckTest` (shape pin, UP-under-failure,
   empty-snapshot honesty) — the mechanism, unit-testable without wiring.
2. Builder wiring: constructor params, report calls, javadoc signal sentences;
   fix the three direct-construction sites; add the two builder failure-mode
   cases and the warm-restart-semantics case.
3. `ProviderReadinessEndpointIT`: aggregate pin rename+grow (authorized), new
   post-boot payload test — the HTTP-boundary proof the wiring holds end-to-end.
4. `8-verify.sh`: variables + read_prop, classification, both probe legs,
   readiness-body scan, WARN summary semantics.
5. Spec amendments (user approves exact wording, §12), design-note updates,
   SETUP_GUIDE row, AdapterReadinessCheck javadoc sentence — last, because they
   record the landed shape.

**Controls to preserve (engineering-rules §10), enumerated:** the builders'
degrade-don't-abort catch (breadth, ERROR-log-first ordering, startup-continues)
and the tests that pin the posture (test_plan.preserves); the
messaging-adapters data map pinned by ReadinessPayloadShapeTest (untouched);
AdapterReadinessCheck.evaluate logic (untouched); 8-verify.sh's existing
contract — /q/health polling, DOWN-scan → DEGRADED note, timeout → RED + exit 1
(8-verify.sh:74-83), the secrets-file guard (:44-47), `--env-file` passing
(M1-389); restore.sh untouched (its exit-status propagation at :776-777/:808-811
is why P6 bites).

## Definition of done

Mirror of the YAML `acceptance:` list: the reproduction test passes (failed
corpus build visible on the payload, check UP); the payload shape pin passes
(exactly the reported corpus keys, booleans only); both builder failure-mode
cases pass (degrade preserved, holder degraded); the warm-restart-semantics case
passes (built ≠ live); the Provider aggregate pin passes renamed and grown with
§8 authorization; the post-boot IT passes at the HTTP boundary; 8-verify.sh
probes the embedding backend (bash -n + live WARN probe, restore-consistent
classification); the summary stays honest (exit unchanged, healthy-line
conditional); the three spec edits land as user-approved rule-text records; the
remaining truth-sites (AdapterReadinessCheck javadoc, design §7.12.1/§7.7.2,
SETUP_GUIDE row) follow; `mvn verify` from the repo root is green.

## Verification

- P1 → HelpCorpusReadinessCheckTest.failedCorpusBuildSurfacesAsInformationalReadinessData
  — feeds an all-failed snapshot, asserts status UP with the degraded entries:
  readiness must not fail on a corpus build failure;
  ProviderReadinessEndpointIT.readyPayloadCarriesHelpCorpusBuildOutcomesAfterBoot
  asserts 200/UP at the endpoint. A mutation flipping the check to
  `status(anyCorpusBuilt)` fails both.
- P2 → HelpCorpusReadinessCheckTest.payloadCarriesExactlyTheReportedCorpusOutcomes
  — asserts the exact key set and boolean type (a mutation adding an exception
  message or a new key fails); the updated aggregate pin fails any unreviewed
  check-name change; acceptance item 9 verifies the disclosure text moved.
- P3 → CommandIntentIndexIT.unchangedCorpusWarmRestartReportsBuiltDespiteDeadBackend
  — warm rows + a throwing embedder: asserts zero embed calls AND holder built;
  a mutation that reports liveness instead of build outcome fails it. Acceptance
  item 9 verifies the spec sentence states the semantics.
- P4 → CommandIntentIndexIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup
  / TopicCorpusRetrievalIT.failedEmbeddingBackendReportsDegradedAndContinuesStartup
  (failure-mode cases) — throwing embedder through onStart: asserts the failure
  never propagates out of onStart, the holder is degraded, and the pre-existing
  posture tests (test_plan.preserves) stay green; a mutation that rethrows or
  drops the log-first ordering fails them.
- P5 → the ticket's own authorization text (acceptance item 5 + Root cause (b)
  census); the reviewer's TEST-INTEGRITY-CHECK reads it against the diff.
- P6 → live probe of acceptance item 7 (ollama stopped → WARN line, exit 0) +
  grep guard of item 8 (healthy-line conditional); a mutation setting exit_code=1
  on the WARN leg contradicts the recorded evidence.
- P7 → bash -n + live probe (ollama leg) of acceptance item 7; llamacpp leg:
  construction mirrors 4-llm.sh:330-336/restore.sh:632-640 (verified against
  those lines at review; a llamacpp-shape live probe if one is available — the
  test stack is ollama-shaped, so the llamacpp leg ships on mirror-construction
  evidence, stated here as the implementor's check).
- P8 → HelpCorpusReadinessCheckTest empty-snapshot case (no data, UP) +
  ProviderReadinessEndpointIT post-boot entries; if readiness could go UP
  pre-build the IT would flake and surface the ordering assumption.
- P9 → grep probes of acceptance item 10 at each truth-site; a mutation that
  edits the code but not the sentences leaves the grep targets absent.
- acceptance item 11 → `mvn verify` from the repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML `out_of_scope:` list. The periodic LLM probe
(deployment.md §Health and observability bullet 3) stays on the observability
backlog — this ticket's readiness signal is boot-time only, and nothing here
observes post-boot embedding failures (chat-time HelpLookupTool degrade and the
breaker are untouched). The Collector's readiness aggregate is untouched (no
corpora there). The wizard is NOT made to fail on a dead embedder: the probe is
a visibility leg, exit semantics unchanged (P6), and restore.sh / apps.sh /
7-apps.sh are not edited. AdapterReadinessCheck's logic is untouched — one
javadoc sentence only. The messaging-adapters data map is untouched. The
duplicated compose-URL constants are NOT factored into a shared lib here
(restore.sh:51-58 owns that follow-up; §3). This ticket modifies pre-existing
tests, authorized per engineering-rules §8 and enumerated at P5: the Provider
aggregate pin (renamed, expected set grown) and the three builder
constructor call sites (throwaway holder, zero assertion change).

## Census

Two mechanical enumerations, disposed:

1. Direct builder constructions (the constructor change reaches all of them):
   `grep -rn 'new CommandIntentIndexBuilder(\|new TopicCorpusBuilder('` →
   - infochat-provider/src/test/.../help/CommandIntentIndexIT.java:75 — fix (throwaway holder)
   - infochat-provider/src/test/.../help/CommandIntentIndexIT.java:165 — fix (throwaway holder)
   - infochat-provider/src/test/.../help/TopicCorpusRetrievalIT.java:90 — fix (throwaway holder)
   (no production call sites — CDI constructs both builders.)
2. "Operator-visible signal" truth-sites (P9):
   `grep -rn 'operator-visible signal' docs/spec docs/design SETUP_GUIDE.md infochat-provider/src/main` →
   - docs/spec/deployment.md:243-244 — spec amendment (acceptance item 9)
   - CommandIntentIndexBuilder.java:83-85 — javadoc update (step 2)
   - AdapterReadinessCheck.java:42-46 (deferred-leg variant) — javadoc sentence (step 5)
   - SETUP_GUIDE.md:736 (recognition story) — row update (step 5)
   - TopicCorpusBuilder.java:83-95 failure-posture javadoc — gains the same one
     clause its twin gets (step 2).

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-818`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-818-surface-absent-embedding-backend.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
