# DRAFT — not yet a ticket

This is a security-hardening ticket draft staged for filing AFTER the
M1 calibration ticket lands. Promote it by:

1. Allocating the next M1 ID via the ID-allocation algorithm in
   `docs/process/workflow.md` (or `/m1-tick`'s decompose / spec-amend
   dispatch).
2. Moving the file to `docs/plan/m1/tickets/M1-NNN-stdout-log-key-redaction.md`.
3. Replacing `M1-NNN` placeholders below with the allocated ID.
4. Setting `created:` and `last_updated:` to the promotion date.
5. Removing this preamble.

The dependency relationship: this draft is the prerequisite for the
exception-message-sanitization draft (the latter reuses this draft's
redactor utility). When promoting both, file this one first; set the
other's `blocked_by:` to this one's allocated ID.

The analysis behind these two drafts lives in the 2026-05-10 logging-
policy review (audit of `docs/spec/security.md` §Secrets handling +
`docs/spec/deployment.md` §Backups, rotation, secrets +
`docs/spec/verification.md` §"User-content log policy"). Two real
gaps were identified: stdout has no automatic API-key redaction (this
draft) and exception messages have no sanitization rule (the sibling
draft).

---

```yaml
id: M1-NNN
title: Redact API-key shapes in stdout logs (mirror audit-log hook)
status: pending
created: <YYYY-MM-DD on promotion>
last_updated: <YYYY-MM-DD on promotion>
blocked_by: []
files_budget: 8
files_scope:
  - docs/spec/security.md
  - docs/spec/deployment.md
  - infochat-common/src/main/java/**/log/**
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-common/src/test/java/**/log/**
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - audit_log redaction hook (already exists; do not modify)
  - quarantine table redaction (separate code path; out of scope)
  - contact-id redaction format (different mechanism; out of scope)
  - Stage-1 sanitizer regex catalogue (different code path; out of scope)
  - exception-message sanitization (sibling draft covers it)
  - log retention or shipping behavior (deployment §7.13.1 unchanged)
  - any framework-level logger configuration outside the console appender
acceptance:
  - "docs/spec/security.md §Secrets handling adds a bullet committing to: stdout console logs pass through the same closed API-key catalogue redactor as audit_log writes, fail-closed on regex timeout (whole field replaced with a fixed sentinel)."
  - "docs/spec/deployment.md §Backups, rotation, secrets adds a bullet mirroring the existing audit-log line: 'Stdout log redaction hook redacts API-key-shaped strings.'"
  - "A RedactingLogFilter (or equivalent JBoss LogManager handler filter) is registered as a console appender filter on both Collector and Provider"
  - "The filter's regex catalogue is loaded from a single shared source so it cannot drift from the audit-log redactor"
  - "On regex timeout the filter replaces the whole log message with the literal sentinel '[REDACTED:timeout]' rather than emitting raw text"
  - "mvn -pl infochat-common test -Dtest=RedactingLogFilterTest passes; the test feeds one synthetic log line per catalogue shape (sk-..., sk-ant-..., sk-proj-..., sk-svcacct-..., gh[posrh]_..., AKIA..., ASIA..., AIza..., xox[abprs]-..., generic 32+-char adjacent to api_key/secret/token/password/bearer) and asserts the rendered output contains '[REDACTED' and does not contain the literal key"
  - "An integration test simulates a failed LLM call whose exception carries 'sk-ant-test-redact-me-please-1234567890' in the message; the test captures stdout and asserts the literal key is absent from every emitted line"
  - "mvn verify from the repo root exits zero"
test_plan:
  adds:
    - infochat-common/src/test/java/**/log/RedactingLogFilterTest.java
    - infochat-common/src/test/java/**/log/RedactingLogFilterIT.java
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
clarity_check: {}
```

# M1-NNN: Redact API-key shapes in stdout logs (mirror audit-log hook)

## Context

The current logging policy applies the closed API-key redaction
catalogue only to `audit_log` writes (`docs/spec/security.md`
§Secrets handling, lines 986–1009). Stdout logs depend entirely on
developer discipline at every call site: a single careless
`log.error("LLM call failed", request)` where `request` carries an
`Authorization: Bearer sk-ant-...` header leaks the key into the
operator's log aggregator (Loki by default, per
`docs/design/07-deployment.md` §7.13). This ticket closes the gap by
mirroring the audit-log redaction hook on the stdout console
appender, with the same closed catalogue and the same
fail-closed-on-timeout discipline. Closing this gap also makes the
spec promise in `deployment.md` §Backups, rotation, secrets
internally consistent: today the audit-log line is a hard
commitment, while stdout redaction is silently absent.

## Definition of Done

- The closed API-key catalogue from `security.md` §Secrets handling
  applies to stdout logs as well as audit-log writes.
- A single shared regex source feeds both the audit-log hook and the
  new stdout filter; the catalogue cannot drift between the two.
- The console appender filter is registered on both Collector and
  Provider services.
- A regex timeout in the filter replaces the entire log message with
  the literal sentinel `[REDACTED:timeout]`.
- Unit tests assert every catalogue shape is replaced.
- An integration test simulates a failed LLM call carrying an API key
  in the exception message and asserts the key does not reach stdout.
- The two spec files are amended to commit to the new behavior.

## Implementation notes

- The audit-log redaction hook lives in the `AuditLogger` write path
  (`docs/design/04-security.md` §`AuditLogger`). Lift the regex set
  into a shared utility so both paths consume the same source.
- Quarkus uses JBoss LogManager. Register the filter via the standard
  console handler filter mechanism; concrete property keys belong in
  design notes, not the spec.
- The closed catalogue is listed verbatim in `security.md` lines
  988-1009. Keep the spec as the editing source; do not duplicate the
  list in code comments.
- Fail-closed timeout: reuse the same `java.util.regex`-plus-watchdog
  pattern documented for Stage 1 sanitizer (`security.md` §Ingest
  pipeline).

## Big-picture notes

- This ticket adds a second layer of defense behind manual
  `contact_id_redacted` formatting at log call sites; it does NOT
  replace developer discipline.
- The redaction operates on the rendered log message after parameter
  substitution. Structured-logging MDC fields written separately from
  the message are out of scope here — that gap is a candidate for a
  future ticket.
- The exception-message-sanitization draft will reuse this redactor
  utility. Design the utility's API with that consumer in mind
  (e.g., a `Redactor` class with a single `redact(String): String`
  entry point, not a filter-only abstraction).

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
  is the sibling draft.
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
