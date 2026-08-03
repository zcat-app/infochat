# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-746/docs/plan/m1/redteam-multi/M1-746-2026-08-03-r4`
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
| 1 | DOS | `QueryTranslationCache.java:41-44` | -- | medium | medium | codex-only -- needs review |
| 2 | DOS | `QueryAnchorTranslator.java:703-706` | low | -- | low | opencode-only -- needs review |
| 3 | DOS | `infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java:979` | low | -- | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `QueryTranslationCache.java:41-44`

**codex** (severity: medium, fix-class: rate-limit)

- PROMISE: "All free-form string and list inputs across every tool below are length-bounded by a profile-driven cap," and the endpoint-response boundary prevents hostile data from exhausting JVM memory.
- GAP (first 400 chars): QueryTranslationCache.java:41-44 retains the exact attacker-supplied sourceText in every cache key. QueryAnchorTranslator.java:252-260 caps only the translated value before caching; it does not cap or avoid retaining the key. QueryAnchorTranslator.java:162-165 accepts the operator-configured input-max-length as the only functional input bound. Raising that configured cap therefore permits 10,000 d...


### Cluster 2: DOS @ `QueryAnchorTranslator.java:703-706`

**opencode** (severity: low, fix-class: input-sanitization)

- PROMISE: "the accepted translation is length-capped at the tool's CONFIGURED `input-max-length` — the same property the tool dispatcher enforces on the raw query, one knob, so the anchored string can never exceed what the raw path permits at ANY operator config" (ticket M1-746, R1 re-audit r2 text); QueryAnchorTranslator javadoc (line 592): "the anchored string may never exceed what the raw path permits at...
- GAP (first 400 chars): The cap is re-validated only on the MISS path. `translate()` short-circuits at the cache lookup (QueryAnchorTranslator.java:703-706) and returns the stored translation without any `translated.length() <= inputMaxLength` re-check; `inputMaxLength` is frozen at construction (lines 657-663). A value accepted under a higher cap is served unchanged after the effective cap drops, so the anchored string ...


### Cluster 3: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java:979`

**opencode** (severity: low, fix-class: input-sanitization)

- PROMISE: "A SEPARATE hard retention ceiling (`MAX_CACHED_TRANSLATION_LENGTH` = 2048 ...) bounds what the cache may retain: a translation within the input cap but over the ceiling is served for the call but never cached, so a raised `input-max-length` cannot resurrect the amplification. Together they keep the 10,000-entry cache at ~20 MB worst case regardless of config, so a hostile endpoint's up-to-8-MiB r...
- GAP (first 400 chars): The retention bound is delivered for the VALUE side only. `QueryTranslationCache.QueryKey` (infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java:979) embeds the raw query as `sourceText`, and `keyFor` (lines 1022-1025) puts it in the key verbatim; the 2048 belt (QueryAnchorTranslator.java:616, checked only at lines 773-779) applies to the cached VALUE, ...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **codex-only**: DOS @ `QueryTranslationCache.java:41-44` (severity medium). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `QueryAnchorTranslator.java:703-706` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java:979` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

