---
id: M1-835
title: "Truthful boot signal for unresolvable LLM endpoints"
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  LlmRouterStartupGuardResolutionTest.unresolvableConfiguredEndpointSignalsBackendAbsentWithoutAborting
  (run RED 2026-08-15 against unmodified main via the public validator and
  an RFC 6761 .invalid host, .scratch/red-run.log: no ERROR line, and the
  false `embedding provider is remote ... will leave the host` WARN fired
  after the `treated as non-loopback` WARN) — a configured
  `infochat.embeddings.base-url` (or LLM base-url) whose host does not
  resolve at boot must produce an ERROR-level backend-absent line naming the
  config key, must NOT abort startup (the absent backend is a supported
  degraded mode, deployment.md:250-251), and must NOT be reported as a remote
  route; today the boot log carries WARN `DNS resolution failed for '%s'
  (treated as non-loopback)` (LlmRouterStartupGuard.java:826-829) followed by
  the FALSE `embedding provider is remote ... post title+summary will leave
  the host` disclosure (:286-289) — the mis-framed signal the 2026-08-13
  incident's boot log carried (.scratch/setup-hurdles.md item 13 LIVE
  INSTANCE addendum).
analysis_ref: docs/plan/m1/tick-analysis/backend-outage-surfacing.md
blocked_by: []
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardResolutionTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardLocalOnlyTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardRedactionTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardTest.java
  - docs/spec/deployment.md
  - docs/spec/llm.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    FAILING startup or readiness on an unresolvable endpoint — that variant
    contradicts the supported degraded mode (deployment.md:237-238, :250-251;
    security.md:1720) and is a spec-amendment decision for the user, not this
    ticket (analysis O1, rejected).
  - >-
    Any HTTP reachability probe at boot — the check is DNS resolution only; a
    resolvable-but-down backend is the runtime breaker's domain (M1-834).
    Boot stays bounded ("checked once at startup, not per call",
    llm.md:167-168).
  - >-
    Admin notification at boot — the boot signal is the ERROR log line (the
    spec's boot-time signal vocabulary, deployment.md:243-244); runtime
    admin-visible surfacing is M1-834.
  - >-
    The local-only FATAL branch's classification (unresolved stays off-host,
    fail-closed), the provider/model mismatch guard, the orphan-api-key WARN,
    and `router.assertAllTasksResolve()`.
  - >-
    prod/scripts/8-verify.sh, readiness payloads, restart policies and why
    the backend was down (batch D owns lifecycle); M1-818's shipped behavior.
acceptance:
  - "LlmRouterStartupGuardResolutionTest.unresolvableConfiguredEndpointSignalsBackendAbsentWithoutAborting (the reproduction, written and run RED at start) passes — a hand-rolled snapshot whose `infochat.embeddings.base-url` host the (stubbed) resolver fails feeds the non-local-only validator: assertDoesNotThrow, and the captured log carries an ERROR line naming `infochat.embeddings.base-url`, the redacted URL, and the degraded-mode consequence (analysis P9, P11; deployment.md §Bootstrap behavior on startup)."
  - "LlmRouterStartupGuardResolutionTest.unresolvableHostIsNotReportedAsRemoteRoute passes (analysis P11) — the same fixture asserts NO captured line pairs the dead URL with `leave the host`; a mutation that keeps the unresolved-is-remote classification in the disclosure paths fails this."
  - "LlmRouterStartupGuardResolutionTest.unresolvableHostUnderLocalOnlyStillRefusesStartup passes (failure-mode, analysis P10) — local-only=true plus the unresolvable embeddings URL throws LocalOnlyConflictException naming the embedding key: the privacy gate fails closed on a host that cannot be proven on-host (llm.md:162-173)."
  - "LlmRouterStartupGuardResolutionTest.eachDistinctHostIsResolvedOnce passes (analysis P12) — a counting stub resolver over a snapshot whose embeddings URL, shared default, and a per-task override coincide records exactly one resolution per distinct host and the log carries at most one backend-absent line per host."
  - "The pre-existing remote-disclosure tests are migrated to the resolver seam and stay green offline (analysis P10; engineering-rules §8 authorization given here): LlmRouterStartupGuardLocalOnlyTest methods asserting the `leave the host` WARN (:248-259, :273-282, :311-322) and LlmRouterStartupGuardRedactionTest's redaction cases (:94-114) currently rely on `api.openai.com` being non-loopback EVEN WHEN DNS FAILS — once unresolved stops implying `remote`, that reliance makes them DNS-dependent, so they are re-pointed at the seam with a stubbed public-IP resolution, asserting the SAME remote-route behavior as before. The migration changes test mechanics, never the asserted contract; the implementor re-runs the census grep in this ticket and disposes of every hit."
  - "LocalOnlyConflictStartupIT and the remaining guard test classes (LlmRouterStartupGuardTest, LlmRouterStartupGuardKeyDerivationTest, LlmRouterStartupGuardLanguageRouteDisclosureTest, LlmRouterStartupGuardLoopbackTest) stay green unmodified — Verify: `mvn -B -pl infochat-llm-adapter -am test -Dtest='LlmRouterStartupGuardTest,LlmRouterStartupGuardKeyDerivationTest,LlmRouterStartupGuardLanguageRouteDisclosureTest,LlmRouterStartupGuardLoopbackTest'` passes and `mvn -B -pl infochat-collector -am verify -Dit.test=LocalOnlyConflictStartupIT -DskipTests` passes, while `git diff --name-only -- infochat-llm-adapter/src/test infochat-collector/src/test` lists none of these classes — unless the census shows a DNS-failure reliance, in which case the same seam migration and authorization applies and is named in the commit message."
  - "Spec amendments recorded, exact wording approved by the user at implementation time (engineering-rules §12; M1-779 rides-the-diff shape — analysis P8): (a) docs/spec/deployment.md §Bootstrap behavior on startup's operator-visible-signals sentence (:243-248) gains the startup resolution ERROR line as a boot-time signal covering warm-corpus restarts; (b) docs/spec/llm.md §Per-task routing rules' guard-scan description (:179-198) states that an unresolvable configured endpoint is reported as absent (never aborted, never reported as remote) while remaining a conflict under local-only. Verify: git diff shows rule-text only — no dates, ticket IDs, or report citations in spec prose."
  - "The guard's javadoc names the tri-state classification and the fail-closed local-only rule (engineering-rules §11 — the existing `treated as non-loopback` WARN comment at LlmRouterStartupGuard.java:826-829 is re-read as a claim about the NEW code) — Verify: `grep -n 'tri-state\\|fail-closed' infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java` hits the class javadoc, and `mvn -B test -Dtest=DocumentedConfigKeyParityTest` passes with no new `infochat.*` config keys."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardResolutionTest.java
  preserves:
    - >-
      The local-only startup-refusal contract (LocalOnlyConflictStartupIT,
      LlmRouterStartupGuardLocalOnlyTest's throw-asserting cases) including
      unresolved-is-off-host under local-only.
    - >-
      The provider/model mismatch guard, the orphan-api-key WARN, the
      language-route disclosure, and the loopback primitives
      (LlmRouterStartupGuardLoopbackTest) — their asserted contracts are
      unchanged; only DNS-failure-reliant fixtures move to the seam.
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D54
  - D56
reviews:
  - round: 1
    date: 2026-08-15
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL (malformed base-url WARN re-logged per evaluation — parse-failure branch bypasses the per-host memoization), SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (comment-cap lint on ResolutionTest javadoc, informational), SCOPE PASS"
    diff_stats: "8 files, +376/-84"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-835-r1.txt
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS; round-1 item 1 SATISFIED"
    diff_stats: "8 files, +432/-85 (round-2 fix diff: +56/-1, HostRouteCache byUrl memoization + malformedSharedDefaultWarnsOncePerScan)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-835-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  lint: "tick-lint: 0 findings, 0 BLOCKERs"
  self_check: >-
    PASS. Census re-run at start: the grep hits are exactly the enumerated
    sites — RedactionTest :32/:38/:40/:94/:96/:114 (migrate, acceptance item
    5), LocalOnlyTest :42-43/:49/:257/:259/:282/:306/:320/:322/:342 (the
    three `leave the host` cases migrate; :342 is provider-name-based and the
    throw-asserting cases stay unmodified), LanguageRouteDisclosureTest :79
    (provider-name-based WARN, no DNS — unmodified per census row),
    LlmRouterStartupGuardTest :52/:204/:245 (mismatch-guard cases,
    posture-independent — confirm by re-running with the seam). No new hits.
    Citations spot-checked green: guard :826-829 WARN leg, :286-289
    embedding-remote disclosure, :277 isRemoteBaseUrl, :762 disclosure path,
    :412-418 language-route WARN (provider-name based, no DNS). No ambiguity
    raised. Module boundary: no tick ticket in-progress/in-review at start;
    parallel-safe. Migration-touch serialization N/A (migration_touch:
    false).
---

# M1-835: Truthful boot signal for unresolvable LLM endpoints

## Context

Live, 2026-08-13 (.scratch/setup-hurdles.md item 13 addendum): after a host
restart the ollama service stayed down; the DB-restored help corpus was warm,
so M1-818's boot-time corpus surface honestly read `true` (warm corpora make
zero embedding calls — corpus availability, never backend liveness,
deployment.md:247-248) and no traffic-independent signal named the dead
backend. The one boot-time signal that DID fire was mis-framed: with the
shipped default `infochat.llm.local-only=false` (provider
application.properties:408, collector :455), LlmRouterStartupGuard resolves
the embeddings base-url at startup, and an unresolvable host logs WARN `DNS
resolution failed ... (treated as non-loopback)` (:826-829) followed by the
false remoteness disclosure `embedding provider is remote ... post
title+summary will leave the host` (:286-289) — a privacy alarm for a backend
nothing can reach, instead of the actionable "your configured backend is
absent; the deployment is running in its documented degraded mode." Shared
analysis: docs/plan/m1/tick-analysis/backend-outage-surfacing.md.

## Root cause

`isLoopback`'s fail-closed classification — unresolvable ⇒ non-loopback — is
correct for the local-only privacy gate (an unprovable-on-host route must
fail closed, llm.md:162-173; pinned at LlmRouterStartupGuardLocalOnlyTest
.java:42-45) but is consumed unchanged by the NON-local-only disclosure paths
(:277 via isRemoteBaseUrl, :762), where "unresolvable" and "resolves to a
public address" are different operator situations requiring different words.
No leg of the guard ever states the backend-absent fact.

## Pitfalls

- P9: the new leg must never abort — a throw from the @Startup guard refuses
  the service start (deployment.md:319-320), converting a chat-tier
  convenience outage into a total outage (security.md:1720;
  deployment.md:250-251). ERROR log, then return.
- P10: preserve the local-only fail-closed classification — and the
  offline-stable test posture. Under local-only=true, unresolved stays an
  offender. Meanwhile the existing `leave the host` WARN assertions
  (LocalOnlyTest :248/:273/:311, RedactionTest :94/:114) are stable offline
  today ONLY because failed DNS counts as non-loopback; reclassifying without
  a resolver seam makes them DNS-dependent — the seam (a pure-validator
  overload taking the resolver; the public signatures keep the real
  `InetAddress::getAllByName`) plus the §8-authorized migration is the fix.
- P11: truthful framing is the point — in non-local-only mode an unresolvable
  host must not be reported as "remote ... will leave the host" (false); it
  gets the new ERROR line. Adding the ERROR while keeping the false WARN is a
  half-fix of the incident's diagnosis trap.
- P12: resolve each distinct host once (embeddings URL, shared default, and
  per-task overrides commonly coincide); the ERROR line uses
  `redactUserInfo` like every sibling line; no new config keys.
- P8 (shared with M1-834): the spec edits are rides-the-diff records (§12) —
  deployment.md's signals sentence and llm.md's guard-scan description gain
  rule text; the user approves the exact wording.

## Approach

Derived from spec_refs: deployment.md §Bootstrap behavior on startup commits
the degraded mode and its operator-visible signals; llm.md §Per-task routing
rules documents the guard's startup scan on both services (:179-185).

- **Files to touch** (guidance, not an allowlist): LlmRouterStartupGuard.java
  (resolver seam, tri-state classification, new ERROR leg, javadoc); new
  LlmRouterStartupGuardResolutionTest; the migrated cases in
  LlmRouterStartupGuardLocalOnlyTest / LlmRouterStartupGuardRedactionTest
  (and any further census hits); docs/spec/deployment.md; docs/spec/llm.md.
- **Steps in order**: (1) resolver seam + tri-state, keeping every public
  signature and the local-only branch byte-equivalent in behavior (P10);
  (2) the new ERROR leg + suppression of the remote-WARN for unresolved hosts
  in the non-local-only paths (P9, P11), with the new test class green;
  (3) the authorized test migration to the seam (acceptance item 5);
  (4) spec amendments (user-approved wording) + javadoc — last, they record
  the landed shape.
- **Controls to preserve (§10)**: the local-only FATAL semantics including
  unresolved-is-off-host and its refusal tests; the mismatch guard,
  orphan-api-key WARN, language-route disclosure; `assertAllTasksResolve()`
  ordering; `redactUserInfo` on every line; @Priority(150) boot ordering;
  "checked once at startup, not per call" — no per-call work added.
- **Pitfall→mitigation**: P9→assertDoesNotThrow reproduction; P10→seam +
  fail-closed test + authorized migration; P11→the no-`leave the host`
  assertion; P12→counting-resolver test; P8→acceptance item 7.

## Definition of done

Mirror of the YAML `acceptance:` list: the reproduction passes (ERROR line,
no abort); the dead host is never reported as a remote route; local-only
still refuses startup on an unresolvable route; each distinct host resolves
once; the DNS-dependent existing tests are migrated to the resolver seam with
the §8 authorization above and are green offline; the remaining guard tests
stay green unmodified or join the authorized migration; both spec amendments
land as user-approved rule text; the javadoc states the tri-state; no new
config keys; `mvn verify` from the repo root is green.

## Verification

- P9 → LlmRouterStartupGuardResolutionTest.unresolvableConfiguredEndpoint-
  SignalsBackendAbsentWithoutAborting (reproduction) — stubbed-failing
  resolver, non-local-only snapshot, assertDoesNotThrow + captured ERROR line
  naming the key.
- P10 → .unresolvableHostUnderLocalOnlyStillRefusesStartup (failure-mode) —
  local-only=true, same fixture, asserts the refusal; plus the migrated
  remote-WARN tests green on stubbed public-IP resolution.
- P11 → .unresolvableHostIsNotReportedAsRemoteRoute — asserts no captured
  line pairs the dead URL with `leave the host`; a mutation keeping the old
  classification fails it.
- P12 → .eachDistinctHostIsResolvedOnce — counting stub resolver over a
  coinciding-URL snapshot; one resolution, one line per host.
- P8 → acceptance item 7's git-diff probe (rule-text only).
- acceptance item 6 → the mvn runs and zero-diff git probe named there.
- acceptance item 9 → `mvn verify` from the repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. No hard fail on unresolvable endpoints (the
evidence's literal angle — a spec conflict, rejected as analysis O1; a
spec-amendment decision for the user). No HTTP reachability probing at boot
(DNS only; resolvable-but-down is the runtime breaker's domain, M1-834). No
admin notification at boot (the spec's boot signal vocabulary is the ERROR
log line). The local-only fatal branch, mismatch guard, orphan-key WARN, and
task-resolution assertion are untouched. 8-verify.sh, readiness payloads, and
lifecycle/restart policy (batch D) are untouched. This ticket modifies
pre-existing tests, authorized per engineering-rules §8 and enumerated at
acceptance item 5: the `leave the host` WARN assertions in
LlmRouterStartupGuardLocalOnlyTest (:248-259, :273-282, :311-322) and
LlmRouterStartupGuardRedactionTest (:94-114) move onto the resolver seam with
stubbed resolution — mechanics only; the asserted remote-route contract is
unchanged, and the migration makes them strictly more offline-stable.

## Census

Guard-test census (the classes whose behavior or fixtures this ticket can
touch) — `ls infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard*.java`
plus the collector IT; dispose of each at `start`:

- LlmRouterStartupGuardLocalOnlyTest — migrate the three `leave the host`
  cases (acceptance item 5); the throw-asserting cases stay unmodified.
- LlmRouterStartupGuardRedactionTest — migrate the two redaction cases
  (acceptance item 5); redaction contract unchanged (P12 reuses it).
- LlmRouterStartupGuardTest — mismatch-guard cases; expected unmodified
  (uses api.openai.com only where the non-loopback classification is
  posture-independent — confirm by re-running with the seam).
- LlmRouterStartupGuardLoopbackTest — pure `everyAddressLoopback` primitive
  pins; unmodified.
- LlmRouterStartupGuardKeyDerivationTest / ...LanguageRouteDisclosureTest —
  key-derivation and languages-axis pins; unmodified (the
  language-disclosure WARN at :412-418 does not resolve DNS).
- infochat-collector LocalOnlyConflictStartupIT — boot-level local-only
  refusal; expected unmodified (P10 preserves the classification it pins).

DNS-failure-reliance grep to re-run at `start`:
`grep -rn 'leave the host\|treated as non-loopback\|api.openai.com'
infochat-llm-adapter/src/test` — every hit needs a row above or a named
migration.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-835`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-835-backend-outage-surfacing-2.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.

## Round 1 rework

Verdict: REWORK (2026-08-15, round 1 of 2). Verdict file:
`.scratch/tick-review-M1-835-r1.txt`. Fix ONLY the items below, re-run
`mvn verify`, then `/tick review M1-835` (round 2).

1. Finding 1: evaluate each base-url once per scan so a malformed value
   logs its "malformed base-url" WARN exactly once — compute the shared
   default's route once before the loop in perTaskRoutes
   (LlmRouterStartupGuard.java:743-761) and pass the already-computed
   HostRoute into signalBackendAbsentIfUnresolved (:898-910, callers at
   :312 and :416), or memoize the malformed verdict in HostRouteCache
   (:865-885). Evaluated via the new
   LlmRouterStartupGuardResolutionTest.malformedSharedDefaultWarnsOncePerScan
   (exactly one "malformed base-url" record for a malformed shared default
   and for a malformed embeddings URL), run with
   `mvn -B -pl infochat-llm-adapter -am test -Dtest=LlmRouterStartupGuardResolutionTest`.

## Review observations

Round 2 (APPROVE) recommended-new-ticket entry, recorded per the review
dispatch (TOUCHED-BY-THIS-DIFF: no, no DECIDE-BEFORE — user reads, user
decides whether to file): the provider/model mismatch scan still re-logs
the "malformed base-url" WARN once per task inheriting a malformed shared
default (isLoopback's URISyntaxException branch, reachable only from
checkProviderModelMismatch; up to 7 duplicate WARNs on a boot with a
malformed default base-url and llama-family models). The same
once-per-distinct-URL discipline this ticket gave the local-only and
disclosure scans would fix it; the isLoopback WARN lines are byte-identical
to the fork point, so it is not a defect of this diff.
