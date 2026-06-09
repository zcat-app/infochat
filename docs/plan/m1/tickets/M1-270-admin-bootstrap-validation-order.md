---
id: M1-270
title: "Validate bootstrap admin ids before any write; SPI hoist"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 14
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The bootstrap admin property keys and their per-adapter optionality (union non-empty rule) — unchanged.
  - Last-admin protection and /grant-admin//revoke-admin scoping.
  - SignalIdentity/SimpleXIdentity parsing rules themselves — they are hoisted behind the SPI, not changed.
  - Gate 7b's other checks and the rest of the AdapterRegistry gate sequence.
acceptance:
  - "Contact-id well-formedness validation is a MessagingAdapter SPI method (e.g. isWellFormedContactId), implemented by Signal (delegating to SignalIdentity), SimpleX (SimpleXIdentity), and the in-memory adapter (free-form per its documented contract); Gate 7b dispatches through the SPI with no name-keyed switch and no permissive default — a future adapter cannot silently skip validation."
  - "AdminBootstrap validates every configured bootstrap admin contact id via the owning adapter's SPI method BEFORE any users/audit write: a named IT boots with a malformed admin value and asserts startup fails with no users row and no audit row committed."
  - "A well-formed bootstrap admin still seeds exactly as today (existing AdminBootstrap tests stay green; idempotent re-boot unchanged)."
  - "The stale AdminBootstrap class comment claiming adapter-specific validation 'does not exist yet' is corrected to describe the shared SPI validation."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
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

# M1-270: Validate bootstrap admin ids before any write; SPI hoist

## Context

Deep-review v4 verified HIGH **H9** plus medium **M-P9**
(`deep-code-review/v4/UNIFIED-REPORT.md` §1/§2; sources
`deep-code-review/v4/gpt-55/report.md` H-01,
`deep-code-review/v4/opus-47/01-architecture.md#F3`,
comment staleness also `deep-code-review/v4/fable5/07-module-infochat-provider.md#F10`):

- **H9:** `AdminBootstrap` is `@Startup @Priority(200)` and seeds/commits
  `users.is_admin=true` plus the audit row; adapter-specific contact-id
  validation (`SignalIdentity`/`SimpleXIdentity.isWellFormed`) runs later in
  `AdapterRegistry` Gate 7b driven by `MessagingStartup` at `@Priority(300)`.
  A malformed admin value is therefore committed, then boot fails — and the
  malformed admin row (and audit entry) survives into the next boot.
- **M-P9:** Gate 7b is a name-keyed switch with a permissive
  `default -> true` — documented as deliberate, but the residual risk is a
  future third adapter silently skipping validation. The report endorses
  hoisting `isWellFormedContactId` onto the SPI, which also gives
  AdminBootstrap a clean pre-write validation hook — one fix serving both
  findings.

## Acceptance

See frontmatter. Order is the security property: validate → write, never
write → validate.

## Out-of-scope

See frontmatter. The identity parsing rules and the rest of the gate
sequence are untouched; only the dispatch mechanism and the call order move.

## Notes

- The in-memory adapter's free-form contact-id contract is documented at its
  Gate 7b carve-out — its SPI implementation should return true with the same
  documented rationale, preserving current behavior.
- SPI surface change → per the call-site sweep rule, grep test doubles and
  anonymous MessagingAdapter implementations across all modules before
  finalizing files_scope; a default method on the SPI avoids breaking them —
  but weigh that against the permissive-default risk this ticket exists to
  remove (a default that returns true recreates M-P9 at the SPI level; prefer
  abstract, fix the doubles).
- Audit semantics: the bootstrap audit row for a *successful* seed is
  unchanged; the failed boot writes nothing — check
  docs/spec/security.md §Per-adapter admin threat profile if recording failed
  bootstrap attempts is ever desired (out of scope here).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-270-*.md
```
