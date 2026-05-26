---
id: M1-097
title: "Nostr event verification + kind filter"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-096
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifier.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifierTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
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
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Per-source trust boundaries
  - docs/spec/security.md §Nostr (StreamSource, v1)
decision_refs:
  - D38
  - D10
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
  secp256k1 (BIP-340). Bouncy Castle provides `ECDSASigner` for
  ECDSA but BIP-340 Schnorr requires `SchnorrSigner` or manual
  assembly using `ECPoint` operations. Bouncy Castle 1.78+ has
  BIP-340 support. The implementer should verify the BC version
  available in Quarkus's dependency tree.
- **Event id derivation.** Per NIP-01, `event.id` is
  `SHA-256(serialize([0, pubkey, created_at, kind, tags, content]))`.
  The verifier first checks that the claimed id matches this hash,
  then checks the signature against the id.
- **Failed-sig counter.** A per-source `AtomicLong` — not per relay.
  No admin notification per failure (a hostile relay can produce many);
  the counter is the audit surface. Exposed via logging at a
  configurable interval or via a Micrometer gauge.
- **Kind allowlist.** Hard-coded `Set.of(1, 6)` in v1. Future NIPs
  can extend this set via config, but v1 is compile-time only.
