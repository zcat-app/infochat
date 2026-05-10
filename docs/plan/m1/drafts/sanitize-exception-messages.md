# DRAFT — not yet a ticket

This is a security-hardening ticket draft staged for filing AFTER the
M1 calibration ticket lands. Promote it by:

1. Allocating the next M1 ID via the ID-allocation algorithm in
   `docs/process/workflow.md` (or `/m1-tick`'s decompose / spec-amend
   dispatch).
2. Moving the file to `docs/plan/m1/tickets/M1-NNN-exception-message-sanitization.md`.
3. Replacing `M1-NNN` placeholders below with the allocated ID.
4. Setting `created:` and `last_updated:` to the promotion date.
5. Setting `blocked_by:` to the allocated ID of the `redact-api-keys-stdout`
   draft (this ticket reuses that draft's redactor utility).
6. Removing this preamble.

The dependency relationship: this draft must be filed AFTER the
`redact-api-keys-stdout` draft. The two drafts are not parallelizable
(both touch `infochat-common/src/main/java/**/log/**`).

The analysis behind these two drafts lives in the 2026-05-10 logging-
policy review (audit of `docs/spec/security.md` §Secrets handling +
`docs/spec/deployment.md` §Backups, rotation, secrets +
`docs/spec/verification.md` §"User-content log policy"). Two real
gaps were identified: stdout has no automatic API-key redaction
(sibling draft) and exception messages have no sanitization rule
(this draft).

---

```yaml
id: M1-NNN
title: Sanitize user content in exception messages and stack traces
status: pending
created: <YYYY-MM-DD on promotion>
last_updated: <YYYY-MM-DD on promotion>
blocked_by:
  - <allocated-id-of-redact-api-keys-stdout-draft>
files_budget: 10
files_scope:
  - docs/spec/security.md
  - docs/spec/verification.md
  - infochat-common/src/main/java/**/log/**
  - infochat-collector/src/main/java/**/messaging/**
  - infochat-provider/src/main/java/**/messaging/**
  - infochat-common/src/test/java/**/log/**
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - API-key redaction in regular log lines (sibling draft covers it)
  - audit_log redaction hook (already exists; do not modify)
  - Stage-1 / Stage-2 content sanitization (different code path)
  - Hibernate org.hibernate.SQL / org.hibernate.type framework logging (known operator-side risk; documented but not gated)
  - HTTP client request/response trace (separate concern; documented as known risk)
  - Migration of every existing log call site repository-wide (only messaging-adapter intake paths in this ticket)
  - MDC / structured-field redaction (separate concern, future ticket)
acceptance:
  - "docs/spec/security.md §Secrets handling adds a 'User content in exceptions' subsection committing: exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose (chat-mode message bodies, post bodies, saved-post annotations, command arguments). The application provides a SafeLog utility that drops the exception message body, retains only the exception class name, and truncates the cause chain to class names."
  - "The same subsection enumerates the known framework-level logging risks that are intentionally NOT closed by this ticket (Hibernate parameter trace, HTTP client body trace) and points operators at the production logger-level baseline they must keep."
  - "A SafeLog class is added under infochat-common/log/. Public API: SafeLog.error(Logger, String msg, Throwable t) and equivalents for warn/info. Implementation emits exactly: msg + ' | exception=' + t.getClass().getName() + (cause-chain class names, depth-capped at 5). The original Throwable is NOT passed to the underlying SLF4J logger."
  - "SafeLog applies the sibling draft's redactor utility to the user-supplied msg before emission so an API key embedded in the msg is also caught."
  - "Three integration tests under infochat-common log/SafeLogIT exercise: (a) inbound JSON parse failure carrying a synthetic chat body 'CONFIDENTIAL_MESSAGE_BODY_42', (b) JDBC PreparedStatement bind-parameter exception against a chat_memory.content value 'CONFIDENTIAL_MEMORY_99', (c) HTTP fetch failure whose URL contains a synthetic API key 'sk-ant-test-key-leak-555'. Each test captures stdout and asserts the literal sentinel string is absent from every emitted line."
  - "docs/spec/verification.md §'User-content log policy' is amended to add the three exception-path scenarios as positive verification commitments alongside the existing /summary, chat-reply, /save, /compress fixture."
  - "Messaging-adapter intake paths use SafeLog: grep -rn 'log\\.\\(error\\|warn\\)\\(.*,\\s*e\\b' infochat-collector/src/main/java/**/messaging infochat-provider/src/main/java/**/messaging returns zero hits."
  - "mvn verify from the repo root exits zero."
test_plan:
  adds:
    - infochat-common/src/test/java/**/log/SafeLogTest.java
    - infochat-common/src/test/java/**/log/SafeLogIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/verification.md
decision_refs:
  - D37

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
```

# M1-NNN: Sanitize user content in exception messages and stack traces

## Context

The verification commitment in `docs/spec/verification.md`
§"User-content log policy" exercises happy-path commands but does not
exercise exception paths. A naked
`try { parseInbound(text) } catch (Exception e) { log.warn("parse failed", e) }`
emits the inbound body via `e.getMessage()` and the full trace; a
JDBC `BatchUpdateException` prints bind parameters including
`chat_memory.content` values. The spec is silent on this — D37
commits "the prose itself is not [logged] at any log level" but the
mechanism preventing it on exception paths is missing. This ticket
closes the gap with a `SafeLog` utility plus a spec rule, and
documents the framework-level logging risks that remain operator
concerns.

## Definition of Done

- A SafeLog utility wraps SLF4J `log.X(msg, Throwable)` calls and
  emits a sanitized line: user message + exception class + truncated
  cause-chain classes only. Exception messages are dropped entirely.
- Messaging-adapter intake paths in Collector and Provider are
  migrated from `log.X(..., e)` to `SafeLog.X(..., e)`.
- Three integration tests cover exception-leak scenarios (parse
  failure, JDBC bind-parameter exception, HTTP fetch failure).
- The spec amendment in `security.md` §Secrets handling commits to
  the rule and explicitly enumerates the framework-level risks left
  open.
- The verification commitment in `verification.md` is extended with
  the three exception scenarios as positive assertions.

## Implementation notes

- Reuse the redactor utility introduced in the sibling draft for the
  user-supplied msg argument. The exception message itself is dropped,
  not redacted — redaction can leak partial information when the
  exception text contains user prose.
- For PII redaction (chat content, post bodies), the redactor cannot
  pattern-match — the content is unconstrained free text. The chosen
  policy is: emit only the exception class name, drop the message body
  entirely, and truncate the cause chain to class names with a depth
  cap of 5.
- This is a deliberate over-redaction: when in doubt, drop content.
  Operators debugging exceptions reproduce locally and read the
  unredacted exception in their dev environment.
- Quarkus dev mode does NOT need to apply this redaction. Use a
  Quarkus profile or runtime property to gate it; the production
  baseline is `redact=true`.

## Big-picture notes

- This ticket addresses developer-controlled exception logging only.
  Framework-level logging (Hibernate SQL parameter trace, HTTP client
  body trace, Netty pipeline dumps) is NOT gated; it is a documented
  operator-side risk.
- A future ticket may add a startup safety check that fails closed if
  `org.hibernate.SQL` / `org.hibernate.type` is set above WARN in a
  production profile. Out of scope here.
- The grep-based acceptance criterion enforces SafeLog in messaging
  intake only. Other code paths can adopt SafeLog incrementally
  without re-opening this ticket; a future ticket may broaden the
  enforced surface.

## Out-of-scope expansion

- Framework-level logging (Hibernate, HTTP client, Netty) is out of
  scope. The spec amendment will explicitly call this out as a known
  operator-side risk so it isn't silently treated as covered.
- Message redaction is over-eager: messages are dropped entirely, not
  pattern-matched. Matching free-text content is the wrong tool for
  unconstrained prose.
- Migration of every existing `log.X(..., e)` call site is out of
  scope. Only messaging-adapter intake paths are migrated in this
  ticket; the grep test enforces zero violations there.
- MDC / structured-logging field redaction is a different code path
  (the SLF4J/MDC API, not the message-rendering path) and is a
  separate future ticket.

## Authorized test changes

- (none — this ticket adds tests but does not modify existing ones)

## Alternatives considered

- Alt A: Configure Logback / JBoss LogManager to truncate stack
  traces. Rejected because truncation alone preserves the exception
  message body, which is the leak source.
- Alt B: A Java agent that intercepts SLF4J calls. Rejected — too
  much complexity, no clear win over a thin SLF4J wrapper.
- Alt C: Sanitize at the appender layer rather than at the call site.
  Rejected because the SafeLog wrapper makes the call-site discipline
  visible (`SafeLog.warn` vs `log.warn`), which helps reviewers and
  enables the grep-based acceptance check.
- Alt D: Pattern-match user content like the API-key catalogue does.
  Rejected because user-authored prose is unconstrained free text —
  no closed catalogue exists for "what looks like a chat message" or
  "what looks like a post body". Drop-when-in-doubt is the only
  honest policy.
