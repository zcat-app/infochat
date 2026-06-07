---
id: M1-215
title: "Core hygiene: sanitized key in getState WARN, full-C0 sanitize, single AuditLogWriter constructor"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SafeLog — audit finding D12 ("error drops stack trace") dissolved at re-grounding: the class javadoc documents the throwable drop as the deliberate D37 posture ("The original Throwable is never passed to the underlying SLF4J logger — no stack trace, no message body") and it already emits the class-name-only, depth-capped, suppressed-walking cause chain the suggested fix asked for; untouched
  - the V5 verb-catalogue comment block — immutable applied migration; the living catalogue (design 02-schema §2.1.8) is M1-210's leg
  - AuditAction javadoc cleanup — the file is in M1-195's files_scope; not touched here
  - notifyOnce's throttle-window semantics and the admin_notification_state schema — only the two named logging/sanitize sites and the constructor shape change
  - RedactionHook and redaction behavior — M1-210 carries its javadoc contract note; AuditLogWriter's redact-before-INSERT flow is unchanged
acceptance:
  - "ThrottledAdminNotifier.getState's SQLException WARN logs the sanitized key: a caller-supplied key containing CR/LF cannot place a raw line break in the WARN line — named test or the WARN provably uses the same sanitized form the SQL already uses (today the catch logs the raw key while safeKey is computed six lines above for the query)"
  - "sanitize neutralizes the full C0 control range, not only CR/LF/NUL: a key or message carrying ESC (0x1B) or another C0 control reaches the log/DB sinks with the control character replaced — named test (today only \\r, \\n, \\0 are replaced, so ESC passes through and an ANSI escape sequence could forge terminal output on an operator scrape)"
  - "AuditLogWriter has exactly one constructor (the injected form): the no-arg CDI/field-injection path is gone, CDI bean discovery still resolves the bean (constructor injection), and the existing non-CDI construction sites keep compiling unchanged — draft-time sweep found exactly two, both already using the injected form (AuditLogWriterIT, RetryDigestCommandTest's field assignment)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D37
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-215: Core hygiene — getState WARN key, full-C0 sanitize, single AuditLogWriter constructor

## Context

Three of the four core lows from the audit's misc bucket (unified D9,
D10, D11 — `deep-code-review/v2/UNIFIED.md` §2), re-grounded
2026-06-07:

1. **D9 (low).** getState's SQLException WARN interpolates the RAW
   caller-supplied key even though the method computes safeKey for the
   query six lines earlier — the one unsanitized sink in a class whose
   whole point is the sanitized ADMIN-NOTIFY scrape contract.
   (opus-47 rated this high; the audit's calibrated severity LOW is
   binding — exploitation needs an SQLException AND attacker-keyed
   input, and today's keys are mostly internal constants.)
2. **D10 (low).** sanitize replaces only CR/LF/NUL; ESC and the rest
   of C0 pass through, leaving ANSI-escape forgery open on terminal
   scrapes of the log line.
3. **D11 (low).** Two constructors (no-arg CDI field-injection + an
   injected form documented "for non-CDI consumers") — two
   initialization paths for one dependency; a non-CDI caller using the
   no-arg form gets a null redaction hook. Collapse to constructor
   injection.

The fourth member (D12, SafeLog) **dissolved at re-grounding** — see
out_of_scope; the drop is recorded with evidence in the batch summary.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T33 under `deep-code-review/v2/` (core
  members; opus-48 core F4, kimi-folder core F3, opus-47 core F3).
- security_relevant is false deliberately: no spec sentence governs
  these sites — the legs anchor to ThrottledAdminNotifier's own
  documented scrape contract and D37's logging posture.
- Constructor-change call-site sweep (M1-175 precedent) done at draft
  time: `new AuditLogWriter(` appears exactly twice, both already
  passing a hook; no test constructs the no-arg form.
