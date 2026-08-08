# Future — live text streaming (web-chat feel)

> **Status: v2 design notes. NOT part of v1. Do NOT implement against this
> without first promoting it to spec.** This file captures the investigation
> and design reasoning from 2026-08-08 so it isn't re-derived; it is not a
> commitment, not part of `spec/decisions.md`, and not part of any MVP.
>
> **Created:** 2026-08-08. The wishlist record is
> `docs/plan/future-features.md` §I1; this file is the design layer above it
> (same relationship as §B2 → `image-generation.md`).

## Goal

Reveal the bot's reply incrementally as it is generated — a web-chat-like
feel — instead of minutes behind a "Generating…" stage label. Controlled by a
configuration flag: **off = today's behaviour exactly** (coarse stage-label
progress, M1-607), **on = live text**. SimpleX is the only transport where the
feature exists (see §Scope).

## Upstream status (verified 2026-08-08 against simplex-chat source)

- **Available, NOT deprecated.** Live messages shipped in simplex-chat v4.4
  (Dec 2022: "they update for all recipients as you type them, every few
  seconds") and are fully present in the latest v7.0.0 (2026-07-28). Command
  grammar unchanged: `/_send <ref> live=on|off` and `/_update item <ref>
  <itemId> live=on|off json …` (v7.0.0 `src/Simplex/Chat/Library/Commands.hs`
  :5453,:5462; parser `liveMessageP`).
- **First-class upstream.** The official clients use the feature themselves
  (their updates-as-you-type UX: `composeState.liveMessage`, `CIMeta.isLive`),
  and the official nodejs bot lib exposes it (`apiSendMessages(…,
  liveMessage)`, `apiUpdateChatItem(…, liveMessage)`, synced with 7.0.0 types).
- **Semantics:** `live' = (live &&) <$> itemLive` — once an item is finalized
  `live=off` it can never become live again. Matches a placeholder→finalize
  lifecycle exactly. Live multi-send is restricted (no live + multiple quotes)
  — irrelevant to single-text bot replies.
- **No upgrade needed:** the project pins simplex-chat v6.5.4
  (`infochat-provider/src/main/docker/Dockerfile.jvm`); the feature exists
  there and was live-verified on v6.5.4.1.

## Substrate that ALREADY exists (verified in code + live runs)

- The adapter already emits live edits: `SimpleXMessageCodec.encodeUpdateCommand`
  → `live=on`, `encodeFinalizeCommand` → `live=off` (SimpleXMessageCodec.java
  :132-145), driving the progress-notifier lifecycle (placeholder → coalesced
  stage-label edits → finalize).
- Live-proven end-to-end on real relays: the s10 live run (`/summary`
  placeholder → edits → finalize via `chatItemUpdated`; live-e2e HANDOFF
  2026-07-03, 6/7 scenarios green).
- Pacing machinery exists: 600 ms edit-coalesce floor
  (`infochat.messaging.progress.min-edit-interval-ms`, max of system floor and
  adapter `minEditInterval`), shared 5/s outbound token bucket, edit-failure
  `fallback_send` path, `adapter.outbound.update.*` metrics.
- What does NOT exist: a token/chunk source. `LlmProvider.generate` is
  single-string non-streaming; no provider impl parses SSE; `ChatAgent` runs a
  blocking multi-turn tool loop. M1-607 deferred exactly this: "A streaming
  SPI is a separate, larger decision."

## Why streaming is blocked today — the English-pivot chat flow

The chat flow (D29/D58 English pivot; verified in `ChatAgent` /
`SemanticSearchTool` / `TranslationPipeline`):

```
user writes Spanish
  → query translated ES→EN (QueryAnchorTranslator, D58 bounded conditions)
  → semantic + lexical search over English anchor fields
  → English context block folded into the prompt
  → LLM generates in English — hard-pinned (REPLY_LANGUAGE_DIRECTIVE,
    ChatAgent.java:201: "Always write your reply in English…")
  → sanitize → TOOL_CALL strip → D21 refusal intercept   [English]
  → chat_memory persists the ENGLISH text (English-canonical)
  → TranslationPipeline EN→ES (scope language ≠ en)
  → delivered in Spanish
```

The Spanish text the user sees only exists AFTER the final translate step —
there is nothing Spanish to stream during generation. (Precision note: the
ingest/query-leg pivot — embeddings, similarity search, tags — is untouched by
any reply-streaming design; the conflict is solely with display-time reply
translation.)

## Variant A — stream the generated English, re-translate chunks (REJECTED)

Throttled chunk batches, each re-translated before display. Rejected
(2026-08-08, user decision): multiplies translator LLM cost per reply, and the
final delivered text is still the whole-reply translation — every streamed
chunk is wasted display ending in a jarring full-text swap. Neither good
practice nor resource-sound.

## Variant B — native-language generation (OPEN, gated on measurement)

Drop the English pin for chat: translate the *retrieved context* into the
user's language, keep the user's question in the original, let a
language-capable model generate the reply directly in the user's language.
This is the summarizer's existing "language-aware" shortcut (llm.md
§Translation flow) extended to chat — and it removes the
translation-vs-streaming conflict, because the generated text IS the display
text.

Findings (2026-08-08):

- **Sanitization is NOT the blocker — the filter can ride the stream.**
  `LlmOutputSanitizer` is deterministic regex/line transforms (scaffolding
  strip, markdown-link rewrite, markdown downgrade, closed-list redaction); it
  can run ON the stream with a small hold-back buffer at chunk boundaries, and
  the D21 refusal intercept is a prefix check that fires before streaming
  starts. Costs: boundary-spanning-token handling, per-call audit-aggregation
  rework, and a spec amendment — bytes that slip past cannot be unseen. No
  reliance on the model being "safe by itself" is required.
- **Conflict 1 — `chat_memory` is English-canonical by design.** Native-language
  assistant turns break the canonicity (context window, `/compress`, memory
  tools) or force a lossy back-translate persist call per turn.
- **Conflict 2 — the tool loop.** The model must interleave the English
  TOOL_CALL protocol, English tool schemas and English tool results with
  user-language prose — a harder task than the translation the measurements
  validated. The summarizer shortcut has no tool loop, which is why it is safe
  there.
- **Conflict 3 — first-token latency.** The context-translation leg runs
  BEFORE generation can start (extra latency + longer prompt: cs/es/ru/tr run
  longer than English), and streaming only ever fixes the generation part.
  Prefill is the dominant wait on local hardware — measure, don't assume.

## Hardware correction (2026-08-08) — the speed veto no longer applies

The first-pass analysis cited the SETUP_GUIDE §"AI backend performance on
modest hardware" figures (~8–10 tok/s generation, prefill-dominated). Those
are the **4-vCPU CPU-only `vps` profile** — the old deployment target. The
deployment target now is the **Strix Halo (BOSGAME) box** (128 GB unified;
Vulkan is the decided backend), where measured numbers are different:

| arm | generation | source |
|---|---|---|
| Vulkan head-to-head (identical build/model/flags) | 825.3 tok/s prefill / **49.51 tok/s** gen | bench-box session notes (local, not committed) |
| Qwen3.6-35B-A3B (Q6_K_XL, 30.4 GiB, MoE ~3B active) | **35.4 tok/s** | `docs/measurement/translator-slot.md` §3 |
| DeepSeek-V4-Flash (remote, incl. network) | **63.2 tok/s** | `docs/measurement/translator-slot.md` §3 |

35–63 tok/s is a comfortable reveal rate (official clients update live
messages "every few seconds" anyway — token-smoothness is unattainable and
unnecessary; chunked ~1 s updates are the realistic ceiling). **Speed is no
longer the deciding factor on this box**; it remains relevant for operators
deploying the `vps` profile, where the feature would simply stay off.

## Model evidence — what exists, what is missing

Supported languages today (`LanguageRegistry.ENABLED_LANGUAGES`, enabled via
M1-716 gate + M1-060/M1-718/M1-719/M1-720): **en, cs, es, ru, tr**.

| evidence | what it proves | what it does NOT prove |
|---|---|---|
| `docs/measurement/model-lang-coverage.md` (2026-08-03) | tokenizer coverage: all five languages representable on nearly every on-disk model; Qwen family ≫ rest | generation quality — the doc itself says "a screening tool, not a verdict" |
| `docs/measurement/translator-slot.md` (2026-08-02) | translation legs: number preservation + query recall@8; nothing beats the DeepSeek incumbent | chat generation quality in any target language |
| fixture-verification record of the translator measurement programme (2026-08-02) | DeepL/Google back-translation verified the **fixture** translations: 12 docs × 8 languages, 96 lines read, 3 corrected | model output — this verified the TEST DATA, not any model |
| language-enablement tickets (M1-716/718/719/720, M1-761, M1-778) | bundle parity (D43) + mechanical target-script check (a cs/ru reply contains Latin/Cyrillic) + the M1-778 fix for prose ignoring `/lang` | human- or judge-scored quality of generated chat prose per language |
| live test sessions (2026-07-29 backup, `test-clients/admin/*-langcs.log`) | `/lang cs` config round-trip works | chat quality — the logs are config confirmations only |

**The gap:** no measurement exists that any model — local or remote — produces
chat-quality prose in cs/es/ru/tr while running the tool loop. The DeepL-based
verification that was remembered from the measurement programme WAS real, but
its scope was fixture verification for the translator legs, not model quality.

## The required measurement (gate for revisiting)

Chat-quality measurement in **all five supported languages**, on the
deployment hardware:

1. **Fixture set per language** — chat questions in cs/es/ru/tr (plus en
   baseline): native-authored where possible, otherwise machine-translated and
   back-translation-verified with the M1-717 protocol (DeepL/Google
   back-translate, every line read, corrections recorded). Track A — the
   model-evaluation fixture programme (harness local, not committed) — already
   has 26 English chat cases (kits B2/B3) as the shape precedent; the
   non-English sets must NOT be retranslations of them where idiom matters.
2. **Production-shaped prompts, tool loop included** — render through the real
   prompt builders so prefill size and TOOL_CALL behaviour are measured, not
   idealized. Pin the repo commit the whole run executes against (the
   measured-surfaces-are-moving rule).
3. **Candidates:** Qwen3.6-35B-A3B (tokenizer-coverage leader, 35.4 tok/s
   local — the natural local candidate), the model currently in the chat slot,
   and remote DeepSeek v4-flash as the reference arm. Greedy decoding (the
   shipped configuration).
4. **Scoring:** pre-registered thresholds written BEFORE the arms run; judge
   and/or human review per language; DeepL back-translation as the sanity tool
   for the reviewer, exactly as in the fixture verification. Hygiene columns
   (defect/void rates) alongside the headline, never averaged.
5. **Record latency on the deployment box** — prefill with translated context
   included, first-token and steady-state, so the §Variant B conflict 3 is
   measured, not assumed.

## Open decisions (before any implementation)

1. **Sanitizer policy for streamed display** — spec amendment governing
   pre-sanitize visibility, even buffered (security.md §LLM output sanitizer).
2. **`chat_memory` canonicity** — keep English-canonical (needs a persist-time
   back-translate) or accept multilingual memory (breaks cross-language memory
   tools; needs its own decision).
3. **Scope and flag shape** — SimpleX-only (Signal edit chains are 2-hop-proven
   at best, F-live-11/M1-566); DM chat mode first; a capability flag on the
   adapter plus an operator config flag defaulting to off.
4. **Which scopes get native generation** — all non-English scopes, or only
   those whose model cleared the per-language measurement.

## Revisit conditions

From §I1, amended by the hardware correction: (a) reply translation becomes
incremental/streaming-capable; (b) streaming is rescoped to same-language
scopes with an explicit sanitizer-policy decision; (c) upstream changes the
live-message economics; **(d) the §Required measurement clears a model for a
shipped non-English language — the first gate to run**; (e) a decision to
abandon English-canonical chat memory. Speed alone no longer vetoes.
