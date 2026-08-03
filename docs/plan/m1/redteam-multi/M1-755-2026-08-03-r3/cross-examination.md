# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-755/docs/plan/m1/redteam-multi/M1-755-2026-08-03-r3`
Auditors: kimi, opencode, codex

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'kimi': 2, 'opencode': 1}.

## Per-auditor verdicts

- **kimi**: FINDINGS (2 finding(s))
- **opencode**: FINDINGS (1 finding(s))
- **codex**: CLEAN (0 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | kimi | opencode | codex | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `no-cite:5933960530902249304` | medium | -- | -- | medium | kimi-only -- needs review |
| 2 | DOS | `SavedCommandHandler.java:373-391` | -- | low | -- | low | opencode-only -- needs review |
| 3 | DOS | `no-cite:2865987858807128954` | low | -- | -- | low | kimi-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `no-cite:5933960530902249304`

**kimi** (severity: medium, fix-class: rate-limit)

- PROMISE: docs/spec/security.md §Rate limiting, "Per-group LLM rate
      (D47) — a separate sub-bucket per approved group bounding
      LLM-triggering operations (chat replies + on-demand /summary +
      /retry re-rolls) across all group members. The per-user LLM cap
      fires first; the per-group cap is the backstop for groups with many
      active members."
- GAP (first 400 chars): The diff's own spec amendment (security.md §Rate limiting,
      "**/saved display-hit translation (M1-755)**") declares the new leg
      "metered as an LLM-triggering operation", yet the only draw in the
      code is the per-user bucket: SavedCommandHandler.buildReply calls
      `llmRateCap.tryAcquire(userId)` (diff hunk at
      infochat-provider/.../SavedCommandHandler.java, the new
      `i...


### Cluster 2: DOS @ `SavedCommandHandler.java:373-391`

**opencode** (severity: low, fix-class: rate-limit)

- PROMISE: >
      The /saved display-hit translation leg's cost metering, as amended by
      this very diff (docs/spec/security.md §Rate limiting): "the leg is
      metered as an LLM-triggering operation: ONE per-user bucket token per
      invocation that actually translates (drawn on the first row that would
      take the translating leg — an `en` scope or an all-no-op page never
      draws; a rejecte...
- GAP (first 400 chars): >
      The draw is keyed to row ELIGIBILITY, not to an actual translator call.
      SavedCommandHandler.buildReply draws the token on the first row that
      passes `!headline.isEmpty() && !"en".equalsIgnoreCase(scopeLanguage)
      && !row.sourceLanguage.equalsIgnoreCase(scopeLanguage)`
      (SavedCommandHandler.java:373-391) BEFORE probing the cache — so a
      fully-converged page (every e...


### Cluster 3: DOS @ `no-cite:2865987858807128954`

**kimi** (severity: low, fix-class: rate-limit)

- PROMISE: docs/spec/security.md §Rate limiting, "Per-user
      interruptible concurrency — ... a ceiling on one sender's
      CONCURRENT interruptible requests ... across all scopes, so group
      membership cannot let a single sender occupy every dispatch worker
      at one instant — the per-minute bucket bounds rate, this bounds
      share."
- GAP (first 400 chars): The /saved translation leg runs inline on the adapter's
      transport dispatch thread (accepted per the amendment: "the leg
      stays on the transport thread by design"), and the per-page budget
      bounds only the PER-INVOCATION hold ("The budget is what bounds the
      per-invocation dispatch-thread hold"). But the leg is outside the
      D35 interruptible class, so no per-user concurren...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **kimi-only**: DOS @ `no-cite:5933960530902249304` (severity medium). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `SavedCommandHandler.java:373-391` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: DOS @ `no-cite:2865987858807128954` (severity low). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.

