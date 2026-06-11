---
id: M1-312
title: "Doc-truth v5: false comments and spec/design drift reconciliation"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 26
files_scope:
  - docs/design/01-architecture.md
  - docs/design/04-security.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/09-reference.md
  - docs/spec/schema.md
  - infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  - infochat-core/src/main/resources/db/migration/V9__provider_state.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/AdminNotificationRecord.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/config/InfochatProfile.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/config/InfochatProfile.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Items absorbed by code tickets — MessagingException/classifyError comments (M1-284/M1-294), StageProgressNotifier 'not exposed' (M1-306), StreamSourceSupervisor.stop (M1-286), preview() label + LinkingJob (M1-292), LlmRouter.Entry ctor (M1-296), logging facades (M1-308), setNullableUuid (M1-298), provenance-stripping (M1-311).
  - Any executable-code change; this ticket edits comments, docs, and SQL comments only.
  - providerName() ArC-proxy-naming coupling (deepseek/04#F1) — robustness note, backlogged.
acceptance:
  - "U-71 comment-truth fixes, each anchored: (a) PinnedDnsResolver pin()'s 'still-active holder' invariant claim that release() doesn't uphold is corrected. (b) LlmProvider 'land with the first concrete impl' / LlmRouter 'only one concrete impl ships' / the guard javadoc calling a public method 'package-private' are corrected. (c) SignalMessageCodec.canonicalizeAci documents a branch that doesn't exist — corrected. (d) Stage1Worker's javadoc names a phantom mp.messaging…broadcast=true property — corrected to the real wiring (skip if M1-295's boundary rewrite already fixed it; re-grep at start). (e) Stage1PipelineIT's committed editor narration ('actually no… Let me re-check.') is removed. (f) AdminNotificationRecord.errorClass rotation overstatement corrected. (g) both InfochatProfile copies' stale 'duplication goes away once infochat-core lands' note corrected (core exists)."
  - "U-72 doc-drift fixes, each anchored: (a) design 01-architecture §1.1 diagram drops removed new_price_snapshot. (b) V9's comment claiming the quarantine_review reconciler 'ships in M2' corrected (it shipped). (c) design 09-reference's 'two deliberate CDI beans in core' updated (AuditLogWriter/DefaultRedactionHook exist). (d) the module table documents that ingest SPIs live in core. (e) V5's 23-of-54 verb-comment catalogue is replaced with a pointer to AuditAction. (f) the Stage-1 regex expansions (override/skip/sudo/superuser/owner/maintainer…) are added to design 04-security §4.2 — update the design, keep the code. (g) remote-embedding confirm log: keep WARN, amend design §5.10 (which says INFO). (h) a design note records the Stage1→Stage2 in-process fusion vs the layered diagram. (i) supportsCodeFormatting's zero-consumer status is documented as v2-pending (the flag STAYS — it is spec-pinned in CLAUDE.md key conventions; do not remove). (j) STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE baking a config value into the verb gets a clarifying comment at the enum (a rename needs a CHECK-set migration — out of scope; note it)."
  - "Spec-text fix from the report's cross-lens list: docs/spec/schema.md (or security.md, wherever §DB roles lives) reconciles the audit_log_view grant wording with V43 and the V31 Provider write-grants with the role description (fable-5/02 obs) — text only, no migration."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-312: Doc-truth v5: false comments and spec/design drift reconciliation

## Context

Deep-review v5 verified **U-71** (load-bearing comments asserting
verifiably false facts — fable-5 CT2, every item individually confirmed)
and **U-72** (spec/design text drift, all confirmed, doc-only)
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; per-item sources listed there
— gitignored; every item inlined in acceptance). M1-271 is the v4
precedent for this combined comment-truth + doc-drift ticket.

## Acceptance

See frontmatter. Items absorbed by code tickets are explicitly excluded in
out_of_scope — re-grep each (d)-style conditional item at start because
the code tickets land first and may have fixed or deleted the anchor.

## Out-of-scope

See frontmatter.

## Notes

- **Migration-file comment edits (V5, V9) change Flyway checksums.** M1 is
  greenfield (no production DBs; test DBs are ephemeral and migrate from
  scratch), so the edit is safe today — state this in the commit message.
  If any long-lived DB exists by implementation time, switch to a
  `flyway repair` note in the deployment docs instead of skipping the fix.
- (j) records the judgment call instead of forcing it: renaming an audit
  verb touches the V5 CHECK set; that's a migration ticket if ever wanted.
- Run after the v5 code tickets, alongside or after M1-311 (which strips
  provenance from some of the same comment blocks). Regenerate every
  anchor by grep at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-312-*.md
```
