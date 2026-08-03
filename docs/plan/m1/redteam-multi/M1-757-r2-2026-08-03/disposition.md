# Disposition — M1-757 re-audit (multi-auditor)

Date: 2026-08-03
Target: M1-757 (in-progress, uncommitted branch tip)
Auditors: codex (codex-cli 0.146.0), opencode (1.18.11)
Base: c4b21b52, head: working-tree (remediation diff, 405 lines)

## Verdicts

- opencode: CLEAN
- codex: CLEAN

Cross-examination: 0 finding clusters, 0 corroborated, 0 single-auditor.
The prior FINDINGS (INFO-LEAK / low — drains at the tail of the failure
catch block, after the fallible failure-recording sub-path) is confirmed
closed: both failure-path drains now run at the top of the catch block
(FetchScheduler.java:527-528), ahead of every fallible statement, so no
exception from the recording sub-path can skip them.

Both auditors received the standard redteam prompt plus an appended
re-audit note (see `reaudit-note.txt`) listing the prior finding and
explicitly authorizing a CLEAN verdict.

## Out-of-model items (advisory, no security commitment)

1. Thread-confinement of the signal channel (opencode; carried forward
   from the r1 audit): a hypothetical async-paginating fetcher would
   silently lose its cap-hit/truncation signal. No current fetcher does
   this. Recommendation: no follow-up ticket.
2. Failure-recording sub-path robustness (opencode): if the catch
   block's failure-recording sub-path throws an unchecked exception,
   the D42 ladder increment and streak-reset `recordTick(false)` are
   skipped for that tick. Benign with respect to the closed finding
   (both drains have already run); pre-existing, not introduced by this
   diff; the precondition is an internal fault (DB down / repository
   defect — DB is inside the trust boundary), not an adversary-reachable
   path. Recommendation: no follow-up ticket.

## Contamination check

Both auditors: clean (no writes outside the evidence directory).

## Committed evidence

- `verdict-opencode.txt`, `verdict-codex.txt`, `cross-examination.md`
- `reaudit-note.txt` (provenance of the re-audit framing)
- This `disposition.md`

Scratch (`prompt-*`, `reply-*`, `inv-*`, `diff.patch`, `preflight.txt`)
is gitignored and not committed.
