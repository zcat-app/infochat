# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-774/docs/plan/m1/redteam-multi/M1-774-r2-2026-08-05`
Auditors: claude, codex, opencode

> **Disposition is the USER's decision, per finding.** Before drafting a
> follow-up ticket, allocating an ID, editing the operand ticket beyond its
> audit record, touching a source file, or writing a disposition, ASK the
> user to choose: (1) fix in the current ticket's scope, (2) defer to a new
> ticket, (3) accept as a stated residual, (4) raise a spec amendment.
> Attach your recommendation to the question -- it does not replace it.
> This applies to corroborated, single-auditor and out-of-model items
> alike, and to findings that are pre-existing or apparently fenced by
> `out_of_scope`; those facts are inputs to the decision, not substitutes.

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'codex': 1, 'opencode': 2}.

## Per-auditor verdicts

- **claude**: CLEAN (0 finding(s))
- **codex**: FINDINGS (1 finding(s))
- **opencode**: FINDINGS (2 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | codex | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `GroupApprovalCheck.java:128` | -- | -- | low | low | opencode-only -- needs review |
| 2 | DOS | `InboundRouter.java:728` | -- | low | -- | low | codex-only -- needs review |
| 3 | DOS | `InboundRouter.java:830-843` | -- | -- | low | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `GroupApprovalCheck.java:128`

**opencode** (severity: low, fix-class: rate-limit)

- PROMISE: "Per-group reply rate (D47) — a single bucket per `groups` row bounding total outbound replies (fixed or command) within a sliding window. Applies to ALL approval states (pending, approved, rejected) so outbound cost is bounded even for unapproved groups." (docs/spec/security.md §Rate limiting) and step 3.5: "Before sending any reply (fixed or command), check the per-group reply rate bucket. If th...
- GAP (first 400 chars): The per-group reply bucket is drawn exactly once per inbound at step 3.5 (GroupApprovalCheck.check → rateCapBucket.tryAcquireGroupReply, GroupApprovalCheck.java:128). The three new M1-774 drains each emit an additional outbound (the REPLY_CONFIRM_CANCELLED acknowledgement) after that single draw, on top of the rejection reply the path already sent: InboundRouter.java:740-744 (chat body cap), :756-...


### Cluster 2: DOS @ `InboundRouter.java:728`

**codex** (severity: low, fix-class: rate-limit)

- PROMISE: "Per-group reply rate (D47) — a single bucket per groups row bounding total outbound replies (fixed or command) within a sliding window. Applies to ALL approval states."
- GAP (first 400 chars): InboundRouter.java:728 and InboundRouter.java:750 now invoke drainPendingConfirm before their fixed body-cap replies; InboundRouter.java:868 does the same before its probation reply. drainPendingConfirm at InboundRouter.java:1692 removes state and calls sendReply itself, with no second per-group reply-cap check or token consumption. Thus one group inbound admitted by the pre-rejection cap check ca...


### Cluster 3: DOS @ `InboundRouter.java:830-843`

**opencode** (severity: low, fix-class: other)

- PROMISE: The diff's own spec amendment (docs/spec/commands.md §/retry) commits: "the probation block, the body-length caps, and the single-line rule all return before the clear and leave the anchor intact — the clear is a DB write, and the pre-parser rejections commit to no DB writes for the bodies they drop."
- GAP (first 400 chars): That write-free claim is false for the probation-block path the same diff modifies. Step 4.1 (auto-promote + membership) executes BEFORE the step-5 probation gate: InboundRouter.java:830-843 runs groupAutoPromoteService.tryAutoPromote(...) and ensureGroupMembership(...) — DB writes — and only then does the step-5 block at :869-901 (with the new drain at :881) return ahead of the step-4.6 anchor cl...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **opencode-only**: DOS @ `GroupApprovalCheck.java:128` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **codex-only**: DOS @ `InboundRouter.java:728` (severity low). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `InboundRouter.java:830-843` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

