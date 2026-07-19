---
name: new-admin-command-couplings
description: Adding a new bot-admin slash command trips 3 hidden couplings beyond the handler+bundle+catalogue that tickets routinely under-scope; check them up front to avoid mid-implementation scope-breach escalations.
metadata: 
  type: project
---

Adding a new bot-admin slash command in infochat trips three couplings that ticket
`files_scope` routinely omits (each cost a scope-breach escalation on M1-575 `/pending`).
Check all three before starting so you scope them from the outset:

1. **IntegrationTestNamingGuard** — a `@QuarkusTest` that `@Inject`s a `DataSource`
   (boots DevServices Postgres) MUST be named `*IT`, not `*Test`, or
   `IntegrationTestNamingGuardTest` (runs in infochat-core, scans all modules) fails.
   Existing `*Test`-named DB tests (e.g. `AuditCommandHandlerTest`) are grandfathered in
   `infochat-core/src/test/resources/integration-test-naming-baseline.txt` — do NOT add a
   new one to the baseline (forbidden workaround); name it `*IT`.

2. **Closed privileged-tier set has a Java mirror.** If the command joins the closed
   bot-admin set in `docs/spec/commands.md` §Permission model, you MUST also add it to
   `LlmOutputSanitizer.CLOSED_LIST` (infochat-provider/.../llm) — `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList`
   parses the spec closed-list and asserts it equals `CLOSED_LIST`. Patterns are derived
   from the list (`.stream().map(...)`), so no parallel array to sync.

3. **Privileged reads of sensitive data are audited-before-effect.** A bot-admin read
   that discloses sensitive/PII data (contact ids, deployment-wide enumeration) writes an
   audit row BEFORE the disclosure, like `AUDIT_READ` / `LIST_GROUPS` / `QUARANTINE_LIST`
   / `LIST_SOURCES_ALL` (spec security.md §Authorization model step 8). Add a new value to
   `AuditAction` (infochat-core) — `audit_log.action` is free TEXT (V5 §2.1.8), so **no
   migration**; `ProcedureOnlyActionSqlParityTest` only guards `ProcedureOnlyAction`, not
   `AuditAction`. Unprivileged reads (Summary, a user's own list) are NOT audited.

Also: command dispatch is CDI-auto-discovery — `InboundRouter` injects
`Instance<CommandHandler>` and matches on `name()`, so a new `@ApplicationScoped` handler
needs no registry edit. And `/m1-tick run` on a `status: draft` ticket first requires a
`draft→pending` promotion commit (`process: promote M1-NNN draft→pending`).
