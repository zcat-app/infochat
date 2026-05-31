---
id: M1-119
title: "SimpleX logging hygiene — drainStream + MalformedFrame exception messages"
status: done
created: 2026-05-31
last_updated: 2026-05-31
blocked_by: []
files_budget: 7
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no new dependency on infochat-core; the
    redaction utilities (Redactor, ContactIds, SafeLog) stay
    upstream and are not imported by the adapter (the structural
    fix here does not need them)
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI or other messaging SPI types
  - SimpleXAdapter.java — capability declarations are unchanged
  - infochat-messaging-adapter/pom.xml — no new dependencies
  - codec input validators (contactId shape, inbound size cap) — M1-118
  - Signal adapter — out of scope
  - amending the M1-103 commit; remediation is a new commit per workflow
acceptance:
  - "SimpleXSubprocessTest.drainStreamEmitsLifecycleEventsOnly passes — when the fake subprocess writes lines to stdout, the application log receives only a fixed-shape lifecycle marker (e.g. 'simplex-chat subprocess output suppressed' once per subprocess lifetime) rather than the raw line contents"
  - "SimpleXSubprocessTest.drainStreamDoesNotLeakSubprocessOutput passes — the test asserts that after the fake subprocess writes a line containing a sentinel string ('REDTEAM-SENTINEL-XXXXX'), the captured log output does NOT contain that sentinel"
  - "SimpleXMessageCodecTest.malformedFrameExceptionHasFixedMessage passes — MalformedFrameException thrown from a non-JSON frame has a fixed message ('frame is not JSON' or equivalent) that does NOT contain bytes from the original input"
  - "SimpleXMessageCodecTest.ignoredVariantReasonStringsAreFixed passes — for the non-direct chatType case, the Ignored variant's reason() returns a fixed string ('newChatItem-non-direct' or equivalent) that does NOT interpolate the chatType value"
  - "SimpleXMessageCodecTest.unknownRespTypeYieldsFixedIgnoredReason passes — the default switch branch in decode() returns an Ignored variant whose reason() is a fixed sentinel ('unknown-resp-type') and does NOT interpolate the attacker-controlled top-level resp.type value (post-audit follow-up fix)"
  - "SimpleXMessageCodecTest.unrecognizedErrorEnvelopeYieldsFixedDetail passes — decodeError on a chatCmdError/chatItemUpdateError frame with no recognized chatError/errorType/error tag returns a CommandError whose detail() is a fixed sentinel ('unrecognized-error-envelope') and does NOT contain the resp envelope bytes (post-audit follow-up fix)"
  - "SimpleXWebSocketClientTest.malformedFrameLogIsSafe passes — when dispatch() catches MalformedFrameException, the WARN log line does not contain Jackson byte fragments from the offending input (verified via a sentinel string assertion on the captured log)"
  - "SimpleXWebSocketClientTest.unrecognizedErrorEnvelopeDoesNotLeakBytesToLog passes — when failPending() logs the no-pending-command DEBUG line for an unrecognized error envelope, the captured log does NOT contain the envelope's attacker-supplied bytes (the JBoss logger is forced to FINE in the test so the DEBUG line is observable; the assertion follows from CommandError.detail() being a fixed sentinel after the post-audit follow-up fix)"
  - "drainStream / log-content policy is documented in docs/design/06-messaging.md (one short subsection explaining the chosen approach and why)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  preserves:
    - all tests currently green on main, including the existing SimpleXSubprocessTest / SimpleXMessageCodecTest / SimpleXWebSocketClientTest methods from M1-103
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §User content in exceptions
decision_refs:
  - D37
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
      added: 399
      removed: 24
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-31
    category: INFO-LEAK
    severity: medium
    promise: |
      "Exception messages and stack traces emitted via the application
      logger MUST NOT contain user-authored prose (chat-mode message
      bodies, post bodies, saved-post annotations, command arguments)."
      AND "the bodies of inbound chat-mode messages never appear in
      non-audit logs, at any log level (decision D37)."
      (`docs/spec/security.md` §User content in exceptions and §Secrets
      handling)
    gap: |
      `SimpleXMessageCodec.java:302` still falls back to `resp.toString()`
      (the entire JSON `resp` envelope) when no recognized
      `chatError`/`errorType`/`error` tag is present. That string is
      stored in `CommandError.detail()`, which then (a) flows verbatim
      into the application logger at `SimpleXWebSocketClient.java:305`
      (`LOG.debug("no pending command for error corrId={}: {}", ...)`)
      and (b) becomes the message text of the `MessagingException`
      constructed at `SimpleXWebSocketClient.java:303`. The diff fixed
      `MalformedFrameException` and the non-direct `chatType` `Ignored`
      reason but left the `decodeError` envelope-dump leak unchanged.
      The spec's "at any log level" wording explicitly covers DEBUG.
    repro: |
      A peer (compromised simplex-chat, MITM at the unauthenticated
      localhost ws://, or a malicious upstream relay echoing crafted
      error envelopes) sends
      `{"resp":{"type":"chatCmdError","attackerPayload":"REDTEAM-SENTINEL plus chat body bytes"}}`
      — an error frame whose envelope carries content but contains no
      `chatError`/`errorType`/`error` field at any depth `findFirstString`
      probes. Codec falls into the `resp.toString()` branch; the entire
      envelope lands at DEBUG via `LOG.debug` and is also embedded into
      the `MessagingException` returned to the adapter caller.
    suggested_fix_class: input-sanitization
  - date: 2026-05-31
    category: INFO-LEAK
    severity: low
    promise: |
      "Exception messages and stack traces emitted via the application
      logger MUST NOT contain user-authored prose" — by parity, neither
      should the parallel non-exception `Ignored.reason()` channel: M1-103
      Finding 4 treats interpolation of untrusted JSON values into
      `Ignored` reason strings as a §User content in exceptions violation
      because they flow into the WS-client DEBUG log via
      `LOG.debug("simplex-chat frame ignored: {}", ignored.reason())`
      (`SimpleXWebSocketClient.java:285`). D37 §User-content logging also
      covers this path "at any log level".
    gap: |
      `SimpleXMessageCodec.java:217` still emits `new Ignored(type)` where
      `type` is the attacker-controllable top-level `resp.type` value from
      the inbound JSON frame. The diff fixed the parallel
      `chatType`-non-direct branch at line 245 with the comment "The
      variant carries no bytes from chatType: that field is
      attacker-influenceable…and the Ignored.reason() value flows into the
      WS-client's DEBUG log" — the exact same reasoning applies to the
      top-level `type` field at line 217, but that site was left unchanged.
      The ticket's Finding 4 description ("Ignored variants interpolate
      untrusted JSON values into reason strings") covers both sites; only
      one was remediated.
    repro: |
      A peer sends `{"resp":{"type":"REDTEAM-SENTINEL-XXXXX-with-chosen-bytes"}}`.
      The codec's `switch (type)` falls through to `default -> new Ignored(type)`;
      dispatch logs `simplex-chat frame ignored: REDTEAM-SENTINEL-XXXXX-with-chosen-bytes`
      at DEBUG. The attacker chose those bytes; the §User-content /
      §User content in exceptions rules say they must not reach the
      application logger.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-05-31
    verdict: FINDINGS
    base: dd69fcb^
    head: dd69fcb
    verdict_file: docs/plan/m1/redteam/M1-119-2026-05-31.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      M1-119 is the remediation for M1-103 Findings 1 and 4. The
      original commit closed both cited sites but did not extend the
      structural-over-filtering pattern to the other two interpolation
      sites in the same codec (decodeError's `resp.toString()` fallback
      and the top-level `default -> new Ignored(type)` branch). Per user
      direction, both findings are fixed in the same M1-119 scope: a
      follow-up commit on the branch replaces both with fixed sentinels
      (`"unrecognized-error-envelope"` and `"unknown-resp-type"`) and
      adds three sentinel-string tests (two in SimpleXMessageCodecTest,
      one end-to-end in SimpleXWebSocketClientTest with the JBoss logger
      forced to FINE so the DEBUG log site at failPending() is observable).
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - 'Acceptance item 6 (design-doc policy subsection) uses the inspection-based "is documented" form rather than a runnable check; reviewer verifies by reading docs/design/06-messaging.md after the diff'
  blockers: []
remediates: M1-103
---

# M1-119: SimpleX logging hygiene — drainStream + MalformedFrame exception messages

## Context

Two findings from the M1-103 red-team audit
(`docs/plan/m1/redteam/M1-103-2026-05-31.md`) land here as a
single remediation ticket because both are about preventing
user-authored or third-party-controlled prose from reaching the
Provider's application log:

- **Finding 1 (INFO-LEAK, high)** — `SimpleXSubprocess.drainStream`
  pipes raw simplex-chat stdout/stderr to SLF4J. simplex-chat may
  log received message envelopes (contact ids, message bodies) on
  stdout depending on its own log level; everything it emits lands
  verbatim in the Provider's log. This violates D37
  (`security.md` §User-content logging — "the bodies of inbound
  chat-mode messages never appear in non-audit logs, at any log
  level") and the contact-id redaction rule.
- **Finding 4 (INFO-LEAK, low)** — `MalformedFrameException`
  embeds Jackson's parse-error message (which includes byte
  fragments of the offending input) and `Ignored` variants
  interpolate untrusted JSON values (`chatType`) into reason
  strings. Both flow into SLF4J unsanitized at dispatch() in the
  WebSocket client. This violates `security.md` §User content in
  exceptions ("Exception messages and stack traces emitted via the
  application logger MUST NOT contain user-authored prose").

The fixes are structural — don't put the user bytes into the
exception or log line in the first place — so this ticket does
NOT pull in `infochat-core` or any of its redaction utilities
(Redactor, ContactIds, SafeLog). Keeping `infochat-messaging-adapter`
as a leaf module preserves the current dependency graph.

The M1-103 commit is `done` and immutable per workflow rules; the
fixes land here as a new commit with `remediates: M1-103`.

## Acceptance

See frontmatter. Three-part remediation:

1. **drainStream policy.** Replace raw line-by-line logging with
   either (a) drop the drain entirely and rely on simplex-chat's
   own log file (operator-controlled), or (b) emit only fixed-shape
   lifecycle markers (process started/exited/errored) and discard
   the line content. Either approach honors D37 by construction —
   the drain physically cannot leak message bodies because it
   does not emit them. The chosen approach is documented in
   `docs/design/06-messaging.md`.
2. **MalformedFrameException message.** Strip the Jackson
   `getOriginalMessage()` embedding at
   `SimpleXMessageCodec.java:194`. The exception carries only a
   fixed message ("frame is not JSON" or equivalent).
3. **Ignored reason strings.** Strip the `:chatType` interpolation
   at `SimpleXMessageCodec.java:233`. The Ignored variant carries
   only the fixed sentinel ("newChatItem-non-direct").

## Out-of-scope

See frontmatter. The codec validators (contactId shape, inbound
size cap) are M1-118 — splitting along finding-category boundaries
keeps each ticket's diff small. The `infochat-core` dependency
question is intentionally answered "no" here; future tickets may
revisit if a different adapter genuinely needs `Redactor.redact(...)`
in code.

The existing M1-103 tests MUST stay green; if a M1-103 test
asserts on a now-changed exception message or Ignored reason
string, update the assertion to the new fixed-string contract
(this is a fixture-realism change, not a behavioral weakening,
and is authorized here). Pre-existing-test edits beyond that are
test-integrity violations per `engineering-rules-verbatim.md` §8.

## Notes

- **API-key Redactor coverage at the console boundary.** Redactor
  (defined in infochat-core, not modified or imported here) is
  registered as `@LoggingFilter(name = "api-key-redactor")` and
  applied via `quarkus.log.console.filter=api-key-redactor`. Any
  SLF4J output the adapter emits is API-key-filtered automatically
  at the JBoss LogManager console boundary, independent of the
  dependency graph. So the API-key portion of Finding 1's promise
  is already covered by infrastructure; the structural fix for
  drainStream addresses the D37 and contact-id portions, which
  Redactor does not catch.
- **Why not SafeLog.warn(...) at the WebSocket client.** SafeLog
  is the right utility if exception cause-chain text might carry
  user prose. Once `MalformedFrameException`'s message is fixed
  (step 2 above), the exception body is no longer the leak vector;
  plain `LOG.warn("simplex-chat sent a malformed frame, skipping")`
  is sufficient and avoids the `infochat-core` dep.
- **Test approach.** Use a sentinel-string assertion: write a
  byte sequence the test owns to the fake subprocess's stdout or
  to the malformed frame, then assert the captured log output
  does NOT contain the sentinel. This is more robust than asserting
  on a specific log-line format and survives format tweaks.
- **Out-of-model items from the audit.** The localhost-WebSocket /
  SSRF observation and the `assertIdentity` comment overstatement
  are advisory-only and NOT in scope here. They can be addressed
  as a comment cleanup ticket OR folded into a future hardening
  pass; neither is severe enough to gate this remediation.
- **Design reference:** `docs/design/06-messaging.md` — add the
  drainStream/log-content policy as a short subsection.
- **Source:** red-team audit verdict
  `docs/plan/m1/redteam/M1-103-2026-05-31.md` (Findings 1 and 4).
