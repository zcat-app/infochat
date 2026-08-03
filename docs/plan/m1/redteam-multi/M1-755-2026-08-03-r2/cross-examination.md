# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-755/docs/plan/m1/redteam-multi/M1-755-2026-08-03-r2`
Auditors: claude, codex, opencode

## Summary

- 2 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 2 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'opencode': 2}.

## Per-auditor verdicts

- **claude**: UNAVAILABLE (0 finding(s))
- **codex**: CLEAN (0 finding(s))
- **opencode**: FINDINGS (2 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | codex | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `SavedCommandHandler.java:340-354` | -- | -- | high | high | opencode-only -- needs review |
| 2 | INFO-LEAK | `SavedCommandHandler.java:351-353` | -- | -- | low | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `SavedCommandHandler.java:340-354`

**opencode** (severity: high, fix-class: rate-limit)

- PROMISE: >
      security.md §Rate limiting classifies /saved as a cheap command —
      "Parser-only + DB-read paginated commands — /help, /status,
      /list-sources, /get-sources, /get-tags, /saved, /audit, /export,
      /quarantine list and similar. One bucket; high cap; cheap." — while
      "LLM-triggering operations (chat replies + on-demand /summary +
      /retry re-rolls) — its own bucket, capp...
- GAP (first 400 chars): >
      SavedCommandHandler.buildReply (SavedCommandHandler.java:340-354)
      invokes translationPipeline.runForDisplayHit once per listed row —
      up to PAGE_SIZE=20 synchronous, blocking ModelTask.TRANSLATOR calls
      per /saved invocation. /saved is not in the D35 interruptible class
      (chat-mode, /summary, /retry), so this runs INLINE on the adapter's
      single transport dispatch...


### Cluster 2: INFO-LEAK @ `SavedCommandHandler.java:351-353`

**opencode** (severity: low, fix-class: trust-boundary-tightening)

- PROMISE: >
      security.md §Secrets handling commits that switch-llm.sh "prints a
      per-task privacy disclosure naming exactly which generative tasks
      now call a remote provider and what each exposes", and characterizes
      the presentation translation legs as bot-prose-only: the M1-746
      query-anchoring leg "is not the bot-prose-only exposure the
      presentation and ingest translation ...
- GAP (first 400 chars): >
      The /saved display-hit leg (SavedCommandHandler.java:351-353) sends
      each saved-post HEADLINE — derived from the user's bookmarked post
      title/body (D13 per-user selection) — to ModelTask.TRANSLATOR,
      which the spec permits to be remote. This makes the §Secrets
      handling "bot-prose-only" characterization of the presentation legs
      inaccurate, and no spec text or swi...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **opencode-only**: DOS @ `SavedCommandHandler.java:340-354` (severity high). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: INFO-LEAK @ `SavedCommandHandler.java:351-353` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

