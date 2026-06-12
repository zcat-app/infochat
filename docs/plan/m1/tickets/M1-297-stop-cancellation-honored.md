---
id: M1-297
title: "/stop cancellation is honored: cancelled flag, stopped terminal, single reply"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - pg_cancel_backend mechanics and the PID registration seam — already wired; only the Java-side flag/terminal handling changes.
  - The progress-notifier edit cadence (M1-306 owns the minEditInterval fix).
  - Rate-cap accounting for cancelled requests.
acceptance:
  - "InFlightTracker.CancellationHandle gains a cancelled flag; /stop marks it BEFORE interrupting (today there is no flag at all — verified 2026-06-11, no 'cancelled' state in the file), and delivery boundaries check it: a worker that missed the interrupt (e.g. it was between interruptible points) has its result DISCARDED at delivery instead of delivered as if /stop never happened; a named test races a completed result against a /stop and asserts no content reply."
  - "Cancelled work renders the spec'd stopped terminal state (decision D31) instead of the failure reply: a progress.stopped bundle key exists (en + cs), the progress sequence terminates in it, and a named test asserts a landed interrupt yields the stopped terminal — today it renders 'Something went wrong… try again', which D31 forbids."
  - "The chat path no longer double-replies on cancellation ('Cancelled…' followed by 'assistant unavailable'): a named test asserts exactly one reply for a cancelled chat request."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 271
      removed: 17
revisions:
  - date: 2026-06-12
    reason: budget-breach refine — acceptance item 2's stopped-terminal
      replacement must edit the only main-source caller of
      progressNotifier.fail(), SummaryCommandHandler.java, which the
      original files_scope omitted (it listed only StopCommandHandler.java
      for the main command package). The decision cannot move into an
      in-scope messaging/ file because CancellationService.cancel()
      releases the CancellationHandle before the summary worker's catch
      reaches fail().
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
escalations:
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — out-of-scope-path trigger during implementation. Acceptance
      item 2 ("Cancelled work renders the spec'd stopped terminal instead
      of the failure reply ... today it renders 'Something went wrong...
      try again'") requires editing the only main-source caller of
      progressNotifier.fail(), which is
      infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java.
      files_scope lists only command/StopCommandHandler.java for the main
      command package, so SummaryCommandHandler.java is out of scope. The
      cancelled->stopped decision cannot move into an in-scope
      messaging/ file (StageProgressNotifier) because
      CancellationService.cancel() releases the CancellationHandle from
      InFlightTracker before the summary worker's catch calls fail(), so
      the notifier can no longer observe the cancelled flag.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - 'Acceptance item 2 cites decision D31 as the authority for the stopped terminal state, but D31 (Progress notifications) does not define it; D35 (Request cancellation) is the correct decision ("Progress notifier renders a final stopped state"). Inline description is sufficient to implement; the wrong cite may cause confusion.'
  blockers: []
---

# M1-297: /stop cancellation is honored: cancelled flag, stopped terminal, single reply

## Context

Deep-review v5 verified MEDIUM **U-17**
(`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources `fable-5/07#F1` (most
complete), `gpt-55#M-13` — gitignored; all load-bearing facts inlined):

`/stop` interrupts the in-flight worker but nothing records that
cancellation happened: no cancelled flag on `InFlightTracker
.CancellationHandle`, no `progress.stopped` bundle key or terminal state.
Three user-visible defects follow: (1) a missed interrupt delivers the
"discarded" result anyway; (2) a landed interrupt renders the generic
failure reply where spec decision D31 requires a "stopped" terminal;
(3) the chat path double-replies ("Cancelled…" then "assistant
unavailable").

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Fix shape (fable-5): `markCancelled()` before interrupt; check at the
  delivery boundaries (progress-notifier terminal and chat reply path);
  add the stopped terminal rendering. The flag is the source of truth —
  interrupt status alone cannot distinguish /stop from other interrupts.
- The delivery-boundary check is Provider-internal state, not defensive
  code — the flag is the system of record for "user said stop".
- files_scope carries both chat and messaging packages because the
  terminal rendering lives in the progress-notifier path; budget headroom
  covers the bundle files (en + cs).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-297-*.md
```
