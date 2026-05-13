---
id: M1-019
title: Redact API-key shapes in stdout logs
status: pending
created: 2026-05-12
last_updated: 2026-05-13
blocked_by: []
files_budget: 8
files_scope:
  - docs/spec/security.md
  - docs/spec/deployment.md
  - infochat-core/src/main/java/**/log/**
  - infochat-core/pom.xml
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-core/src/test/java/**/log/**
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - audit_log writer (does not exist in code yet — future tickets will use the same Redactor utility this ticket creates)
  - quarantine table redaction (separate code path; out of scope)
  - contact-id redaction format (different mechanism; out of scope)
  - Stage-1 sanitizer regex catalogue (different code path; out of scope)
  - exception-message sanitization (sibling ticket M1-020 covers it)
  - log retention or shipping behavior (deployment §7.13.1 unchanged)
  - any framework-level logger configuration outside the console appender
acceptance:
  - "docs/spec/security.md §Secrets handling adds a bullet committing to: stdout console logs pass through the closed API-key catalogue redactor, fail-closed on regex timeout (whole message replaced with a fixed sentinel). The future audit_log writer will consume the same Redactor utility so the two cannot drift."
  - "docs/spec/deployment.md §Backups, rotation, secrets adds a bullet: 'Stdout log redaction hook redacts API-key-shaped strings before any console output (fail-closed on regex timeout).'"
  - "A RedactingLogFilter (or equivalent JBoss LogManager handler filter) is registered as a console appender filter on both Collector and Provider"
  - "The filter's regex catalogue lives in a single Redactor utility class so any future caller (e.g. the audit_log writer) consumes the same source"
  - "On regex timeout the filter replaces the whole log message with the literal sentinel '[REDACTED:timeout]' rather than emitting raw text"
  - "mvn -pl infochat-core test -Dtest=RedactingLogFilterTest passes; the test feeds one synthetic log line per catalogue shape (sk-..., sk-ant-..., sk-proj-..., sk-svcacct-..., gh[posrh]_..., AKIA..., ASIA..., AIza..., xox[abprs]-..., generic 32+-char adjacent to api_key/secret/token/password/bearer) and asserts the rendered output contains '[REDACTED' and does not contain the literal key"
  - "An integration test simulates a failed log path whose log message carries 'sk-ant-test-redact-me-please-1234567890'; the test captures stdout and asserts the literal key is absent from every emitted line"
  - "mvn verify from the repo root exits zero"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/log/RedactingLogFilterTest.java
    - infochat-core/src/test/java/io/infochat/core/log/RedactingLogFilterIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D37

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-13
  verdict: WARN
  warnings:
    - "Acceptance items 1 and 2 (spec amendments to security.md and deployment.md) state the required bullet text inline, making them verifiable by grep, but they do not cite a runnable command form (e.g., \"grep -n 'stdout console logs' docs/spec/security.md returns a match\"). This is a weak acceptance criterion but not a blocker because the expected text is fully specified. Consider adding an explicit grep command for reviewer clarity."
  blockers: []
---

# M1-019: Redact API-key shapes in stdout logs

## Context

The spec commits to redacting the closed API-key catalogue
(`docs/spec/security.md` §Secrets handling, lines 986–1009) on every
audit_log row. The audit_log writer itself hasn't been built yet —
that comes in a later M1 ticket — but stdout logs already need the
same protection: a single careless
`log.error("LLM call failed", request)` where `request` carries an
`Authorization: Bearer sk-ant-...` header leaks the key into the
operator's log aggregator (Loki by default, per
`docs/design/07-deployment.md` §7.13). This ticket builds the
Redactor utility holding the closed catalogue and the
fail-closed-on-timeout discipline, registers it as a JBoss
LogManager console filter on both services, and leaves the Redactor
shape such that the future audit_log writer can call the same
`redact(String): String` entry point so the two cannot drift.

## Definition of Done

- The closed API-key catalogue from `security.md` §Secrets handling
  applies to every line written to stdout.
- A single `Redactor` utility class holds the catalogue; any future
  caller (audit_log writer when its ticket lands) calls
  `Redactor.redact(String): String` so the catalogue cannot drift.
- The console appender filter is registered on both Collector and
  Provider services.
- A regex timeout in the filter replaces the entire log message with
  the literal sentinel `[REDACTED:timeout]`.
- Unit tests assert every catalogue shape is replaced.
- An integration test asserts a log message carrying a synthetic API
  key never reaches stdout in literal form.
- The two spec files are amended to commit to the new behavior.

## Implementation notes

- The Redactor utility lives in `infochat-core` (shared module). The
  filter (a JBoss `java.util.logging.Filter`) is also in
  `infochat-core` so Collector and Provider can each reference it
  via the same logging property.
- Quarkus uses JBoss LogManager. Register the filter via the
  standard console-handler filter property
  (`quarkus.log.console.filter`).
- The closed catalogue is listed verbatim in `security.md` lines
  988-1009. Keep the spec as the editing source; do not duplicate
  the list in code comments.
- Fail-closed timeout: wrap each regex evaluation in a hard wall-clock
  budget; on timeout replace the whole message with
  `[REDACTED:timeout]`. The Stage 1 sanitizer (`security.md` §Ingest
  pipeline) uses the same shape.

## Big-picture notes

- This ticket adds a second layer of defense behind manual
  `contact_id_redacted` formatting at log call sites; it does NOT
  replace developer discipline.
- The redaction operates on the rendered log message after parameter
  substitution. Structured-logging MDC fields written separately from
  the message are out of scope here — that gap is a candidate for a
  future ticket.
- The exception-message-sanitization ticket (M1-020) and the future
  audit_log writer will reuse this Redactor utility. The utility's
  API is a class with a single `redact(String): String` entry point,
  not a filter-only abstraction.

## Out-of-scope expansion

This ticket is narrowly scoped to API-key redaction on stdout. It
does NOT:

- Modify the existing audit-log redaction hook.
- Touch Stage-1 / Stage-2 content sanitization (different threat
  model, different code path).
- Add contact-ID redaction to the filter (different shape, different
  call-site contract — handled by manual formatting today).
- Change log retention, shipping, or aggregator choice (operator
  concern, `docs/design/07-deployment.md` §7.13.1).
- Add a `SafeLogger` SLF4J wrapper for exception sanitization — that
  is the sibling ticket M1-020.
- Add framework-level logger redaction (Hibernate `org.hibernate.SQL`,
  HTTP client request/response trace) — known operator-side risk,
  out of v1 scope.

## Authorized test changes

- (none — this ticket adds tests but does not modify existing ones)

## Alternatives considered

- Alt A: SLF4J `MaskingConverter` pattern. Rejected because Quarkus
  uses JBoss LogManager natively; registering the filter at the
  handler level matches the audit-log hook's architecture and avoids
  introducing a parallel logging façade.
- Alt B: Static analysis / lint rule that flags `log.X(..., apiKey)`
  call sites at compile time. Rejected because static analysis
  catches structural patterns, not data-dependent leaks (an API key
  embedded inside a request object passed to `log.X` would slip
  through).
- Alt C: Push redaction into the centralized log aggregator (Loki).
  Rejected because the trust boundary is at the host: the spec keeps
  redaction inside the bot so an operator who ships logs to a
  third-party SIEM still gets the same guarantee.
