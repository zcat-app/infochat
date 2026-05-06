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

- Security judge — small, fast, local model. Goal: high recall on                                                                                                                                                                                     
  injection-shaped content; throughput.
- Tagger — competent at controlled-vocabulary classification.
- Entity extractor — competent at structured output.
- Summarizer / chat agent — the "good" model the operator paid for or                                                                                                                                                                                 
  chose to run locally.
- Embedder — a single embedding model per deployment (changing it                                                                                                                                                                                     
  invalidates existing vectors).
- Translator — the chat model with a translation prompt by default; a
  dedicated provider may be plugged in.

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
- Embedding is the last release-blocking step before `READY`. On failure                                                                                                                                                                              
  (after one retry), the post is released without a vector and is                                                                                                                                                                                     
  excluded from semantic linking. The post is otherwise normal.
- The embedding model is chosen per profile and **must not change** for                                                                                                                                                                               
  an existing deployment without a re-embed plan, because vectors from                                                                                                                                                                                
  different models are not comparable.
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

`/summary security -w 24h` returns the same set of posts twice in a row          
because the SQL doesn't depend on the LLM. The prose around them differs;                                                                                                                                                                             
the *set* doesn't.

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

- Security Stage 2 — verdict vs. infra split (see `security.md`).
- Tagger — bootstrap tags fallback.
- Entity / embedding — release without artifact.
- Translation — fall back to English with a one-line note (the user                                                                                                                                                                                   
  should never see a hung response because translation flaked).

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
- Metric names, label sets, and dashboards