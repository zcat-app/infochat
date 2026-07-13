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
> `realistic-now` · `v2-milestone` · `needs-analysis` · `parked`.

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
**What:** `/image [-r WxH] -d "description"` → generate an image via a backend and
return it, or return text explaining why it wasn't possible.

**Current state — not carriable today** (outbound has no attachment field).

**Prerequisites / tensions:** needs the outbound half of the media SPI + an
image-gen backend. **Privacy/cost tension:** local generation (e.g. Stable
Diffusion) is heavy on a CPU-only VPS (current profile); remote generation means
prompt data leaves the box, which cuts against the privacy-first posture. The
"return text why it failed" graceful-degradation instinct is correct and should
be the contract.

**Verdict: `v2-milestone`.**

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

---

## F. Parked

### F1. Reactions / feedback loop
**What:** 👍/👎 or "less like this" as a per-user ranking signal.

**Why parked:** the adapter SPI exposes **no reaction capability** (no reaction
flag on `CapabilityFlags`), so this would force clunky command-based hacks with
uneven cross-adapter support — over-engineering risk for marginal signal. Revisit
only if a media/reaction SPI extension (see §B) lands and exposes native
reactions. **Verdict: `parked`.**
