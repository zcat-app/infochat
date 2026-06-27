---
id: M1-472
title: "Deep-review low-severity cleanup sweep: /summary single-connection retrieval + messaging redaction dedup"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ContactIdRedactor.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/ContactIdRedactorTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # Two independent low-severity deep-review cleanups bundled as a sweep
  # (the M1-462 precedent). No external-contract change: /summary returns the
  # same posts; redacted log tokens keep the identical "contact#<8 hex>" form.
  # SearchPostsTool already follows the single-connection discipline this
  # ticket copies into EligiblePostQuery — do not touch it. The api-package
  # Utf8 helper is the model for the new shared redactor; do not modify Utf8.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/Utf8.java
acceptance:
  - >-
    EligiblePostQuery.fetch opens ONE connection for the whole call and threads
    it into countFollowedTags, topActiveFollowedTags, readTagMode, and
    selectPosts (each currently opens its own dataSource.getConnection() at
    lines 232/264/300/320). cancellationService.applyStatementTimeout(conn) is
    applied ONCE on that shared connection rather than once per helper, so a
    single /summary performs one pool acquisition and one SET statement_timeout
    instead of up to four, and the (up to four) reads observe a single
    consistent snapshot. The public readVocabulary() stays on its own
    connection. This copies the discipline SearchPostsTool already documents
    ("the pool sees one acquisition rather than four", SearchPostsTool.java:81).
  - >-
    The contact-id redaction primitive ("contact#" + first 4 bytes of
    SHA-256(contactId) as hex) exists in exactly ONE place — a shared helper in
    the api package app.zcat.infochat.messaging (alongside Utf8) — and both
    SimpleXWebSocketClient (was lines 387-396) and SignalMessageCodec (was
    lines 380-389) call it instead of each carrying a byte-for-byte copy. The
    emitted token form is unchanged ("contact#<8 hex chars>"), so no log
    consumer or test of the redacted form changes meaning.
  - >-
    A focused unit test pins the shared redactor: same input → same
    "contact#<8 hex>" token, and the SimpleX and Signal call sites produce an
    identical token for an identical contact id (the property the duplication
    previously left un-enforced).
  - mvn -B verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/ContactIdRedactorTest.java
      — asserts the shared redactor's token form and determinism, and that
      both transports redact an identical contact id to the identical token.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
      — extend the existing /summary IT to assert the multi-tag-restriction
      path (followedCount > threshold) still returns the same eligible set on
      the single shared connection; assertions only tightened, none removed.
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-472: Deep-review low-severity cleanup sweep

Two independent low-severity findings from the `/deep-code-review full` run
(2026-06-27), bundled as a single cleanup sweep following the M1-462
precedent ("Deep-review low-severity cleanup sweep"). They share no code; each
is small and behaviour-preserving.

## Context

### A. `/summary` borrows four pool connections per call (provider F2 — PERFORMANCE)

`EligiblePostQuery.fetch` (`EligiblePostQuery.java:132`) calls four private
helpers — `countFollowedTags` (own `getConnection` at line 264),
`topActiveFollowedTags` (line 300), `readTagMode` (line 320), and
`selectPosts` (line 232) — each independently borrowing a pooled connection
and each running `cancellationService.applyStatementTimeout(conn)` (a `SET`
round-trip) via `prepareTimed`. So a single `/summary` (the warmest
user-facing read path besides chat) does up to four acquire/release cycles and
four `SET statement_timeout` round-trips, and the four reads run as four
separate implicit transactions — not one MVCC snapshot. This is the exact
anti-pattern the sibling `SearchPostsTool` was written to avoid; its comment
(`SearchPostsTool.java:81`) reads "the pool sees one acquisition rather than
four." Determinism (D19, "same DB state → same result") holds on a quiescent
DB, so the snapshot split is a latent consistency smell rather than a
correctness break — hence **low**.

### B. Contact-id redaction duplicated across transports (messaging F1 — MAINTAINABILITY)

The D37 log-hygiene primitive `redactContactId` — `"contact#" + first 4 bytes
of SHA-256(contactId)` — is duplicated byte-for-byte in
`SimpleXWebSocketClient.java:387-396` and `SignalMessageCodec.java:380-389`.
This is the same cross-package duplication the module's api-package `Utf8`
helper was introduced to eliminate. Because it is a security-relevant
log-hygiene primitive, silent divergence between the two copies would mean the
two transports redact differently — so it belongs in one shared place. **Low**
(maintainability, with a log-hygiene flavour).

Source: `/deep-code-review full` (2026-06-27), provider report F2 + messaging
report F1.

## Acceptance

See frontmatter. (A) thread one connection + one statement-timeout `SET`
through `EligiblePostQuery.fetch`'s four private helpers (leaving the public
`readVocabulary` on its own connection). (B) hoist `redactContactId` to a
shared api-package helper (`ContactIdRedactor`, beside `Utf8`) and call it from
both transports; token form unchanged. Full suite green.

## Out-of-scope

See frontmatter. `SearchPostsTool` (already single-connection) and `Utf8` (the
model, not a target) are untouched. No `/summary` result-set change; no change
to the redacted token format.

## Notes

- (A) is a pure connection-threading change: each helper gains a `Connection`
  parameter and drops its own `getConnection()`/`applyStatementTimeout`; the
  SQL strings and binds are unchanged. The single-snapshot consistency is a
  free side benefit of one transaction, not a behavioural requirement of the
  ticket.
- (B) the new `ContactIdRedactor` is a package-private/public static helper in
  `app.zcat.infochat.messaging` mirroring `Utf8`; both call sites lose their
  private copy. The Signal copy's javadoc already notes the function "is shared
  by every Signal drop site" — this widens that sharing across the
  simplex/signal package boundary.
- Bundled per the M1-462 deep-review-sweep precedent. If the reviewer prefers,
  the two halves are independently committable, but they are co-located here to
  avoid two single-finding tickets.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-472-*.md
```
