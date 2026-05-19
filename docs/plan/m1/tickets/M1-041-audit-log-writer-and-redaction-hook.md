---
id: M1-041
title: Audit-log writer + RedactionHook + LlmOutputSanitizer audit row
status: deferred
created: 2026-05-19
last_updated: 2026-05-19
deferred_reason: post-mvp-audit-writer-consolidation
deferred_on: []
blocked_by: []
files_budget: 12
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-core/src/main/resources/db/migration/V<N>__llm_output_sanitized_action.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/AuditLogWriterIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/RedactionHookTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
remediates: M1-037 M1-033
out_of_scope:
  - any change to V5__identity_audit.sql, V6/V8 audit_log GRANTs, or the audit_log column shape itself — this ticket only adds a new audit verb (LLM_OUTPUT_SANITIZED) to the action CHECK constraint and consolidates the application-layer writer
  - any change to audit_log triggers (append-only triggers from M1-008a and the actor-integrity trigger from M1-021 if it has landed by start time)
  - any change to bootstrap admin / Stage 2 release-on-failure logic beyond migrating the audit INSERT call site onto the new writer
  - any feature additions to /audit (the admin review command for sanitizer events lives in a separate T2-G follow-up)
  - any operator-side log retention / log shipping work
  - any change to /summary's prose generation, cluster traversal, or degraded fallback path (M1-040 territory)
acceptance:
  - "AuditLogWriter.java is the SOLE INSERT path into audit_log for application-layer writers. grep -rn 'INSERT\\s+INTO\\s+audit_log' infochat-collector/src/main infochat-provider/src/main infochat-core/src/main returns matches only inside AuditLogWriter.java and SECURITY DEFINER stored procedures (delete_preban_user, approve_quarantine etc. — those carry the carve-out per V5/V6/V10)"
  - "RedactionHook is an SPI interface in infochat-core/audit with a single `redact(AuditRow): AuditRow` entry. The default implementation applies the closed redaction catalogue from docs/spec/security.md §Secrets handling to the row's `details_json` and `target_contact_id` fields; an alternative implementation can be wired via CDI alternative for testing"
  - "A new Flyway migration at the next free V<N> integer adds LLM_OUTPUT_SANITIZED to the audit_log.action CHECK constraint (and any other new verbs this consolidation requires). grep -E 'LLM_OUTPUT_SANITIZED' V<N>__llm_output_sanitized_action.sql returns at least one match"
  - "LlmOutputSanitizer emits one audit_log row per sanitizer hit (NOT throttled) via AuditLogWriter, per docs/spec/security.md §LLM output sanitizer 'Every match is audit-logged (per-occurrence, not throttled)'. The action is LLM_OUTPUT_SANITIZED; details_json carries the match-count + match-kind enumeration without the user-visible LLM output text"
  - "StartupReleaseOnStage2FailureWarn migrates its raw-JDBC INSERT onto AuditLogWriter — the writer call site replaces the inline INSERT. M1-033's audit-row tests continue to pass with the new writer (semantics preserved)"
  - "AuditLogWriterIT covers: (a) happy-path INSERT round-trips through the redaction hook; (b) redaction hook is applied (a row whose details_json contains an API-key shape emerges with the key redacted); (c) the writer is transaction-safe (calling INSIDE a @Transactional method commits in the same tx as the surrounding work)"
  - "LlmOutputSanitizerAuditRowIT covers: a /summary call whose LLM output triggers two sanitizer hits writes EXACTLY two audit_log rows with action=LLM_OUTPUT_SANITIZED (the per-occurrence promise — NOT one coalesced row per call)"
  - "mvn -B clean verify from the repo root exits 0; all existing audit-log-touching tests continue to pass"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/audit/AuditLogWriterIT.java
    - infochat-core/src/test/java/app/zcat/infochat/core/audit/RedactionHookTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Secrets handling (audit-log redaction hook)
  - docs/spec/security.md §Audit
decision_refs: []
---

# M1-041: Audit-log writer + RedactionHook + LlmOutputSanitizer audit row

## Context

Two related red-team findings cluster on the same architectural
gap — the spec promises a redaction-hook layer for audit-log writes
but no `AuditLogWriter` middleware exists in the repo. Every
`INSERT INTO audit_log` in M1 to date is raw JDBC:

1. **M1-037 finding 2 (medium AUDIT-EVASION)** — The
   `LlmOutputSanitizer` logs sanitizer hits at WARN via JBoss
   logging but writes no `audit_log` row. The spec's §LLM output
   sanitizer commits "Every match is audit-logged (per-occurrence,
   not throttled)." An admin running `/audit` (the spec-promised
   review surface for sanitizer events) sees nothing.

2. **M1-033 OUT-OF-MODEL #3** — `StartupReleaseOnStage2FailureWarn`
   uses raw JDBC for its audit INSERT, bypassing the spec's
   redaction-hook layer. The new row has no user-content fields
   (system-actor + operator-config profile name), so the bypass
   is harmless TODAY — but it's the project-wide pattern, and
   audit-log writes that DO carry user content will start landing
   as soon as T2-A wires `/grant-admin`, `/ban`, `/unban` etc.

## Why this is deferred

**Today's audit-log call sites are 2 — both system-actor, neither
carries user content.** The redaction-hook layer's job is to
catch user-derived data (contact ids, message bodies, post titles)
before it reaches the durable audit row. With only system-actor
rows in play, the hook has nothing to actually redact.

The writer's job is to give every call site one place to flow
through — but with only 2 call sites, the abstraction earns less
than its weight in maintenance overhead. The right time to build
both is when the surface stabilizes:

- **T2-A** lands `/ban`, `/unban`, `/grant-admin`, `/revoke-admin`,
  `/promote`, `/demote`, `/vouch`, invite-consume — every one
  writes an audit row with user-derived fields (`target_contact_id`,
  `details_json` carrying actor display names).
- **T2-B** lands `/save`, `/forget` adjacent paths that write
  audit rows carrying saved-post titles, source URLs.
- **T2-E** lands `/forget` and `/export` — privacy commands whose
  audit rows MUST exclude the very content they're operating on.

After T2-A/B/E are merged, all ~10 audit-write call sites are
known. The redaction hook can be designed against the full
call-site picture; the writer's API is informed by every actual
consumer; the migration of existing call sites + the new
LlmOutputSanitizer hit happens in one coordinated ticket.

## What we accept as residual risk meanwhile

- **`LlmOutputSanitizer` hits are not in audit_log.** They are in
  stdout/journald at WARN with a canonical `error_class` string.
  An operator who needs the signal today can grep the structured
  log. `/audit` (which doesn't exist yet either) will show nothing
  until this ticket lands; this is consistent with the spec's
  `LLM_OUTPUT_SANITIZED` verb being absent from the V5 CHECK
  constraint at M1's current state.

- **Stage 2 release-on-failure audit row bypasses the (nonexistent)
  redaction hook.** Harmless today because the row carries
  no user content. The migration to the writer is mechanical
  when the writer lands.

## Definition of Done

(Authored speculatively — refine before unblocking via
`/m1-tick reopen M1-041`)

- `AuditLogWriter` in `infochat-core/audit/` is the sole
  application-layer INSERT path into `audit_log`. SECURITY
  DEFINER stored procedures (`delete_preban_user`,
  `approve_quarantine`, …) carve out with their own internal
  INSERTs per V5/V6/V10.
- `RedactionHook` is a single-method SPI in `infochat-core/audit/`;
  the default impl applies the spec's closed redaction catalogue
  to `details_json` and `target_contact_id` before INSERT.
- A new Flyway migration adds `LLM_OUTPUT_SANITIZED` to the
  `audit_log.action` CHECK constraint.
- `LlmOutputSanitizer` emits one audit row per sanitizer hit
  via the writer (NOT throttled, NOT coalesced).
- `StartupReleaseOnStage2FailureWarn` migrates its raw INSERT
  onto the writer.
- Tests pin the redaction-hook semantics, the per-occurrence
  audit row count for sanitizer hits, and the transactional
  semantics of the writer.

## Implementation notes

- **Spec the writer from the call-site picture, not from first
  principles.** Before implementing, audit every existing
  `INSERT INTO audit_log` call site (raw JDBC) and every T2-A/B/E
  call site that will land. The writer's API should be the
  minimal shape that ALL of them can call without per-call-site
  ceremony.
- **Redaction hook semantics.** The default impl applies the
  closed redaction catalogue from §Secrets handling — contact ids
  → prefix+ellipsis+suffix; API-key shapes → `[REDACTED]`. The
  hook receives the constructed audit-row record and returns a
  redacted variant; the writer never sees the unredacted form
  past the hook.
- **Per-occurrence sanitizer audit.** §LLM output sanitizer is
  unambiguous: "Every match is audit-logged (per-occurrence, not
  throttled)." Two hits in one /summary reply → two rows. This
  contrasts with `details_json`-coalesced shapes used elsewhere.
- **Don't touch the SECURITY DEFINER procedures.**
  `delete_preban_user` (V5 + V6 from M1-021 when it lands) and
  `approve_quarantine` (V10 from M1-032) carve out an
  internal-INSERT path. Those continue to write directly; the
  writer governs everything else.

## Big-picture notes

- **Order in the dependency graph.** Run this AFTER T2-A/B/E
  are merged but BEFORE the M1 release tag. The spec promise
  ("every match is audit-logged per-occurrence") is currently
  silently downgraded; this ticket closes the gap before tag.
- **`/audit` admin command depends on this.** When `/audit`
  lands (likely T2-G with the rest of the admin-review surface),
  it queries `audit_log` for sanitizer events. Those rows MUST
  exist by then.
- **The redaction-hook layer is the seam for future operator-
  side requirements.** Some operators may want stricter
  redaction (e.g. dropping all `target_contact_id` values to
  meet an external compliance requirement). The hook is the
  hook for that policy.

## Out-of-scope expansion

- See `out_of_scope` block — the deferred state means this
  ticket has not been authored against a specific build of the
  T2-A/B/E call sites; the body above is a placeholder for the
  consolidation work, not a frozen specification.

## Authorized test changes

- (TBD — refine before unblocking; the migration of existing
  M1-008a / M1-033 audit-row tests onto the new writer may
  require listed authorized changes here.)

## Alternatives considered

- **Build the writer now (post-Tier-1).** Rejected — see "Why
  this is deferred". Only 2 call sites today; designing the
  API against 2 vs. 10 produces a different (less informed)
  shape.
- **Skip the writer; just add the sanitizer audit row inline.**
  Rejected — the M1-033 OOM #3 finding is the same root cause.
  Splitting them produces two tickets that solve the same
  architectural gap one row at a time.
- **Build the redaction hook as a JDBC `Connection` wrapper
  rather than an application-layer SPI.** Rejected — the
  redaction must be visible in code review (a hidden wrapper
  is invisible to reviewers and to the spec's promise that
  redaction is auditable).
