---
id: M1-242
title: "Signal inbound decode hardening + oversize outcome"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - docs/design/06-messaging.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundByteCapTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAciValidationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupSpanTypeTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SimpleX byte-cap enforcement (SimpleXMessageCodec MAX_INBOUND_TEXT_BYTES) and SimpleX queue-address charset validation — already correct; this ticket brings Signal in line with them, it does not change SimpleX's intake gates (the only SimpleX touch authorized here is the oversize-outcome leg, and only under Option A — see Acceptance).
  - The SignalMessageCodec DM-path timestamp helper (usableTimestamp/integralLong) and the SignalGroupHandler timestamp guard (landed by M1-238) — unchanged.
  - The constant-time mention compare (M1-238) and capability flags — unchanged.
  - MAX_INBOUND_LINE_CHARS itself stays as the coarse unterminated-line OOM guard; only its comment is corrected, its value/behavior is untouched.
acceptance:
  - "T1: SignalMessageCodec enforces a decoded-body byte cap of MAX_INBOUND_TEXT_BYTES = 16_384 (UTF-8 bytes, mirroring SimpleXMessageCodec) on the DM path (extractDm) and the group path (via SignalGroupHandler); a body whose UTF-8 byte length exceeds the cap is rejected at decode (extractDm returns Optional.empty(); the group frame is dropped). SignalInboundByteCapTest asserts a body that is under MAX_INBOUND_LINE_CHARS in UTF-16 chars but over 16_384 UTF-8 bytes (multi-byte chars) is rejected on both the DM and group paths, and a well-formed body is delivered."
  - "T1 (comment fix): the SignalJsonRpcClient line-cap comment (near MAX_INBOUND_LINE_CHARS) no longer claims it implements/\"Matches\" the maxInboundMessageBytes capability — it documents itself as the coarse char-domain unterminated-line guard, with the byte-domain capability enforced in SignalMessageCodec."
  - "T2: the inbound Signal contact id (sourceUuid on the DM path, and the group sender id) is validated at decode against the v1 accepted-identity charset BEFORE it becomes a (adapter, contact_id) join-key value; a value that does not match is rejected (extractDm returns Optional.empty(); group frame dropped), consistent with \"a message whose identity cannot be asserted is dropped at decode.\" SignalAciValidationTest asserts a canonical lowercase UUID is accepted and a non-conforming wire string is dropped. (The accepted set — UUID-only vs UUID+E.164 — is fixed by the §Notes decision below and asserted by the test.)"
  - "T21: SignalGroupSpanTypeTest asserts that a group frame carrying a non-integer mention-span value is handled without an exception escaping handleReceive (the span is skipped and the message delivered unstripped) while a well-formed mention span is stripped as before. No production change to the span read: SignalGroupHandler already reads spans via getInt(name, -1), and Parsson's getInt(String,int) returns the default for a non-JsonNumber value (verified Parsson 1.1.7), so a wrong-typed span yields -1 and is skipped by the existing start<0 / length<=0 bounds guard. The test pins this implementation-dependent trust-boundary behavior as a regression guard. (SignalGroupHandler is still edited in this ticket for T1's group-path byte cap and T2's group-sender UUID gate — only the span read is unchanged.)"
  - "T22: the inbound oversize outcome is made consistent and documented. EITHER (Option A) both adapters emit the fixed oversize reply that docs/design/06-messaging.md §6.3.10 commits to (a named test pins the reply on the oversize path), OR (Option B) docs/design/06-messaging.md §6.3.10 is amended to bless silent drop as the v1 behavior and the adapters keep dropping silently. The ticket picks one; design text and code agree."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundByteCapTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAciValidationTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupSpanTypeTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs:
  - D46
  - D10
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 476
      removed: 28
escalations:
  - date: 2026-06-09
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — no review round. Pre-implementation source audit found T21's
      premise false: the ticket claimed SignalGroupHandler.stripBotMentions
      reads spans via getInt that "throws ClassCastException on a wrong-typed
      span", but the call sites use the two-arg getInt(name, -1) and Parsson
      1.1.7 (the only JSON-P impl on the adapter classpath, Quarkus default)
      implements getInt(String,int) to return the default on a non-JsonNumber
      value. No CCE is thrown today; the implied production change is a
      behavior-preserving no-op. Corroborated by SignalMessageCodec.decode,
      which already relies on the same getInt-with-default safety
      (err.getInt("code", -32603), ungated). Resolution: refine T21 to a
      test-only regression pin.
revisions:
  - date: 2026-06-09
    reason: premise-fail refine — T21's ClassCastException premise is falsified against Parsson 1.1.7 getInt(String,int) (returns default on non-JsonNumber); rewrite T21 to a test-only regression pin (no production span-read change), keeping T1/T1-comment/T2/T22 unchanged
    prior_values: |
      acceptance[3] (T21): "T21: SignalGroupHandler mention-strip reads the
        span offset/length defensively so a present-but-wrong-typed JSON span
        value drops only that span/message cleanly instead of throwing
        ClassCastException out of handleReceive; SignalGroupSpanTypeTest
        asserts a group frame carrying a non-integer span value is handled
        without an exception escaping handleReceive while a well-formed
        mention span is stripped as before."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: df83e13
    head: working-tree (m1/M1-242-signal-inbound-decode-hardening, uncommitted, --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-242-2026-06-09.md
    out_of_model_count: 1
    note: |
      CLEAN — no delivered threat-model gap. The UUID identity gate, decoded-body
      UTF-8 byte cap (DM + group), and silent oversize drop are all hardening
      moves; byte cap mirrors SimpleX and enforces maxInboundMessageBytes, regex
      anchored/linear (no ReDoS). One OUT-OF-MODEL advisory (pre-existing, NOT a
      finding, verified untouched by this diff): SignalGroupHandler.aciFromArrayEntry
      (lines 272–285, membership path) maps arbitrary member-delta strings to
      (adapter, contact_id) join keys without isAcceptableAci. Candidate for a
      follow-up ticket; explicitly out of M1-242 scope (message-sender path only).
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-242: Signal inbound decode hardening + oversize outcome

## Context

Four findings on the Signal adapter's inbound decode boundary, grouped because
they all live in `SignalMessageCodec.extractDm` + the group path and all concern
robustly handling an untrusted Signal wire frame. Source: `deep-code-review/v3/`
UNIFIED-REPORT.md T1, T2, T21, T22 (opus `05#F1/F2/F3`, mimo `05#F1`).

- **T1 [high] — byte-cap not enforced.** The only Signal inbound bound is
  `MAX_INBOUND_LINE_CHARS = 16_384`, a UTF-16 *char* cap on the whole JSON-RPC
  envelope line. `extractDm` builds the body with no byte check, so the declared
  `maxInboundMessageBytes = 16_384` capability is unenforced. SimpleX *does*
  enforce `MAX_INBOUND_TEXT_BYTES = 16_384` on the decoded body in UTF-8 bytes.
  The `SignalJsonRpcClient` comment falsely claims the line cap "Matches" the
  capability.
- **T2 [high] — `canonicalizeAci` accepts arbitrary wire strings.** It only
  lowercases (its javadoc: "Returns the input untouched if it does not parse as a
  UUID"), and `extractDm` passes the result straight into `ReceivedDm`/`Identity`,
  so an arbitrary `sourceUuid` becomes a permanent `(adapter, contact_id)`
  join-key. SimpleX validates every inbound id against its queue-address charset;
  Signal has no analogous gate.
- **T21 [low] — span-type read (regression pin only).** The deep-review flagged
  `SignalGroupHandler` mention-strip as throwing `ClassCastException` on a
  wrong-typed span. Verified false against the implementation on the classpath:
  the call sites use the two-arg `getInt(name, -1)`, and Parsson 1.1.7 returns
  the default for a non-`JsonNumber` value, so a wrong-typed span yields `-1`
  and is skipped by the existing bounds guard — no CCE, no production change
  needed. This ticket adds `SignalGroupSpanTypeTest` to pin that
  implementation-dependent behavior as a regression guard (premise-fail refine,
  2026-06-09).
- **T22 [low] — silent oversize drop.** `design/06-messaging.md §6.3.10` commits
  to a fixed reply on oversize; both adapters currently drop silently. Coupled to
  T1 because the oversize *outcome* is decided at the same code site T1 adds the
  cap to.

## Acceptance

See frontmatter. In prose: enforce the decoded-body UTF-8 byte cap on Signal's
DM and group paths (mirroring SimpleX) and fix the misleading line-cap comment;
validate inbound Signal identities at decode and drop unassertable ones; read
group mention spans without throwing on a wrong type; and resolve the oversize
outcome so design text and adapter behavior agree. Named tests pin each; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. SimpleX's existing intake gates, the M1-238 timestamp/mention
work, and `MAX_INBOUND_LINE_CHARS` itself are untouched (only its comment is
corrected). SimpleX code is touched only if the implementer picks Option A for
T22.

## Notes

- **T2 identity decision (must be made before implementing).** The
  `canonicalizeAci` javadoc deliberately leaves acceptance "to the caller …
  (e.g. legacy phone-number sources during account migration)." The ticket must
  decide whether non-UUID (E.164) ACIs are a real v1 case: accept `UUID + E.164`
  via a charset gate, or `UUID-only` and drop others. Pick one and pin it in
  `SignalAciValidationTest`. Don't hard-fail legitimate identities without
  deciding. Recommended default: **UUID-only** for v1 (matches the canonical
  lowercase-UUID contract the codec already documents); revisit if/when account
  migration is a real requirement.
- `SignalGroupHandler` may already hold a reference to the codec post-M1-238; if
  the byte-cap/validation helpers are best placed on the codec, expose them
  package-private and call from the handler (implementer's choice).
- `security_relevant: true` (T1/T2 are intake trust-boundary). A `/redteam` pass
  is appropriate after implementation.
- Cross-cutting: this is one of three points on the inbound size-cap surface
  (T1 here, the command body cap in M1-244, the oversize outcome T22 here). The
  UNIFIED-REPORT theme #1 suggests a one-paragraph "which cap, what units, what
  outcome" note in `messaging.md`; that doc note is optional and not required by
  acceptance.
</content>
</invoke>
