---
id: M1-457
title: Fix /source-enable revive to allow all HTTP-shaped kinds
status: done
created: 2026-06-26
last_updated: 2026-06-26
blocked_by: []
reviews:
  - round: 1
    date: 2026-06-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 84
      removed: 9
clarity_check:
  date: 2026-06-26
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false claimed on a bot-admin command handler that writes audit rows; diff is low-risk (kind-check substitution in an already-audited path), but the reviewer should confirm the SOURCE_ENABLE audit write is exercised by the new sourceEnableRevivesSoftDeletedHttpNonRssKind test."
  blockers: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the main kind gate and the failed/disabled re-enable path  # already correct (STREAM_KINDS)
  - StreamSource (nostr) revive                                # genuine v1 deferral — needs the relay-probe primitive
  - any Flyway migration
  - M1-456's nitter /add-source work
acceptance:
  - SourceEnableCommandHandlerTest.sourceEnableRevivesSoftDeletedHttpNonRssKind passes
  - SourceEnableCommandHandlerTest.sourceEnableSoftDeletedNostrStillReturnsKindNotSupported passes
  - "Reviving a soft-deleted `reddit` (or `youtube`/`odysee`/`nitter`/`bluesky`) source succeeds: `deleted_at` cleared, `status='active'`, a `SOURCE_ENABLE` audit row written, and the no-subscriptions-restored disclosure returned"
  - "A soft-deleted `nostr` source still returns `error.source_enable.kind_not_supported_in_v1` (rejected at the main gate)"
  - the pre-existing rss soft-delete revive test still passes unchanged
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/03-commands.md §3.7
---

# M1-457: Fix /source-enable revive to allow all HTTP-shaped kinds

## Context

`/source-enable`'s soft-delete **revive** path (`executeRevive`) rejects
every kind except `rss` via `!"rss".equals(kind)` (`SourceEnableCommandHandler.java:287`
and the TOCTOU re-check at `:308`), returning `error.source_enable.kind_not_supported_in_v1`.
This is a stale placeholder: it was authored in M1-053 (2026-05-24) when
`rss` was the *only* HTTP fetcher, so `!"rss"` then meant "is this a stream
kind?". Two days later the other HTTP fetchers landed (nitter M1-089, reddit
M1-088, youtube M1-090, odysee M1-091). The handler's **main** kind gate and
the **failed/disabled** re-enable path were later generalized to
`STREAM_KINDS.contains(kind)` (commit `7810663a`, with the comment "reject
only stream-shaped kinds. Every HTTP-shaped kind … flows through the existing
probe-and-reactivate path"), but the parallel check inside `executeRevive`
was missed. The result: an admin can revive a soft-deleted `rss` source but
not a soft-deleted `reddit`/`youtube`/`odysee`/`nitter`/`bluesky` one — even
though the design (`docs/design/03-commands.md:743-750`) says all HTTP-shaped
kinds revive (HEAD probe). The `!"rss"` check is also redundant: the main gate
at `:189` already excludes stream kinds before `executeRevive` runs.

## Acceptance

- The two `!"rss".equals(kind)` checks in `executeRevive` (`:287`, `:308`) are
  replaced with `STREAM_KINDS.contains(kind)`, matching the main gate (`:189`)
  and the failed/disabled re-check (`:221`).
- Reviving a soft-deleted HTTP non-rss source (test seeds a `kind='reddit'`,
  `deleted_at IS NOT NULL` row) succeeds end-to-end: confirm prompt → confirm
  → `deleted_at` cleared, `status='active'`, `consecutive_failures=0`, a
  `SOURCE_ENABLE` audit row, and the no-subscriptions-restored disclosure.
  Test: `sourceEnableRevivesSoftDeletedHttpNonRssKind`.
- A soft-deleted `nostr` source still returns `error.source_enable.kind_not_supported_in_v1`
  (rejected at the main gate, no probe, no state change). Test:
  `sourceEnableSoftDeletedNostrStillReturnsKindNotSupported`.
- The existing rss soft-delete revive test
  (`sourceEnableSoftDeletedConfirmWithinWindowRunsProbeAndRevives`) still
  passes unchanged.
- `mvn verify` is green.

## Out-of-scope

The main gate and the failed/disabled re-enable path already use the correct
`STREAM_KINDS` check — don't touch them. **Stream-kind (nostr) revive stays
rejected**: that is a genuine, documented v1 deferral (no relay-probe
primitive until `StreamSourceSupervisor` lands — M1-053 §out-of-scope), not
this bug. No bundle-key change is needed (`KIND_NOT_SUPPORTED_IN_V1` is still
used, now only for stream kinds). No migration. This ticket is independent of
M1-456.

## Notes

- This is a pure code-to-spec reconciliation: the design doc already promises
  HTTP-shaped revive, so no spec amendment is required — only the code is
  brought into line. (The doc's mention of a *relay* probe for StreamSource
  revive remains aspirational/deferred and is intentionally left as-is.)
- After this lands, M1-456's forced-`nitter` sources are fully revivable like
  any other HTTP kind — removing the only behavioural downside of choosing
  `nitter` over `rss`.
- Adjacent code: `reactivateFailedOrDisabled` (`:205`) is the reference for
  the correct gate shape; `STREAM_KINDS = Set.of("nostr")` (`:92`).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-457-source-enable-revive-http-kinds.md
```
