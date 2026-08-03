# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-746/docs/plan/m1/redteam-multi/M1-746-2026-08-03-r3`
Auditors: opencode, codex

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'opencode': 2, 'codex': 1}.

## Per-auditor verdicts

- **opencode**: FINDINGS (2 finding(s))
- **codex**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | opencode | codex | Severity (max) | Attribution |
|---|---|---|---|---|---|---|
| 1 | DOS | `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java:174-182` | -- | medium | medium | codex-only -- needs review |
| 2 | DOS | `QueryAnchorTranslator.java:148-235` | low | -- | low | opencode-only -- needs review |
| 3 | DOS | `QueryAnchorTranslator.java:226` | low | -- | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java:174-182`

**codex** (severity: medium, fix-class: other)

- PROMISE: "An in-memory circuit breaker keyed by resolved provider endpoint ... guards every LLM/embedding transport call. After a configured count of CONSECUTIVE transport-unreachable failures ... the endpoint's breaker trips OPEN and subsequent calls short-circuit ... without an HTTP attempt."
- GAP (first 400 chars): QueryAnchorTranslator checks the existing breaker at infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java:174-182, but invokes provider.generate directly at :192-194 and its failure path at :195-203 only logs and falls back. The diff contains no registry success/failure update for this new transport call, so repeated transport failures from this path neve...


### Cluster 2: DOS @ `QueryAnchorTranslator.java:148-235`

**opencode** (severity: low, fix-class: other)

- PROMISE: "the threat model is updated, not silently diverged" (ticket R3, 2026-08-03) — and security.md §Failure handling, "Provider-side (user-facing) LLM failures" / "Chat-mode replies": "the deterministic digest-first semantic pre-fetch, which runs once before the LLM call by design (the D28 'always runs, folded in' pattern): on an LLM-unreachable turn that read-only retrieval may already have executed ...
- GAP (first 400 chars): The diff routes a generative ModelTask.TRANSLATOR call into the D28 pre-fetch for every non-English scope — QueryAnchorTranslator.translate (QueryAnchorTranslator.java:148-235, the provider call at :192-194) invoked from SemanticSearchTool.execute (SemanticSearchTool.java:126-127) BEFORE the embed (:132) — but the R3 amendment updated only the semanticSearch tool row and §Rate limiting, not the §F...


### Cluster 3: DOS @ `QueryAnchorTranslator.java:226`

**opencode** (severity: low, fix-class: input-sanitization)

- PROMISE: "it is the R1 memory bound that keeps the cache from amplifying a hostile endpoint's up-to-8-MiB response into tens of gigabytes of retained heap (the transport cap's memory-protection purpose survives retention)" (QueryAnchorTranslator.java:77-80); "let the cache retain an arbitrarily large body (a hostile endpoint can return up to the 8 MiB transport cap) and amplify it 10,000 ways into the heap...
- GAP (first 400 chars): The retained-value cap is `translated.length() > inputMaxLength` (QueryAnchorTranslator.java:226), where `inputMaxLength` is the operator-configurable `infochat.chat.tool.input-max-length` (QueryAnchorTranslator.java:123-124, default 500). The retention bound therefore scales linearly with the operator knob: at the default the cache (QueryTranslationCache.java:56-59, maximumSize 10_000, expireAfte...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **codex-only**: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java:174-182` (severity medium). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `QueryAnchorTranslator.java:148-235` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `QueryAnchorTranslator.java:226` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

