# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-767/docs/plan/m1/redteam-multi/M1-767-2026-08-04`
Auditors: claude, kimi

## Summary

- 6 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 6 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 4, 'kimi': 2}.

## Per-auditor verdicts

- **claude**: FINDINGS (4 finding(s))
- **kimi**: FINDINGS (2 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | kimi | Severity (max) | Attribution |
|---|---|---|---|---|---|---|
| 1 | DOS | `DigestRenderer.java:145` | -- | medium | medium | kimi-only -- needs review |
| 2 | DOS | `DigestRenderer.java:362-363` | medium | -- | medium | claude-only -- needs review |
| 3 | DOS | `DigestRenderer.java:430-433` | medium | -- | medium | claude-only -- needs review |
| 4 | DOS | `DigestWorker.java:227-228` | medium | -- | medium | claude-only -- needs review |
| 5 | DOS | `ThrottledAdminNotifier.java:234-244` | low | -- | low | claude-only -- needs review |
| 6 | DOS | `no-cite:2148410072612748172` | -- | low | low | kimi-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `DigestRenderer.java:145`

**kimi** (severity: medium, fix-class: rate-limit)

- PROMISE: docs/spec/security.md §Rate limiting ("Per-group LLM rate
      (D47)"): "Periodic digests do NOT count against user-initiated
      per-group LLM budget (they are system-initiated; the aggregate
      system LLM budget is the backstop for digest cost)." The threat
      model also establishes that feed publishers are untrusted
      ("The Collector is exposed to arbitrary feed content. Every RSS
...
- GAP (first 400 chars): The "backstop" is gate-then-draw with no intra-render bound.
      DigestWorker.executeSlot consults SystemLlmBudget.canStartRender()
      once, before the render starts (DigestWorker.java, the
      `!systemLlmBudget.canStartRender()` clause added at the former
      line-219 slot-window check). The render's calls are then drawn
      AFTER they complete: recordCalls(shownClusters.size()) after
...


### Cluster 2: DOS @ `DigestRenderer.java:362-363`

**claude** (severity: medium, fix-class: other)

- PROMISE: security.md §Failure handling — "Fail-fast changes WHEN a doomed call
              fails, never WHERE it goes or how the task degrades: the short-circuit
              surfaces as the same failure the consumer already handles" and "A complete
              LLM outage degrades quality, not safety." §Trust boundaries item 9 puts the
              hostile/unreachable endpoint in scope: "a hostile or...
- GAP (first 400 chars): The draws record calls that were never made on exactly the failure paths where
          zero HTTP requests are issued, so an outage burns the 24-hour budget.
          `DigestRenderer.java:362-363` calls `summaryProseGenerator.generate(...)` and
          then unconditionally `recordCalls(shownClusters.size())`. Inside
          `SummaryProseGenerator.generate` an unresolvable provider returns ev...


### Cluster 3: DOS @ `DigestRenderer.java:430-433`

**claude** (severity: medium, fix-class: rate-limit)

- PROMISE: security.md §Rate limiting — "Periodic digests do NOT count against
              user-initiated per-group LLM budget (they are system-initiated; the
              aggregate system LLM budget is the backstop for digest cost)." A backstop
              on *cost* has to count the calls that are actually made; a ceiling that
              meters a strict subset of the render's generative calls is not...
- GAP (first 400 chars): The meter systematically under-counts the render's real provider calls on the
          DEFAULT digest mode. `DigestRenderer.renderSections` records exactly one call
          per roll-up call site (DigestRenderer.java:430-433, `recordCalls(1)`, comment:
          "the generator makes one provider call per invocation"), but
          `CategoryRollupGenerator.generateRollup` makes TWO provider-reac...


### Cluster 4: DOS @ `DigestWorker.java:227-228`

**claude** (severity: medium, fix-class: rate-limit)

- PROMISE: security.md §Rate limiting — "**Per-group LLM rate (D47)** — a separate
              sub-bucket per approved group bounding LLM-triggering operations (chat
              replies + on-demand `/summary` + `/retry` re-rolls) across all group
              members. The per-user LLM cap fires first; the per-group cap is the
              backstop for groups with many active members. ... Periodic diges...
- GAP (first 400 chars): The new control is a single deployment-wide counter with no per-group share,
          no per-render bound, and an admission-only gate.
          `SystemLlmBudget.callTimestamps` (SystemLlmBudget.java:~54, "One system-wide
          deque of call timestamps — no per-user or per-group key") is consulted exactly
          once per render, in `DigestWorker.executeSlot` at DigestWorker.java:227-228
  ...


### Cluster 5: DOS @ `ThrottledAdminNotifier.java:234-244`

**claude** (severity: low, fix-class: other)

- PROMISE: security.md §Failure handling — "**Admin notifications** are coalesced per
              `(channel, error_class)` for a short window so an outage produces one
              summary message, not 200 individual alerts." The coalescing exists so a
              breach condition does not turn into per-event work.
- GAP (first 400 chars): `SystemLlmBudget.canStartRender()` (SystemLlmBudget.java:~106-113) is
          `synchronized` and calls `adminNotifier.notifyOnce(BREACH_KEY, …)` INSIDE the
          monitor, on EVERY refusal. `ThrottledAdminNotifier.notifyOnce` opens a JDBC
          connection and runs an UPSERT against `admin_notification_state`
          (ThrottledAdminNotifier.java:234-244) — the coalescing suppresses the *...


### Cluster 6: DOS @ `no-cite:2148410072612748172`

**kimi** (severity: low, fix-class: rate-limit)

- PROMISE: Same §Rate limiting commitment — the budget is meaningful
      only if its accounting tracks actual provider calls; a meter that
      undercounts is a weaker backstop than the one the spec names.
- GAP (first 400 chars): Four undercount paths in the draw sites: (a) recordCalls is
      placed after summaryProseGenerator.generate returns, so an
      exception mid-generate (schema-violating reply after retry,
      transport failure) drops the count of the calls already made —
      no finally-block draw (DigestRenderer.java ~363, ~376);
      (b) §Failure handling mandates "retry once" on schema-violating
      LL...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **kimi-only**: DOS @ `DigestRenderer.java:145` (severity medium). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `DigestRenderer.java:362-363` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `DigestRenderer.java:430-433` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `DigestWorker.java:227-228` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `ThrottledAdminNotifier.java:234-244` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: DOS @ `no-cite:2148410072612748172` (severity low). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.

