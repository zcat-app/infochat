# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-746/docs/plan/m1/redteam-multi/M1-746-2026-08-03-r5`
Auditors: claude, opencode, codex

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 1, 'opencode': 1, 'codex': 1}.

## Per-auditor verdicts

- **claude**: FINDINGS (1 finding(s))
- **opencode**: FINDINGS (1 finding(s))
- **codex**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | opencode | codex | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | INJECTION | `QueryAnchorTranslator.java:110-123` | -- | high | -- | high | opencode-only -- needs review |
| 2 | INJECTION | `QueryAnchorTranslator.java:211-218` | high | -- | -- | high | claude-only -- needs review |
| 3 | INJECTION | `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java:115` | -- | -- | low | low | codex-only -- needs review |

## Per-cluster detail

### Cluster 1: INJECTION @ `QueryAnchorTranslator.java:110-123`

**opencode** (severity: high, fix-class: input-sanitization)

- PROMISE: >-
      The amended `semanticSearch` tool row (docs/spec/security.md, §Prompt-
      injection defenses, tool-allowlist) commits that "when the scope
      declares a non-English `/lang`, the query text is first translated to
      the corpus anchor language (English, D29) by a generative
      `ModelTask.TRANSLATOR` call (decoded greedily — temperature 0 on the
      wire; language-only prompt; ...
- GAP (first 400 chars): >-
      The user's query is never inserted into the translator prompt.
      QueryAnchorTranslator.PROMPT_TEMPLATE
      (infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/
      QueryAnchorTranslator.java:110-123) carries a LITERAL `...` between the
      `<<<UNTRUSTED_CONTENT id="{{id}}">>>` and `<<<END id="{{id}}">>>`
      markers, and translate() (same file, lines 211-218)...


### Cluster 2: INJECTION @ `QueryAnchorTranslator.java:211-218`

**claude** (severity: high, fix-class: trust-boundary-tightening)

- PROMISE: security.md §Prompt-injection defenses, `semanticSearch` tool row (amended by this diff): "Query anchoring (M1-746, D58): when the scope declares a non-English `/lang`, the query text is first translated to the corpus anchor language (English, D29) by a generative `ModelTask.TRANSLATOR` call (decoded greedily — temperature 0 on the wire; language-only prompt; result cached per (scope, query, langu...
- GAP (first 400 chars): The user's query text is NEVER sent to the translator. QueryAnchorTranslator.java:211-218 builds the prompt as `PROMPT_TEMPLATE.replace("{{SOURCE_LANGUAGE}}", ...).replace("{{id}}", ...)` and calls `provider.generate(ModelTask.TRANSLATOR, "", prompt)` — the `query` parameter is used only for the `en` short-circuit (line 172), the cache key (line 194), and the fallback returns; it appears nowhere i...


### Cluster 3: INJECTION @ `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java:115`

**codex** (severity: low, fix-class: input-sanitization)

- PROMISE: "Every prompt that includes user-derived text is wrapped in a delimiter block whose marker contains a per-call random value."
- GAP (first 400 chars): The scope-declared /lang value is read as sourceLanguage in infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java:115 and interpolated directly into the translator instruction at infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java:211-218. The only random delimiter is placed in the static template at QueryAnchorTran...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **opencode-only**: INJECTION @ `QueryAnchorTranslator.java:110-123` (severity high). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: INJECTION @ `QueryAnchorTranslator.java:211-218` (severity high). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **codex-only**: INJECTION @ `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java:115` (severity low). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.

