---
id: M1-650
title: "Sentinel-lint test endpoints"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/test/java/app/zcat/infochat/core/testsupport/TestEndpointSentinelGuardTest.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Non-test profiles. %laptop / %vps / %pi / %dev SHOULD point at a real local
    ollama on 11434 — that is the product working as designed. The guard
    evaluates the %test-effective value only and must not constrain any other
    profile.
  - >-
    %remote-llm's deliberate ABSENCE of infochat.llm.default.base-url
    (infochat-collector/.../application.properties:742 documents this: an
    unrouted task must refuse boot rather than silently resolve, per D56). The
    guard must not require a key to exist — only that IF a %test-effective
    value exists, it is the sentinel.
  - >-
    Hardcoded URL literals in Java sources. KrakenSnapshotSource hardcodes its
    endpoint, so a properties-file lint structurally cannot see it. Real
    testability wart, separate concern, already recorded in the handoff's
    REPO-WIDE HERMETICITY AUDIT. Do not widen this ticket to a source-literal
    grep.
  - >-
    quarkus.* datasource / http URLs. Those are DevServices-managed with dynamic
    loopback ports and are hermetic by a different mechanism; a sentinel rule
    would break them. The guard matches infochat.* keys only.
  - >-
    The SPI-coverage matrix (a guard asking whether each CDI-resolved SPI has a
    test double). Different technique, checks the bean half rather than the
    config half, and ranks below this one. Not this ticket.
  - >-
    Giving TestLlmProvider an explicit providerName(). Recommended AGAINST
    separately — it carries a silent name-keyed-config coupling
    (infochat.llm.TestLlmProvider.languages in TranslationPipelineIT).
  - >-
    Deleting or altering the existing sentinel line at
    infochat-provider/.../application.properties:432. M1-644 added it; it is
    already correct and is the model the other three sites copy.
acceptance:
  - >-
    A new TestEndpointSentinelGuardTest in
    app.zcat.infochat.core.testsupport scans every
    */src/main/resources/application.properties in the repo (locating the repo
    root the same way IntegrationTestNamingGuardTest already does), and for each
    property key matching infochat.*base-url computes the value that resolves
    under the %test profile — i.e. a %test.<key> line if present, else the
    unprofiled <key> line — and asserts it equals http://localhost:9.
  - >-
    The guard is FAIL-CLOSED: a key whose %test-effective value is a live
    endpoint fails, INCLUDING the case where no %test override exists at all and
    the unprofiled value leaks into the test profile. That second arm is not
    hypothetical — infochat-collector/.../application.properties:543 sets
    infochat.embeddings.base-url=http://localhost:11434/v1 with no %test line
    today, so a lint that only inspected literal %test. lines would miss it. A
    NEW infochat.*base-url key added by a future ticket without a %test override
    must turn this test red.
  - >-
    infochat-provider/src/main/resources/application.properties:357
    (%test.infochat.llm.default.base-url) is changed from
    http://localhost:11434/v1 to http://localhost:9.
  - >-
    infochat-collector/src/main/resources/application.properties:408
    (%test.infochat.llm.default.base-url) is changed from
    http://localhost:11434/v1 to http://localhost:9.
  - >-
    infochat-collector/src/main/resources/application.properties gains a
    %test.infochat.embeddings.base-url=http://localhost:9 line covering the
    unprofiled :543 key, mirroring the provider's :432 line.
  - >-
    LlmRouterStartupGuard's loopback / local-only validation still passes with
    the sentinel in place. http://localhost:9 IS loopback, so the local-only
    posture should be satisfied, but this MUST be demonstrated by the suite
    rather than assumed — the guard runs at every @QuarkusTest boot, so a green
    full suite IS the demonstration. If it instead fails boot, that is a real
    finding: escalate rather than weakening the guard.
  - mvn verify from the repo root is green with nothing listening on 11434
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/testsupport/TestEndpointSentinelGuardTest.java
  preserves:
    - all tests currently green on main
    - >-
      the three direct-construction embedder ITs and every existing @Alternative
      test double — this ticket changes only what an UNSTUBBED path would dial,
      so a correctly stubbed test must be byte-identically unaffected
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

# M1-650: Sentinel-lint test endpoints

## Context

M1-644 fixed one instance of "the test suite silently dialled a real daemon":
the provider's `EmbeddingProvider` was unstubbed, so provider ITs embedded
against whatever ollama happened to hold `localhost:11434`. The suite had been
green for weeks purely because a prod ollama was usually up; when the operator
stopped it, 15 router-concurrency ITs went red. Full diagnosis is in
`.scratch/HANDOFF-2026-07-18-router-it-baseline.md`.

M1-644 fixed the *bean* half (a `@Alternative` stub holds the CDI slot) and
added a single sentinel line as a tripwire. **The config half is still open, and
it is open in three places.** Verified directly on `main` @ `db200608`:

| Site | Current `%test`-effective value |
|---|---|
| `infochat-provider/.../application.properties:357` | `http://localhost:11434/v1` |
| `infochat-collector/.../application.properties:408` | `http://localhost:11434/v1` |
| `infochat-collector/.../application.properties:543` | `http://localhost:11434/v1` (no `%test` override at all) |

Exactly one endpoint in the whole repo is sentinelised today — the provider's
`:432`, which M1-644 added.

These three are *inert right now*, because an enabled `@Alternative` holds the
CDI slot in both modules, so `LlmRouter`'s name lookup misses and nothing can
dial them. **But inert-by-accident is precisely the M1-644 pattern.**
`LlmProvider` is one removed `@Alternative` away from re-running this incident,
and the configuration would happily let it. The point of this ticket is that
hermeticity should not depend on a bean registration nobody is guarding.

The escape vector M1-644 identified was "a spec commitment with no executable
check behind it" — `docs/spec/verification.md` §Test layers requires layer-3 ITs
to run against a fake LLM, and nothing enforced it. `TestDoubleWiringIT` made
that executable for two SPIs. This ticket makes it executable for the config
surface, exhaustively and with no hand-maintained list.

## Acceptance

See the YAML `acceptance:` list. In prose: add one fail-closed guard test that
computes the `%test`-effective value of every `infochat.*base-url` key across all
modules and requires it to be the unreachable sentinel `http://localhost:9`; fix
the three sites that currently violate it; prove the sentinel does not upset
`LlmRouterStartupGuard` by way of a green full suite.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing exclusions: this touches no
non-test profile (a real ollama on 11434 is CORRECT for `%laptop`/`%vps`/`%pi`/
`%dev`), does not require any key to exist (so `%remote-llm`'s deliberate absence
of a default base-url survives untouched), and does not chase hardcoded URL
literals in Java sources — a properties lint structurally cannot see those, and
pretending otherwise would give false assurance.

## Notes

- Adjacent code / the pattern to match: `IntegrationTestNamingGuardTest`, which
  already lives in the same `testsupport` package this ticket adds to, is the
  closest structural precedent — a plain JUnit (NOT `@QuarkusTest`) guard that
  locates the repo root and scans sibling modules' sources. **Read it for shape;
  this ticket does NOT modify it** (it is deliberately absent from
  `files_scope`).
- **Do NOT copy its allowlist shape.** That guard asserts the found-set is a
  SUBSET of a checked-in baseline (`unexpected.removeAll(baseline)`), which is
  fail-OPEN: a stale baseline entry is never detected, and its own javadoc
  concedes this. This ticket's guard needs no baseline at all — the assertion is
  a property of each value, not membership in a list — which is exactly why it
  was preferred over the alternatives.
- `http://localhost:9` is the repo's established unreachable sentinel
  (`OpenAiCompatibleEmbeddingProviderTest`, `AnthropicProviderTest`,
  `OpenAiCompatibleProviderTest`, `HttpProviderSharedPipelineTest`). Port 9 is
  the discard port; nothing will ever answer. Spelling is exactly
  `http://localhost:9` — no trailing slash, no `/v1`.
- Expect `infochat-messaging-adapter/src/main/resources/application.properties`
  to contain no `infochat.*base-url` keys today; the guard should still scan it,
  so a future one is caught.
- Why `risk: medium` on a config-only diff: it changes what EVERY test in both
  modules would dial on an unstubbed path. The expectation is zero behavioral
  change (a refused connection to :9 is the same failure class as a refused
  connection to :11434), but the blast radius is the whole suite, so the full
  verify is doing real work here rather than rubber-stamping.
- This was selected over two rejected alternatives, both recorded in the
  handoff §2.3: an SPI-coverage matrix (checks the bean half, would have passed
  GREEN on all three sites above) and a `TestLlmProvider.providerName()` rename
  (test-hygiene only, silent config coupling). A third — banning bespoke
  `HttpClient` outside `infochat-ssrf` — is strictly stronger than this ticket
  and would have made the original bug structurally impossible, but is a much
  larger change and deserves its own discussion.
