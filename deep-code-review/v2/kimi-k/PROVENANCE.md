# Provenance — mixed-endpoint run

This folder is NOT a clean kimi-k dataset, unlike the sibling v2 endpoint
folders (deepseek, mimo-v25pro, opus-47, opus-48).

The original `/deep-code-review full` run (`.reviews/deep-review/full-2026-06-07-0057/`)
was started in a session on the kimi-k endpoint on 2026-06-07. That session
built all inventories and prompt files but produced only ONE report before
it was abandoned:

- `04-module-infochat-llm-adapter.md` — **kimi-k** (written 2026-06-07 01:23)

The run was finished on 2026-06-07 in a follow-up session on the standard
Anthropic endpoint (Opus 4.8), reusing the kimi session's pre-built prompt
files unchanged:

- `01-architecture.md` — Opus 4.8
- `02-module-infochat-core.md` — Opus 4.8
- `03-module-infochat-ssrf.md` — Opus 4.8 (first attempt failed with API
  `Overloaded`; succeeded on retry)
- `05-module-infochat-messaging-adapter.md` — Opus 4.8
- `06-module-infochat-collector.md` — Opus 4.8
- `07-module-infochat-provider.md` — Opus 4.8
- `00-summary.md` — Opus 4.8 (review-synthesizer over all 7 reports)

For cross-model comparisons, only `04-module-infochat-llm-adapter.md` is a
kimi-k data point; treat the rest as additional Opus 4.8 output.
