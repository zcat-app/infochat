---
id: M1-307
title: "Provider code lows: JSON conventions, sanitizer seam, dead keys, misc one-liners"
status: done
created: 2026-06-11
last_updated: 2026-06-13
blocked_by: []
files_budget: 18
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Behaviour changes beyond the enumerated items — this is a lows sweep; every changed line traces to one list entry.
  - The /retry-counter and chat-tool items (M1-306) and localization labels (M1-303).
  - quoteJsonString()/reply() helper dedup (M1-309's domain; the serializeClusterMap and writeAuditRow items here only route through the EXISTING core JsonEscaper).
acceptance:
  - "U-62: SummaryCommandHandler.serializeClusterMap (:445) builds persisted JSON via the module's JsonEscaper convention instead of raw concatenation (safe for today's t-…/p-… ids; the divergence is the defect); persisted bytes for existing id shapes are unchanged (named test)."
  - "U-63: ChatAgent.writeAuditRow's details_json raw concatenation routes through the same convention (internal-only values today; opus-47's SECURITY/medium framing is downgraded by the report — treat as convention fix); existing audit assertions stay green."
  - "U-64: LlmOutputSanitizer's null-guards on @Inject fields (:257 'auditLogWriter == null || dataSource == null') — a test seam in the production path that silently disables the spec-committed LLM_OUTPUT_SANITIZED audit emission — are replaced by an explicit seam constructor; production CDI path always audits; a named test pins that sanitization without audit wiring is impossible in the CDI path."
  - "U-65: PendingInviteRow.expectedContactId gains @Nullable (null for OPEN_ADAPTER rows; the adjacent component is already annotated; one token + any NullAway fallout)."
  - "U-68 one-liners, each verified 2026-06-11: (a) value-less --vs no longer silently ignored — AssetHandler:162 'if (\"--vs\".equals(tokens[i]) && i + 1 < tokens.length)' skips the flag when the value is missing; reply with the usage message instead; named test. (b) TranslationPipeline:81 new Locale(…) → Locale.of (deprecated ctor). (c) the five bare RuntimeException sites (DigestScheduler:190,:315; InviteCommandHandler:276,:552; BanCommandHandler:231) become IllegalStateException per house convention. (d) InFlightTracker:10's unused AtomicReference import is dropped. (e) dead CHAT_MODE_REPLY constant (InboundRouter:204) and its assertNotEquals guard in InboundRouterTest are removed. (f) dead bundle key REPLY_WELCOME_GROUP_FIRST_MENTION (BundleKeys:294, dead post-D47) and its bundle entries are removed."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 19
      added: 131
      removed: 65
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE U-68: sub-items (b) Locale.of, (c) five RuntimeException→IllegalStateException replacements, and (d) unused import removal have no named automated test; verifiable only by diff inspection (mvn verify compile step catches (d)). Ticket already labels them 'each verified 2026-06-11'."
  blockers: []
---

# M1-307: Provider code lows: JSON conventions, sanitizer seam, dead keys, misc one-liners

## Context

Deep-review v5 verified **U-62**, **U-63**, **U-64**, **U-65**, and the
provider members of the **U-68** one-liner list
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `opus-48/07#F2/#F3`,
`opus-47/07#F4/#F6/#F7/#F8`, `fable-5/07#F5/#F6`, `gpt-55#L-11/L-12`,
`deepseek/07#F3/#F4`, `mimo/7#F1` — gitignored; every item is inlined with
file:line in acceptance, all re-verified 2026-06-11).

M1-261 (v4 consistency-code-lows) is the precedent shape: one sweep, every
line traceable to a named entry.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Dead-code removals (e) and (f) delete a test guard and bundle entries —
  that's authorized test modification, named here per the test-integrity
  rules (the guard exists only to pin the dead constant).
- Coordination: heavy file overlap with M1-303/M1-306/M1-288 — sequence
  consciously at start; this sweep rebases easily, land it last among the
  provider cluster.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-307-*.md
```
