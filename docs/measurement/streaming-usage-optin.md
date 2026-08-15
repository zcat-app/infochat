# Streaming usage opt-in: per-backend wire observation

Does each fleet backend stream token usage WITHOUT the opt-in, WITH
`stream_options: {"include_usage": true}`, and does it tolerate the field at
all? The observation matrix behind the M1-853 request-shape decision (the
M1-847 round-1 review's RECOMMENDED-NEW-TICKET, DECIDE-BEFORE M1-849's live
probe). Evidence-only: no code, no spec.

**Status: FINAL — decision rule and cell design locked and committed
BEFORE any cell ran (protocol commit `3ecc23f9`; results commits follow it,
so `git log --follow` shows the order). Runnability is a start-time fact
recorded in §2, with one ORDER-TOUCHING CORRECTION named here per the
corrections-stay-visible rule: the first results commit (`f40de3cc`)
recorded OpenAI/DeepSeek/NanoGPT as "no credential on this box" — that
claim was FALSE (credentials existed in harness auth stores and a
migration backup; see §2's correction note). The corrected cells ran AFTER
that commit, under the same locked §1 rule and §2 prompt; their results
land in the correction commit. The rule itself was never edited.**

## 1. Decision rule (locked before any cell runs)

Per `(backend[, model], flag-state)` cell, record:

- **error-or-not** — the HTTP status of the streaming request;
- **usage-present-or-not** — whether any SSE frame carried a `usage` block;
- **frame shape** — verbatim the observed frame the usage rode (terminal
  data frame / message halves / other), never a summary.

The fleet shape is decided by:

> **UNCONDITIONAL `stream_options.include_usage=true` in the shared stream
> branch IFF every tested with-flag cell is error-free AND no with-flag
> cell loses usage its without-flag cell had. Any erroring or
> usage-losing backend demotes the shape to the narrowest correct
> mechanism (the existing provider-entry / `customizeRequestBody` seam,
> never base-url sniffing — analysis P6) scoped to exactly the offending
> backends. NOT-OBSERVED cells are residuals, never vetoes.**

An erroring or usage-losing with-flag cell is a RESULT that demotes the
shape — recorded, never retried away, dropped, or averaged into a verdict.

## 2. Cells and prompt

Fixed prompt for every cell, both flag states: system `You are a helpful
assistant.`, user `Say hi.`, `max_tokens: 16`, production request shape
(`{model, max_tokens, stream:true, messages}`, `stream_options` added for
with-flag cells — the `assembleBody` shape, OpenAiCompatibleProvider.java).
Raw SSE transcripts captured per cell under `.bench/streaming-usage-optin/`
(gitignored, the M1-850 posture).

| backend (model) | route | runnable here |
|---|---|---|
| OpenAI (gpt-4o-mini; gpt-5.6-luna) | api.openai.com, chat-completions | yes — key in the codex harness auth store (operator-directed 2026-08-15) |
| DeepSeek (deepseek-v4-flash) | api.deepseek.com, chat-completions | yes — key in the opencode harness auth store (operator-directed 2026-08-15) |
| Ollama (qwen2.5:0.5b) | local :11434 `/v1` compat | yes |
| llama.cpp (qwen2.5:0.5b GGUF) | local llama-server compat route | yes |
| OpenRouter (per-model) | openrouter.ai | no credential found in any harness store or backup |
| NanoGPT (per-model) | nano-gpt.com | yes — operator-supplied key, account funded mid-run (2026-08-15) |
| Anthropic (control leg) | api.anthropic.com `/v1/messages` | attempted — key authenticates, account credit-less (400 before streaming); operator decision: no Anthropic spend |

**Correction (corrections stay visible).** The protocol commit's
runnability table claimed "no credential on this box" for OpenAI, DeepSeek,
and NanoGPT. That was the product of a too-narrow probe (one gitignored
secrets file checked and stated as a box-wide fact): a migration-backup
DeepSeek key existed but is DEAD (rejected 401, "api key ****225 is
invalid" — retired with the old host), while LIVE keys sat in harness auth
stores the probe never looked at (codex: `OPENAI_API_KEY`; opencode:
`deepseek`) and the NanoGPT key arrived operator-supplied mid-run. The
corrected rows are what §3 carries; the false claim stays visible here.

## 3. Matrix

All runs executed against repo commit `3ecc23f9` (the protocol commit;
pinned per the measured-surfaces-are-moving rule, translator-slot.md:69-71).
Raw SSE transcripts: `.bench/streaming-usage-optin/` (gitignored, the
M1-850 posture) — `ollama-{without,with}-flag.sse`,
`llamacpp-{without,with}-flag.sse`, `deepseek-{without,with}-flag.sse`,
`openai-{without,with}-flag.sse` (gpt-4o-mini),
`openai-luna-{without,with}-flag.sse` (production-shape 400s),
`openai-luna-adapted-{without,with}-flag.sse`,
`nanogpt-{without,with}-flag.sse`, plus response headers and the
llama-server log. Frame quotes below elide the llama.cpp `model` field's
value (it echoes the served GGUF's host blob path; host paths stay out of
committed docs — the full frames are in the transcripts).

| backend (model) | flag | HTTP | error shape | usage | frame the usage rode |
|---|---|---|---|---|---|
| Ollama 0.30.8 (qwen2.5:0.5b, `/v1` compat) | without | 200 | — | NO | none — stream ends at the finish frame (`finish_reason:"length"`, no usage block), then `[DONE]` |
| Ollama 0.30.8 (qwen2.5:0.5b, `/v1` compat) | with | 200 | — | YES | terminal data frame AFTER the finish frame, `choices:[]` + `{"prompt_tokens":22,"completion_tokens":16,"total_tokens":38}`, then `[DONE]` — verbatim: `{"id":"chatcmpl-…","object":"chat.completion.chunk","model":"qwen2.5:0.5b","system_fingerprint":"fp_ollama","choices":[],"usage":{"prompt_tokens":22,"completion_tokens":16,"total_tokens":38}}` |
| llama.cpp b10221 llama-server (qwen2.5:0.5b GGUF, compat route) | without | 200 | — | NO | none — terminal frame carries llama.cpp's own `timings` object, no `usage`, then `[DONE]` |
| llama.cpp b10221 llama-server (qwen2.5:0.5b GGUF, compat route) | with | 200 | — | YES | terminal data frame AFTER the finish frame, `choices:[]` + `{"completion_tokens":10,"prompt_tokens":22,"total_tokens":32,"prompt_tokens_details":{"cached_tokens":21}}` (plus `timings`) — verbatim: `{"choices":[],"created":…,"model":<elided>,"system_fingerprint":"b10221-815a2a591","object":"chat.completion.chunk","usage":{…},"timings":{…}}` |
| DeepSeek (deepseek-v4-flash) | without | 200 | — | YES | usage block rides the FINISH FRAME itself (same frame as `finish_reason:"length"`): `"usage":{"prompt_tokens":92,"completion_tokens":16,"total_tokens":108,…,"completion_tokens_details":{"reasoning_tokens":16},…}`; NO separate empty-choices frame |
| DeepSeek (deepseek-v4-flash) | with | 200 | — | YES | same finish-frame placement; intermediate frames additionally carry explicit `"usage":null` — verbatim terminal: `{"…","choices":[{…,"finish_reason":"length"}],"usage":{"prompt_tokens":92,"completion_tokens":16,"total_tokens":108,…}}` then `[DONE]` |
| OpenAI (gpt-4o-mini) | without | 200 | — | NO | none — ends at finish frame (`finish_reason:"stop"`, `"usage":null` absent on plain frames), then `[DONE]` |
| OpenAI (gpt-4o-mini) | with | 200 | — | YES | finish frame carries `"usage":null`, then the terminal data frame `choices:[]` + `"usage":{"prompt_tokens":20,"completion_tokens":9,"total_tokens":29,…}`, then `[DONE]` — the documented shape |
| OpenAI (gpt-5.6-luna), production shape (`max_tokens`) | without | 400 | `unsupported_parameter`: "'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead." | — | REJECTED BEFORE STREAMING — attributable to the model family's parameter set, NOT the flag (identical 400 with and without `stream_options`) |
| OpenAI (gpt-5.6-luna), production shape | with | 400 | identical to the without-flag 400 | — | see above — the flag question is unreached on this leg |
| OpenAI (gpt-5.6-luna), adapted (`max_completion_tokens`) | without | 200 | — | NO | none — ends at finish frame, then `[DONE]` |
| OpenAI (gpt-5.6-luna), adapted | with | 200 | — | YES | finish frame carries `"usage":null`, then terminal data frame `choices:[]` + `"usage":{"prompt_tokens":19,"completion_tokens":5,"total_tokens":24,…}`, then `[DONE]` — same shape as gpt-4o-mini |
| NanoGPT (openai/gpt-4o-mini) | without | 200 | — | NO | none — no `usage` block anywhere; the gateway injects its own `x_nanogpt_pricing` accounting object on the finish frame (inputTokens/outputTokens/USD cost) |
| NanoGPT (openai/gpt-4o-mini) | with | 200 | — | YES | usage rides the FINISH FRAME (NOT a separate empty-choices frame; `choices[0]` still populated), alongside `x_nanogpt_pricing`: `"usage":{"prompt_tokens":20,"completion_tokens":9,"total_tokens":29,"reasoning_tokens":0,…}` — a gateway-reshaped block with extra fields |
| OpenRouter (any model) | without | — | — | — | NOT-OBSERVED — no credential found in any harness store or backup |
| OpenRouter (any model) | with | — | — | — | NOT-OBSERVED — no credential found in any harness store or backup |
| Anthropic (control leg, any model) | n/a (no opt-in in dialect) | 400 | `invalid_request_error`: "Your credit balance is too low to access the Anthropic API." | — | NOT-OBSERVED for the parser question — operator-supplied key AUTHENTICATES but the account is credit-less; the API rejects before any streaming, so the two usage halves stay unconfirmed on real wire (operator decision 2026-08-15: no Anthropic spend) |

NanoGPT attempt history (recorded, not retried away): the first paid-model
probes returned **402 `insufficient_balance`** (account at $0.000000,
required ~$0.000013), and the sole `:free`-tagged model
(`dots-studio/dots-3-note-preview:free`) returned **503
`service_unavailable`** twice — the operator then funded the account and
the recorded cells ran clean. The dead migration-backup DeepSeek key
(rejected **401** `invalid api key ****225`) is named in §2's correction
note.

**The M1-847 review's relayed assumption is FALSIFIED by observation.**
It claimed OpenAI AND DeepSeek attach the terminal usage frame "only when
the request also carries `stream_options.include_usage`". Observed: true
for OpenAI (both models), FALSE for DeepSeek — DeepSeek sends usage on the
stream WITHOUT the flag, riding the finish frame, and the flag changes
only intermediate frames' explicit `"usage":null`. Two further
placement shapes exist beyond the documented one: DeepSeek and NanoGPT
both put usage on the finish frame with populated `choices` (no
empty-choices frame at all). The parser consequence is named in §4; the
fakes consequence lands in M1-853.

No with-flag cell errored (the two 400s are the gpt-5 family's
`max_tokens` rejection, present without the flag too — a model-family
parameter fact, not a flag response). No with-flag cell lost usage its
without-flag cell had — every with-flag cell either gained usage
(Ollama, llama.cpp, OpenAI ×2, NanoGPT) or already had it (DeepSeek).

## 4. Residuals

- **OpenRouter: NOT-OBSERVED** (no credential in any harness store or
  backup). Per §1 a residual, never a veto. M1-849's live probe re-checks
  the decided shape against the deployment's actual endpoint.
- **Gateways are per-model, not per-backend.** OpenRouter and NanoGPT front
  many upstream models; gateway behavior is upstream-dependent and does not
  generalize across models. Observed here: exactly ONE NanoGPT model
  (`openai/gpt-4o-mini`); its finish-frame usage placement alongside
  `x_nanogpt_pricing` is a fact about that (gateway, model) cell only —
  another model may reshape or drop the usage block. Any future gateway
  observation records per-(gateway, model id); evidence justifies a row and
  never appears inside one (translator-slot.md's standing rule).
- **Anthropic control leg: NOT-OBSERVED.** An operator-supplied key
  AUTHENTICATES, but the account carries no credits — the API rejects with
  400 "credit balance is too low" BEFORE any streaming, so the leg never
  reached the wire question. A local mock would observe nothing about the
  real wire (it would only mirror our own fake — what the in-tree
  `AnthropicProviderStreamingTest` already pins), so the leg was not
  simulated. The parser's two usage halves (message_start / message_delta)
  stay pinned by that in-tree fake only; the first live-streamed Anthropic
  call on a credited account is the real confirmation. Operator decision
  2026-08-15: no Anthropic spend; residual, never a veto.
- **Parser consequence of the observed placements (input to M1-853's
  adaptation scope, per P9).** Three DISTINCT usage placements were
  observed across backends: (a) empty-`choices` terminal frame after the
  finish frame — Ollama, llama.cpp, OpenAI both models; (b) usage on the
  FINISH frame with populated `choices` — DeepSeek (with and without the
  flag), NanoGPT (with the flag); (c) no usage at all — every without-flag
  cell except DeepSeek's. The current `StreamingParser` consumes (a) and
  (b) both (it reads `usage` from ANY frame whose token fields convert,
  OpenAiCompatibleProvider.java:396-414 — the DeepSeek without-flag leg
  proves (b) works end to end on real wire), and (c) is the honest-gap
  state M1-853's failure-mode test pins. No parser adaptation is indicated
  by anything observed; M1-853's fakes mirror placements (a) AND (b) so
  the pin covers both real shapes.
- **The gpt-5 model family rejects the production adapter's body shape
  entirely** (streaming and non-streaming alike): `max_tokens` → 400
  `unsupported_parameter`, "Use 'max_completion_tokens' instead" —
  observed on gpt-5-mini and gpt-5.6-luna, before any flag question. This
  is OUTSIDE this matrix's question (the flag), recorded because M1-849's
  probe or any future OpenAI config pointing at a gpt-5-family model
  400s on the adapter's unconditional `max_tokens`. If that ever becomes
  a supported configuration it is a new ticket's decision (a
  per-family body branch rides the same seam this record's decision
  feeds); no direction is set here.
- **Model scope of the cells.** One model per backend cell (two for
  OpenAI, both models of one family pair), chosen for cost — the
  observation is about the SERVER/GATEWAY's wire behavior (frame
  placement, field tolerance), not the model; a different model id on the
  same server versions is not expected to change it, but that inference is
  untested and named here as a residual, not a fact. The gateways are the
  exception: their behavior is per-model by construction (above).
- **NanoGPT's `x_nanogpt_pricing` is gateway-injected accounting, not
  usage.** It was present on the finish frame in BOTH flag states. The
  parser ignores unknown fields and reads only `usage`; the record names
  it so a future reader does not mistake the gateway's USD cost block for
  a usage report (nothing here builds on it).

## 5. Decision

Applying §1's locked rule to §3: every TESTED with-flag cell — Ollama
0.30.8 `/v1`, llama.cpp b10221, DeepSeek, OpenAI gpt-4o-mini, OpenAI
gpt-5.6-luna (adapted leg), NanoGPT openai/gpt-4o-mini — is error-free,
and no with-flag cell lost usage its without-flag cell had (five gained
usage; DeepSeek already had it). The gpt-5 production-shape 400s are not
flag responses (identical without the flag; §3 names the family
parameter cause). No demotion trigger fired.

**Decision: UNCONDITIONAL `stream_options.include_usage=true` in the
shared stream branch of `OpenAiCompatibleProvider.assembleBody` — no
per-provider branch, no config key, no base-url sniffing. The NOT-OBSERVED
surfaces (OpenRouter, Anthropic's control leg) are residuals for M1-849's
live probe to re-check against the deployment's actual endpoint, never
vetoes.**

M1-853 lands exactly this shape; its fakes mirror the OBSERVED placements
— the empty-`choices` terminal frame (Ollama/llama.cpp/OpenAI) AND the
finish-frame placement (DeepSeek/NanoGPT) — so both real wire shapes are
pinned, alongside the honest-gap no-usage state.
