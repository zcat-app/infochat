---
id: M1-097
title: "Nostr event verification + kind filter"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-096
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifier.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSignedEventFixtures.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/pom.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-provider/** — no provider changes
  - cross-relay dedup — M1-098
  - per-relay degradation — M1-099
  - kind-6 linking — M1-100
  - SSRF on wss:// — M1-101
  - any Nostr key handling, signing, or publishing — the Collector is forever read-only per D38
acceptance:
  - "NostrEventVerifier verifies BIP-340 Schnorr signatures on secp256k1 using Bouncy Castle — verifies event.sig against event.id using event.pubkey"
  - "NostrEventVerifier.verify(NostrEvent) returns true only when the event id matches the SHA-256 of the canonical event JSON AND the signature is valid against that id and the claimed pubkey"
  - "NostrStreamSource applies verification before Stage 1: signature verification → kind allowlist → outbox write, per security.md §Nostr"
  - "Failed verification → event dropped, failed-sig counter incremented, never enqueued — no admin notification per failure"
  - "Kind allowlist permits only kinds 1 (text notes) and 6 (reposts); all other kinds are dropped without parsing after signature verification passes"
  - "NostrEventVerifierTest.validSignature_passes passes — a known-good Nostr event with a valid BIP-340 signature is accepted"
  - "NostrEventVerifierTest.invalidSignature_rejected passes — an event with a tampered signature is rejected"
  - "NostrEventVerifierTest.idMismatch_rejected passes — an event whose id does not match the SHA-256 of its canonical JSON is rejected"
  - "NostrEventVerifierTest.tamperedContent_rejected passes — an event whose content was modified after signing is rejected (id mismatch)"
  - "NostrStreamSourceVerificationIT.unverifiedEventsDropped passes — a fake relay sends events with invalid signatures; none reach the outbox; the failed-sig counter increments"
  - "NostrStreamSourceVerificationIT.disallowedKindDropped passes — a fake relay sends a kind-7 event with a valid signature; it is dropped at the kind filter, not enqueued"
  - "NostrStreamSourceVerificationIT.kind1AndKind6Accepted passes — kind 1 and kind 6 events with valid signatures reach the outbox"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSignedEventFixtures.java
  modifies:
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
      why_safe: |
        Existing tests use literal "sig" strings as the signature field; pre-verification
        they passed because no signature gate existed. M1-097 introduces the gate, which
        would silently regress these tests unless the fixtures are upgraded to real
        BIP-340-signed events. Modification preserves the test intent verbatim:
        connectsToAllConfiguredRelays (REQ-handshake), receivesAndDeliversEvents
        (event-delivery), reconnectsWithSinceOnDisconnect (reconnect-since-cursor),
        stopDrainsAndClosesConnections (stop-drain) — only the event payloads'
        sig/id/pubkey fields change to be cryptographically valid (sourced from
        NostrSignedEventFixtures). No assertion weakened; no test disabled; no gate
        bypassed.
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
      why_safe: |
        Same root cause as NostrStreamSourceTest.java: endToEndWithFakeRelay sends an
        unsigned NostrEvent that would fail the new BIP-340 gate. Replace the fake
        payload with a real signed fixture from NostrSignedEventFixtures; the assertion
        "the post carries the Nostr event id as upstream_identifier" remains
        substantively unchanged (the literal expected id updates to the fixture's id).
        Test intent (end-to-end persist + eval-queue emit) preserved verbatim.
  preserves:
    - all tests currently green on main, with NostrStreamSourceTest and NostrStreamSourceIT modified per the modifies block above (test intent preserved, only event-payload fixtures upgraded to real BIP-340 signatures)
spec_refs:
  - docs/spec/security.md §Per-source trust boundaries
  - docs/spec/security.md §Nostr (StreamSource, v1)
decision_refs:
  - D38
  - D10
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 954
      removed: 31
escalations:
  - date: 2026-05-30
    reason: budget-breach
    reviewer_verdict_excerpt: |
      About to touch infochat-collector/pom.xml to add org.bouncycastle:bcprov-jdk18on:1.80
      (required by acceptance #1 "using Bouncy Castle"). pom.xml is not in files_scope and
      no margin exists in files_budget (4 files listed, budget 6, but pom is outside the
      explicit scope allowlist regardless of budget headroom). Refine to add pom.xml to
      files_scope and adjust files_budget accordingly.
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      During implementation survey discovered that existing NostrStreamSourceTest.java
      (5 NostrStreamSource constructor sites, 3 sendEvent calls with unsigned events
      using "sig" as a literal) and NostrStreamSourceIT.java (1 constructor call,
      1 unsigned sendEvent) would silently regress under the new BIP-340 gate. These
      files were not in files_scope and test_plan had no modifies: section.
      No design alternative avoids modifying them (cycled through no-op verifier
      injection, static utility verifier, lift-to-RelayConnection, signature-shape
      conditional, system-property toggle, instanceof check — all either fail to fix
      the unsigned-events problem or install a security backdoor). Refine to add
      both existing test files plus a new shared NostrSignedEventFixtures.java to
      files_scope, authorize modifications via test_plan.modifies with why_safe, and
      bump files_budget accordingly.
revisions:
  - date: 2026-05-30
    reason: budget-breach
    snapshot:
      files_budget: 6
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifier.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - date: 2026-05-31
    reason: budget-breach
    snapshot:
      files_budget: 7
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifier.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
        - infochat-collector/pom.xml
      test_plan_adds:
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
      test_plan_modifies: absent
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-31
    verdict: CLEAN
    base: eaa636497b32ac2c45d8ddb51a26c86d56366314^
    head: eaa636497b32ac2c45d8ddb51a26c86d56366314
    verdict_file: docs/plan/m1/redteam/M1-097-2026-05-31.md
    findings_count: 0
    out_of_model_count: 4
    note: |
      Diff installs the Nostr trust boundary (BIP-340 Schnorr verification
      + kind-1/6 allowlist) at NostrStreamSource.enqueueInbound. Threat-actor
      mapped each promise in security.md §Nostr to the delivered code:
      ordering (signature → kind → outbox), failed-sig counter, forever-read-only,
      and trust-anchor-is-pubkey-not-relay all hold. Forged signatures, tampered
      content, id-mismatch with valid Schnorr, JSON-injection through
      pubkey/content/tags, and constant-time id equality were probed; none
      gave way. Four advisory OUT-OF-MODEL items recorded (no Prometheus
      counter, no kind-drop counter, hand-rolled BC math is not constant-time
      but holds no secrets, "dropped without parsing" satisfied in spirit
      not literally); none constitute a gap in the documented threat model.
      No remediation ticket opened.
clarity_check:
  date: 2026-05-30
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-097: Nostr event verification + kind filter

## Context

D38 and `security.md` §Per-source trust boundaries commit to: every
Nostr event MUST pass signature verification before Stage 1. The
ordering is: signature verification → kind allowlist → outbox write.
This ticket implements both gates.

`security_relevant: true` — this is the trust boundary between
untrusted relay data and the ingest pipeline.

## Acceptance

See frontmatter. BIP-340 Schnorr verification and kind-1/kind-6
allowlist, wired into NostrStreamSource's receive path.

## Out-of-scope

- Key handling, signing, publishing — Collector is forever read-only.
- Cross-relay dedup — M1-098.
- Kind-6 linking — M1-100.

## Notes

- **BIP-340 verification.** Nostr uses Schnorr signatures on
  secp256k1 (BIP-340). Bouncy Castle 1.80 — the current latest as
  of this ticket's authoring — does NOT expose a public BIP-340
  verifier in `org.bouncycastle.crypto.signers` (verified by jar
  inspection: only `Ed25519Signer`, `Ed448Signer`, `ECDSASigner`,
  `SM2Signer`, and friends; the sole "Schnorr" reference is
  `ECSchnorrZKP` inside the EC J-PAKE package, an unrelated
  zero-knowledge proof). The verifier therefore hand-rolls the
  BIP-340 framing (x-only pubkey lift, tagged-hash challenge
  `SHA256(SHA256(tag) || SHA256(tag) || data)` with tag
  `"BIP0340/challenge"`, `R = s·G − e·P` check, R-at-infinity /
  odd-y rejection) on top of BC's secp256k1 curve parameters
  (`SECNamedCurves.getByName("secp256k1")`) and `ECPoint` math.
  Reference for cross-check: the BIP-340 test-vectors CSV in the
  bitcoin/bips repo — a representative subset (≥4 pass, ≥4 fail)
  is embedded as constants in `NostrEventVerifierTest`.
- **BC dependency.** Add `org.bouncycastle:bcprov-jdk18on:1.80`
  to `infochat-collector/pom.xml` as a hand-pinned dependency
  next to the existing `owasp-java-html-sanitizer` /
  `commons-text` pins (same comment pattern explaining the
  non-BOM-managed pin and the use-site). The dep stays scoped to
  the collector — SimpleX and Signal adapters bring their own
  crypto via their SDKs; Collector is forever read-only per D38,
  so no signing surface is ever needed.
- **Event id derivation.** Per NIP-01, `event.id` is
  `SHA-256(serialize([0, pubkey, created_at, kind, tags, content]))`.
  The verifier first checks that the claimed id matches this hash,
  then checks the signature against the id. **The canonical
  serializer is hand-rolled, not Jackson-dependent.** Jackson's
  defaults happen to match NIP-01's escape rules today, but a
  future Jackson upgrade or a stray `MAPPER.configure(...)` could
  silently break the id check (which IS the security boundary);
  a ~30-LOC manual serializer that only knows the six canonical
  fields is correct by construction and dependency-stable.
- **Failed-sig counter.** A per-source `AtomicLong` — not per relay.
  No admin notification per failure (a hostile relay can produce many);
  the counter is the audit surface. Exposed via logging only in v1
  (first failure + every 100th cumulative, mirroring the existing
  `droppedEvents` log pattern in `NostrStreamSource.enqueueInbound`).
  Micrometer exposure is deferred to a future ticket if operational
  needs require it.
- **Wiring point.** Verification + kind filter are inserted at the
  top of `NostrStreamSource.enqueueInbound`, before the queue offer.
  This colocates the failed-sig counter with the existing
  `droppedEvents` counter, keeps all gating logic in one method, and
  matches the M1-098 dedup ticket's positioning ("Dedup runs after
  signature verification and kind filter, before outbox write") — the
  three gates form a single linear pipeline inside `enqueueInbound`.
- **Kind allowlist.** Hard-coded `Set.of(1, 6)` in v1. Future NIPs
  can extend this set via config, but v1 is compile-time only.
- **Verifier API surface.** Two methods: package-private
  `verifySchnorr(byte[] pubkey32, byte[] msg32, byte[] sig64)` for
  the BIP-340 vector tests (which deal in raw bytes), and public
  `verify(NostrEvent)` for the stream-source wiring. The high-level
  method computes the canonical event id, compares to `event.id`,
  parses the hex pubkey/sig, and delegates to `verifySchnorr`.
