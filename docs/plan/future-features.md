# Future features — post-v1 wishlist

> **Created:** 2026-07-13, brainstorming session.
> **Scope:** deferred / candidate features to consider **after** v1 ships.
> This is a wishlist, not a commitment and not a v1 tracker. For what
> remains before the v1 *release*, see `docs/plan/v1-verification-truth.md`
> (the dated, provenance-tagged record of what's verified vs. still owed) and
> the live ticket board (`docs/plan/m1/STATUS.md`) — not the stale
> `docs/plan/v1-completion-plan.md` (a 2026-05-26 snapshot, now mostly done).
>
> **Note:** the subscription/tag-model redesign (subscribe-by-tag, source
> browsing, private custom sources) was pulled into **v1** — see
> `docs/plan/subscription-model-redesign.md`, not this wishlist.
>
> Each item records: **what**, **current state** (verified against code on
> the date above), **prerequisites / tensions**, and a **verdict**:
> `realistic-now` · `v2-milestone` · `needs-analysis` · `parked` ·
> `declined` (§J only — rejected outright, no revisit track).

---

## A. Endorsed / high-interest

### A1. Entity dossiers
**What:** a user asks about a named entity ("tell me about X") and gets a
deterministically-assembled timeline of posts mentioning X, plus related
entities — an entity-centric view, not a generic keyword search.

**Current state — does NOT exist as a feature.** The substrate is already
there and populated: the `post_entity` table (Tier-2 named-entity extraction,
decision D6) and the `post_reference` graph exist, but `post_entity` is
**internal-only** — used for cross-source linking, never exposed as a query.
Chat mode can approximate this today via `SearchPostsTool` / `GetReferencesTool`,
but there is no entity timeline assembly and no related-entities listing.

**Prerequisites / tensions:** stays cleanly inside the deterministic-retrieval
boundary (it's SQL over `post_entity` + `post_reference`). Main design work is
the query surface (a command vs a chat tool) and dossier rendering.

**Verdict: `realistic-now`.** Best substrate-to-value ratio of any new feature —
the data is already extracted, it just isn't surfaced.

---

## B. Multimodal (one foundation, three features)

> **Key finding (verified 2026-07-13):** the adapter SPI is **text-only in both
> directions**. `InboundMessage` and `OutboundMessage` are text-only records;
> both the SimpleX and Signal codecs are text-only and **actively drop inbound
> non-text** (a voice note never reaches the Provider). `CapabilityFlags` has
> no media/attachment/voice flag. The spec (`docs/spec/messaging.md`) and design
> notes (`docs/design/05/06`) explicitly scope attachments/voice/multi-modal
> **out of v1**.
>
> Consequence: all three ideas below ride on ONE prerequisite — a
> **media/attachment channel on the adapter SPI** (inbound + outbound fields,
> a new capability flag, and encode/decode changes in both adapters). Build that
> once; each feature is then incremental. Treat B as a single **v2 milestone**,
> sequenced voice-in → image → read.

### B1. Voice input → transcription → answer
**What:** user sends a voice message; the bot transcribes it (e.g. `whisper.cpp`,
local), echoes `processing request: {transcript}`, then answers as normal.

**Current state — not carriable today** (inbound non-text is dropped at decode).

**Prerequisites / tensions:** needs the inbound half of the media SPI. `whisper.cpp`
local keeps it privacy-preserving (no data leaves the host), which fits the
product. Determinism-clean: the transcript simply becomes the query text.

**Verdict: `v2-milestone`.** Highest value of the three — voice is a natural
messaging affordance and the transcription backend is self-contained.

### B2. `/image` — generate and return an image
**What:** `/image [--ratio|-r <WxH>] [--prompt|-p] "{description}"` → generate an
image via a local backend and return it, or return text explaining why it wasn't
possible. The "return text why it failed" graceful-degradation instinct is
correct and should be the contract.

**Current state — not carriable today** (outbound has no attachment field), but
**the backend is proven local and fast** as of the spike below.

**Backend — measured on this host 2026-08-05, not estimated.** ComfyUI (manual
install; ROCm 7.13 torch lives in conda env `py314` only) on the Strix Halo /
gfx1151 box:

- **Mage-Flow Turbo** (4.1B, MIT, `Comfy-Org/Mage-Flow`) — **4.38 s** per
  1024x1024 image steady-state; 15 s on the first run (model load). Settings
  4 steps / cfg 1.0. Needs the `qwen3vl_4b` text encoder + `mage_flow_vae` and
  the **Flux2 latent format: 128 channels, downscale ratio 16** — a 16-channel
  SD3/Z-Image latent node sails through sampling and then fails at VAE decode.
- Comparison points: Z-Image Turbo (5.9B) **21 s** measured; Krea 2 Turbo
  (13.1B, bf16, official Comfy template, euler/simple) — measured 2026-08-07:
  8 steps @ 1 MP **53.59 s** steady-state (87.47 s cold with model load),
  6 steps @ 1 MP **39 s**, 8 steps @ 0.6 MP **31.37 s**, 6 steps @ 0.6 MP
  **23.78 s**. Krea 2 raw ~5 min, estimated, not run. Step/resolution tuning
  plus the deferred ESRGAN upscale stage makes Krea viable as the quality tier.
- Launch flags are load-bearing on gfx1151: `--disable-mmap` (without it the
  first image took **11 minutes** — a ROCm mmap bug above 64GB), plus
  `--bf16-vae`, `--highvram`, and env
  `TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL=1` (**10.2x** attention, measured).
  fp8/int8 checkpoints are pointless here: `torch._scaled_mm` requires ROCm
  MI300+, so gfx1151 has no fp8 matmul and quantized weights only add dequant
  overhead.
- **Optional upscale stage — in scope, deliberately deferred (2026-08-05).** An
  ESRGAN pass (e.g. 4x-UltraSharp, ~64 MB) enlarges cheaply, letting the sampler
  run at a lower resolution and the output land at a higher one. **Adding it
  later costs infochat almost nothing:** the upscaler is just nodes inside the
  ComfyUI graph, so there is no SPI change, no new egress, no new security
  surface (user text still reaches only `CLIPTextEncode.text`), and no new flag
  — provided `--ratio|-r` is specified as *output* size, leaving the server free
  to hit it by sampling higher or by upscaling. The only infochat-visible costs
  are a few seconds of added latency (shifting the queue/rate-cap budget) and a
  larger attachment. Not measured; at 4.38 s it is not needed to ship.
  **Update 2026-08-09 — revived, and widened to all three models.** Found while
  preparing M1-798 (the ETA-constant work exposed it): the shipped pipeline
  bakes sampler settings into the workflow template with no operator-visible
  configuration, and this deferred upscale stage was dropped by the
  seven-ticket analysis decomposition entirely — none of M1-797..M1-806
  carries it, although the design addendum names it in the Krea 2 tier
  ("pairs with the ESRGAN upscale stage to recover output resolution").
  Re-scoped (user-approved direction 2026-08-09, numbers pending
  measurement): the upscaler becomes an OPTIONAL pipeline stage for ALL three
  models, on/off decided at setup time and baked into the per-model workflow
  template by the wizard step — Mage-Flow to reach bigger outputs (it is
  already fast), Z-Image because it is trained/optimized for 1024×1024 and
  other sampling resolutions gamble quality (sample at 1024, upscale to
  target), Krea 2 as the crucial case (0.6 MP sweet spot + upscale should
  recover 1 MP-class output near Z-Image's ~22 s instead of 53 s native).
  Diffusion steps become operator configuration the same way, with per-model
  recommended values from the measurements. `--resolution` stays the final
  output size; a converter maps it to per-model sampling density + upscale.
  Stock-node support verified at the pinned ComfyUI commit (spandrel-based
  UpscaleModelLoader + internally-tiled ImageUpscaleWithModel +
  ImageScaleToTotalPixels; no custom nodes, no image change);
  models/upscale_models/ is empty today. A container measurement spike gates
  the ship and runs before M1-798 starts (M1-798 prints the numbers and
  writes the templates, so the decision is an INPUT to it). Design layer:
  `docs/design/future/image-generation.md`, addendum 2026-08-09.
- **Unverified:** the attachment size limits SimpleX and Signal impose. Those
  bound maximum output resolution however the pixels are produced, so they need
  checking before `--ratio` accepts large sizes.

**Prerequisites / tensions:**

- Needs the outbound half of the media SPI (see the §B header). That remains the
  bulk of the work — the backend is the easy part.
- **The privacy/cost tension recorded 2026-07-13 is resolved.** That entry
  assumed local generation was too heavy for the deployment profile and that
  remote generation would leak prompts. At 4.38 s and never leaving the host,
  local generation now *supports* the privacy-first posture instead of cutting
  against it. The tension returns only on a CPU-only VPS profile.
- **Content liability is no longer the open question.** The 2026-08-05 design
  conversation settled it: `/image` is available in DM and groups, with no
  prompt pre-filter, resting on attribution (D44 invite-gated DMs, D47
  admin-approved groups) and operator model choice rather than on model
  guardrails. A `/redteam` pass at design time still stands.
- **The design layer now lives in
  [`docs/design/future/image-generation.md`](../design/future/image-generation.md)** —
  the 18-step flow, the adapter-SPI delta, the credit/cooldown/queue-depth
  model, the six-link no-content chain (the prompt is treated as message
  content: never logged, never audited, stripped from the PNG), the tmpfs
  spool + sweeper lifecycle, and the failure contract. The architecture and
  security constraints from this spike — no MCP, server-side graph, `127.0.0.1`
  bind, SSRF-routed egress, GPU queueing — are carried there verbatim rather
  than duplicated here.

**Verdict: `v2-milestone`** — the backend is proven and the content policy is
settled; the outbound-media plumbing is what remains. **User priority
(2026-08-05): B2 ahead of B1/B3**, against the §B header's voice-in → image →
read sequencing.

### B3. `/read` — return the last answer as audio (TTS)
**What:** `/read` sends the previous answer back as a playable audio file.

**Current state — not carriable today** (same outbound-media gap as B2).

**Prerequisites / tensions:** rides on the same outbound-media foundation as B2,
plus a TTS backend (local preferred for privacy).

**Verdict: `v2-milestone`** — cheapest of the three once B2's plumbing exists.

---

## C. Proactive delivery

The product is today largely **pull** (commands, chat) plus a **scheduled group
digest**. These items add proactive, per-user push. The plumbing (LISTEN/NOTIFY,
entities, tags, per-(user,scope) isolation, the digest scheduler) already exists.

### C1. Watchlist / alerts (push on new matching post)
**What:** `/watch <tag|entity|keyword>` → push a message when a new matching post
lands.

**Current state — does NOT exist.** Note the naming trap: `/follow-tag` and
`/follow-all-sources` are **content subscriptions** that gate digest/summary
content — they are **pull, not push**. There is no push-on-new-match today; the
new-post fan-out path (`NewPostHandler`) is currently a logging stub.

**Verdict: `v2-milestone`.** The single change that most alters what the product
*is*; reuses NOTIFY. Deterministic if the match is a SQL predicate.

### C2. Scheduled personal (DM) digest
**What:** the group digest, but per-user in DM, over the user's subscribed
tags/entities.

**Current state — does NOT exist.** Only the **group** digest is scheduled;
`/digest on|off` is group-only. DM users get on-demand `/summary` (pull) but no
scheduled personal digest.

**Verdict: `v2-milestone`.** Natural sibling of C1; reuses the digest worker.

### C3. Developing-story / trending detection
**What:** surface when many sources cover the same entity/topic in a short window.

**Current state — does NOT exist as a surfaced signal.** The clustering infra it
would build on **does** exist (the `post_reference` graph + `ClusterTraversal`
connected-components), but clusters are only ever shown inside a `/summary` or
digest the user explicitly requests — never as a trending signal, ranking, or
alert.

**Verdict: `needs-analysis`** — the detection heuristic (window, threshold,
novelty vs. re-surfacing) needs design before it's a ticket.

---

## D. Translation & cross-lingual

The user's proposal ("set language=czech → translate the original post with a
local model; per-language RAG") tangles two separable things. Decomposed:

### D1. Display-time translation of post bodies
**What:** show a non-English post's body translated into the user's language,
opt-in, at read time.

**Current state / tension.** Today translation is a **presentation-layer** concern
limited to LLM-authored bot prose; **decision D29 forbids translating source post
bodies**, and a verification spy enforces that no `post.body` reaches the
translator. The stated rationale is **retrieval determinism** — embeddings,
retrieval, and entity extraction must run on original-language text
(`llm.md`, `05-llm-and-embeddings.md`). **Reframe:** D29 is really "never
translate the body *that feeds embeddings/retrieval*." A pure *display-time*
translation — never stored, never embedded, never fed to retrieval — does not
touch determinism, but it *would* trip the current spy and therefore needs a
**spec amendment** carving out an opt-in presentation transform.

**Verdict: `needs-analysis`** (spec decision on D29 first).

**Update 2026-08-02 — RESOLVED, and the reframe above was right.** D29 was
amended (`21ad3517`) to permit exactly the carve-out this entry argued for:
display-time translation of a retrieved post's **title and snippet** into the
reader's language, never stored, never embedded. Filed as **M1-747**. Note the
narrowing — title and snippet, not the whole body: cost then scales with what a
user reads rather than with what has been ingested. The verification spy was
retargeted at the presentation path in the same commit.

### D2. Cross-lingual retrieval
**What:** a Czech query retrieves relevant English (etc.) posts.

**Current state / tension.** This is an **embedding-model** question, not a
corpus-partitioning one. v1 embeds every post with `nomic-embed-text` (768-d,
local, single vector space, guarded by the D54 model-identity check).
`nomic-embed-text` is only weakly multilingual, so cross-lingual matching is
incidental. A stronger multilingual embedding model would enable this — but that
is a corpus-wide re-embed and a model-identity-guard change, not a per-language
RAG. **Note:** the proposed "per-language RAG (empty if no Czech subscription)"
would fork the single-vector-space guarantee and is the wrong framing — the goal
is cross-lingual *matching*, which partitioning actively works against.

**Verdict: `needs-analysis`** (evaluate a multilingual embedding model + re-embed
cost/quality trade-off).

**Update 2026-07-30 — actioned, do not re-analyze from scratch.** Measured against
the live embedder: `nomic-embed-text` cannot represent Cyrillic at all (0/7 ranking
within a Russian-only pool; random Cyrillic characters score 0.710 against a real
Russian sentence versus 0.805 for a genuine paraphrase — the vector encodes script,
not meaning, because the English WordPiece vocab tokenizes Russian at 1.13
chars/token versus 5.46 for English). Three multilingual replacements score 7/7
across en/fr/ru/cs. The re-embed and threshold recalibration are now ticket
**M1-717**; the evaluation instructions and results scaffold are
`/home/infochat/EMBEDDER-HANDOFF-PROMPT.md` and
`EMBEDDER-MEASUREMENT-RESULTS.md`. This entry's framing above was correct on both
counts — it is an embedding-model question, and partitioning works against the goal.

**Update 2026-08-02 — RESOLVED, but NOT by an embedder swap. Do not act on the
2026-07-30 note above.** Measurement (`docs/measurement/translator-slot.md`)
established that the multilingual swap buys +0.12 on non-English and *costs*
0.02–0.07 on English, on a corpus that is 100% English — and that the incumbent
`nomic-embed-text-v1.5` is not beaten on English by any candidate while being
2–5× faster. The adopted answer is the **English pivot** (D29 amended): the
corpus is anchored in English at ingest and the query is translated into the
anchor under D58's four bounded conditions, so **both sides of every comparison
are English and the embedder never sees non-English text**. Consequences:
no 768→1024 migration, no whole-corpus re-embed, no D54 amendment, and an
embedder may be selected on English inputs alone. **M1-717's stated
justification ("the swap is required regardless") is gone** — it is a filed
ticket someone could still pick up, so it needs closing as superseded or
rescoping to the separability problem, which is unsolved and independent.
Implementation: M1-745 (ingest leg), M1-746 (query leg).

### D3. Per-language full-text search config (lexical arm)
**What:** the lexical half of hybrid retrieval stems with the target language's
rules, so an inflected Spanish/Russian/Turkish query matches its document forms.

**Current state / tension.** `post.search_tsv` is a **STORED generated column**
pinned to `to_tsvector('english', …)` (`V58__post_search_tsv.sql`), and the query
side pins `plainto_tsquery('english', …)` in `SemanticSearchTool`. Both sides must
share a regconfig or matching degrades — different stemmers emit different lexemes —
so changing only the query side to the scope's language makes results *worse*, not
better. Doing it properly needs a `post.language` column (does not exist; language
is recorded only on `scope_preferences`), ingest-time language detection, and a
rewritten generated column, which `V58`'s own cost note flags as a full partition
rewrite plus a whole-corpus GIN rebuild. Postgres ships snowball configs for
`spanish`/`russian`/`turkish`/`arabic`/`hindi` but **not** Thai/Japanese/Chinese/
Korean — those have no word-boundary notion in the default parser and would need a
tokenizer extension (pgroonga, pg_bigm, MeCab-based), i.e. a new dependency in the
Postgres image. Separately, making the regconfig an input to the fused query needs a
D19 answer: `SemanticSearchTool` pins it deliberately so the retrieved set cannot
become session- or GUC-dependent.

**Contingent on ingesting non-English *sources*.** With a ~100% English corpus (2 of
9,259 post titles carry non-Latin script as of 2026-07-30) the document side must
stay `english` no matter how many *output* languages ship, so this buys nothing
today. Explicitly deferred out of M1-717.

**Verdict: `parked`** (understood, no current effect; revisit when non-English
sources are ingested).

**Update 2026-08-02 — SUPERSEDED as a plan; kept because its analysis is the
reason the alternative won.** Per-language regconfig is precisely the option the
English pivot rejected. D29 (amended `21ad3517`) anchors the corpus in English,
so `search_tsv` keeps ONE `'english'` configuration and a non-English post is
matched through its derived English field instead of its own stemmer. That
sidesteps every cost this entry enumerates — no `post.language`-driven column
fork, no per-language GIN rebuild, no session-dependent regconfig (so the D19
question does not arise), and no tokenizer extension for Thai/Japanese/Chinese/
Korean, which have no snowball config at all. The ingest-time language question
is answered by a **declared** `source.language`, never detection. Implemented by
M1-745.

### D4. Configurable corpus anchor language
**What:** make the corpus anchor language a deployment setting (`en` by default
and recommended) instead of hardcoded English, so a deployment whose sources and
users are all one non-English language stops paying two translation hops for
nothing.

**Why it is real.** Under a hardcoded English anchor, a Spanish-only deployment
translates every Spanish post ES→EN at ingest and every Spanish query ES→EN,
round-tripping through a language nobody reads, paying an LLM call per post plus
translation loss on exactly the entities and technical terms D4/D29 care about —
while native ES→ES matching would have none of it.

**Why it is NOT built now.** v1 ships English and **Czech**, and Czech **cannot
be an anchor**: this Postgres has 29 text-search configs and `czech` is not among
them (nor `japanese`/`chinese`/`thai`/`korean`). The knob's only working values —
`spanish`, `portuguese`, `russian`, `turkish`, `arabic`, and the other snowball
languages — are ones this product does not ship a `/lang` bundle for. It would be
a setting with no usable non-default value, which is speculative generality.

**Constraints for whoever builds it, so they are not rediscovered:**
- Must validate the anchor against `pg_ts_config` **at boot and refuse to start**
  on an unsupported value. Silently falling back to `simple` (no stemming)
  degrades the lexical arm — the exact failure the pivot exists to fix, and the
  M1-597 shape (~4,700 posts silently labelled `unknown`).
- It is a **deploy-time, effectively immutable** setting: `search_tsv` is a STORED
  generated column with the regconfig baked into its DDL, so changing it later
  means a migration plus a full partition rewrite, GIN rebuild and re-embed.
- It **reopens the embedder question** for that deployment. "Select the embedder
  on English inputs alone" is only valid because the anchor is English;
  nomic-v1.5's 0.630 English recall does not transfer to another anchor.
- For a **mixed** corpus it can backfire. Crypto/AI news is English-dominated, so
  a Spanish anchor would translate EN→ES for the *majority* of posts — more work
  than the English anchor, and lossy on tickers and project names. The knob wins
  only for a genuinely monolingual deployment, and the operator is the one who
  knows which they have.

**Prerequisite that is not the knob:** such a deployment also needs a `/lang`
bundle for its language (D43 bilateral keyset). The anchor is not the first thing
it would be missing.

**Verdict: `needs-analysis`** — revisit when a deployment appears whose sources
are majority one non-English language that Postgres stems. The change is small
when it comes: M1-745 already isolates it to one comparison
(`source.language <> 'en'`) and one DDL literal.

---

## E. Smaller polish (data mostly exists)

### E1. Chat-mode clustering parity
Cross-source story clustering already ships in `/summary` and digests
(`ClusterBlockRenderer`: one story → "covered by: source A (uid …), source B
(uid …), score: N sources"). Chat-mode tools (`searchPosts`, `getReferences`)
do **not** cluster — they list posts independently. Bringing chat mode to parity
is a bounded enhancement. **Verdict: `realistic-now`.**

### E2. Canonical duplicate collapse
There is cross-source *linking* (`post_reference` edges) but no *collapse* — no
`duplicate_of` / `cluster_id` column, no simhash/content-hash near-dup. Exact
refetch dedup (global UID) and cross-relay Nostr dedup are robust. True
"N outlets → one canonical post" is a schema + ingest change. **Verdict:
`needs-analysis`.**

### E3. Generic degraded-feed detection
The xcancel "not whitelisted" placeholder is now detected and trips the D42
failure ladder (M1-588). But that check is **xcancel-specific**; there is no
generic "200-but-degraded-content" framework for rss/bluesky/youtube/odysee.
**Verdict: `realistic-now`** (extend the existing D42 ladder).

### E4. Source-health operator view
The health *state* already exists (`consecutive_failures`, `last_success_at`,
`active→failed`; `/list-sources`; Prometheus registry). What's missing is a
single at-a-glance operator view (e.g. a `/source-health` summary) so silent
staleness is *seen*, not discovered in a live test. **Verdict: `realistic-now`**
(surfacing only; no new tracking).

### E5. Content-world user-state integrity invariants (D59 hardening)
The M1-621 red-team flagged (out-of-model, inside the accepted §DB-roles
residual) that a Provider SQL-injection foothold could mass-insert/delete
`source_exclusion` rows to blank chosen bootstrap sources out of every scope's
digest/retrieval — but the same foothold could already mass-mutate
`source_subscription` (Provider has held full DML there since V7), so the delta
is small. Still, exclusion + subscription rows are the user-state whose
presence/absence reshapes every scope's content world; a future row-count
invariant or trigger over BOTH tables would catch such tampering. **Verdict:
`parked`** (post-v1 hardening; not a threat-model violation today).

### E6. Digest-cache invalidation on exclusion change (D59)
`/unfollow-source` (bootstrap exclude) and `/follow-all-sources` (clear
exclusions) do not bump `scope_preferences.source_subscription_version`, so a
cached group digest can transiently include a just-excluded bootstrap source
until the next slot recompute. Currently harmless: the version column is
**inert** (stored, never compared on read — M1-621 plan-phase ground truth), and
excluded content is public bootstrap corpus (a preference opt-out, not a
confidentiality control). If a future ticket makes `source_subscription_version`
load-bearing for cache invalidation, the exclusion paths must bump it too.
**Verdict: `parked`** (correctness quirk, no current effect).

### E7. Cost-weighted LLM rate cap (calls vs. actual cost)
`LlmRateCap` charges **one token per turn**, but a turn's cost is not fixed: a
chat turn runs a multi-turn tool loop of up to `ChatAgent.MAX_TOOL_ITERATIONS`
(10) LLM round-trips, while an on-demand `/summary` is a single summarizer call.
Both draw the same single token, so a sender asking loop-heavy questions can cost
roughly an order of magnitude more than another sender on an identical budget.
Surfaced by the 2026-07-16 concurrency review (which also produced M1-635/M1-636).

Harmless today: the cap (10/min; 20 under `%remote-llm`) still bounds worst-case
*absolute* spend per sender, and the population is a handful of known users. The
mismatch only becomes interesting when the bot is opened to senders who are not
known personally, or if LLM spend becomes material.

Two tensions any redesign must respect. First, **input size is the wrong proxy**:
a short question can trigger a 10-iteration loop and a long one can be answered
in a single call, so counting characters or input tokens mispredicts cost in both
directions. Second, **the single-bucket property is load-bearing** —
`LlmRateCap`'s javadoc records that chat, `/summary` and `/retry` deliberately
share ONE bucket so "a caller cannot bypass the cap by switching surfaces";
splitting the bucket per surface to price them differently would reopen that
bypass.

The model that would work is **post-hoc cost debt**: charge the bucket by what a
turn actually consumed (tool-loop iterations, or provider-reported usage), so an
expensive turn shrinks the *next* turn's budget. This necessarily permits one
over-budget turn before it bites — cost is unknown until the call returns — and
it requires the LLM client to report usage back to the cap plus a debt/decay
policy. **Verdict: `parked`** (real mismatch, no current effect; revisit on open
enrolment or material spend).

### E8. Embedding-space equivalence guard (backend-swap determinism)
`EmbeddingMetadataStartupGuard` pins the embedding model **name + dimension**,
not vector-space equivalence, and the `allow-model-change=true` override only
WARNs ("run the re-embed procedure") rather than enforcing it. Two backends both
reporting `nomic-embed-text`/768 but producing differently-normalized vectors
would pass silently, writing pgvector rows incomparable with existing ones —
degrading deterministic retrieval (D6/D19) with no error. Fenced today: D54 pins
embeddings to ONE local Ollama nomic-768 backend for the deployment's life,
`allow-model-change=false` by default, and the M1-428 re-embed procedure is
documented — so the exposure requires a deliberate same-name backend swap the
design already steers against. A true numeric-equivalence check needs a reference
corpus at startup (non-trivial). Origin: M1-513 relevance analysis (2026-07-17;
ticket abandoned as superseded). **Verdict: `parked`** (real latent gap, no
current effect).

---

## F. Parked

### F1. Reactions / feedback loop
**What:** 👍/👎 or "less like this" as a per-user ranking signal.

**Why parked:** the adapter SPI exposes **no reaction capability** (no reaction
flag on `CapabilityFlags`), so this would force clunky command-based hacks with
uneven cross-adapter support — over-engineering risk for marginal signal. Revisit
only if a media/reaction SPI extension (see §B) lands and exposes native
reactions. **Verdict: `parked`.**

---

## G. External integrations

> **New category (added 2026-08-05).** Every entry in §A–§F is either about
> infochat's own data or about widening the adapter SPI. This section is for
> integrations with a **third-party service that has its own user identity
> model** — which is the part that needs design work, not the API call.

### G1. `/jellyfin` — search a local media server
**What:** `/jellyfin [audio | movie | show | book] <search term>` queries a
self-hosted [Jellyfin](https://github.com/jellyfin/jellyfin) media server and
returns matching items. The bare form searches all types.

**Current state — does NOT exist; zero substrate.** No Jellyfin reference
anywhere in the repo (verified 2026-08-05). Jellyfin's side is the easy half and
is ready to use as-is: `GET /Search/Hints` (the endpoint the web UI's search box
drives) and `GET /Items?searchTerm=…&recursive=true` both accept
`includeItemTypes`, so the four subcommands map to `Audio,MusicAlbum,MusicArtist`
/ `Movie` / `Series,Episode` / `Book,AudioBook`.

**Shape:** asset-command-shaped, not post-shaped. Like `/zcash` and `/monero`,
results are an external live fetch with **no Stage 1/2 evaluation, no tagging, no
embedding, and no `post` rows** — `docs/design/10-asset-commands.md` is the
template, not the ingest pipeline. The reply names its data source, per the same
attribution rule.

**Prerequisites / tensions:**

1. **Identity bridging is the unsolved part.** Jellyfin enforces per-user library
   access (Dashboard → Users → Library Access), and its search honours it:
   `/Search/Hints` and `/Items` both resolve the caller via
   `RequestHelpers.GetUserId`, and a non-admin token cannot pass another user's
   id (`"User must be administrator to access another user"`). So the isolation
   is real and server-side — but infochat users are adapter contact ids, not
   Jellyfin accounts, and *something* has to bind the two. Three credential
   shapes, none clearly right:
   - **(a) One operator API key, no mapping.** Simplest; every infochat user
     searches the whole library. Discards the per-user isolation entirely.
   - **(b) Operator API key + per-user `jellyfin_user_id` mapping.** Every call
     passes `userId=`, yielding that user's exact view. **Best of the three** —
     the only one where the stored-secret count stays at one, with no password
     handling and no token expiry. *Trap:* a Dashboard API key is a server-level
     credential bound to no user, so a call that omits `userId` is **not**
     user-filtered and silently returns everything. The mapping would have to be
     mandatory, not defaulted.
   - **(c) Per-user access tokens via Quick Connect.** `POST
     /QuickConnect/Initiate` returns a secret + short code, the user enters the
     code in their own Jellyfin client, and the secret is then exchanged for that
     user's token — real per-user auth with no password reaching the bot. Costs
     N secrets at rest plus expiry handling; that surface would need justifying.

   Open question either way: is the binding **operator-configured** (admin sets
   the mapping) or **user-driven** (a link/pair flow)? That choice, not the
   search call, is what this entry is waiting on.

2. **DM-only.** A group reply renders one member's library view into a room
   transcript everyone can read — under (b) the sender's own view, which is
   exactly the per-(user, scope) leak the isolation rule forbids. Restrict to DM,
   mirroring how saves are scoped.

3. **Search quality ceiling.** Jellyfin's search is substring/prefix matching
   over titles and a little metadata — not full-text over lyrics, book contents,
   or subtitles. Setting expectations is part of the design, not a later fix.

**Verdict: `needs-analysis`.** The API surface is trivial and the command shape
has a clean precedent; the identity-bridging model is the blocker and no option
is convincing enough to commit to yet.

---

## H. User-to-user routing

> **New category (added 2026-08-06).** §A–§F are about infochat's own data or
> about widening the adapter SPI; §G is about third-party services. This section
> is for the bot carrying traffic **between two of its own users** — a different
> thing again, because it is the one feature class that deliberately breaks the
> per-(user, scope) isolation invariant rather than working within it.

### H1. `/bridge` — cross-adapter user-to-user channel

**What:** two infochat users on different adapters get a bot-mediated channel.
Sketched shape (user proposal, 2026-08-06): Alice sends `/bridge open`, receives
a token, passes it to Bob out-of-band; Bob sends `/bridge connect <token>`, which
raises an approval prompt to Alice; on approval a channel exists until burned
(`/bridge close`, expiry, or revocation).

**Current state — does NOT exist; zero substrate, and the codebase actively
guards against the thing it would do.** There is no cross-user message path of
any kind. `MessagingAdapter`'s javadoc pins **`(adapter, contact_id)` as the
cross-adapter isolation join key**; `InboundRouter` and `InterruptibleDispatcher`
both carry comments naming a worker-side read of another user's state as "exactly
the cross-user leak." Ban is enforced per-`(adapter, contact_id)` at intake.
`CapabilityFlags` has no relay-relevant flag. So this is not a module that slots
in — it is a **designed exception to a stated invariant, and needs a decision
(D-number) plus a spec amendment before any code**.

**Prerequisites / tensions:**

1. **The bot becomes a MITM between two humans, and no crypto fixes it.** Today
   infochat is a *counterparty* — users talk *to* the bot and everyone
   understands the bot reads it. A relay silently converts "Signal E2EE between
   Alice and Bob" into two E2EE legs with a plaintext hop on the VPS. This is
   inherent to protocol bridging, not an implementation flaw (Matrix bridges have
   had it unsolved for a decade). **Mixnets do not help**: they distribute the
   `Alice→Bob` fact across independent nodes precisely because mix nodes are dumb
   forwarders needing only next-hop. A bridge must decrypt, read plaintext,
   resolve the peer's id on the target adapter, and re-encrypt — every step
   requires the knowledge a mixnet exists to withhold. Same for PIR / oblivious
   mailboxes (Pung, Talek, Vuvuzela): those hide *storage and retrieval* of
   opaque blobs, and stop applying the moment the server must transform the blob.
   General rule: cryptography can hide data from a node that need not read it,
   never from a node whose job is to read it. Consequence for naming and copy:
   the feature must **not** be called a "secure" or "private" channel, and should
   carry a per-message not-end-to-end-encrypted marker.

2. **It builds a cross-network correlation database.** Routing requires storing
   `alice@signal ↔ alice@simplex` plus the pairwise edges. That artifact exists
   nowhere today and no single messenger operator can produce it. Mitigations
   shrink it but cannot remove it (routing *is* knowing who talks to whom): no
   directory and no discovery of any kind; edges only by explicit mutual opt-in;
   store `(edge_id, adapter_a, contact_a, adapter_b, contact_b)` with no names
   and no "user → their contacts" query path; local-only aliases; zero content
   retention; per-edge revocation and default expiry.

3. **Ban evasion is reintroduced.** Ban is per-`(adapter, contact_id)`, so a user
   banned on Signal reaches the same victim over SimpleX through the bridge.
   Closing that requires unifying ban across adapters — which requires the
   correlation database from (2). Circular; pick deliberately. Ban must be
   re-checked on open, on connect, **and on every relayed message** (ban state
   changes mid-bridge).

4. **Trust laundering across adapter trust levels.** A `LOW`-trust adapter's
   message relayed into a `HIGH`-trust user's DM arrives indistinguishable from
   any other. `AdapterTrustLevel` is currently a registration-time gate, not a
   per-message property; a relay would have to carry and render it per message,
   unstrippably.

5. **Token handling — the invite precedent is the trap, not logging.** A grep
   found **no** command-argument logging, so the "password appears in logs" worry
   is unfounded as the code stands. The real hazard is the opposite one:
   `InviteCommandHandler` writes the **minted invite code itself** into the audit
   row (`AuditAction.INVITE_CREATE`, audit-before-effect). A bridge token
   following that precedent would be persisted in plaintext in `audit_log`. It
   needs the D37 `connectContact()` treatment instead — never logged at any
   level, never persisted — which means auditing the *event* with an edge id, not
   the token.

6. **User-chosen passwords are the wrong primitive.** The sketch's
   `--password 'pswd'` gives low entropy, and because the token namespace is
   global across open bridges, `/bridge connect` becomes a *probe*: spray common
   passwords, harvest approval prompts from strangers. Bot-generated, high-entropy,
   single-use, one-guess-per-token, rate-limited per requester. The pattern worth
   copying is **Magic Wormhole's** codes (`7-crossover-clockwork`) — short and
   human-transcribable, SPAKE2-backed so observing the code channel does not yield
   the key. Its derived short authentication string also answers the residual
   impersonation gap (Alice approves *whoever knew the token*, not provably Bob).

7. **UX: separate establishment from use.** Re-approval per reconnect — the
   sketch's stated shape — makes it unusable; nobody accepts a handshake per
   conversation. Approve once → durable pairing; burn is explicit or on
   inactivity; keep single-use as an opt-in mode. Prefer **inactivity expiry over
   message counts** (a bridge dying mid-conversation at message N is baffling).
   Do not offer "forever": the edge list would then only ever grow, which is the
   artifact (2) is trying to bound.

8. **The value/security inversion — the reason this is not obviously worth
   building.** The token must travel out-of-band. If Alice and Bob already have a
   private channel to carry it, they could carry a SimpleX invite link through
   that same channel instead and get real E2EE with no plaintext hop — the bridge
   adds nothing. The bridge only earns its existence when the out-of-band channel
   is *public or low-bandwidth* (a code read aloud, or posted), which is exactly
   where the token is weakest and enumeration risk highest. **The materially
   simpler alternative is a rendezvous, not a relay:** the bot brokers a one-time
   contact-link exchange between two consenting users, deletes the edge, and gets
   out of the way — no plaintext, no persistent graph, no ban-evasion path, and
   the conversation then rides SimpleX's own design (no user identifiers,
   unidirectional queues deliberately split across two servers so neither sees
   both directions), which is a production relay that genuinely does not learn the
   social graph. Its one limit is real and unfixable: it only works when both
   parties already share a protocol. **Cross-protocol translation and metadata
   privacy are mutually exclusive** — closer to a theorem than an engineering gap.

**Briar as a third adapter — separate question, answered; do not re-derive.**
Raised alongside the bridge, and the answer is independent of it. `briar-headless`
is a genuine REST + WebSocket API (bearer token from `~/.briar/auth_token`;
`GET /v1/contacts/add/link`, `POST /v1/contacts/add/pending`,
`POST /v1/messages/{contactId}`, `WS /v1/ws` with
`ConversationMessageReceivedEvent`), and it maps cleanly onto `send` /
`setInboundHandler` / `start` / `stop` / `connected`, with `connectContact()`
fitting `add/link` exactly. Three blockers and one finding:

- **The finding that decides it: Briar's mesh property does not transfer to a
  server-hosted bot.** BLE/Wi-Fi mesh only reaches physically-nearby peers; the
  Provider is a server, so reaching it always needs internet. A Briar adapter
  therefore buys *a second Tor-transported, no-phone-number channel* — not
  shutdown resilience — and SimpleX already covers that ground with better
  ergonomics and an actively-developed codebase. Evaluate on that basis, not on
  the mesh story. (The same collapse applies to H1: if both users must reach the
  VPS, the architecture is internet-dependent whatever it speaks.)
- **`contactId` is a node-local integer**, not key-anchored — restore the node
  from backup or re-handshake and integer `3` can be a different human, i.e.
  silent privilege transfer, exactly what `isWellFormedContactId` exists to
  prevent. `pendingContactId` *is* a base64 hash of the handshake public key but
  only exists pre-confirmation, so the adapter would have to capture the peer key
  at `ContactAddedEvent` and hold its own mapping. Until then it is honestly
  `AdapterTrustLevel.LOW` and Provider gates it out of admin paths.
- **No groups in the headless REST surface** (private messages and blogs only) —
  a permanent capability gap against the groups milestone, not a temporary one.
- **No reusable invite link**: every contact is a mutual `briar://` exchange plus
  a Tor handshake needing both nodes online, so onboarding is a per-user
  out-of-band round trip.

Briar entered **maintenance mode 2026-07-09** (security and bugfixes only,
volunteer-run, no dedicated funding). That is close to neutral here — the API
will not grow, but it will not churn either.

**Verdict: `needs-analysis`.** The proposed shape is structurally sound
(capability token, mutual opt-in, no directory, burnable) and the fixes in (5)–(7)
are known. What is unresolved is whether the feature should exist at all, given
(1) — the bot reads everything and cannot be made not to — and (8) — the
rendezvous alternative delivers most of the value at a fraction of the risk for
same-protocol pairs, leaving the relay to justify itself on the cross-protocol
case alone. Sequencing if it does proceed: rendezvous first; relay second, opt-in,
Signal↔SimpleX only (both `HIGH` trust, both already working); Briar last, and
only for its own reasons.

---

## I. Live text streaming (web-chat feel)

> **New category (added 2026-08-08).** Streaming reveal of bot replies so slow
> turns feel like a live web-chat interaction instead of minutes behind a
> "Generating…" stage label. Investigated 2026-08-08 against upstream and the
> codebase; parked the same day — the entry records the investigation so it is
> not re-derived.

### I1. Streaming reveal of bot replies via SimpleX live messages

**What:** reveal the bot's answer incrementally as it is generated, by editing a
placeholder message in place with SimpleX "live messages" (`/_update item …
live=on`, terminal `live=off` finalize), behind a config flag: off = today's
behaviour (coarse stage-label progress, M1-607), on = live text.

**Current state — investigated 2026-08-08:**

- **Upstream: available, NOT deprecated.** Live messages shipped in simplex-chat
  v4.4 (Dec 2022) and are fully present in the latest v7.0.0 (2026-07-28); the
  command grammar is unchanged (`/_send <ref> live=on|off`, `/_update item <ref>
  <itemId> live=on|off json …` — v7.0.0 `src/Simplex/Chat/Library/Commands.hs`
  :5453,:5462). The official clients use the feature themselves (their
  updates-as-you-type UX), and the official nodejs bot lib exposes it
  (`apiSendMessages(…, liveMessage)`, `apiUpdateChatItem(…, liveMessage)`).
  Semantic: once an item is finalized `live=off` it can never become live again
  (`live' = (live &&) <$> itemLive`) — matches a placeholder→finalize lifecycle.
- **The adapter ALREADY uses it.** `SimpleXMessageCodec.encodeUpdateCommand` /
  `encodeFinalizeCommand` emit `live=on` / `live=off` for the progress-notifier
  path (placeholder → coalesced stage-label edits → finalize), live-proven on
  v6.5.4.1 (the s10 live run: `/summary` finalized via `chatItemUpdated`). The
  project pins simplex-chat v6.5.4 (Dockerfile); both it and v7.0.0 support the
  feature — no upstream upgrade would be needed.
- **What is missing: the token source.** `LlmProvider.generate` is single-string
  non-streaming; none of the three provider impls parses SSE; `ChatAgent` runs a
  blocking multi-turn tool loop. M1-607 deferred exactly this: "A streaming SPI
  is a separate, larger decision."

**Difficulty (assessed 2026-08-08): medium-high.** The transport ~20% is done
and live-proven (coalescing 600ms floor, shared 5/s token bucket, edit-failure
`fallback_send`, metrics). The other ~80%: a streaming LLM SPI + three provider
impls + router/startup-assertion changes + ChatAgent wiring + a notifier
streaming path + a capability flag + host live-validation. Multi-ticket effort.

**Scope if revisited:** SimpleX-only (Signal edits are real message edits; only a
2-hop chain is live-proven — F-live-11/M1-566). DM chat mode first; groups work
technically but every update is a full relay message fanned out to every member.

**Hurdles (in size order):**

1. **Display-time reply translation — the deciding one.** The streamed prefix is
   GENERATED text, but for non-English scopes the final visible text is its
   D29 presentation-layer translation — the streamed content is discarded and
   replaced wholesale, so every streamed chunk is wasted display ending in a
   jarring full-text swap. The considered alternative (throttled chunk batches,
   re-translated on every batch) multiplies translator LLM cost per reply and
   still ends in the same swap. (Precision note for future readers: the
   ingest/query-leg English pivot — embeddings, similarity search, tags — is
   UNTOUCHED by reply streaming; the conflict is solely with display-time reply
   translation. That is still enough to decide it.)
2. **Sanitizer / refusal timing.** The M1-663 sanitizer regime and the D21
   refusal intercept run AFTER generation: streamed text is pre-sanitize, and a
   refusal cannot be unseen once revealed. Streaming temporarily displays
   unsanitized model output — a spec/security decision, not just code.
3. **Non-streaming LLM SPI** — the largest pure-work item.
4. **Multi-turn tool loop** — segments that end in a tool call produce no final
   text; must not flash tool JSON or text that gets dropped.
5. **Pacing reality** — each edit is a full encrypted SMP message over relays
   drawing from the shared 5/s bucket, and official clients render live updates
   "every few seconds" anyway, so token-smoothness is unattainable; chunked
   (~1s) updates are the realistic ceiling.

**Verdict: `parked`** (user decision, 2026-08-08 — the translation conflict is
structural, and chunk-batch re-translation is neither good practice nor resource
sound). Revisit only if one of these changes: (a) reply translation becomes
incremental/streaming-capable; (b) streaming is rescoped to same-language scopes
only, with an explicit sanitizer-policy decision for pre-sanitize display; or
(c) upstream changes the live-message economics (e.g. a first-class streaming
bot API).

**Addendum 2026-08-08 — the native-generation variant, analysed and also parked.**
Proposed alternative: drop the English pin for chat; translate the *retrieved
context* into the user's language, keep the user's question in the original, and
let a language-capable model generate the reply directly in the user's language —
which removes the translation-vs-streaming conflict (this is the summarizer's
existing "language-aware" shortcut, llm.md §Translation flow, extended to chat).
Findings:

- **Sanitization is NOT the blocker.** `LlmOutputSanitizer` is deterministic
  regex/line transforms; it can run ON the stream with a small hold-back buffer
  at chunk boundaries, and the D21 refusal intercept is a prefix check that fires
  before streaming starts. Costs: boundary-spanning-token handling, per-call audit
  aggregation rework, and a spec amendment — leaked bytes cannot be unseen.
- **The model evidence does not exist.** `docs/measurement/model-lang-coverage.md`
  is tokenizer-only ("a screening tool, not a verdict"); `translator-slot.md`
  measures translation legs, not target-language chat quality. No measurement
  proves any local model produces chat-quality Czech/Spanish WITH tool calling.
- **Latency math works against it.** On the shipped 4-vCPU profile generation is
  ~8–10 tok/s and the wait is PREFILL-dominated (SETUP_GUIDE §AI backend
  performance); the variant adds an EN→user-language context-translation leg
  BEFORE the first token can be generated, and Czech/Spanish context runs longer
  than English. Streaming would only ever fix the generation part.
- **Two new structural conflicts:** `chat_memory` is English-canonical by design
  (native-language turns break it or force a lossy back-translate per turn), and
  the chat tool loop forces the model to interleave the English TOOL_CALL
  protocol and English tool results with user-language prose — a harder task than
  the translation the measurements did validate.
- Multilingual-strong models (Qwen 30B+, best tokenizer coverage) are bench-box
  only (~35 tok/s on Strix Halo); remote DeepSeek is the only measured fast option
  and costs the privacy trade.

Parked for the same reason: the variant converts one blocker into three
(unmeasured model quality, memory canonicity, prefill-dominated latency plus a
pre-generation translation leg). Revisit conditions (a) and (c) above still stand;
additionally (d) a measured local model clearing chat-quality generation in a
shipped non-English language, or (e) a decision to abandon English-canonical
chat memory, would each reopen it independently.

**Update 2026-08-08 (later same day) — hardware correction + design layer.**
The addendum's latency argument cited the SETUP_GUIDE 4-vCPU figures — that is
the **old `vps` deployment target**. The deployment target is now the **Strix
Halo (BOSGAME) box** (Vulkan decided backend), where measured generation is
49.5 tok/s (Vulkan head-to-head) / 35.4 tok/s (Qwen3.6-35B-A3B local) / 63.2
tok/s (remote DeepSeek incl. network) — **the speed veto no longer applies on
this box** (it still holds for `vps`-profile deployments, where the flag would
simply stay off). Also corrected: supported languages are **en, cs, es, ru,
tr** (M1-716/718/719/720), and the remembered DeepL quality verification WAS
real but scoped to **fixture** validation for the translator legs (the
programme's fixture-verification record, 2026-08-02: 96 lines read, 3
corrections) — chat generation quality in the supported languages remains
UNMEASURED. The design layer now lives in
[`docs/design/future/live-text-streaming.md`](../design/future/live-text-streaming.md)
— upstream status, the existing live-edit substrate, both variants, the
hardware correction, the evidence inventory, and **the required chat-quality
measurement per supported language, which is the first gate to run before any
revisit**. Verdict unchanged: `parked`, pending that measurement.

---

## J. Declined (out of scope)

> Items considered and explicitly declined — recorded so they are not
> re-derived. Unlike §A–§I these carry no revisit track; a new user request
> is the only thing that reopens them.

### J1. Public IPFS/IPNS publishing
**What:** publish a static, JS-free HTML page summarizing recent activity to
IPFS and update an IPNS name on the digest cadence, so a stable URL always
serves the latest snapshot — an uncensorable demo of what the bot does for
prospective users.

**Current state — declined before any implementation.** A v2 design note
existed (`public-ipfs-publishing.md`: `public` pseudo-user scope, RSS-only
allowlist, separate read-only submodule, operator-run Kubo node). It never
left design.

**Verdict: `declined`** (user decision, 2026-08-08 — out of scope for this
project). The idea is a publishing/demo surface, not a messaging or
aggregator feature: it would add a third service, an IPFS node the operator
must run, and immutable-content liability, against zero user demand. The
design note is kept in stripped form with a decline banner
(`docs/design/future/public-ipfs-publishing.md`); the full design detail is
in git history.
