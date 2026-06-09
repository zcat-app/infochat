---
id: M1-272
title: "Redactor thrown-chain coverage + log hygiene sweep"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/MembershipEventHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Redactor in-band sentinel exact-equality quirk and the params-array in-place mutation — wont-fix lean per the unified report; record, don't fix.
  - The redaction catalogue contents (what counts as a secret) — unchanged; only WHERE the catalogue is applied widens.
  - NewPostListener — already hardened; it is the parity model, not a target.
  - LLM error-body previews (downgraded in the report; bounded and scanned already).
acceptance:
  - "Redactor covers the thrown chain: messages of record.getThrown() and its cause chain pass through the same catalogue scan as the message and params before reaching the console; a named test asserts an API-key-shaped string inside a nested cause's message is redacted in console output."
  - "ThrottledAdminNotifier no longer binds the raw throwable around the redactor, and AbstractInstanceLockGuard no longer interpolates raw e.getMessage() into its fatal line — both routes are covered by the redaction/sanitization path; named tests."
  - "The core sanitize() helper strips DEL and C1 controls (including 0x9B CSI), matching its 'leaves no gaps' comment; named test with C1 bytes."
  - "NostrRelayConnection never lets raw relay bytes reach WARN logs: malformed-frame failures log a fixed reason code or a control-char-stripped summary (MalformedFrameException no longer embeds unstripped frame bytes in its message); named test with a control-char/ANSI frame."
  - "QuarantineReviewListener reaches NewPostListener parity: no raw NOTIFY payload echoed in ERROR logs or exception messages at the three cited sites (:255-256, :285, :295); named test."
  - "MembershipEventHandler redacts adapterGroupId at the UserLeft/BotRemoved warn sites (:72, :138), matching its other sites."
  - "The heartbeat host_id is sanitized before log interpolation."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-272: Redactor thrown-chain coverage + log hygiene sweep

## Context

Deep-review v4 verified medium **M-C1**, medium **M-K6**, medium **M-P10**,
and the **T-LOG** low sweep (`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3;
sources `deep-code-review/v4/fable5/02-module-infochat-core.md#F1/#F3`,
`deep-code-review/v4/fable5/06-module-infochat-collector.md#F1`,
`deep-code-review/v4/opus-47/06-module-infochat-collector.md#F1`,
`deep-code-review/v4/opus-47/07-module-infochat-provider.md#F3`,
`deep-code-review/v4/gpt-55/report.md` M-04, `deep-code-review/v4/mimo/report.md`
CORE-002, MED-003):

- **M-C1:** `Redactor.isLoggable` redacts message+params but never walks
  `record.getThrown()`; `ThrottledAdminNotifier` sanitizes the message arg but
  binds the raw throwable (`LOG.warnf(e, …)`); `AbstractInstanceLockGuard`
  interpolates raw `e.getMessage()` into a fatal line. Residual exposure:
  non-key secrets, control chars, and the thrown-stack rendering.
- **M-K6** (3 independent runs): `NostrRelayConnection.handleFrame` logs
  `e.getMessage()` of `MalformedFrameException`, which embeds
  `summarize(frame)` — ≤120 chars of raw relay bytes, control chars not
  stripped. The console Redactor scans for API-key shapes only, so
  newline/ANSI log forging is open from an untrusted relay.
- **T-LOG:** `sanitize()` strips C0 only, leaving DEL + C1 (incl. 0x9B CSI)
  despite its comment; `QuarantineReviewListener` echoes the raw NOTIFY
  payload where `NewPostListener` is hardened (downgraded from HIGH — payload
  fields are collector-built, attacker influence requires DB compromise;
  hygiene parity); `MembershipEventHandler` logs `adapterGroupId` unredacted
  at two warn sites; heartbeat `host_id` interpolated unsanitized.

## Acceptance

See frontmatter. The structural fix is the Redactor walking the thrown chain
(fable5's Option B); the per-site items are the sweep that makes the
guarantee uniform.

## Out-of-scope

See frontmatter — two verified-but-wont-fix Redactor quirks stay as recorded
facts.

## Notes

- Order the work structural-first: extend Redactor, then re-check which
  per-site fixes are still needed (some sites may become safe once the
  thrown chain is covered; keep their explicit fixes only where the site
  bypasses the console handler, e.g. exception messages flowing into admin
  notifications).
- The Nostr fix should route through a fixed reason code or the shared
  control-char-stripping helper — the same helper the sanitize() leg
  strengthens, so do that leg first.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-272-*.md
```
