---
id: M1-038
title: InboundRouter hardening — fenced-code carve-out + body-size cap + contact-ID redaction in logs
status: pending
created: 2026-05-19
last_updated: 2026-05-19
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log/ContactIdsTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-035b
out_of_scope:
  - any change to InboundRouter intake-gate ordering — ban check, invite gate, slow-start probation, transport-level rate-limit cap are T2-A territory; this ticket changes only `normalize()` and the three error-log redaction sites in `onMessage`
  - any new CommandHandler implementation (T2-A onwards)
  - any modification of AutoRegisterService (M1-035d territory)
  - any change to scope/contact-id schemas, V5 / V11 migrations, or the messaging-adapter SPI types
  - any audit-log writer / RedactionHook work — M1-041 territory
  - any stdout JBoss LogManager console-filter work — M1-019 territory
  - any SafeLog / exception-message redaction surface — M1-020 territory
  - any change to the spec — the spec already promises both the fenced-code carve-out (§Authorization model step 1.7 + §Ingest pipeline) and contact-ID redaction (§Secrets handling); this ticket is pure code remediation
acceptance:
  - "infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java exists and declares a public static method `redact(String): String` returning the prefix + ellipsis + suffix form per docs/spec/security.md §Secrets handling. grep -E 'public\\s+static\\s+String\\s+redact\\s*\\(' ContactIds.java returns at least one match"
  - "ContactIdsTest exercises: (a) null input → returns a fixed sentinel (e.g. `\"<null>\"`) without throwing; (b) empty input → returns a fixed sentinel; (c) short input (≤ prefix+suffix length) → returns the literal sentinel `\"<short>\"` or equivalent without exposing the original; (d) typical input (≥ 16 chars) → returns prefix + ellipsis + suffix where neither prefix nor suffix exposes the full id; (e) the returned string contains the ellipsis literal `\"...\"` (or U+2026)"
  - "InboundRouter.normalize() implements the CommonMark fenced-code carve-out per docs/spec/security.md §Authorization model step 1.7 (`fence recognition per the CommonMark rule documented in §Ingest pipeline`): code points inside fenced blocks (opened by ≥3 consecutive `` ` `` or `~` on a line, closed by the same fence type with ≥ that many) are preserved byte-for-byte (no NFKC, no bidi-strip, no zero-width-strip, no trim). grep -E 'fence|FENCE' InboundRouter.java returns at least one match"
  - "InboundRouterNormalizeTest covers: (a) plain text outside fences is NFKC-normalized + bidi-stripped + trimmed as before (M1-035b regression); (b) the body `\"```\\nﬁnal\\n```\"` (with U+FB01 LATIN SMALL LIGATURE FI inside the fence) round-trips unchanged byte-for-byte; (c) a fenced block containing a fullwidth digit (e.g. U+FF11) round-trips unchanged; (d) trailing whitespace INSIDE a fenced block is preserved; (e) text BEFORE and AFTER a fenced block in the same message is normalized as before; (f) a `~~~`-delimited fence carves out the same way as a `` ``` ``-delimited one"
  - "InboundRouter enforces a body-size cap before invoking Normalizer.normalize. The cap is configurable via the property `infochat.router.max-inbound-body-bytes` with a sensible default (≤ 100 KiB; document the chosen default in Implementation notes). Inbound whose UTF-8 byte length exceeds the cap is dropped with a fixed reply literal (re-use an existing fixed-reply key or add `error.router.message_too_large`); the inbound NEVER reaches Normalizer.normalize. grep -E 'max-inbound-body-bytes|maxInboundBodyBytes|MAX_INBOUND' InboundRouter.java returns at least one match"
  - "InboundRouterNormalizeTest also covers: (g) a body whose UTF-8 byte length exceeds the cap is rejected with the fixed too-large reply AND normalize() is NEVER invoked on that input (verify via test seam or by asserting the cap-checked branch returns before the normalize call)"
  - "InboundRouter.onMessage's three error-log sites (the catch-all exception path, the no-replyTarget warn path, and the reply-send-failed path — M1-035b finding 5) use ContactIds.redact when interpolating `scope` into the log message. grep -E 'ContactIds\\.redact|contactIds\\.redact' InboundRouter.java returns at least three matches"
  - "InboundRouterContactIdRedactionTest captures stdout/SLF4J output for the three error-log sites (triggered via a stub CommandHandler that throws, an absent replyTarget, and a failing reply target) and asserts the unredacted contact-id literal NEVER appears in the captured output"
  - "mvn -B clean verify from the repo root exits 0; existing AdapterRouterIT (M1-035) and the M1-035b production-code tests continue to pass unchanged"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/log/ContactIdsTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  preserves:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java (M1-035 umbrella)
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Ingest pipeline
  - docs/spec/security.md §Secrets handling
decision_refs: []
---

# M1-038: InboundRouter hardening — fenced-code carve-out + body-size cap + contact-ID redaction in logs

## Context

M1-035b's red-team audit returned three net-new findings against
`InboundRouter.onMessage` / `normalize` that are NOT covered by the
T2-A onboarding/auth umbrella (T2-A wires the gates upstream of the
router — ban check, invite gate, probation, rate-limit cap — but
does not touch `normalize()` or the error-log sites):

1. **Finding 3 (medium INFO-LEAK)** — `normalize()` applies
   whole-body NFKC + bidi-strip + zero-width-strip + `trim()` with
   no fenced-code carve-out, directly contradicting
   `docs/spec/security.md` §Authorization model step 1.7 ("...
   outside fenced code blocks; fence recognition per the CommonMark
   rule documented in §Ingest pipeline") and §Ingest pipeline ("Bytes
   inside fences are preserved verbatim"). The Javadoc at
   `InboundRouter.java:194-197` admits "Whole-body NFKC is sufficient
   for MVP" — that is exactly the spec violation.

2. **Finding 5 (low INFO-LEAK)** — three SLF4J error-log call sites
   in `onMessage` (`log.error("InboundRouter dispatch failed for
   scope={}", msg.scope(), e)` and equivalents at the no-replyTarget
   and send-failed paths) interpolate `scope` unredacted. For DM
   scopes the scope carries the contact-id, violating §Secrets
   handling ("Contact IDs are logged in redacted form (prefix +
   ellipsis + suffix) outside the audit log").

3. **Finding 6 (medium DOS)** — `normalize()` calls
   `Normalizer.normalize(raw, NFKC)` with no body-size cap. The
   adapter's `maxInboundMessageBytes` is honor-system; the router is
   the centralized seam for defense in depth. NFKC on a maliciously
   crafted input (Hangul expansion, deeply repeated combining marks)
   can amplify cost.

All three sit in the same file with overlapping test surface and
shared origin (M1-035b). The contact-ID redaction helper this ticket
introduces (`ContactIds.redact`) is consumed by M1-039 (`/add-source`
handler hardening) immediately and by future T2-A intake gates.

## Definition of Done

- A new `ContactIds.redact(String)` helper lives in `infochat-core`,
  returning the prefix + ellipsis + suffix form spec'd in §Secrets
  handling. Null / empty / short inputs map to fixed sentinels that
  do not expose the original bytes.
- `InboundRouter.normalize()` implements the CommonMark fenced-code
  carve-out. Bytes inside fences round-trip unchanged; bytes outside
  fences are normalized as today.
- `InboundRouter` enforces a configurable body-size cap (default
  ≤ 100 KiB) BEFORE invoking `Normalizer.normalize`. Oversize bodies
  are dropped with a fixed reply.
- The three error-log sites in `onMessage` interpolate `scope` via
  `ContactIds.redact`.
- New tests pin the normalize carve-out, the size cap rejection
  path, and the absence of unredacted contact-id literals in the
  captured error-log output.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Fence recognition.** CommonMark §4.5: an info-string-less fenced
  code block opens on a line containing only ≥3 consecutive backticks
  (`` ` ``) or tildes (`~`) and closes on the next line containing
  only the same fence character at ≥ that count. The carve-out
  implementation can scan line-by-line, tracking an in-fence boolean
  plus the opening-fence character + length, applying NFKC + strips +
  trim ONLY to lines outside the active fence. The single-line
  inline-code surface (single `` ` `` pairs within a line) is NOT
  fenced-code — leave those subject to NFKC; the spec carve-out is
  fenced blocks only.
- **Size cap default.** Pick a value ≤ the in-memory adapter's
  `maxInboundMessageBytes` default (100 KB per `CapabilityFlags`).
  64 KiB is a reasonable plain-text chat MMP and matches the
  defense-in-depth posture: the adapter declares 100 KB, the router
  caps at 64 KiB, so a misbehaving adapter that lets 80 KB through
  still gets stopped. Document the chosen default in the
  `application.properties` comment.
- **Cap point.** Measure `raw.getBytes(StandardCharsets.UTF_8).length`
  before any normalization; rejecting on character count would let
  surrogate-pair-heavy inputs slip past. The fixed-reply key can
  re-use an existing literal (e.g. `INTERNAL_ERROR_REPLY`) or add a
  new `error.router.message_too_large` — implementer's call.
- **`ContactIds.redact` shape.** §Secrets handling pins "prefix +
  ellipsis + suffix" but not the exact cutoffs. Suggested:
  first 8 chars + `"..."` + last 4 chars for inputs ≥ 16 chars;
  literal `"<short>"` for shorter inputs; literal `"<null>"` for
  null. The implementer can refine. The helper lives in
  `infochat-core` so M1-039 and future T2-A intake gates can consume
  it without a cross-module dependency.
- **Three error-log sites.** Audit `InboundRouter.java` for every
  `log.error(..., msg.scope(), ...)` / `log.error(..., scope, ...)`
  in `onMessage`. The M1-035b finding cites lines 130, 138, 145;
  verify at start time by grep on the current file.

## Big-picture notes

- **T2-A will land the upstream intake gates.** Ban check, invite
  gate, probation gate, transport-level rate-limit cap. This ticket
  deliberately does NOT pre-empt that work — the three findings
  above sit in `normalize()` and in the error-log sites, both of
  which T2-A does not rewrite. After T2-A lands, the gate ordering
  becomes: rate-limit → ban → invite → probation → normalize →
  dispatch. `normalize()` stays where it is; its carve-out and cap
  remain load-bearing.
- **The `ContactIds.redact` helper is the seam for cross-module
  contact-id redaction.** M1-019 (deferred) builds a separate
  API-key-shaped redactor for stdout JBoss filters; M1-020
  (deferred) builds the SafeLog wrapper for exception messages.
  This ticket adds the third seam: a small static helper for
  contact-id values that appear in operator log call sites. The
  three redactor families are deliberately separate because their
  inputs and outputs differ (API key catalogue vs. exception
  messages vs. contact-id strings) — a single "Redactor" class
  conflating them would force the consumer to know which kind of
  input it is handing in.
- **Fenced-code carve-out is the template for T2-D chat-mode.** When
  chat-mode lands, its inbound parser will inherit `normalize()`'s
  output. If we ship `normalize()` without the carve-out, T2-D
  copies the broken behavior. Fixing it here means the chat-input
  parity rule the spec promises holds from day one.

## Out-of-scope expansion

- **Intake gate ordering / T2-A territory.** Ban check, invite gate,
  probation gate, transport-level rate-limit cap, the unknown-DM
  "access requires an invitation" fixed reply, the M1-036
  finding-1-equivalent for `/help` — all live upstream of
  `normalize()` and ship as T2-A. Touching them here would balloon
  scope and pre-empt T2-A's coherent surface.
- **Stdout API-key redaction.** M1-019 territory. The catalogue and
  filter shape there is different (regex-driven, console-handler
  filter, fail-closed on timeout). This ticket's `ContactIds.redact`
  is a small static helper used at specific call sites.
- **Exception-message sanitization.** M1-020 territory. The SafeLog
  wrapper there drops exception messages entirely; this ticket only
  redacts contact-id interpolation values.
- **Audit-log redaction-hook.** M1-041 territory. Audit-log writers
  are project-wide raw JDBC today; the hook layer is deferred for
  consolidation with the T2-A/B/E call sites.

## Authorized test changes

- (none — this ticket adds three new test files and does not modify
  any pre-existing test. The new tests subclass / mirror the
  M1-035-era harness pattern.)

## Alternatives considered

- **Apply the carve-out by tokenizing the body in `onMessage` and
  splitting routing per-segment.** Rejected — the carve-out is a
  normalization rule, not a dispatch rule. Keeping it inside
  `normalize()` localizes the change and preserves the spec's "the
  normalized body REPLACES the raw body for all downstream
  processing" invariant.
- **Use a CommonMark library (commonmark-java) for fence detection.**
  Rejected — pulling in a Markdown parser for a single carve-out
  rule is over-weight. CommonMark fence recognition is ~30 lines of
  state-machine logic; the spec promise is the carve-out, not
  full-fidelity Markdown parsing.
- **Cap body size at the adapter SPI layer instead of the router.**
  Rejected — the adapter SPI's `maxInboundMessageBytes` IS the
  honor-system cap; production adapters (SimpleX, Signal) will land
  in T3-A and enforce it. The router cap is defense-in-depth that
  fires even when an adapter misbehaves or a test adapter bypasses
  its own declared cap.
- **Build a generic `Redactor` class covering API keys + contact IDs
  + URLs in one file.** Rejected — see Big-picture notes. The three
  inputs/outputs differ and conflating them forces every consumer to
  pass a "redaction kind" enum. Three small specialized helpers
  read cleaner.
