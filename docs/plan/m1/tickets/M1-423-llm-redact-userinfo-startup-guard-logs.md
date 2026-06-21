---
id: M1-423
title: "llm: redact userinfo in LlmRouterStartupGuard base-url logs"
status: done
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
files_budget: 4
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - LlmHttpSupport.requireHttpBaseUrl and its failure-branch redaction — already done by M1-401; only its redactUserInfo helper's visibility changes here.
  - The validation/routing logic of the guard (which routes are rejected, the loopback check, assertAllTasksResolve) — unchanged; only the diagnostic-message/log content is made leak-safe.
  - Provider-name and host-only log lines in the guard (e.g. "provider=anthropic", the DNS-failure "host" WARN at isLoopback) — these carry no userinfo and are left as-is.
  - Any base-url consumer outside infochat-llm-adapter.
acceptance:
  - "LlmRouterStartupGuard routes every base-url value it echoes into a LOG.warnf / LOG.fatal line or into the LocalOnlyConflictException message through LlmHttpSupport.redactUserInfo, so a credential-bearing base-url (e.g. https://user:pass@host/v1) cannot appear verbatim in the boot log or the thrown exception."
  - "LlmHttpSupport.redactUserInfo is promoted from package-private to public static so the routing package can call it; its behavior is otherwise unchanged."
  - "A new test in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing asserts that validateLocalOnlyConfiguration, given local-only=true plus a credential-bearing embedding base-url, throws LocalOnlyConflictException whose message does NOT contain the credential substring (user:pass)."
  - "A new test uses the existing CapturingHandler to assert the non-local-only remote-embedding WARN and the warnRemoteLlmTaskRoutes WARN emit redacted base-urls (no credential substring) for a credential-bearing base-url."
  - "Existing LlmRouterStartupGuardLocalOnlyTest, LlmRouterStartupGuardLoopbackTest, and LlmRouterStartupGuardKeyDerivationTest remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing (credential-redaction assertions for the guard's fatal + warn paths)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/llm.md §Per-task routing rules
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 158
      removed: 13
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-21
    verdict: CLEAN
    base: f46db8c5f5a3676ec098c31a62ae5e5c03729d9a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-423-2026-06-21.md
    out_of_model_count: 0
    note: |
      Pre-commit adversarial review of the credential-redaction diff on the
      in-progress branch. CLEAN — all base-url echoes routed through
      LlmHttpSupport.redactUserInfo; M1-330 leak class closed on the
      startup-guard surface. No findings, no out-of-model observations.
clarity_check:
  date: 2026-06-21
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-423: redact userinfo in LlmRouterStartupGuard base-url logs

## Context

Deep-review full (2026-06-21) llm-adapter finding **F1** (SECURITY). Verified at
source 2026-06-21.

M1-330/M1-401 established that a credential-bearing LLM base-url
(`https://user:pass@host/v1`) must never reach a diagnostic message or the boot
log, and built `LlmHttpSupport.redactUserInfo` (now `static`, package-private) for
exactly that. M1-401 closed the leak inside `LlmHttpSupport.requireHttpBaseUrl`.

`LlmRouterStartupGuard` (`infochat-llm-adapter/.../routing/LlmRouterStartupGuard.java`)
is a separate `@Startup` bean (run by both Collector and Provider) that reads
base-urls **raw** via `snapshotConfig` (no validation) and echoes them verbatim in
several places:

- the non-local-only remote-embedding `LOG.warnf` (lines 203-205),
- the `LocalOnlyConflictException` FATAL message built from `offenders`, which
  include `base-url=<embeddingBaseUrl>` (lines 249-250) and per-task
  `base-url=<offHostBaseUrl>` (lines 222-224), logged at line 274,
- the `warnRemoteLlmTaskRoutes` per-task WARN (lines 307-311),
- the malformed-URI WARN in `isLoopback` (lines 396-397).

The FATAL/local-only-conflict path is the sharpest: the guard logs the credential
**and throws to abort boot before any provider's `requireHttpBaseUrl` runs**, so
M1-401's redaction never gets a chance on that path. This re-opens the M1-330 leak
class on a surface M1-401 did not cover. Operator-supplied secret, so a
misconfiguration-time leak — but the config boundary is exactly where the project
has chosen to make this airtight.

## Acceptance

See frontmatter. The shape: make `redactUserInfo` `public static`, then wrap every
base-url echoed into a log/exception line in `LlmRouterStartupGuard` with it. The
provider-name offenders (`provider=...`) and the host-only DNS-failure WARN carry no
userinfo and are left unchanged.

## Out-of-scope

See frontmatter. Diagnostic/log hardening only — no change to which routes the guard
accepts or rejects.

## Notes

- Decision-record anchor: restores the M1-330 invariant ("no userinfo in LLM
  base-url diagnostics") on the startup-guard surface; sibling of M1-401.
- The routing test dir already has `CapturingHandler.java` (a JBoss log handler for
  assertions) and `LlmRouterStartupGuardLocalOnlyTest` — reuse both rather than
  standing up new log-capture infrastructure.
- Alternative considered: duplicate a small redactor into the `routing` package
  instead of widening `redactUserInfo` visibility. Rejected — one shared redactor is
  the single-source posture M1-401 set; two copies can drift.
