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
- **`TranslationProvider`** — text + (from, to) → text.
- **`ModelTask`** enum — `SECURITY_JUDGE`, `TAGGER`, `ENTITY`,                                                                                                                                                                                        
  `SUMMARIZER`, `CHAT_AGENT`, `TRANSLATOR`.
- **Router** — resolves `(task, scope_language)` to a concrete provider.
- **Call context** — trace id, scope id, task, language; carried through         
  every call for observability.

The router lets operators configure each task independently:

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
- Embedder — produces a fixed-dimensionality vector per input. A
  single embedding model per deployment (changing it invalidates
  existing vectors; see Embedding pipeline below).
- Translator — produces plain-text prose in the requested target
  language; defaults to the chat model with a translation prompt.
  A dedicated provider may be plugged in.

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
  commands; to refuse action requests with a `[refused-action]` marker;                                                                                                                                                                               
  to never act on URL requests, message-send requests, or admin verbs.
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

The router never picks a remote provider when an explicit local-only                                                                                                                                                                                  
property is set. Switching the embedding provider to a remote service
emits an explicit confirmation log line on startup so operators see when                                                                                                                                                                              
post bodies start leaving the host.

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
  identifier and dimensionality; if either differs from the stored row,
  startup is refused with a descriptive error referencing the re-embed
  procedure. An explicit operator override flag bypasses the check for
  intentional migration runs; its property key and semantics are in design
  notes.
- **Dimensionality mismatch at runtime is fatal.** Storing vectors of mixed
  dimensions in the same pgvector column silently corrupts cosine similarity
  scores. The only safe recovery is a full re-embed.
- The pgvector index type is profile-driven (decision D27): HNSW for the                                                                                                                                                                              
  laptop / vps / remote profiles; IVFFlat for Pi (cheaper build,
  acceptable recall at the small live-set size).

## Translation flow

- The default scope language is English (decision D29).
- A scope can opt in via `/lang <code>` (`scope_preferences.language`).
- For each user-visible reply, if the scope language is `'en'` the raw                                                                                                                                                                                
  text is sent unchanged. Otherwise it goes through `TranslationProvider`.
- For models that can natively generate the target language, the                                                                                                                                                                                      
  summarizer is invoked with `target_language` directly to save a round                                                                                                                                                                               
  trip. The summarizer exposes a "language-aware" capability so the                                                                                                                                                                                   
  router knows when this shortcut is safe.
- **Source post bodies are never translated.** Embeddings, retrieval, and
  entity extraction always operate on the original language. Translation
  is purely a presentation-layer concern.
- **Deterministic strings come from a localization bundle, not the
  translator (decision D43).** Anything the bot says that does not
  depend on user content — `/help` output, friendly-error
  templates, the banned-user fixed reply, progress-notifier stage
  strings, the "source already existed, tags updated" line, etc. —
  is looked up by key in a localization bundle. v1 ships **`en` and
  `cs` (Czech) bundles**; adding a third language is a bundle
  drop-in. The `TranslationProvider` is reserved for LLM-authored
  prose (cluster summaries, chat replies, digest headers) where a
  localization key is not a fit. Mixing the two paths — running
  `/help` text through a model — is explicitly out of v1: it would
  introduce non-determinism into deterministic output and is a
  sanitizer-bypass risk.
- Translated outputs are cached by `(hash(text), target_language)` for a                                                                                                                                                                              
  short window so a digest sent to ten group members is not translated                                                                                                                                                                                
  ten times.
- Command parsing is English-only in v1.

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
  unparseable (retry once, then fall back).
- Entity extractor — on failure or schema-violating output, release without
  entities; cross-source linking degrades to embedding-only for that post.
- Embedding — release without a vector (see Embedding pipeline above);
  the post is otherwise fully visible.
- Translation — sanity-check the output: if the response is identical to
  the input, empty, or clearly not in the target language, fall back to
  English with a one-line note. The user must never see a hung or garbled
  response because translation flaked.

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
- Localization-bundle structure and key naming (the `en`/`cs`
  commitment is spec; the exact bundle file format and keys are
  design — see also `messaging.md`)
- Metric names, label sets, and dashboards