---
id: M1-406
title: "cleanup: clarity and comment-policy drift sweep (3 files)"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any behavior change. All three edits are clarity-only — a statement reorder with an identical result, a duplicate-comment removal, and a comment addition. No control flow, no query, no output, no thrown exception changes.
  - The membership-vs-body dispatch logic in SignalGroupHandler.handleReceive — only a WHY-comment is ADDED documenting the existing assumption; the either/or control flow (dispatch membership and return) is NOT changed, and the speculative fall-through alternative is explicitly NOT taken.
  - KrakenSnapshotSource's TICKERS map, SUPPORTED_VS set, and the thrown FetchException messages — unchanged; only the position of the vsUpper computation relative to the unsupported-vs guard moves.
  - The connect-timeout value and the HttpClient construction in OpenAiCompatibleProvider — unchanged; only the duplicate field-javadoc copy of the WHY-rationale is removed.
acceptance:
  - "KrakenSnapshotSource.fetchSnapshot computes vsUpper AFTER the unsupported-vs guard (guard-first per the CLAUDE.md early-return style), so the toUpperCase work is not done on a value the guard is about to reject; the supported-set check and the thrown FetchException are unchanged."
  - "OpenAiCompatibleProvider documents the connect-timeout WHY-rationale once — at the constructor where the timeout is set, matching AnthropicProvider — and the duplicate field-javadoc copy is removed, leaving the field uncommented."
  - "SignalGroupHandler.handleReceive carries a WHY-comment naming the signal-cli protocol guarantee it relies on (a member delta and a chat `message` body arrive on SEPARATE groupV2 notifications, never the same one) that makes dispatching membership and returning safe; the control flow is unchanged."
  - "No test changes are required; all tests currently green on main stay green. mvn -B clean verify from the repo root exits 0."
test_plan:
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

# M1-406: clarity and comment-policy drift sweep

## Context

Deep-review full (2026-06-19) surfaced three independent, zero-behavior-change
clarity/comment-policy drift items across three modules. They are bundled here to
avoid three separate one-line-fix tickets; each is a CLAUDE.md §Coding-style or
§Comment-policy nit with no functional effect. All verified at source 2026-06-19:

- **collector F2** (KrakenSnapshotSource.java:101-104) — `vsUpper` is computed one
  line BEFORE the guard that may reject the same `vs`, against the §"Early return /
  early exit" style (guards first, main-path work after).
- **llm F2** (OpenAiCompatibleProvider.java:98-118) — the connect-timeout WHY is
  written twice (field javadoc + constructor comment) for one fact; `AnthropicProvider`
  carries only the constructor copy. §Comment-policy: "the simpler the implementation,
  the less commenting it needs."
- **messaging F2** (SignalGroupHandler.java:143-148) — `handleReceive` dispatches
  membership and returns before checking for a chat body, which is correct only
  because signal-cli never emits both on one `groupV2` notification — an undocumented,
  untested protocol assumption whose silent failure mode is dropping a legitimate
  bot-mention. §Comment-policy: a "hidden constraint / subtle correctness argument"
  warrants a WHY-comment.

## Acceptance

See frontmatter. Three surgical edits, one per file, all behavior-preserving.

## Out-of-scope

See frontmatter. No control-flow change anywhere; the SignalGroupHandler item is the
comment-only form deliberately (the speculative fall-through is NOT implemented,
since the combined-frame shape does not occur today).

## Notes

- These three were parked as the cosmetic one-liners in the deep-review reports
  (collector F2, llm-adapter F2, messaging-adapter F2); the substantive findings from
  the same run are M1-401 (llm SECURITY), M1-402 (SimpleX reconnect), M1-403 (re-eval
  perf), M1-404 (Stage 1 dedup), M1-405 (dispatcher accounting).
- Adjacent code for the llm item: AnthropicProvider (single constructor comment) is
  the symmetric pattern to match.
