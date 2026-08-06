# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-774/docs/plan/m1/redteam-multi/M1-774-2026-08-05`
Auditors: codex, claude, opencode

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

- 2 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 2 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'codex': 1, 'opencode': 1}.

## Per-auditor verdicts

- **codex**: FINDINGS (1 finding(s))
- **claude**: UNAVAILABLE (0 finding(s))
- **opencode**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | codex | claude | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:724-733` | medium | -- | -- | medium | codex-only -- needs review |
| 2 | AUTH-BYPASS | `InboundRouter.java:736` | -- | -- | low | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:724-733`

**codex** (severity: medium, fix-class: rate-limit)

- PROMISE: "Per-group reply rate cap. Before sending any reply (fixed or command), check the per-group reply rate bucket. If the bucket is exhausted, silently drop" and the bucket "bounds total outbound replies" for all group states.
- GAP (first 400 chars): infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:724-733 and :746-755 call drainPendingConfirm after the authorization/approval gate has admitted the inbound, then each sends the cap rejection. drainPendingConfirm at :1661-1679 can itself send a cancellation reply, so one group inbound admitted by the per-group reply bucket can produce two outbound replies wi...


### Cluster 2: AUTH-BYPASS @ `InboundRouter.java:736`

**opencode** (severity: low, fix-class: trust-boundary-tightening)

- PROMISE: "Inputs rejected ahead of the cancel sweep are the exceptions. The transport rate-cap drop, the transport byte cap, and empty-after-normalization all fire before the users-row read, so no drain is possible there without breaking the query-free hostile-flood path …" (commands.md §Surface conventions, amended by this diff) — i.e. the only justification the amended spec gives for the transport-byte-c...
- GAP (first 400 chars): The drain M1-774 adds at the two body caps (InboundRouter.java:736, :752) closes the stale-payload window for bodies over `chatBodyCap`/`commandBodyCap`, but the M1-038 transport byte cap fires EARLIER (InboundRouter.java:538, before the users-row read) and still returns with NO drain. A chat body in (65536, ∞) bytes never reaches the draining chat-cap branch (line 726), so the pending entry survi...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **codex-only**: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:724-733` (severity medium). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: AUTH-BYPASS @ `InboundRouter.java:736` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

