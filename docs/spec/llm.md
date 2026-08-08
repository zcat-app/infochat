# LLM and embeddings

This file describes how infochat integrates with language and embedding                                                                                                                                                                               
models: the SPI shape, per-task routing, the determinism boundary, and the                                                                                                                                                                            
translation layer. Concrete class names, package layout, prompt templates,                                                                                                                                                                            
property keys, and per-profile model choices live in                             
`docs/design/05-llm-and-embeddings.md`.

## Goals

1. **Local-first by default.** Ollama / llama.cpp out of the box; remote
   providers (OpenAI-compatible, Anthropic) are opt-in via config.
2. **Per-task routing.** The security judge, tagger, entity extractor,                                                                                                                                                                                
   summarizer, chat agent, embedder, and translator can each be a                                                                                                                                                                                     
   different model behind a different provider.
3. **Profile-driven defaults.** Picking a hardware profile (decision D27)                                                                                                                                                                             
   gives a working configuration without hand-tuning. Operators override         
   only what they need.
4. **Determinism boundary.** Retrieval is always SQL. The LLM only               
   generates prose or extracts structured fields at ingest. The same                                                                                                                                                                                  
   command returns the same set of posts twice in a row (architecture                                                                                                                                                                                 
   principle 1, decision D19).
5. **Prompt-injection-aware shapes.** Every untrusted input is wrapped in                                                                                                                                                                             
   a delimited block (see `security.md`); system prompts forbid in-band                                                                                                                                                                               
   commands.

## SPI shape

The LLM adapter exposes pluggable interfaces (decision D32):

- **`LlmProvider`** — chat completion + structured-output classification.
- **`EmbeddingProvider`** — text → vector batch.
- **`TranslationProvider`** — text + (from, to) → text. **Placement:**
  unlike the other interfaces in this list, this SPI is owned by the
  messaging adapter, not the LLM adapter. Translation of bot-authored
  prose is a presentation-layer concern (decision D29) and the contract is
  model-agnostic — an implementation need not call an LLM at all. The
  LLM-backed implementation is one plug among possible others and
  dispatches to the `TRANSLATOR` task internally; the two are
  different surfaces. The SPI stays specified in this file because
  §Translation flow and the `TRANSLATOR` routing are part of this
  file's surface.
- **`ModelTask`** enum — `SECURITY_JUDGE`, `TAGGER`, `ENTITY`,
  `CLASSIFIER`, `SUMMARIZER`, `CHAT_AGENT`, `TRANSLATOR`. **Scope of the enum:**
  `ModelTask` enumerates `LlmProvider` tasks **only**. The embedder
  is **not** a `ModelTask` — `EmbeddingProvider` is a distinct SPI
  with its own provider selection (operators configure the
  embedding provider via a dedicated property surface; the
  property keys live in design notes). This keeps the router
  signature `(ModelTask, scope_language) → LlmProvider` and
  prevents the conceptually-different embedding lifecycle (model
  identity guard, dimensionality invariants, batch shape) from
  being routed through the same `LlmProvider` machinery as chat
  and classification calls.
- **Router** — resolves `(ModelTask, scope_language)` to a concrete
  `LlmProvider`. Embedding-provider selection runs through a
  separate, simpler resolution path (one provider per deployment;
  no per-task or per-language routing — `EmbeddingProvider` has no
  `ModelTask` axis).
- **Call context** — trace id, scope id, task, language; carried through         
  every call for observability. The same call context wraps both
  `LlmProvider` and `EmbeddingProvider` calls so traces stitch
  across the embedding boundary.

The router lets operators configure each `LlmProvider` task
independently:

- Security judge — small, fast, local model. Optimized for high
  recall on injection-shaped content and for throughput.
- Tagger — produces a list of zero-or-more controlled-vocabulary tags
  (Tier 1) that conform to the supplied vocabulary set; output is
  validated against the vocabulary before use.
- Entity extractor — produces structured output (named entities) that
  conforms to a fixed JSON schema; schema-violating output is treated
  as unparseable per `security.md` §Failure handling.
- Summarizer / chat agent — produces plain-text prose; per-task
  routing lets operators point this at the model they have chosen
  for production-quality output (local or remote).
- Translator — produces plain-text prose in the requested target
  language; defaults to the chat model with a translation prompt.
  A dedicated provider may be plugged in.

`EmbeddingProvider` is configured separately (see §Embedding
pipeline below). It produces a fixed-dimensionality vector per
input; a single embedding model per deployment (changing it
invalidates existing vectors).

Concrete property keys, default models per profile, and the routing                                                                                                                                                                                   
algorithm live in `docs/design/05-llm-and-embeddings.md`.

## Why a thin SPI on top of LangChain4j

LangChain4j gives multi-provider chat/embedding interfaces. We add:

1. Per-task qualifiers, so the chat agent and the security judge are                                                                                                                                                                                  
   *different injection points*, not the same global model.
2. Hot-swap by config — switching from Ollama to Anthropic is a property                                                                                                                                                                              
   change.
3. Cache-friendly call shapes — system prefix is stable; untrusted content
   sits at the end of the prompt; supports prompt caching where the                                                                                                                                                                                   
   provider does.
4. Per-call observability via the call context.
5. Bounded concurrency per provider (a worker semaphore).
6. Failure → throttled admin notification rather than a crashed pipeline.

The SPI is small (a handful of interfaces) by design. We are not
re-implementing LangChain4j; we wrap it just enough to match the system's                                                                                                                                                                             
needs.

## Prompt-injection-aware prompt shape

Every prompt that includes user-derived text follows the wrapper                                                                                                                                                                                      
convention from `security.md`:

- A per-call random delimiter wraps untrusted blocks.
- The system prompt instructs the model to treat the block as data, not
  commands; to refuse action requests with a **structured refusal
  marker** (the literal token used in v1 lives in design notes — keeping
  the literal out of the spec means changing it does not require a
  spec amendment); to never act on URL requests, message-send requests,
  or admin verbs.
- The Stage 1 redaction step strips literal `<<<UNTRUSTED>>>` markers                                                                                                                                                                                 
  upstream so an attacker can't hard-code one inside the body.

Concrete templates per task are in design notes.

## Per-task routing rules

The router is told the task and the scope's language. It picks a provider                                                                                                                                                                             
based on (in priority order):

1. An explicit per-task override property.
2. Whether the chosen task supports the requested target language                                                                                                                                                                                     
   natively (the summarizer and translator may use a single LLM call when                                                                                                                                                                             
   the model can produce the target language directly).
3. The profile default for that task.

**One LLM service by default (D56).** The provider-routing chain above
selects *which wire dialect* serves a task; the *endpoint and
credential* resolve separately, on a shared-default-with-override
model: a task's `base-url` uses the per-task property when set, else
the deployment-wide `infochat.llm.default.base-url`; its `api-key`
uses the per-task property when set, else — ONLY when the base-url
also resolved from the shared default — `infochat.llm.default.api-key`.
The coupling is a security property: **the default credential travels
only to the default endpoint.** A task whose base-url is pinned
per-task never inherits the shared key implicitly (the pinned endpoint
is a party the key was not minted for); a pinned route that needs a
credential states it explicitly. In practice one deployment runs one
LLM service, so the endpoint is stated once and every task — including
any task added in a future release — inherits it; a per-task override
still wins when present. **A task with no effective base-url (neither
key set) refuses startup** with an error naming both settable keys —
never a silent per-call failure. No per-task endpoint defaults are
baked into the application: the bare-metal profiles ship a
profile-scoped shared default pointing at the on-host runtime, and the
`remote-llm` profile deliberately ships none, so an operator config
that predates a newly added task fails boot loudly instead of
inheriting a loopback address the host may not serve. Model choice
stays per-task (task tuning), not endpoint-inherited.

**Local-only is the most-restrictive posture.** When the operator
sets the explicit local-only property, the router never picks a
remote provider — and a per-task override pointing to a remote
provider while local-only is set is **a configuration conflict
that fails startup with a fatal log line identifying the
offending task and provider**. This is checked once at startup,
not per call, so an operator cannot accidentally route one task
remote while believing the deployment is local-only. The
local-only posture is a privacy and data-leakage commitment
(post bodies must not leave the host); silently letting a per-task
override bypass it would defeat the commitment without operator
notice.

Switching the embedding provider to a remote service
emits an explicit confirmation log line on startup so operators see when                                                                                                                                                                              
post bodies start leaving the host.

The local-only conflict check and the remote-embedding confirmation
log run on **both** services' startups — Collector and Provider. This
is intentional, not incidental: each service routes live LLM calls
(the Stage 2 security judge, tagging, entity extraction, classification,
and embedding generation run in the Collector's ingest pipeline; the
chat, summarizer, and translator call sites run in the Provider), and both services load
the same LLM adapter, so each validates the configuration it boots
with. The guard's scan covers the per-task base-urls **as effectively
resolved** (the per-task key, else the shared default — the same
resolution the providers perform, so an off-host shared default is an
offender for every task that inherits it, reported against the default
key), the embedding base-url, per-task provider overrides (and the
configured default provider) that name a cloud-only provider, and
cloud-only providers made reachable for non-English scopes via a
per-provider language capability key. An **advisory** scan additionally
warns when a task carries a per-task `api-key` but no per-task
`base-url` — that credential rides the shared default endpoint (a party
it may not be minted for), the mirror of the credential-coupling rule;
it is a WARN, not a boot failure, because the same shape is the
legitimate "separate credential for the default endpoint" config.

**No fallback chain in v1.** The router resolves `(ModelTask,
scope_language)` to **exactly one** `LlmProvider`; an unreachable
provider degrades that task to its task-specific failure path
(`security.md` §Failure handling — Provider-side LLM failures and
ingest-side per-stage rules) and does NOT silently switch to a
different configured provider. Operators who require HA on a
per-task provider must over-provision that provider directly.
Adding a fallback chain is a v2 candidate.

## Embedding pipeline

- One embedding per post (title + summary, by convention).
- **Batch SPI.** `EmbeddingProvider` is shaped around batch input
  (`List<String> → List<Vector>`) so the pipeline can amortize
  per-call overhead across multiple posts. The Collector's eval
  pipeline batches by a profile-driven batch size (value in design
  notes) when a batch's worth of embedding-ready posts is queued or
  a flush timer fires. Single-post calls remain valid (a batch of
  one) so the SPI does not force batching on callers that don't
  need it.
- **One-failure-fails-batch retry.** If the provider returns a batch
  result of the wrong shape, an exception, or any per-element error
  the Collector cannot map back to a specific post, the **entire
  batch** retries once. If retry also fails, every post in the batch
  follows the embedding-failure release path (release without a
  vector — see below). This is intentional: silently dropping some
  posts from a batch result without a clean per-post error mapping
  is a worse failure mode than a uniform retry.
- Embedding is the last release-blocking step before `READY`. On failure
  (after one retry), the post is released without a vector and is
  excluded from semantic linking. The post is otherwise normal.
- The embedding model is chosen per profile and **must not change** for                                                                                                                                                                               
  an existing deployment without a re-embed plan, because vectors from                                                                                                                                                                                
  different models are not comparable.
- **Model identity guard.** The active embedding model's identifier and
  vector dimensionality are stored in a singleton metadata row on first
  use. On every startup the `EmbeddingProvider` reports its current
  identifier and dimensionality; if either differs from the stored row
  **and embeddings already exist**, startup is refused with a descriptive
  error referencing the re-embed procedure. With **no embeddings stored
  yet** there is nothing incompatible to protect, so on a mismatch the
  guard instead **adopts** the configured identity — it records it in the
  singleton row and starts, no re-embed required. This is what makes
  "stored … on first use" true for a backend whose configured identifier
  differs from the seeded default (e.g. a llama.cpp deployment whose GGUF
  filename is its model identity). An explicit operator override flag
  bypasses the with-embeddings refusal for intentional migration runs; its
  property key and semantics are in design notes.
- **Dimensionality mismatch at runtime is fatal.** Storing vectors of mixed
  dimensions in the same pgvector column silently corrupts cosine similarity
  scores. The only safe recovery is a full re-embed.
- The pgvector index type is a **post-v1** profile-driven choice
  (decision D27). The intended shape is HNSW for the laptop / vps /
  remote profiles and IVFFlat for Pi (cheaper build, acceptable recall
  at the small live-set size). **v1 ships HNSW unconditionally on every
  profile** — the migrations create it with no profile branch and no
  index-type property exists. `docs/design/01-architecture.md` §1.7
  records the same deferral (amended 2026-07-27 from a shipped-tense
  claim — the requirement is deferred, not retired).
- **Second embedded corpus — command intents** (decision D66).
  The `doc_embedding` table ships a second embedded corpus alongside
  `post_embedding`: one row per catalogue command, ~41 documents of a
  sentence or two, written by the Provider-side `CommandIntentIndexBuilder`
  at every startup. The table is the structural opposite of
  `post_embedding` on every load-bearing axis: NOT partitioned (an intent
  document has no TTL — it is correct until the command itself changes),
  Provider-owned (the builder is the sole writer; the Collector holds
  nothing on it), and grant-opened to provider INSERT + DELETE so the
  DELETE-then-INSERT upsert can run. The `embedding_metadata` singleton
  identity guard covers BOTH corpora — one model, one dimension
  app-wide — so the model/dimension identity assumption holds uniformly.
  The corpus is read by the chat-side `helpLookup` tool, which embeds
  the model-supplied free-text query and probes for the nearest
  command-intent document. The match is decided entirely by SQL (D19:
  the LLM never picks the match), and the returned description is
  composed at call time from the runtime catalogue (match-not-assert —
  a stale intent document can degrade a match but can never produce
  wrong syntax). A content-hash skip on warm restart means steady-state
  startup cost is one SELECT (no embedding calls); a change to the
  source text or to the active embedding model forces a re-embed of the
  affected rows, so a stale vector can never outlive its source text.
  The chat delivery path is governed by the §LLM output sanitizer
  amendment (see commands.md §Chat mode, decision D67); this section is
  deliberately silent on it.

## Translation flow

- The default scope language is English (decision D29).
- A scope can opt in via `/lang <code>` (`scope_preferences.language`).
- For each user-visible reply, if the scope language is `'en'` the raw                                                                                                                                                                                
  text is sent unchanged. Otherwise it goes through `TranslationProvider`.
- For models that can natively generate the target language, the                                                                                                                                                                                      
  summarizer is invoked with `target_language` directly to save a round                                                                                                                                                                               
  trip. The summarizer exposes a "language-aware" capability so the                                                                                                                                                                                   
  router knows when this shortcut is safe.
- **Source post bodies are never rewritten (decision D29).** A non-English
  post is translated to English once at ingest into a derived field; the
  original body is retained unmodified in storage. Embeddings and
  retrieval — both the semantic and the lexical arm — operate
  on the English field, so there is one vector space and one FTS
  configuration rather than per-language variants. Entity extraction
  likewise reads the English field, so the controlled vocabulary does not
  fork by source language.
- **The English field is a display artifact too (decision D29, amended
  2026-08-04).** "Never rewritten" is a guarantee about the stored row,
  not about the render. A headline whose source language differs from the
  reader's is displayed in the reader's language: from the English field
  directly when the reader is English (a column read — no translator
  call), otherwise by translating that field into the reader's language.
  The display translator's source is therefore always English, so only
  one direction per reader language is ever exercised rather than one per
  (source, reader) pair. The original headline remains visible on a
  bracketed line beneath it; an unbracketed line always means the text is
  already in the reader's language. When the English field is NULL on a
  non-English source — the ingest translator exhausted its attempts — the
  bracketed original takes the primary slot and the repair is a
  collector-side re-drive, not a display-time retry, which would
  reintroduce a translator call on the scheduled digest path.
  **What backs the bracket (D29 (c), amended 2026-08-05).** It is a
  rendering rule, not a language proof: languages are declared, never
  inferred, so "unbracketed" means the line came from a channel contracted
  to produce the reader's language and passed the mechanical checks
  §Failure handling names below. Two enforcement points, both mechanical:
  the ingest leg refuses to STORE an anchor byte-identical to the input it
  was handed, and the render refuses to PROMOTE an anchor that still
  carries every one of the publisher's words in the publisher's order,
  degrading to the bracketed shape instead. The render check is the
  load-bearing one — being evaluated on the final rendered strings it
  covers every reduction the render applies, rather than a list of them —
  and it is applied at BOTH translation hops by one shared predicate: a
  non-English reader is translated twice (source to English at ingest,
  English to their own language at display), so the display translator's
  reply is held to the same test as the anchor, and a reply still carrying
  every word of its input in order is treated as no translation.
  It is bounded, and the bound is stated: it catches a derivation built by
  ADDING to the original — padding between words or at either end, visible
  or not — for a headline that fits inside the display cut. It does not
  catch one built by CHANGING it (a character inserted inside a word, a
  reworded line, a fluent mistranslation, third-language output), nor — on
  the ANCHOR hop, whose operands are the rendered lines — ANY insertion at
  or before the cut on a headline longer than it, a leading pad being only
  the most obvious form: material added ahead of the cut shifts it and so
  alters the tail rather than extending it. Only an addition past the cut
  is still caught there, because truncation discards it. Both render
  unbracketed. Those residuals follow
  from refusing language inference and are stated, not closed; D29 (c)
  records why the second one is not worth a second evaluation pass.
- **Deterministic strings come from a localization bundle, not the
  translator (decision D43).** Anything the bot says that does not
  depend on user content — `/help` output, friendly-error
  templates, the banned-user fixed reply, progress-notifier stage
  strings, the "source already existed, tags updated" line, etc. —
  is looked up by key in a localization bundle. v1 ships **`en`,
  `cs` (Czech), `es` (Spanish), `ru` (Russian) and `tr` (Turkish)
  bundles**. A loaded
  bundle does not by itself make a
  language selectable: `/lang` accepts only codes from the declared
  enabled set (`LanguageRegistry`), so enabling a language is a
  deliberate, reviewed change gated on measured quality — never a
  side effect of dropping in a bundle. The `TranslationProvider` is
  reserved for LLM-authored
  prose (cluster summaries, chat replies, digest headers) where a
  localization key is not a fit. Mixing the two paths — running
  `/help` text through a model — is explicitly out of v1: it would
  introduce non-determinism into deterministic output and is a
  sanitizer-bypass risk.
- Translated outputs are cached by `(hash(text), target_language)` for a
  short window so a digest sent to ten group members is not translated
  ten times.
- Command parsing is English-only in v1.

### Pipeline order (delivery direction)

This pipeline applies **only** to LLM-authored output (cluster
summaries, chat-agent replies, digest prose, `/retry` re-rolls).
Deterministic localization-bundle strings (decision D43 — `/help`,
friendly errors, banned-user reply, progress-notifier stages, etc.)
are emitted **directly to the adapter** with no LLM call, no
sanitizer pass, no `TranslationProvider` invocation, and no
translation-cache interaction. Bundle strings are looked up by key
in the scope's language bundle; if the key is missing in the
scope's language, lookup falls back to `en` (a missing `en` key is
a startup error, decision D43). The two paths never mix.

For LLM-authored output, the order from generation to delivery is:

1. LLM prose (summarizer, chat agent, digest writer).
2. **LLM output sanitizer** (`security.md` §LLM output sanitizer) —
   strips admin command strings.
3. `TranslationProvider` — skipped if the scope language is English.
4. **LLM output sanitizer (re-run on translated text)** — the
   translator is itself an LLM and can introduce admin-command-shaped
   strings, so the sanitizer runs again on the translated output.
   Double-sanitization is intentional, not duplicated work.
5. **Translation cache write** (key:
   `(hash(post-sanitizer-1 English text), target_language)`, value:
   post-sanitizer-2 translated text). The cache key is derived from
   the **post-sanitizer-1** English text — the form produced by
   step 2, with admin-command strings already stripped — so two
   callers whose pre-sanitizer LLM outputs differ trivially (e.g.
   one carried an admin-verb fragment that the sanitizer stripped,
   the other did not) collide on the same key after sanitization.
   Keying on pre-sanitizer text would let two semantically-equal
   English strings miss the cache, multiplying translator load
   without benefit. The cache stores the already-sanitized
   translated form so cache hits skip step 4 too.
6. Adapter delivery.

Cache lookups occur between step 3 and step 4 — a hit short-circuits
both the translator call and the second sanitizer pass.

## Determinism boundary

Stated explicitly because everything else depends on it:

- Retrieval (which posts come back, in what order, with what filters) is                                                                                                                                                                              
  always SQL.
- The LLM is allowed to: write prose summaries, classify (Tier 1 tags),                                                                                                                                                                               
  extract named entities, judge security, embed text, translate text.
- The LLM is **not** allowed to: decide who can do what, mutate state,                                                                                                                                                                                
  pick the set of posts a query returns, run arbitrary SQL, fetch URLs,                                                                                                                                                                               
  send messages outside the current reply.

**Temporal scope of "same set."** Determinism is *same DB state → same
results*, not absolute determinism over wall-clock time. Between two
invocations of `/summary security -w 24h` new posts may have been ingested
or aged out, so the second call legitimately returns a different set; what
the spec guarantees is that **given the same DB state**, the SQL returns
the same rows in the same order — the LLM is not in that loop.

**`/retry` does not re-query.** `/retry` (decision D36) reuses the
deterministic post selection and clustering captured by the original
summary-producing command; it does not re-execute the SQL against current
DB state. This means `/retry` is also stable against ingest racing with the
user: the cluster of posts the user is regenerating prose for is the same
cluster they originally saw, even if a new post would now alter the
selection. The only thing `/retry` re-rolls is the prose layer.

`/summary security -w 24h` returns the same set of posts twice in a row
**within the same DB state** because the SQL doesn't depend on the LLM.
The prose around them differs; the *set* doesn't.

## Memory retrieval

The chat agent's memory access is hybrid (decision D28):

- **Pre-fetch.** Cheap deterministic keyword match on `chat_memory` for          
  the calling (user, scope) — always runs, results are folded into the                                                                                                                                                                                
  agent prompt before the LLM call.
- **Recall tool.** A scope-filtered, read-only `recallMemory(keywords)`                                                                                                                                                                               
  tool the agent can invoke for deeper digs.

The recall tool is part of the strict tool allowlist (`security.md`); it         
never crosses scope boundaries.

## Failure handling (recap)

Per-task failure rules from `security.md` and decision D22:

**Schema-violating output** (wrong JSON shape, unexpected label, missing
required field) is treated identically to an unparseable reply at every
stage.

- Security Stage 2 — verdict vs. infra split (see `security.md`).
- Tagger — bootstrap tags fallback; schema-violating output is treated as
  unparseable (retry once, then fall back). **Partial-valid handling.**
  When the LLM emits a list of tags and only some entries pass the
  controlled-vocabulary validation (post-normalization per
  `commands.md` §Surface conventions: NFC + lower-case + character
  class), the **valid tags are kept** and the invalid tags are
  silently dropped — losing useful information because of one bad
  entry would degrade tagging quality across deployments where the
  smaller models occasionally emit one out-of-vocab tag in an
  otherwise-clean list. **An empty proposal is an outcome, not a
  failure.** A reply that parses cleanly and proposes no tags at
  all — the empty list the tagger prompt explicitly asks for when
  nothing in the vocabulary fits — is a legitimate result of the
  stage per the zero-or-more contract in §SPI shape: the post is
  stored with no tags, is not retried, and raises no admin
  notification. The bootstrap-tags fallback fires only when zero
  valid tags survive a **non-empty** proposal (or when the reply
  is unparseable / schema-violating per the rule above). The two
  zero-tag cases are told apart by the invalid-tag count below,
  which is zero exactly when the model proposed nothing. A `tags`
  array whose entries are not strings is not a proposal at all —
  it is schema-violating per the rule above and never reaches the
  count. An
  untagged post is excluded from every tag-keyed retrieval branch
  and renders in the digest's Other section; it does not leave the
  corpus. A
  per-post counter records "tagger emitted N valid + M invalid"
  for observability; sustained high invalid rates surface an
  operator alert (cadence and threshold in design notes). The
  invalid-rate counter cannot see a wholly non-functioning tagger —
  an all-empty output reports N=0 valid AND M=0 invalid on every
  post — so a separate aggregate counter tracks the no-tags share of
  the tagger's recent completions: when that share exceeds a
  configured threshold over a minimum sample, a throttled admin
  alert fires under the distinct error class
  `tagger.sustained_no_tags` (never `tagger.fallback_to_bootstrap` —
  the two conditions have different meanings and different operator
  runbooks). Below the minimum sample the window is silent even at
  100% no-tags, so cold start cannot false-alarm; a normal trickle
  of untaggable posts stays far below the threshold and fires
  nothing (window size, minimum sample, and threshold in design
  notes).
- Entity extractor — on failure or schema-violating output, release without
  entities; cross-source linking degrades to embedding-only for that post.
- Embedding — release without a vector (see Embedding pipeline above);
  the post is otherwise fully visible. **Retry policy**: on a batch
  failure the same batch is resubmitted as-is; the batch is **not
  split** on retry. If batch size correlates with failures, operators
  reduce the profile-driven batch size — the spec does not introduce
  a per-retry split path.
- **Compression (manual `/compress` or auto-compress).** LLM
  unreachable, timeout, or schema-violating reply after retry → the
  chat session is **held at the ceiling**: the user's next chat-mode
  message returns a localized friendly error
  ("memory checkpoint pending; please `/compress` manually or try
  again later"), and the session is never silently truncated.
  Manual `/compress` failure surfaces the same error and leaves the
  session unchanged. The escape hatch is `/clear` (which discards
  the live window — the user's choice, not the system's). The
  auto-compress trigger fires when the chat session occupies a
  profile-driven percentage of the context-window ceiling, leaving
  headroom for the compress prompt and reply itself; the exact
  percentage lives in design notes. (See also `security.md`
  §Failure handling — same wording.)
- Translation — sanity-check the output. The check fails when **(a)**
  the provider returns an HTTP error, **(b)** the output is
  byte-identical to an input the caller DECLARED to be in the
  translator's source language (English), **(c)** the output is empty or
  whitespace-only, or **(d)** for non-Latin target scripts the
  output contains zero target-script characters; for Latin target
  scripts, where no script test can separate source from target, the
  output still carries every one of a declared-English input's words in
  the input's order — the D29 (c) echo test, one shared predicate across
  both translation hops. The exact
  threshold for (d) lives in design notes. On a sanity-check
  failure the system falls back to English with a one-line
  note. The fallback note itself is a localization-bundle string
  (D43), not hardcoded English. The user must never see a hung or
  garbled response because translation flaked.
  The declared-English qualifier on (b) and (d) is what keeps the note
  honest: it asserts the delivered text is English, so it may fire only
  over text the caller declared English. Text already in the reader's
  language, returned unchanged, is the CORRECT translation and is not a
  failure; a third language echoed back is a failure the note cannot
  describe, so it degrades to silence rather than to a false claim.
- Chat reply emptied by the output sanitizer. A chat reply that the
  output sanitizer reduces to empty — and that no deterministic help
  block rescued — degrades like a chat-agent failure: the localized
  friendly error replaces the reply, the turn is discarded (no session
  advance, no memory write), and the chat placeholder finalizes with
  that string, never blank and never the bare retrieval-provenance
  notice. A reply that carries a deterministic help block is not
  empty: it is delivered as composed.

Admin notifications are throttled per error class.

## Hardware profile contract

The profile name (decision D27) is the spec-level commitment. The values         
behind each profile (context window, default chat / embedding model,                                                                                                                                                                                  
worker concurrency, vector index type, eval queue depth, summary worker                                                                                                                                                                               
count) live in design notes. The intent: a fresh operator picks one                                                                                                                                                                                   
profile and gets a working system; a tuning operator overrides one                                                                                                                                                                                    
property.

## Bounded concurrency and observability

- Per-provider concurrency is bounded so a slow provider applies                                                                                                                                                                                      
  back-pressure to the eval queue rather than exhausting threads.
- Every LLM call emits per-task latency and token-count metrics labeled                                                                                                                                                                               
  by task and provider.
- Metric labels are never wire-derived. The `model` label carries the
  operator-configured model id for the task, never the model string a
  provider's response reports. A metric registry retains one meter per
  distinct label value for the process lifetime, so an endpoint-chosen
  label value would be an unbounded memory-amplification channel for a
  hostile or compromised endpoint.
- Recorded token counts are untrusted input too — the provider reports
  them, the system does not measure them — and are checked at that same
  single boundary before any counter moves. A report is impossible, and
  so discarded, when a count is negative, when the output count exceeds
  the generation cap the request carried, or when the input count
  exceeds a ceiling derived from the size of the prompt that was sent
  plus a small fixed allowance for the provider's own chat-template
  overhead. The cap is the effective one, including the default applied
  when no per-task `max-tokens` is configured — an absent key does not
  mean uncapped, because the request carries the default either way.
  The input ceiling is deliberately above any real tokenization of that
  prompt, so it never discards an honest reply; it rejects magnitudes
  no tokenization of it could produce.
- A discarded report is dropped whole rather than clamped, and the call
  counts as reporting no usage — the state a provider that reports
  nothing already produces. A lying endpoint therefore leaves a visible
  gap between the call counter and the token counters instead of a
  plausible figure. No counter ever moves backwards (a decrement reads
  downstream as a counter reset, silently mis-reporting every rate over
  the series) and none can be inflated to a magnitude that swamps later
  honest increments for the lifetime of the process.
- The checks are one-sided: they reject the impossible, not the merely
  wrong. A reply that understates its usage, or overstates it anywhere
  below those ceilings — including inside the template-overhead
  allowance on the input side, which is bounded but not zero — is
  indistinguishable from an honest one and is recorded as reported. Any
  future consumer that turns these counters into a decision input must
  weigh that residual rather than assume the recorded figure is true.
- Trace ids tie a chat-agent reply back to the tool calls and the eval                                                                                                                                                                                
  artifacts it consulted.

Exact metric names and labels live in design notes.

## What lives in design notes

- The full SPI Java surface (class and method names)
- Property keys for routing and per-task overrides
- Default model strings per profile
- Prompt templates for each task
- Per-profile context-window sizes and auto-compress thresholds
- Vector index build parameters
- Translation cache TTL and key shape
- Embedding batch size per profile (the batch-shaped SPI is spec; the
  exact size is design)
- Embedding model identity row shape, override flag property key, and re-embed procedure
- Localization-bundle structure and key naming (the
  `en`/`cs`/`es`/`ru`/`tr` commitment is spec; the exact bundle file
  format and keys are
  design — see also `messaging.md`)
- Metric names, label sets, and dashboards