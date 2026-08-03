# Security model

This file defines the threats infochat defends against, the trust boundaries                                                                                                                                                                          
that make those defenses possible, and the invariants the rest of the system
must uphold. Concrete regex strings, exact prompt wrappers, retry counts,                                                                                                                                                                             
table column names, and Postgres role grants live in `docs/design/04-security.md`.

`security.md` is the source of truth for the *trust path*. When `architecture.md`
or `commands.md` mention authorization, ban handling, or quarantine, this file                                                                                                                                                                        
is the document that constrains them.

## Threat model

We assume:

- The **Provider** is exposed to the internet through every enabled
  messaging adapter (one Provider may run multiple adapters per
  `deployment.md` §Topology). Adversaries can send arbitrary text on
  any of them; the cross-adapter isolation invariant
  (`messaging.md` §Per-adapter trust level) prevents identity bleed
  between adapters.
- The **Collector** is exposed to arbitrary feed content. Every RSS publisher,   
  Reddit poster, Bluesky user, etc. is untrusted.
- The **DB** is internal — only the two services and the operator reach it.
- **LLMs** (local or remote) are black boxes that can be coaxed into emitting                                                                                                                                                                         
  attacker-chosen output. Local and remote LLMs have the same trust level.
- **Operator-set config** (properties files, bootstrap JSON) is trusted.

Out of scope for v1: side-channel attacks against the LLM host, supply-chain                                                                                                                                                                          
attacks on operator infrastructure, TLS/MITM (assumed handled by the adapter                                                                                                                                                                          
and HTTPS), Sybil resistance against an adapter that exposes no                                                                                                                                                                                       
fingerprinting hooks (see `docs/design/04-security.md` §4.12 for what this                                                                                                                                                                            
buys us and what it doesn't).

The threats we explicitly defend against are catalogued (T1–T9) in design                                                                                                                                                                             
notes. The spec-level commitments below cover all of them.

## Trust boundaries

1. **Adapter → Provider.** The adapter asserts identity via a stable,                                                                                                                                                                                 
   cryptographically anchored ID. Display names are informational and never                                                                                                                                                                           
   used for authorization (decision D10). For SimpleX the anchored ID
   is the **per-connection contact id** the transport assigns; a
   sender's advertised profile address (`contact.profile.contactLink`)
   is **self-asserted and not verified** (out of scope of the SMP
   protocol) and so falls in the same "informational, never used for
   authorization" category as display names — it never influences the
   resolved identity, including for the SimpleX admin claim-token path
   (decision D50, §Authorization model).
2. **Provider intake → command/chat router.** Identity resolution and the        
   ban check run *before* parsing. Banned users get one fixed reply and                                                                                                                                                                               
   never reach the parser, the chat agent, or any DB query past the ban          
   check (decision D11).
3. **Authorization → execution.** Permission checks run in deterministic                                                                                                                                                                              
   Java. The LLM is downstream of every authorization decision; it never
   participates in deciding who can do what (architecture principle 3).
4. **Collector ingest → user-visible store.** No post becomes user-visible                                                                                                                                                                            
   without passing the layered ingest checks (§ Ingest pipeline).
5. **LLM ↔ system state.** The LLM's tool surface is a fixed allowlist of                                                                                                                                                                             
   read-only, scope-filtered functions. There is no path from any LLM tool                                                                                                                                                                            
   to mutating authorization state, sources, subscriptions, or audit rows.
6. **Health/management HTTP surface → network.** The health endpoints are
   unauthenticated in v1 and disclose operational topology: which messaging
   adapters are enabled and up, and whether the DB is reachable. The
   shipped default binds them to loopback; exposing them beyond the host
   is an explicit operator action (widen the bind, firewall the port to
   the prober), never a default (`deployment.md` §Health and
   observability).
7. **SimpleX local transport → network.** The SimpleX adapter speaks an
   unauthenticated WebSocket bot API to a co-located `simplex-chat`
   subprocess it spawns; there is no session, cookie, or token, because
   the bot's identity lives in the subprocess data-dir, not in a
   presented credential. That "no authentication" property is sound only
   while the channel stays loopback: the shipped default binds the
   adapter's ws-port to loopback. Exposing it beyond the host — binding
   off-loopback or forwarding the port — is an explicit operator action,
   never a default, and voids the property: any host that reaches the
   port can then drive the bot's SimpleX identity with no credential.
8. **Local LLM backend → network.** When the operator runs a local
   generative or embeddings backend (Ollama, or the llama.cpp
   `llama-server` instances introduced by D49 — one per model, plus a
   second instance for the llama.cpp embeddings shape), those servers
   expose an unauthenticated OpenAI-compatible / inference HTTP API:
   there is no token, session, or credential, because the backend trusts
   anything that can reach its port. That "no authentication" property is
   sound only while the ports stay off the host network. The shipped
   compose default keeps them there — the llama.cpp generative and
   embeddings services publish no host port (reachable only as
   `llamacpp:8080` / the embeddings service over the compose network),
   and Ollama publishes to loopback only (`127.0.0.1:11434`). Exposing
   any of them beyond the host — adding a host port mapping, binding
   off-loopback, or forwarding the port — is an explicit operator action,
   never a default, and voids the property: any host that reaches the
   port can then run inference against, and read embeddings from, the
   deployment's models with no credential.
9. **LLM/embeddings provider response → system.** Everything a
   generative or embeddings endpoint returns is endpoint-chosen input,
   not a trusted internal value: whatever answers on the configured
   base-url picks every field of the reply, so a hostile or compromised
   endpoint is in scope here for the same reason a hostile feed is on
   the ingest side. Two properties bound what a reply can do. The
   response body is read under an operator-configurable cap (clamped to
   1–8 MiB) before parsing, so a pathological multi-GB reply cannot
   exhaust the JVM. Metric labels are never wire-derived — the `model`
   label carries the operator-configured model id for the task, never
   the model string the reply reports, so a distinct-per-call model
   string cannot mint unbounded retained meters (`llm.md` §Bounded
   concurrency and observability). Provider-reported **numeric usage**
   (token counts) is checked at that same boundary before it reaches
   the counters: a count is impossible, and the report discarded whole
   rather than clamped, when it is negative, when the output count
   exceeds the generation cap the request carried (the effective cap,
   default included — an absent per-task `max-tokens` does not mean
   uncapped), or when the input count exceeds a ceiling derived from
   the size of the prompt sent plus a small fixed allowance for the
   provider's own chat-template overhead. The call then counts as
   reporting no usage, so
   tampering shows up as a gap between the call counter and the token
   counters rather than as a plausible figure, and no counter can be
   driven backwards or inflated to a magnitude that swamps later
   honest increments for the process lifetime. The residual is the
   in-range lie: a reply reporting any count below those ceilings —
   including one inside the input side's bounded template-overhead
   allowance, and including an Anthropic reply choosing three
   cache/input fields whose 64-bit sum wraps, since the boundary sees
   only the wrapped total — is indistinguishable from an honest one. No v1 decision reads these
   counters, so its blast radius stays inside usage observability. A
   future cost-weighted rate cap would promote them to a decision
   input and has to weigh that residual before charging a bucket
   against them.

## Ingest pipeline (security side)

Every post goes through two stages before it can reach a user (decision                                                                                                                                                                               
D20):

- **Stage 1 — deterministic.** Runs on every post. HTML is sanitized
  against an allowlist; the body is Unicode-normalized (NFKC,
  bidi-control and zero-width stripping — applied **unconditionally
  to the entire body**, including any text the source happens to
  enclose in code-fence syntax: ingest content is upstream-untrusted
  and the regex set must operate on a normalized form, so the
  chat-intake fenced-code carve-out (below) does **not** apply on
  the ingest path); a prompt-injection regex
  set runs with bounded execution time. **Regex engine commitment
  (v1):** the Stage 1 implementation uses `java.util.regex` with a
  per-input wall-clock watchdog that aborts the match when the cap
  fires (the cap value is profile-driven and lives in design
  notes); a watchdog abort is a Stage 1 infrastructure failure
  (fail-closed, §Failure handling). `java.util.regex` is a
  backtracking engine, so catastrophic backtracking is **mitigated
  by the watchdog timeout, not prevented at the engine level** —
  the spec commits to this trade-off explicitly so an
  implementation choosing a true linear-time engine (RE2/J or
  similar) does so as a v2 amendment, not a silent design tweak.
  An RE2-style swap is a v2 candidate. Matches are recorded as
  quarantine spans and replaced in the body with a **structured
  placeholder committed at spec level**: the literal sequence
  `[REDACTED:<id>]`, where `<id>` is a per-row random opaque token
  (hex- or base32-encoded; the encoding choice and the token-byte
  length are profile-driven and live in design notes, but the
  surrounding `[REDACTED:` and `]` brackets are fixed). The
  brackets and `REDACTED:` literal are byte-identical across every
  implementation so user-facing prose, snapshot bodies, and tests
  recognise the marker by exact-match; the per-row `<id>`
  randomization is what stops attackers from pre-crafting a fake
  placeholder that would survive the Stage 1 `<<<UNTRUSTED>>>`
  marker strip (`llm.md` §Prompt-injection-aware prompt shape).
  Stage 1 *never* blocks release on its own — it scrubs and routes
  to review.
- **Stage 2 — LLM judge.** Only invoked when Stage 1 flagged something.                                                                                                                                                                               
  The judge sees the *original* (pre-redaction) content inside an
  untrusted-content wrapper and returns one of a fixed label set. See                                                                                                                                                                                 
  Failure handling below for the verdict-vs-infrastructure split.

The Provider's chat intake mirrors the Stage 1 Unicode steps (NFKC + bidi                                                                                                                                                                             
strip + zero-width strip) but **carves out fenced code blocks**: a user
typing a deliberately exotic code snippet should see it round-trip
unchanged. Fence recognition is the closed CommonMark rule —
a line beginning (after up to three leading spaces) with a run of
**three or more backticks** opens a fenced block; the block ends
at the next line whose leading run of backticks is at least as
long as the opener's run; if no closing fence is found the block
extends to end-of-input. The recognition pass runs **before**
Unicode normalization on a copy of the input; normalization is
applied to text outside fences only. Bytes inside fences are
preserved verbatim. The Provider does *not* run the Stage 1
regex set on chat input — chat-input safety relies on the
delimiter convention plus the LLM tool boundary.

**Stage 1 is a coarse filter, not a complete defense.** It exists to                                                                                                                                                                                  
(a) skip Stage 2 on the ~95%+ clean majority and (b) provide a degraded                                                                                                                                                                               
mode (Stage-1-redacted-but-released) when the judge can't run. Stage 2 is                                                                                                                                                                             
the actual security boundary.

## SSRF and outbound connections

Every outbound connection from the Collector (feeds, redirects, and
`StreamSource` connections) and from the Provider (`/add-source` URL
validation HEAD/GET probes per `commands.md` §Source management) runs
through a fail-closed allowlist (decision D20). Both services use the
**same shared library module** (`infochat-ssrf`) which carries the
IP blocklist, DNS-rebind defense, redirect cap, and timeout caps —
the architecture's "DB-only inter-service communication" rule is
about runtime data, not compile-time code sharing, so a Maven
sibling module both services depend on is the right shape. There
is no Provider→Collector RPC for SSRF checks.

- Allowed schemes: `http`, `https`, `ws`, `wss`. The IP-blocklist and
  DNS-rebind defenses are **transport-agnostic** — a `wss://` relay
  connection is gated by the same checks as an `https://` feed fetch
  (decision D38).
- DNS-resolved IPs are checked against a blocklist of private, loopback,
  link-local, multicast, CGNAT, and cloud-metadata ranges (notably
  `169.254.169.254` and IPv6 equivalents) plus the host's own non-loopback
  interfaces.
- DNS is re-resolved after every redirect (TOCTOU defense); the IP
  blocklist re-applies each hop. For long-lived `StreamSource`
  connections the IP check applies on every reconnect, and **any
  peer-IP change observed at the socket layer is a hard close** —
  the implementation does not transparently accept it as a connection
  migration. A reconnect must re-pass the full allowlist before any
  event is emitted on the new socket.
- Redirect, body-size, connect-timeout, and read-timeout caps are
  enforced; an unset timeout is a configuration error.
- HTTP-shaped fetchers: `GET` and `HEAD` only. WebSocket-shaped stream
  sources have no method concept; trust commitments instead live in
  the per-source trust boundaries section below.

The allowlist is not user-configurable. Operators with a legitimate need
to scrape an internal feed run a separate ingestion pipeline.

## Per-source trust boundaries

Some ingest sources sign their own payloads at the protocol layer.
Verification of those signatures is a per-source trust boundary that
runs **before** Stage 1 (decision D38). The SPI does not know about
signatures; each implementation enforces its own boundary and is
responsible for never emitting an unverified event into the outbox.

### Nostr (StreamSource, v1)

- **Signature verification.** Every received event MUST pass
  signature verification against its claimed pubkey before reaching
  Stage 1. The pubkey is the only identity the Collector trusts for
  that event; the relay that delivered it is *not* a trust anchor.
  This is the ingest-layer mirror of decision D10 (cryptographic
  identity is the trust anchor).
- **Failure mode.** Failed verification → drop, increment a counter,
  never enqueue. Never released as `READY`, never visible to users.
  No admin notification per failure (a hostile or buggy relay can
  produce many) — the counter is the audit surface.
- **Forever read-only.** The Collector never holds a Nostr private
  key, never signs an event, never publishes. There is no
  key-handling code path in the codebase, even disabled. Lifting this
  requires a spec amendment and is out of scope for v1.
- **Kind allowlist.** Only kinds 1 (text notes) and 6 (reposts) are
  parsed in v1. All other kinds — including kind 4 (DMs), kind 7
  (reactions), and any encrypted-content NIPs — are dropped without
  parsing. **Ordering at the StreamSource trust boundary:**
  signature verification → kind allowlist → outbox write. Stage 1
  (HTML sanitization, regex set, Unicode normalization) begins at
  outbox-write time and applies to the body of allowed kinds. The
  kind allowlist is **not** part of Stage 1 — it is a Nostr-specific
  protocol gate that prevents disallowed event types from reaching
  the pipeline at all. This ordering means an unverified event of
  any kind is dropped at the signature step and never reaches the
  kind filter; an event of a disallowed kind is dropped at the kind
  filter and never reaches the outbox; only events that pass both
  gates enter Stage 1.
- **Repost handling.** Kind 6 reposts carry the original event id as
  a reference; the original event is **not** auto-resolved in v1 (no
  extra fetches, no relay round-trips).
  - A kind-6 event with a **non-empty `content` field** stores the
    commentary text as the post body; the original event id is stored
    as a `post_reference` edge with `link_type = 'repost'`. The
    cross-source link is by `upstream_identifier` (the Nostr event id)
    — see `architecture.md` §Source identity for the linking rule.
  - A kind-6 event with an **empty `content` field** stores an empty
    post body; the `post_reference` edge is still written.
  - A kind-6 that references an original of a **disallowed kind**
    (kind 4, 7, or any other kind outside the allowlist) stores only
    the `post_reference` edge. The reference is a cryptographic event
    id (a hash) and **reveals no content about the original event**.
    The disallowed-kind original is never fetched, never parsed, and
    never stored.
  If the original is later seen via a separate allowed-kind event
  delivery (i.e., the referenced event is itself a kind 1), normal
  cross-source linking applies.
- **Operator-configured relay list.** The relay set is configured via
  `application.properties` and the bootstrap JSON; **NIP-65
  auto-discovery is explicitly out of v1**. Trade-off: content posted
  only to relays outside the operator's list is invisible to the bot.
  This is a deliberate v1 simplification — the operator picks which
  slice of the Nostr network the bot listens to. Surfacing this in
  user-facing help is design-notes territory.

## Prompt-injection defenses (LLM call sites)

Even after Stage 1+2, post bodies reaching the summarizer or chat agent are                                                                                                                                                                           
considered untrusted (decision D21):

- Every prompt that includes user-derived text is wrapped in a delimiter                                                                                                                                                                              
  block whose marker contains a per-call random value. Attackers cannot                                                                                                                                                                               
  pre-guess the marker and therefore cannot forge a closing tag inside the                                                                                                                                                                            
  body.
- The system prompt instructs the model to never follow instructions
  inside the wrapper, to refuse action requests with a **structured
  refusal marker** (the literal token used in v1 lives in design notes),
  and to treat the content as data.
- The LLM tool surface is a strict allowlist. Every name appears
  verbatim in the agent's tool registry; nothing else is callable.
  The v1 list is **closed at spec level** (additions or removals
  are spec amendments, not design tweaks). **All free-form string
  and list inputs across every tool below are length-bounded by a
  profile-driven cap** (the cap value lives in design notes); a
  call exceeding the cap is rejected by the tool dispatcher
  before any SQL runs and the LLM sees a typed validation-error
  reply. Per-tool overrides (cap differences, additional rules)
  are noted in the Notes column.

  <!-- tool-allowlist:begin -->

  | Name | Inputs | Output | Notes |
  |---|---|---|---|
  | `searchPosts` | `tags: list<Tier-1 tag>` (each value validated against the controlled vocabulary), `window: duration`, `limit: int ≤ profile-driven cap` | list of `{uid, title, url, ready_at, tags}` | Returns `READY` posts visible in the calling `(user, scope)`'s world only (D59: live, non-excluded bootstrap sources OR the scope's subscriptions). The requested tag filter applies as-is (validated against the controlled vocabulary); the scope's `tag_mode`/`scope_tag` preferences intentionally do NOT apply — tag preferences narrow the digests only (commands.md §Per-scope tag preferences, D59). The `window` filter binds to `ready_at` (the pipeline's READY-transition time): the result is the posts that became readable within the window, so a post with an old `published_at` but a recent `ready_at` DOES surface in a short window — it arrived in that window (commands.md §Content, *What the window measures*). Results are ordered `COALESCE(published_at, fetched_at)` descending. The ordering falls back to `fetched_at` rather than sorting on a bare `published_at` because that column is source-supplied AND nullable, and Postgres sorts NULLs first under `DESC`: a bare key would let any feed seize the head of every result — the position re-injected first into the chat prompt — by simply omitting its publication date, which is strictly easier than the future-dating the ingest clamp already denies (schema.md §"`published_at` clamp"). The fallback is `fetched_at`, **not** `ready_at`: `ready_at` is stamped at the READY promotion (always later than the row's own fetch) and is re-stamped by `approve_quarantine` and re-evaluation, so keying on it would rank an undated post above the ceiling the clamp imposes on dated ones and let a released post jump to a position no dated post can reach. `fetched_at` is the partition key and is never re-stamped. **The bound this gives is precise, and is worth stating exactly rather than over-promising:** an undated post sorts at the top of *its own fetch cycle* — level with the ceiling a dated post from that same fetch could claim — and no higher, and it cannot move on release. That bound is stated against its own fetch, not against the corpus, and the difference matters: a **later** fetch does not automatically displace it, because the clamp bounds `published_at` from above only and an honest backfilled item can key arbitrarily far in the past. What the bound denies is any position a dated post from the same fetch could not already have taken. Omitting a date therefore buys a bounded, self-decaying position, not the unconditional head a bare `published_at DESC` would grant. |
  | `semanticSearch` | `query: string` (free text, length-capped), `limit: int ≤ profile-driven cap` | list of `{uid, title, url, similarity}` (`similarity` null for lexical-only rows) | **Hybrid** semantic + lexical retrieval over the post corpus, fused by Reciprocal Rank Fusion (D58): a pgvector cosine-distance arm over the post-embedding store and a full-text arm over `post.search_tsv` (`plainto_tsquery`, query bound as a parameter — never string-concatenated). Returns `READY` posts visible in the calling `(user, scope)`'s world only — **both** arms carry the same D59 world + `READY` predicate as `searchPosts` inside the arm before its limit, so a post outside the caller's world (a private custom the scope never subscribed, an excluded bootstrap source, or not yet `READY`) can never surface through either arm or the fused result. The query is embedded on the **local** embedding backend (embeddings never leave the deployment, D54); the fused candidate set and its order are decided entirely by SQL (per-arm `ORDER BY` + RRF, tie-broken by `post_id`), reproducible on unchanged DB state and never chosen by the LLM (D19). **Query anchoring (M1-746, D58):** when the scope declares a non-English `/lang`, the query text is first translated to the corpus anchor language (English, D29) by a generative `ModelTask.TRANSLATOR` call (decoded greedily — temperature 0 on the wire; language-only prompt; result cached per (scope, query, language); accepted translation capped at the tool's input length) — an `en` scope is a strict no-op. A translator failure, an open breaker, or an over-cap translation falls back to the raw query text (degraded retrieval beats no retrieval); the fallback and the translation both reach the arms only as bind parameters. The translation leg draws no per-user bucket token (see §Rate limiting) and its outputs are never shown to the user — they enter the retrieval arms only. A configured cosine-distance relevance threshold gates the **semantic arm**; the lexical arm surfaces keyword-exact matches the semantic arm's threshold would exclude. When both arms return nothing → empty result → the agent answers from general knowledge. The scope's per-tag preferences (`tag_mode`, commands.md §Per-scope tag preferences) intentionally do **not** apply to either arm — under D59 tag preferences narrow the digests only (no chat retrieval surface applies them); this tool is scoped by the world predicate only. `similarity` (= 1 − distance) is a display value only, emitted `null` for a lexical-only row that has no embedding; the raw embedding vector is never exposed (D5). Besides being model-callable, the chat agent dispatches this tool **deterministically on every chat turn** (the D28 pre-fetch pattern) and re-injects the result through the untrusted-content wrapper — retrieved titles/URLs are attacker-influenced content. The deterministic pre-fetch shares the tool loop's per-turn dispatch context, so the fixed per-turn call cap and the identical-call cache hold turn-wide. The turn's retrieval outcome also drives a deterministic, bundle-localized provenance notice on the reply (grounded-with-count vs general-knowledge; count only, no feed-derived text interpolated — the D31/D43 constraint; commands.md §Chat mode, D58). |
  | `getPost` | `uid: string` | `{uid, title, body, url, ready_at, tags}` or `null` | Scope-filtered: returns null for a UID not visible in the calling scope (the same path as a UID that does not exist; the existence-vs-no-access distinction is never exposed). |
  | `getReferences` | `uid: string`, `limit: int ≤ profile-driven cap` | list of `{uid, title, url, link_type, score}` | Edges from the `post_reference` graph. Scope-filtered the same way as `searchPosts`. |
  | `recallMemory` | `keywords: list<string>` (each ≤ a profile-driven length cap) | list of `{compressed_at, summary, references}` | Reads `chat_memory` for the calling `(user, scope)` only — D28. **Not** the user-facing `/recall <keyword>` command, which is v2-deferred per SPEC.md §"Deferred to v2". |
  | `listSaves` | `tags: list<personal tag>` (free-form, but length-capped), `window: duration` | list of `{uid, saved_at, personal_tags, snapshot_title, snapshot_url}` | Reads the caller's `saved_post` rows globally (D13: per-user across scopes); never returns another user's saves. |
  | `helpLookup` | `query: string` (free text, length-capped by the dispatcher's per-turn input cap) | `{command: <name>, description: <runtime one-liner>}` or `{command: null}` | Reads the `doc_embedding` command-intent corpus (V60) and resolves a free-text intent to a catalogue command name via one pgvector cosine probe on the **local** embedding backend (D54). The tier filter runs INSIDE the query (`target_ref = ANY(?)` bound to the caller's visible command set, computed via `HelpCommandHandler.visible`/`resolveCallerTier`), so an invisible command's name never enters the model context (the existence-oracle defense widened to free-text input). **Match-not-assert invariant:** embedded text is used only for MATCHING; the returned `description` is composed at call time from the runtime `HelpCommandHandler.CATALOGUE`'s short-help bundle key, never from the indexed text — a stale intent row can degrade a match but can never produce wrong syntax. Full usage/examples bodies never enter the model context (the delivery path is governed by the §LLM output sanitizer amendment, decision D67). Below the calibrated similarity threshold the tool returns `{command: null}` and the agent is directed (`ChatAgent.TOOL_INSTRUCTIONS`) to say it does not know and point at `/help` rather than answering from general knowledge. D19 determinism: the match is decided entirely by SQL; D66 records the corpus + invariant + tier-filter-before-return rule. |

  <!-- tool-allowlist:end -->

  Every argument is type-checked and bound to enums, validated
  ranges, or length caps before the underlying SQL runs. Every output
  is a typed structured value, never a passthrough of free-form
  upstream text outside the post body / saved snapshot already vetted
  by the ingest pipeline. Verification (`verification.md` §Security)
  asserts the registry's name set equals the table above
  byte-for-byte; CI fails on a mismatch in either direction (a name
  added to the registry without a matching spec row, or a spec row
  with no matching registry entry).
- **Never exposed (forever):** any tool that mutates `users`,                                                                                                                                                                                         
  `group_membership`, `is_admin`, `is_banned`, `audit_log`, `source`,                                                                                                                                                                                 
  `source_subscription`; any tool running arbitrary SQL; any tool sending                                                                                                                                                                             
  messages outside the current conversation; any tool fetching arbitrary                                                                                                                                                                              
  URLs.

### LLM output sanitizer

Before any **LLM-generated** text is delivered to a user, the candidate
output is passed through a deterministic outbound regex pass that
strips or refuses output containing admin command strings
(`/grant-admin`, `/ban`, `/promote`, `/remove-source`, etc.). The
sanitizer applies to the **full set of LLM-authored output surfaces**:
chat-mode replies, on-the-fly `/summary` prose, periodic group
digests, `/retry` re-rolls, and any future LLM-emitted text. It does
**not** apply to deterministic command output (`/help`, `/status`,
`/list-sources`, etc.). "Never passes through an LLM" is true of this
surface but is **not** what makes it safe: a deterministic reply that
echoes an attacker's raw token is exactly as dangerous as an LLM
emitting one. The exemption is safe only to the extent deterministic
output is **bot-authored** — interpolates no inbound-derived text
(parse-validated echoes, per `commands.md` §Discovery, count as
bot-authored). That is a property the handlers must **maintain**, not
one the exemption may assume: prior tickets removed the reflecting
echoes from the friendly-**error** surface and
`InboundReflectionGuardTest` guards that surface against their
return. The **reply/success** surface is not yet fully guaranteed. Two
live instances are known, both now **CLOSED** — but they did **not**
close the same way, and the difference is the lesson:

- the `/add-source` `--name` display-name echo is closed at
  the **write boundary alone**: the override is discarded if the
  NFKC-folded name contains a slash, and because a display name has no
  legitimate slash, constraining the single produced value covers every
  surface that later renders it.
- the `/save -t` personal-tag echo into the group-visible `/saved`
  reply needed **both** a write-side reject **and** render-side
  redaction. The whole `/save` is rejected if any NFKC-folded tag
  contains a slash — but that only bounds NEW tag writes. The *same*
  reply line interpolates the post **title**, which is upstream-
  controlled and legitimately contains slashes (`TCP/IP`), so it cannot
  be rejected; and pre-existing tag rows predate the reject. So the
  group-visible echo surfaces (`/saved` reply, `/summary`, and the
  degraded group digest) are additionally passed through the closed-list
  `LlmOutputSanitizer` at **render**, where a title or tag whose
  canonical form is a privileged command renders as
  `[redacted command]`. On `/summary` the redaction unit is ONE post's
  title on every render form (`commands.md` §Content): degraded prose
  (`title — url (uid)` per post) is derived from the cluster at render
  and sanitized per title
  (`SummaryProseGenerator.degradedProseFor`), so the default
  categorized form — which renders no headline — redacts every feed
  title that reaches the reader, and the `--full` flat form redacts both
  its headline and every title inside its `summary:` field. `/retry`
  replays the flat form and inherits the same per-title redaction with
  no handler change. A command-shaped title is redacted and audited no
  matter where its post sits in the cluster — the detector no longer
  depends on cluster position. The one residual is a privileged command
  split ACROSS two posts' fields (see §"Flag position mirrors the
  parser's own scan" below): neither redacted nor audited, accepted
  because dispatch still requires `is_admin=true` and the multi-author
  sanitize unit that would catch it is the content-suppression vector
  the per-field narrowing removed. This is distinct from the degraded
  digest's `](` adjacency residual, closed at the outbound chokepoint
  (see §"Sanitizer output never contains `](`" below). The surface is
  therefore **closed** on all render forms. This
  instance sits at the **same attacker tier**
  as `/add-source` in a DM — both are open to any non-banned user
  (`commands.md` §Source management) — not a lower one; it escaped
  earlier constraint because the write-side caps were designed for size,
  not content shape, a reminder that "this field is bounded" is not
  "this field is safe to echo".

The write-boundary rejects carry no **mechanical guard** (the reflection
guard's census is error-scoped, and both of these are `reply.*` keys)
and other `reply.*` echoes remain unreviewed; the `/save -t` render-side
redaction, by contrast, is audit-logged on every hit — rows aggregate
per distinct token per call and carry the exact occurrence count. So
for now this exemption carries a **residual
risk** on non-error deterministic output, not a proven-safe blanket.

**Delivery-ordering contract.** Command-usage or help text delivered
into a chat-mode reply must satisfy **one of two** paths: either
(a) it is **deterministic end-to-end** — deterministic code decides
both **whether** it is delivered and **what** it contains, driven by
the parsed user request, never by a model-elected tool call — or
(b) it is passed through the sanitizer like any other LLM-authored
output. The exemption for deterministic command output therefore
requires more than deterministic bytes: it requires a deterministic
**emission decision** to match. Output whose content is bot-authored
and deterministic while the decision to emit it is the model's does
**not** qualify — that shape is inside the sanitizer's mandate, not
the exemption, and passes through the outbound regex pass like any
other LLM-authored output (motivating finding:
`docs/plan/m1/redteam/M1-648-2026-07-19-r2.md` — a post-sanitize,
model-elected append of privileged command usage into a chat reply,
byte-true against the old exemption's prose and contract-false under
this one). The "driven by the parsed user request" qualifier is
load-bearing: the caller's own inbound text is the same trust grade
`/help` itself runs on, whereas the model's context is
attacker-influenced by this threat model's own admission
(§Prompt-injection defenses, `semanticSearch` row, on the D28
pre-fetch that re-injects feed-derived text every turn), so a
delivery decision keyed off model-elected tool output is exactly the
shape the sanitizer exists to catch.

Admin commands are dispatched only by the deterministic command path,
so a copy-pasted reply still requires `is_admin=true` to do anything;
the sanitizer closes the social-engineering surface where a small LLM
emits plausible-looking admin commands across any of the surfaces above.
Every match is audit-logged; rows aggregate per distinct token per
sanitize call and carry the exact occurrence count — counted, never
throttled.

**Canonical-form matching.** The closed-list pass matches against the
**canonical** form of the candidate output — NFKC normalization
followed by the bidi-control and zero-width strip — which is the same
representation the deterministic command parser consumes (§Message
intake step 1.7). Matching the raw bytes instead left a representation
asymmetry the sanitizer was blind to: `／grant-admin` (fullwidth
solidus), an all-fullwidth token, a token split by a zero-width space
or wrapped in bidi isolates, and a multi-word entry joined by U+3000
each survived sanitization verbatim, yet each parses as a privileged
command the moment a reader copy-pastes the bot's line back in. When
the canonical form carries no closed-list token the **original bytes**
are returned unchanged, so legitimate Unicode prose is never reflowed;
only a match may change the output's representation, and a match means
the text carried a token that canonicalizes into a real command.
**Case is decided per token, mirroring the parser token by token** —
it is a property of how each token is *parsed*, not of the
representation, so a blanket rule in either direction is wrong. The
**command name** is matched exactly, because dispatch resolves it by
exact comparison: `/Grant-Admin` and `/Invite create` are not commands,
and redacting them would corrupt prose for no security gain. A
**subcommand** token is matched case-insensitively, because the
handlers lower-case it before switching on it: `/invite CREATE` *does*
dispatch, so matching it exactly would leave every multi-word
subcommand entry evadable by changing one word's case — silently, since
a non-match emits neither the WARN nor the audit row. A **flag** token
follows whatever its own handler does, derived per entry rather than
asserted globally — for every flag-bearing entry in today's closed list
the handler compares exactly, so the flag is matched exactly and `--ALL`
never dispatches. That derivation is deliberate, not incidental: flag
parsing is *not* uniform across the codebase (some handlers lower-case
flag tokens before comparing), so a rule stated as a blanket property of
flags would be the same over-broad claim as a blanket rule on case, and
a future closed-list entry pairing a flag with a flag-folding parser
would silently inherit an evasion.

**Flag position mirrors the parser's own scan.** A flag-bearing
closed-list entry matches its flag at **any position in the command's
argument run**, not only immediately after the command word. The rule is
the same match-what-the-parser-dispatches derivation as the case rule
above: the flag-parsing handlers loop over *every* argument token, so
`/list-sources --page 1 --all` dispatches the admin-only global
listing identically to `/list-sources --all`, and a fixed
adjacent-token match let that line ship verbatim — with no redaction
marker, no WARN and no audit row, disclosing the deployment-wide
source catalogue the entry exists to keep out of LLM output (§Source
URL visibility). The argument run spans the **whole message, across
newlines**: the router hands the handler the entire, possibly
multi-line, body, and `ListSourcesArgs.parse` tokenizes it with
`split("\s+")` — Java `\s` includes `\n` — so a `--all` on any line
after `/list-sources` dispatches `all=true`, and the scan matches it
there. It deliberately does **not** stop at a sentence terminator or an
intervening `/`, because the parser does not either —
`ListSourcesArgs.parse` ignores any token it does not recognize rather
than rejecting it, so a punctuation- or slash-bearing token
(`/list-sources --filter rss/news --all`, `/list-sources why? --all`)
is still a real argument that dispatches `all=true`. A sentence-scoped
or line-scoped bound would leave exactly those forms dispatching while
evading the match — and the line bound also regressed the adjacent
`/list-sources\n--all` case the earlier `\s+` regex used to catch.
Whitespace is read the way the parser's `split("\s+")` reads it — every
ASCII `\s` character is a token separator, so `/list-sources` and
`--all` separated by a bare `\r` or by newlines match, mirroring the
dispatch. **The span's justification holds because every caller's unit of input is
one author's field** (restoring the invariant the categorized-render
work briefly widened). The argument below — that a collapsed span only ever swallows
bot-authored bytes — requires every sanitize call over feed-derived text
to be scoped to a *single* author's field, and every call site is built
that way: `ClusterBlockRenderer` passes one post title,
`DegradedDigestRenderer` one title per post, the `/saved` reply one
row's title OR up to `BODY_SCAN_LIMIT` chars of its body, plus one
row's tags, and degraded prose is DERIVED from the
cluster at render and sanitized per post title
(`SummaryProseGenerator.degradedProseFor`) — the renderers never trust
the prose bytes a `ClusterProse` record carries, so a hand-assembled
record cannot smuggle unsanitized titles past them (redteam,
2026-07-25) — so no caller hands the sanitizer a multi-post
concatenation. The whole-message
span guarantee is therefore scoped **within one author's field**: a
flag-bearing entry redacts only when its command word and flag appear in
the same field. The converse residual is accepted and deliberate: a
privileged command split ACROSS two posts' fields — `/list-sources` in
one title, `--all` in another — is **neither redacted nor audited**,
because the two tokens never share one sanitize input. That is the
original posture on the flat renders, and it is bounded by dispatch
still requiring `is_admin=true`; the alternative — one sanitize call
over assembled multi-author prose — is strictly worse, because the span
then swallows *other publishers'* bytes and a co-clustered attacker can
delete a third party's post (the content-suppression vector the
categorized-render redteam caught). The detector narrows only for that split case:
within one field, `LLM_OUTPUT_SANITIZED` rows fire at
least as often as before the narrowing (aggregated per distinct token
per call, carrying the exact occurrence count).

The cost of the whole-message bound is that a genuine mention
of the command and a later, unrelated admin flag in the bot's own
explanatory prose collapse into one redacted span; that is bot-authored
text, and the same span copy-pasted from the `/` does dispatch, so the
redaction is defensible. Flag entries are matched by a **whitespace
tokenizer**, not a regex, implemented as a single left-to-right scan
(the command- and flag-search cursors only advance) that is linear in
the reply length. A regex was rejected because matching a flag at any
position is either bounded — re-opening an evasion for a flag placed
past the bound, which the parser still dispatches — or super-linear
under backtracking / `find()` re-anchoring on an attacker-influenced
reply (§Trust boundaries item 9 puts a hostile endpoint's reply in
scope, so an in-cap reply must not be convertible into unbounded CPU).

**Markdown flattening survives canonicalization.** The link-flatten
pass runs on the raw output first, and again on the canonical form
inside the closed-list pass. NFKC folds fullwidth brackets into real
`[...](...)` syntax, so canonicalization can *synthesize* link syntax
the raw-byte pass never saw; without the second flatten a closed-list
hit would deliver exactly the label-hiding markdown the first pass
exists to remove. Flattening runs before the replacement, so the
redaction marker's own brackets are never consumed as link text; and
because no flatten runs *after* the replacement, the marker is kept
from landing directly against a following `(` — the match's
word-boundary rule admits one, so a token written as `/ban(url)` would
otherwise leave the sanitizer emitting link syntax it manufactured
itself.

**Sanitizer output never contains `](`.** The guarantee is now an
OUTBOUND property, carried once at `OutboundDelivery`: every
outbound body — chat reply, progress placeholder/finalize, periodic
digest, group announcement — has its `](` adjacency broken before it
reaches the transport, regardless of how it was assembled. It therefore
covers the WHOLE delivered message, not only what the sanitizer emits:
a render path that *assembles* sanitizer output with bytes the sanitizer
never saw — a source display name, a bare feed URL — no longer escapes
it, because any `](` the join creates is broken at the chokepoint. This
subsumes the residual the degraded per-cluster prose branch used to
carry: it sanitizes each feed-derived headline but joins the results
with the source's display name and a bare URL, neither of which passes
through the sanitizer, and the assembled message used to sit outside
the guarantee. The mechanism is the single
`LlmOutputSanitizer.breakLinkAdjacency` declaration, called both inside
the sanitizer's own passes (so a redaction or canonicalization cannot
manufacture link syntax it then emits) and at the outbound
chokepoint; it is idempotent, so the two call sites stack. The
closed-list command redaction is a SEPARATE control and does NOT move
to the chokepoint — its unit is one author's free-text field, and
running it over a URL would rewrite ordinary feed paths like `/audit`
or `/pending` to `[redacted command]` — so it stays at each sanitize
call site, with the unit everywhere narrowed to one author's field:
degraded prose is sanitized per post title at composition, so
the `/summary --flat` and `/retry` flat renders — whose `summary:`
field carries degraded prose verbatim — now deliver per-title redaction
instead of a raw command-shaped title, and the default categorized
`/summary` form relies on the same per-title redaction, derived from
the cluster at render rather than sanitizing an assembled per-cluster
string.
Flattening alone cannot carry
that guarantee, because flattening means *parsing*, and the parser is a
regular expression: CommonMark permits balanced brackets inside a link
label, which no regular expression can track, so `[Read [the]
report](url)` is a genuine link the pass will never match. The
guarantee is therefore carried by a second, weaker-but-total mechanism —
any `](` the flatten could not consume has its **adjacency broken**
(`](` → `] (`). No character is lost, so the label and the bare URL both
stay readable; the link simply stops being one, because a renderer
resolves a link only on the adjacent pair. The property is about two
characters rather than about markdown, which is precisely why it can be
stated absolutely without inheriting the parser's limits.

This matters most on the canonicalized path, where it prevents the
sanitizer *creating* a link that never existed. Text arriving as `[Read
[the] report ］（url）` carries a fullwidth `］` that no client renders as
link syntax; NFKC folds it to the `]` that completes a real link, and
the nested label keeps the flatten from parsing the result. Without the
adjacency break, a closed-list hit would deliver a working link
manufactured from text that was not one. Note the two passes stay
independent throughout: the closed-list match's word-boundary rule
admits `)`, so a privileged token inside a link target is stripped,
audit-logged and WARNed whether or not any flattening succeeded.

**The chokepoint routing is build-guarded.** The `](`-free OUTBOUND
property above rests on every outbound path routing through
`OutboundDelivery`'s entry points; that routing was a convention
enforced by census, not a structural property. It is now build-guarded:
an ArchUnit test (`OutboundChokepointArchTest`) fails the build
if any class in the provider main source other than `OutboundDelivery`
and `DigestDelivery.RecordingAdapter` holds a direct call OR a
method-reference edge (`adapter::send`) to `MessagingAdapter.send`,
`.update`, or `.finalizeMessage` — the three outbound-body methods of
the SPI. A drift assertion fails the build if the SPI grows a
body-delivering method the guard does not yet name, the same shape as
the sanitizer match-set derivation's CI check below. The guard catches
the accidental shapes — a provider class that calls the adapter
directly or hands `adapter::send` to a helper — which is how a future
bypass would realistically land; it is not totality. Five residual
routes leave no edge the provider-scoped scan sees and are accepted as
documented residual risk rather than claimed closed: helper indirection
(a static helper in another module wrapping `adapter.send`, called from
provider — an interprocedural shape a static edge guard cannot trace);
a sender compiled in a sibling module (scoped to the provider module
today, bounded by the module DAG — core, collector, llm-adapter, ssrf,
and messaging-adapter are enforcer-blocked from the messaging-adapter
dependency, core's rule having landed with M1-702, so the DAG bound is
structural for every one of them; infochat-messaging-adapter's own
classes still reach the SPI it defines, which no dependency ban can
express); reflective invocation (`Method.invoke` /
`MethodHandle` / dynamic proxy), a deliberate-evasion shape outside the
threat model's external-adversary scope; a body-delivering overload
reusing a non-body method name (the drift check classifies the SPI by
method name, so a future overload like `setTyping(ContactId,
OutboundDraft)` inherits the existing non-body name's classification and
slips the name-based guard — low severity, since the common case of a
genuinely new method name is caught); and direct transport access that
bypasses the SPI entirely (provider code opening its own socket to the
transport subprocess / daemon RPC), which produces no `MessagingAdapter`
edge at all — a deliberate or grossly-negligent shape, not an accidental
one. The honest, narrower guarantee — no provider class outside the two
allowlisted may reach the three outbound-body methods directly or via
method reference — is what the `mvn verify` gate enforces; the census
stays the backstop for the residual routes.

**Bare feed URLs rest on the ingest scheme allowlist, not on any render
pass.** Degraded prose interpolates `post.url` bare (`title — url
(uid)`), and no sanitize pass ever sees it — correctly, because the
closed-list pass would rewrite ordinary feed paths (`/audit`,
`/pending`) to `[redacted command]`. Display-side safety for that
operand is carried at the WRITE boundary:
`PostPersister.normalizeUrlForStorage` binds a feed link only when it
parses as an `http`/`https` URI and stores NULL otherwise, and the url
column has exactly one writer, so a `javascript:`-style feed link never
reaches storage, let alone a reply. Chat clients may auto-link the bare
`http(s)` URLs that remain — that is intended (D30 plain-text
formatting). How a client renders scheme-like text inside free-text
fields (a title, LLM prose) is client behavior outside this threat
model; the label-hiding markdown variant of the same concern is the one
the bot can control, and it is closed above.

**Match-set derivation.** The sanitizer's match set is **derived
from the closed privileged-tier list at spec level**
(`commands.md` §Permission model — "Closed list of privileged-tier
commands"), not from the design-tier per-actor matrix and not
hand-maintained. Every command in the bot-admin and group-admin
tiers of that closed list is in the sanitizer set. CI fails on a
mismatch (a new admin command added without a matching sanitizer
entry, or a sanitizer entry that no longer corresponds to a
listed command). Because the closed list is spec, adding or
removing a privileged-tier command is a spec amendment that
forces a paired sanitizer update; this makes "admin commands
never leak through LLM output" a structural property of the
codebase rather than a discipline.

## Authorization model

Two admin tiers (decision D9):

- **Bot admin** — global. Bootstrapped from config; `/grant-admin` by                                                                                                                                                                                 
  another bot admin. **The bootstrap mechanism is per-adapter
  (decision D50), because the adapters expose different identity
  primitives.** Signal pre-seeds an `is_admin = true` row from the
  configured ACI (a real cryptographic account id) at startup.
  **SimpleX cannot be configured by address** — it exposes no
  pre-configurable cryptographic sender address; identity is the
  per-connection id, and a sender's advertised profile address
  (`contact.profile.contactLink`) is **self-asserted, not
  verified** (out of scope of the SMP protocol), so it is never an
  authorization key. SimpleX admin is instead claimed by a
  **single-use secret token** (`infochat.adapters.simplex.admin-token`,
  the D44 invite-code shape extended to flip `is_admin`): the first
  DM presenting the token flips `is_admin` on the *sending
  connection's* `(simplex, contact_id)` row; while that admin exists a
  later presentation grants nothing and gives the same fixed reply an
  invalid invite would (no validity oracle). Nothing is pre-seeded for
  SimpleX. Operators should unset the token once the first admin is
  established (the residual single-use caveat and that mitigation are
  detailed in §Per-adapter admin threat profile). See `deployment.md`
  §Operator inputs item 2 and §Per-adapter admin threat profile.
- **Group admin** — one group only. Bootstrapped by first `@mention` in a                                                                                                                                                                             
  new group; `/promote` / `/demote` by bot admin.

Invariants (also enforced in `schema.md`):

- **Last-admin protection (bot admin only).** Cannot revoke the only
  bot admin's `is_admin`, cannot ban the only bot admin, cannot ban
  self. **The "only bot admin" check is global across adapters** —
  the count is `SELECT COUNT(*) FROM users WHERE is_admin = true AND
  is_banned = false`, not per-adapter — a banned admin does not count
  as a live admin, so the trigger excludes banned rows. A deployment
  with admins on multiple adapters may demote any single admin row as
  long as at least one non-banned `is_admin = true` row remains
  anywhere. This pairs with
  `/grant-admin` / `/revoke-admin` being inbound-adapter-scoped
  (`commands.md` §Admin): the per-adapter scoping bounds the
  blast radius of a single-adapter compromise, and the global
  last-admin counter prevents that scoping from being weaponised
  to lock the deployment out of admin entirely.
  Enforced at the trigger layer, not just the command layer, so a
  buggy command cannot bypass it. **Group admin has no last-admin
  protection** — a group can exist with zero admins (a banned or
  demoted group admin is not auto-replaced; the next bot-admin
  `/promote` or first-mention path refills the slot).
  - **Blind spot: the count is flag-based, not reachability-based.**
    The COUNT above counts `is_admin = true AND is_banned = false`
    rows only; it has **no reachability dimension** — it does not
    verify that an admin's `contact_id` ever byte-matches what its
    adapter reports for inbound messages. A bootstrap-seeded admin
    whose `contact_id` never matches inbound — a **phantom admin**,
    e.g. from a mistyped bare contact id — therefore counts as a live
    admin even though no message can ever be attributed to it.
    Consequence: with a phantom admin plus a reachable co-admin, the
    co-admin can `/revoke-admin` (or ban) the reachable admin(s) down
    to only the phantom; the trigger sees `count >= 1`, allows it, and
    leaves the deployment **locked out of admin while the invariant
    believes one admin remains**. This is an
    **operator-misconfiguration risk, not an adversary-reachable
    path** — the seeded contact id is trusted operator config
    (§Trust boundaries), never attacker-controlled, and a co-admin
    abusing it is already a trusted bot admin. Operator detection and
    recovery steps live in the deployment runbook
    (`docs/design/07-deployment.md` §7.14/§7.15). A
    reachability-aware "confirmed admin" count that would close the
    gap is a future hardening: it needs a schema column, an intake
    write, and a change to this security invariant's trigger, so it is
    deferred behind a spec amendment rather than bolted on here.
- **One group admin per group at any time.** Enforced by partial unique
  index. The auto-promote path applies whenever the group has **zero**
  `is_group_admin` rows — covering both newly-approved groups and
  groups left without an admin due to demotion or ban. The first
  eligible @mentioning user — registered, non-probation, and
  non-banned — wins the slot (the "first registered,
  non-probation, non-banned `@mention` wins" rule). The
  `activated_by` column records which user activated the group for
  accountability only (D47); it confers no auto-promote priority.
  Banned and probation users are ineligible (probation
  users cannot run admin commands by D45; promoting one would be a
  footgun). The promote is `INSERT … ON CONFLICT DO NOTHING`
  against the partial unique index; the row that loses a race
  produces no error and no admin row — the user receives the
  standard non-admin response for whatever command they sent.
  `/promote` demotes the existing group admin in the same
  transaction.
  - **Leaves do not free the slot on membership-event-less adapters
    (v1 non-commitment).** The auto-promote trigger list above —
    demotion or ban — deliberately omits a member *leaving*. On
    adapters without a native per-user membership signal, where
    `supportsMembershipEvents = false` (**both v1 production
    adapters**; see `messaging.md` §Required SPI surface — Membership
    events), a member who leaves a group does **not** have their
    `group_membership` row or their `is_group_admin` flag
    soft-cleared: there is no left-group event, and a group-scope send
    carries no per-user delivery-failure carrier from which one could
    be inferred (`messaging.md` §Failure handling — "User left
    group"). Two consequences follow, and both are stated
    NON-COMMITMENTS rather than threat-model violations — v1 never
    committed to leave-driven cleanup on these adapters. (1) A
    **departed group admin still counts as the active admin**: the
    group's `is_group_admin` row count stays non-zero, so the
    auto-promote path does not fire and the group is *not* treated as
    admin-less. (2) If that departed admin later rejoins, they
    **silently resume group admin**, because their row was never
    cleared. The documented remediation is a bot-admin **`/demote`** of
    the stale admin (`commands.md` §Admin): the departed member's row
    is still active (`removed_at IS NULL`), which is exactly what
    `/demote` requires of its target, and clearing it frees the
    partial-unique-index slot for the next eligible first-mention
    auto-promote or an explicit `/promote`.
- **Banned-admin lockout escape hatch.** If the existing group admin is
  banned (their `is_group_admin` row remains but is unreachable per §User
  ban), a bot admin can `/promote` a different group member; the demote
  side of the swap clears `is_group_admin` on the banned row in the same
  transaction. This avoids a permanent group-admin lockout when the
  current admin is banned and `/unban` is not desired.

Authorization evaluation order on every inbound message.

**Step labels are stable cross-reference identifiers, not execution-order
indices.** Steps below are numbered for downstream references (code
comments, ticket bodies, design notes); the numeric order matches
execution order EXCEPT that **step 4 (ban check) executes after step 3
and before step 3.5** — a banned user reaching the group `@mention` path
short-circuits at step 4 with the fixed ban reply, before any group
approval check, per-group rate-cap consumption, or group-related DB
write. All other steps execute in numeric order.

1. Resolve identity from the adapter.
1.5. **Transport-level rate cap.** Apply the per-`(adapter,
   contact_id)` inbound rate cap (§Rate limiting, "Chat-mode
   message rate (transport-level)"). Over-cap inbound is **dropped
   silently** for the rest of the cap window — no reply (including
   no fixed ban reply, no fixed invite-required reply, no friendly
   error). The cap runs after step 1 (the bucket is keyed by the
   resolved `(adapter, contact_id)`) and before every
   application-level check below, so a hostile flood cannot drive
   outbound cost via the per-inbound fixed-reply paths in steps
   2 and 4. The brute-force invite-code limit
   (§Invite-code registration) is a **separate** counter applied
   inside step 2 (it counts attempts that reach step 2, not raw
   inbound).
1.7. **Unicode-normalize the body** (NFKC + bidi-control strip +
   zero-width strip + leading/trailing whitespace trim outside
   fenced code blocks; fence recognition per the CommonMark rule
   documented in §Ingest pipeline) **before any body-content
   check**, so a `/` cannot be disguised by homoglyphs or bidi
   overrides and a copy-pasted invite code with whitespace,
   homoglyphs, or zero-width formatting is matched on its
   semantic value, not its raw bytes. This is the chat-input parity
   step that mirrors Stage 1 ingest normalization with the
   user-intent fenced-code carve-out (the carve-out is chat-side
   only — ingest applies unconditionally). Normalization runs
   after the rate cap (so over-cap inbound is dropped without
   spending normalization cost on adversarial bodies) and before
   the invite-code check (step 2), the group authorization check
   (step 3/3.5, D47), and parse (step 6). The ban check (step 4) reads the
   cryptographic contact id, not the body, so its position
   relative to normalization is immaterial. **The normalized body
   replaces the raw body for all downstream processing**: the
   invite-code consume, the command parser, the chat agent, and
   the LLM all receive only the normalized form. The raw body is
   discarded after this step and never reaches the LLM in any
   call path.
2. **DM — unknown contact.** If no user row exists for this (contact\_id,
   adapter): check whether the full normalized message body is a valid
   PENDING invite code bound to this exact (contact\_id, adapter) pair
   (decision D44).
   - Valid: create user row (probation start), mark code USED, send welcome,
     stop. No further processing of this message.
   - Invalid / expired / absent: fixed "access requires an invitation" reply,
     drop. No registration, no LLM, no DB write beyond the drop counter.
3. **Group — unregistered or unknown contact (D47).** If the inbound is
   a group `@mention`:
   - If no user row exists for this (contact\_id, adapter), or the
     row's `registration_state` is `'preban'`: **silent drop** — no
     reply, no registration, no DB write. The bot is invisible to
     unregistered contacts in groups. The auto-registration path
     is removed by D47.
   - If a user row exists with `registration_state IN ('invited',
     'vouched')`: proceed to step 4 (ban check) before step 3.5.
4. **Ban check.** If `is_banned=true`: fixed reply, stop. No parser, no DB
   query past the ban check, no LLM. **Execution position:** step 4
   fires after step 3 (registered/preban filter) and BEFORE step 3.5
   (group approval); the numeric label is retained as a stable
   cross-reference identifier — see the execution-order note at the
   top of this section. DM-scope and group-scope inbound both reach
   this step before any group approval check or group-related DB
   write.
3.5. **Group — approval check + per-group rate cap (D47).** A banned
   user has already short-circuited at step 4 with the fixed ban
   reply; this step only fires for non-banned, registered users in
   group scope. Look up the `groups` row by
   `(adapter, upstream_group_id)`.

   **Per-group reply rate cap.** Before sending any reply (fixed or
   command), check the per-group reply rate bucket. If the bucket
   is exhausted, **silently drop** — no reply, no further
   processing. This bounds outbound adapter-send cost for ALL
   group states (pending, approved, rejected). The bucket is
   shared across approval states for the same group row. The
   per-user transport cap (step 1.5) fires before this step; the
   per-group cap is the aggregate backstop. Profile-driven values
   in design notes.

   Then check `approval_status`:
   - If no row exists: create one with `approval_status = 'pending'`
     and `activated_by` = the current user's id. The creation uses
     `INSERT ... ON CONFLICT (adapter, upstream_group_id) DO NOTHING`;
     a concurrent race produces at most one row (the loser re-reads
     the existing row's `approval_status` via a follow-up SELECT and
     proceeds to the pending branch below). Only the winning INSERT
     sets `activated_by`. Enforce the per-user group activation cap
     and the global max-groups cap (both profile-driven; values in
     design notes). If either cap is exceeded, send a fixed "group
     activation limit reached" reply and stop. Otherwise send the
     fixed "this group is pending admin approval — your command was
     not processed, please resend after approval" reply and stop.
     Send a **throttled admin notification** (one per group
     creation, not per subsequent @mention) to every bot admin with
     the group's adapter, upstream\_group\_id, activating user's
     contact id (redacted per §Secrets handling), and a
     copy-pasteable `/approve-group <uuid>` command string.
   - If the row exists and `approval_status = 'pending'`: send the
     fixed "this group is pending admin approval" reply and stop.
     No admin re-notification (throttled: fires only on row
     creation).
   - If the row exists and `approval_status = 'rejected'`: send the
     fixed "this group was rejected by an admin" reply and stop.
   - If the row exists and `approval_status = 'approved'`: proceed
     to step 4.1 (auto-promote / membership) and on to step 5+.
5. (reserved — body normalization moved to step 1.7 so the
   invite-code consume in step 2 sees the normalized form. Step
   numbering preserved for cross-reference stability.)
6. Parse command (or fall to chat-mode).
7. **Permission check** against the matrix. Probation restrictions (D45)
   are part of the permission matrix: blocked commands return a friendly
   "probation period" reply and never reach execution.
8. Audit-log the intent.
9. Execute.
10. LLM only enters for chat-mode replies, summary prose, and the eval
    pipeline.

Steps 1–9 never call the LLM. This is the determinism boundary that makes
privilege escalation via injection (T3) infeasible.

## Per-adapter admin threat profile

Each enabled adapter has a different real-world compromise surface,
and admin rows are per-`(adapter, contact_id)` (one Provider may
run multiple adapters per `deployment.md` §Topology). Operators
should pick admin placement deliberately:

- **Signal admin.** The admin's identity is anchored cryptographically
  to the ACI, but that ACI is bound to a phone number / username
  recoverable through carrier and account-recovery flows. SIM-swap,
  port-out fraud, and account-recovery social engineering are real
  threats. A Signal admin compromise gives an attacker bot-admin
  powers on the Signal adapter only (per the inbound-adapter-scoped
  grant rule above), but that includes invite issuance, ban,
  source mutation, and audit access for that adapter.
- **SimpleX admin.** The admin's identity is the per-connection id
  the transport assigns — cryptographically authenticated by SMP,
  with no phone number, no username layer, and no third-party
  recovery path. Because SimpleX exposes **no pre-configurable
  cryptographic sender address** (the advertised
  `contact.profile.contactLink` is self-asserted and not verified —
  out of scope of the SMP protocol), the admin is **not** configured
  by address; it is established by a **single-use claim-token**
  (decision D50, `deployment.md` §Operator inputs item 2): the
  operator sets `infochat.adapters.simplex.admin-token`, and the
  first DM presenting it flips `is_admin` on the sending
  connection's row. **Why the token is secure where an advertised
  address would not be:** the connection is cryptographically
  authenticated by SMP and the token is a secret only the operator
  holds, so an attacker without the token cannot claim admin and
  cannot influence their own connection-based `contact_id`; an
   attacker who could only spoof an advertised address (the discarded
   by-address approach) could impersonate the admin outright. The claim is
  **single-use while a SimpleX admin exists**: the first DM presenting
  the token establishes the admin, and while that admin row exists the
  token grants nothing on any later presentation (same or different
  contact). **Operators should unset
  `infochat.adapters.simplex.admin-token` once the first admin is
  established** — standard bootstrap-secret hygiene: with no token
  configured, nothing can re-establish admin. That hygiene also closes
  the residual case where a *still-configured* token could re-arm if
  the SimpleX admin is later `/revoke-admin`'d (possible in a
  multi-adapter deployment because last-admin protection is global
  across adapters). Making single-use survive a revoke *without*
  relying on the operator unsetting the token is **future work**: it
  needs a durable token-spent marker, and because the application DB
  role is write-only on the audit log (audit-integrity
  least-privilege), that marker requires a schema change beyond this
  change's scope. Re-issuing/rotating the token and multi-admin
  issuance are likewise future work. This is the recommended
  high-assurance admin placement.

**Operator-side mitigations:**

- Run admin only on the higher-trust adapter (typically SimpleX),
  even when both adapters serve users. The bootstrap admin contact
  id is configured per adapter and is **optional per adapter** —
  an adapter may be enabled for users with no bootstrap admin
  configured; only the union of admin rows across adapters must be
  non-empty.
- Treat ephemeral SimpleX queue rotation as the routine mitigation
  for suspected exposure. Rotation is a property change plus
  restart; the audit log records the bootstrap of the new admin
  contact.
- Cross-adapter elevation is impossible by design (`/grant-admin`
  is inbound-adapter-scoped). A compromised Signal admin cannot
  grant admin on SimpleX without also compromising a SimpleX
  admin's chat session. **Cross-adapter `/invite create` is a
  separate surface and is intentionally permitted** — `/invite
  create --adapter <name>` may name any enabled adapter regardless
  of which adapter the command arrived on (per `commands.md`
  §Admin `/invite create`). The trade-off is acceptable because an
  invite code grants only **registration on the named adapter**,
  not elevation: the first contact to present the code is
  recorded under the inbound-adapter as a fresh user with no
  admin bit and the standard probation gate. A compromised
  Signal admin can therefore mint a SimpleX invite, but cannot
  use that invite path to mint a SimpleX admin. Per-adapter
  PENDING caps and the audit trail bound the cross-adapter
  invite-issuance rate. This carve-out is what the "elevation
  vs registration" distinction above turns on.
- **Cross-adapter `/recover-pool` is likewise intentionally
  permitted.** `/recover-pool <adapter> <upstream-group-id>`
  (`commands.md` §Permission model) frees an `auto_joined_group`
  slot by the supplied **target** natural key, which may name any
  enabled adapter regardless of the inbound one — a Signal admin can
  free a SimpleX residual. This is required, not incidental: the
   command's primary target is the residual join-only SimpleX group
   that has no native leave signal (so the automatic freeing
   never fires), and if SimpleX is the saturated adapter the admin may
  only be reachable on another. The trade-off rests on a *different*
  basis than `/invite create`: the free grants **neither registration
  nor elevation** — it only clears `removed_at` on a slot the bot
  already holds, and re-enabling an auto-join on the named adapter
  still requires a genuine inbound group invitation there. The act is
  bot-admin-only and audit-logged (`RECOVER_AUTO_JOINED_GROUP`). A
  single-adapter admin compromise can therefore drop another adapter's
  D47 cap count, but cannot mint membership, identity, or elevation on
  it; per-adapter re-saturation still gates on real invitations
  (the saturation-reset path; remediates the prior redteam's Finding 2).

## User ban

- Bot-wide flag with reason, actor, timestamp.
- Banned-user check is the first thing after identity resolution.
- Banned user receives one fixed reply per inbound message, regardless of                                                                                                                                                                             
  input.
- **Transport-level rate cap fires before the ban check** (and
  before every other application-level check) — see §Authorization
  model step 1.5. A banned user hitting the cap receives no reply
  at all (including no fixed ban reply) until the cap resets. This
  bounds outbound cost from a hostile banned user driving inbound
  floods that would otherwise produce a fixed reply per inbound
  message.
- **Banning a user who is a group admin.** Their `is_group_admin` rows
  remain but are unreachable. **On `/unban`, restored group-admin roles
  are explicitly disclosed** in the command's reply and in the
  audit-log entry: the reply lists every `(group_id, group_label)` for
  which `is_group_admin = true` is being reinstated, with a hint
  pointing at `/demote <contact>` for cases where the executing admin
  did not intend to restore elevated privileges. The audit row
  carries the same list under `details_json.restored_group_admin`.
  Without this disclosure, an admin who issues `/unban` for a routine
  reason can silently re-grant group-admin powers across every group
  the unbanned user previously administered, with no signal in the
  command output that this happened.
- Banning a bot admin requires `/revoke-admin` first (last-admin protection                                                                                                                                                                           
  applies).
- **Pre-ban against unknown contact.** `/ban <contact>` against a
  contact id with no existing user row creates a row with
  `is_banned = true` and **`registration_state = 'preban'`** (the
  `users.registration_state` enum is the structural marker that the
  row was minted purely for the ban and never carried a registration
  ceremony). The contact is banned even on first attempt.
- **Pre-ban → unban does NOT grant DM access.** `/unban` against a
  `registration_state = 'preban'` row **deletes the row entirely**
  rather than flipping `is_banned = false` on it. The contact's next
  DM is therefore an unknown-contact DM and routes through the
  invite-code gate (authorization step 2), as it would have without
  the pre-ban. Without this rule a pre-ban → unban sequence would
  silently bypass the invite gate, because step 2 fires only when no
  `users` row exists — once a pre-ban has minted a row, a subsequent
  `/unban` would leave the row in place and the contact would reach
  the bot on next DM with no invite ever presented. The `/unban`
  reply surfaces the deletion (`"Pre-ban-only row removed; contact
  will require a fresh invite to DM."`) so the executing admin
  understands the post-condition; the deletion is audit-logged as
  `UNBAN_PREBAN_DELETE`. Pre-ban rows that have a non-`preban`
  `registration_state` (i.e. an already-registered user later
  banned, then unbanned) are **not** affected by this rule — their
  ban flag is cleared in place and the group-admin restoration
  rule above applies.

## Invite-code registration

The invite-code system (decision D44) is the application-level entry gate for
DM access, applied uniformly across all adapters:

- A bot admin issues `/invite create --adapter <name>` with at most one of two
  mutually exclusive flags:
  - `--contact <id>` — strict invite, bound to a specific (contact\_id,
    adapter) pair. No confirmation required; risk is bounded to one identity.
  - `--open` — adapter-bound invite, not pre-bound to a contact\_id; the
    first unknown contact on that adapter to present the code is registered.
    Requires confirm (broader blast radius).
  Providing neither flag defaults to `--open` (decision D60) and passes
  through the same confirm gate; no code is created until the admin confirms.
  Malformed issuance input — an unrecognized token, a value-less `--contact`,
  or a stray bare argument — is rejected with an explicit error; no code is
  created and no confirm is armed. Providing both is an error; no code is
  created.
  The code is shown to the admin once in the reply and stored with status
  `PENDING`.
- An unknown DM contact's first message is checked against the invite table.
  For a `--contact` invite: contact\_id, adapter, and code value must all
  match, and status must be `PENDING` and not expired. For an `--open` invite:
  only adapter and code value must match; any unknown contact on that adapter
  may consume it.
- On success: user row created (probation begins per D45), code marked `USED`,
  welcome sent. The invite-acceptance is audit-logged.
- On failure: fixed "access requires an invitation" reply. No registration, no
  further processing. The drop is counted but not individually audit-logged
  (a hostile actor can trigger many drops).
- **Invite codes are single-use.** A `USED` code cannot be replayed.
- **Codes carry a TTL.** An expired code is treated as absent. A code
  expires at the instant `NOW() >= expires_at` — the boundary is
  inclusive, so a code with `expires_at = T` is expired starting
  at wall-clock time T (not T+ε). The TTL value is operator-configured
  and lives in design notes.
- **Cross-adapter isolation.** An invite bound to `(contact-id-A, simplex)`
  cannot be consumed from `(contact-id-A, signal)` — the adapter field is part
  of the match key. This prevents a code intercepted on one platform from being
  used on another.
- **Bot admin and bootstrap-seeded users are exempt** from the invite
  requirement; they are created directly by config at startup.
- **Group interaction requires prior DM registration (D47).** A user
  must have `registration_state IN ('invited', 'vouched')` to
  interact with the bot in any group. Unknown contacts in groups
  are invisible to the bot (silent drop at authorization step 3).
  The DM invite gate is the universal registration path — there is
  no group-side registration bypass. No DM-gate carve-out remains
  (the pre-D47 step 4.7 is reserved).
- **Pre-ban still works.** `/ban <contact>` against an unknown contact creates
  the user row with `is_banned=true` without requiring an invite. The ban check
  (step 4) fires before any command could succeed even if the contact later
  presents a valid invite — but in practice the pre-ban row means the invite
  check (step 2) finds a known contact and routes to the ban path instead.
  **Pre-ban revokes pending invites for the same contact.** If
  `/ban <contact>` runs while one or more `PENDING` invites exist
  for the same `(adapter, contact_id)` (either pre-bound via
  `--contact` or open-but-bound-on-consume targeting that contact),
  every such invite is transitioned to `REVOKED` in the same
  transaction as the ban (audit-logged with the ban's
  `request_id`). The intake-side ban check would block the contact
  even if a stale invite remained, but explicit revoke keeps
  `/invite list` honest and prevents an unbanned-then-rebanned
  cycle from leaving an orphan invite in `PENDING`.
- `/invite list [--page N]` shows PENDING codes with their target contact,
  adapter, and expiry. `/invite revoke <code>` transitions a PENDING code to
  `REVOKED` immediately. `/invite revoke` requires confirm.
- **Brute-force rate limit.** A per-`(adapter, contact_id)` rate limit
  applies to invite-code attempts. Failed attempts increment a counter;
  when the counter exceeds a profile-driven threshold within a
  profile-driven window, further attempts from that
  `(adapter, contact_id)` are rejected without checking the code, and
  an audit row records the threshold breach. The limit prevents a
  patient brute-force search of the UUID space; it does not change
  the per-failure user-visible reply.
- **Caps on simultaneous PENDING invites.** The system enforces two
  caps on outstanding `PENDING` codes (exact values are profile-driven
  and live in design notes):
  - A **per-adapter cap on `--open` invites**: an admin attempting to
    mint an `--open` code while the cap is met receives a friendly
    error listing the current open codes and a hint pointing at
    `/invite revoke`. Open codes have the broadest blast radius (any
    unknown contact on the adapter can consume them), so the cap is
    deliberately small.
  - A **global cap on `--contact` invites**: contact-bound codes are
    safer (one identity each) but unbounded creation is still a
    footgun. The global cap is set high enough that legitimate bulk
    onboarding works and low enough that an accidental loop cannot
    quietly create thousands of pending codes.
  Codes that are `USED`, `REVOKED`, or whose `expires_at` has
  passed do not count toward either cap. There is no stored
  `EXPIRED` status (`schema.md` §Identity and access — Invite code):
  the active-pending count query filters
  `status = 'PENDING' AND (expires_at IS NULL OR expires_at > NOW())`,
  so codes free their cap slot the instant their `expires_at`
  elapses without a state transition ever being written. The two
  caps prevent code-leakage attacks (a leaked open code consumed
  by an adversary) from compounding through bulk issuance and
  bound the operator's exposure if a single admin account is
  compromised.
- **`/invite list` disclosure.** The list output **must visually
  distinguish `--open` codes from `--contact` codes** (e.g., a
  prominent `OPEN` marker on open rows). Open codes are the
  higher-blast-radius primitive and should not blend into a long
  contact-bound list; an admin auditing exposure must be able to spot
  them at a glance.
- **`/invite pending-contacts` disclosure.** The sourcing surface for
  `--contact` invites (D60) deliberately shows the **full, un-redacted**
  `contact_id` of connected-but-unregistered contacts — a value that is
  otherwise redacted wherever it appears (logs, exceptions, the
  `/invite list` target column). The disclosure is what makes the
  theft-resistant `--contact` binding usable at the only moment it can
  bind (after the contact connects, before they register), and it is
  weighed against that anti-theft benefit and bounded: bot-admin-only,
  DM-only, scoped to the inbound adapter, paged, and sourced only from
  `invite_code_attempt` rows — contacts that already knocked on the
  bot themselves; the surface can name no one who has not. The read is
  audit-logged before the ids are returned (audit-before-effect, the
  same posture as `/pending`), so every disclosure leaves an
  operator-visible trail.
- **Pre-banned contact + invite.** `/invite create --contact <id>`
  against a contact whose `users` row already has `is_banned=true`
  returns a friendly error pointing the admin at `/unban`; **no
  invite is created**. The intake-side ban check (authorization
  step 4) is the second line of defense — even if a stale invite
  exists, the ban check fires first — but refusing to mint the
  invite at all keeps the audit trail clean.

## Slow-start tier

Every newly registered user enters a probation period (decision D45). The
duration is profile-driven (value in design notes). During probation:

- **Allowed** (read-only subset plus the user's own privacy/locale
  levers): `/help`, `/status`, `/get-tags`, `/get-sources`,
  `/list-sources`, `/summary`, `/saved`, **all operator-configured
  asset commands** (every top-level asset command registered via
  `bootstrap-assets.json` per D39 — read-only public-endpoint
  reads with negligible cost; the allowlist is "the asset-command
  family," not a fixed enumeration, so adding a future asset to
  `bootstrap-assets.json` does not require a security.md
  amendment), `/export`, **`/forget`** (the user's privacy lever —
  blocking it during probation would undermine D37), **`/lang`** (a
  single-row UPDATE with no LLM cost — blocking it means a non-English
  new user cannot get help in their language during the window when
  they most need it).
- **Blocked**: chat mode, `/add-source`, `/save`, `/unsave`,
  `/follow-tag`, `/unfollow-tag`, `/clear`, `/compress`,
  `/group-timezone`, `/retry` (LLM-invoking write), and any admin
  command. `/stop` is **not blocked** — it returns the standard
  idempotent no-op reply during probation regardless of in-flight
  state, because it has no side effect (a probation user has no
  in-flight LLM work to cancel since chat mode and `/retry` are
  blocked, and the no-op reply is the same whether probation is
  in effect or not).
- Blocked operations return a friendly reply stating when full access unlocks;
  the reply never reaches the LLM or any write path.
- After the probation window elapses, the user is automatically promoted to
  full access — no admin action required. The mechanism is **lazy**: the
  permission check is `probation_until IS NULL OR probation_until < NOW()`.
  The user is promoted at the instant `NOW() > probation_until`, regardless
  of whether the column has been nulled. A passive sweep clears the column
  on the next request from a promoted user; no background job is required.
- A bot admin can issue `/vouch <contact>` at any time to immediately graduate
  a user from probation. The vouch is audit-logged.
- Probation state is a single `probation_until` timestamp on the `users` row.
  `NULL` means full access. Checking it is a single indexed read in the
  permission step, adding no measurable latency.

## Quarantine workflow

- Every Stage 1 or Stage 2 hit creates a quarantine row holding span
  offsets, a placeholder id, the verbatim original, and a review
  status `∈ {PENDING, BENIGN_CLOSED, APPROVED, REJECTED}`
  (`schema.md` §Posts and derivatives).
- Posts with PENDING quarantine entries can still be visible to users
  (with Stage 1 redactions in place). A Stage 2 `BENIGN` verdict keeps
  the post visible with **Stage 1 redactions retained** — the verdict
  transitions the quarantine row from `PENDING` to `BENIGN_CLOSED` but
  does **not** lift redactions. `BENIGN_CLOSED` is the durable signal
  for "Stage 2 cleared this; redactions remain until admin chooses to
  approve." A Stage 2 `INJECTION`, `MALWARE`, or `UNKNOWN` verdict
  hides the entire post (`QUARANTINED` status); the quarantine row
  stays `PENDING` (subject to admin review and the admin-review TTL).
- **Redactions are lifted only by `/quarantine approve`.** This rule
  applies uniformly to first-pass and re-evaluation BENIGN verdicts:
  a re-eval BENIGN does not auto-lift first-pass redactions either.
  An admin reviewing the quarantine row is the only path that
  restores the original span. This is the safer of the two
  verdict-vs-redaction interpretations and avoids the "first pass
  keeps redactions, re-eval lifts them" inconsistency.
- Admins review via `/quarantine list` and `/quarantine approve|reject`.
  `/quarantine list` defaults to `PENDING` rows only — the active
  review queue; `BENIGN_CLOSED` rows are not surfaced unless an admin
  passes `--all` (forensic / audit view). Approve transitions
  `PENDING → APPROVED` (or `BENIGN_CLOSED → APPROVED`), restores the
  original span, and **fires `NOTIFY new_post`** for the post (so the
  Provider re-renders the now-unredacted body via the standard
  high-water-mark path — `architecture.md` §Inter-service
  communication); reject transitions `PENDING → REJECTED` (and on
  `BENIGN_CLOSED` rows the same forensic rejection is reachable,
  leaving the placeholder permanent). The Provider DB role
  (`security.md` §DB roles) does not have `SELECT` on the raw
  original column; approve and reject run as **stored procedures**
  (`approve_quarantine(quarantine_id, actor_id)` and
  `reject_quarantine(quarantine_id, actor_id)`) that internally
  read the original under the procedure's elevated rights and
  perform the restore + audit-log + NOTIFY in one transaction. The
  Provider role has `EXECUTE` on these procedures, never `SELECT`
  on the underlying raw-original column.
- The verbatim original is intentionally **not** displayed in chat
  (could re-inject in the admin's client). Operators use `psql` with
  the admin role on the rare occasions it's needed.
- The placeholder format is the spec-committed marker
  `[REDACTED:<id>]` (`security.md` §Ingest pipeline). The
  surrounding brackets and `REDACTED:` literal are fixed; the
  `<id>` token is per-row randomized so attackers cannot pre-craft
  a fake placeholder that would survive the Stage 1 marker strip.

## Failure handling

The split between *verdict* and *infrastructure failure* is the heart of
the policy (decision D22). Per stage:

**Schema-violating LLM output** (wrong JSON shape, unexpected label value,
missing required field) is treated identically to an unparseable reply at
every stage: retry once, then apply the stage-specific failure path below.

- **Stage 2 verdict** of `BENIGN` → post released to the tagger and
  embedding stage; Stage 1 redactions remain in the body (quarantine
  rows transition `PENDING → BENIGN_CLOSED`, not deleted — the
  original text is restorable only via admin `/quarantine approve`).
  **Re-evaluation BENIGN follows the same rule:** redactions are not
  auto-lifted on re-eval, only on `/quarantine approve`. See
  §Quarantine workflow and §Re-evaluation job.
- **Stage 2 verdict** of `INJECTION`, `MALWARE`, or `UNKNOWN` → post
  stays `QUARANTINED` until admin review. The judge model treating
  `UNKNOWN` as a soft injection signal is intentional: a degraded judge
  must never auto-release.
- **Stage 2 infrastructure failure** (LLM unreachable, timeout, unparseable
  or schema-violating reply after retry) → release as `READY` with the
  **Stage 1 redactions retained**, mark the post for re-evaluation (see
  Re-evaluation job below), notify admin via the throttled channel. A
  profile-driven flag lets production profiles invert this default and keep
  the post quarantined.
- **Stage 1 infrastructure failure** (regex watchdog crash, HTML sanitizer
  exception) → fail-closed: the post is immediately `QUARANTINED` and never
  auto-released. Admin is notified via the throttled channel. Stage 1
  infrastructure failure must never default to release — the deterministic
  guard failing is a safety-critical event.
- **Fetcher failure** (HTTP error, connection timeout, feed parse failure on
  an HTTP-shaped source) → retry on the next scheduled tick (decision D42).
  After *N* consecutive per-source failures (profile-driven), the source
  `status` transitions to `'failed'` and the scheduler skips it; a
  throttled admin notification is sent with the error class and source
  id. Other sources are unaffected. A fetch-failure park is then
  re-probed automatically on exponential backoff and restored to
  `active` on the first success; after the absolute re-probe cap it is
  terminally parked and only an explicit admin re-enable
  (`/source-enable`) revives it (D42 as amended by M1-752). Parks
  written by the per-source UNKNOWN-rate auto-disable (below) and by
  D38's cycle cap are excluded from re-probe — they recover only by
  admin action, distinguished via the park-reason discriminator
  (`schema.md` §Sources and tags), which is written only by the
  guarded condition that parks the row, is never downgraded from
  manual-only to fetch-failure, and is fail-closed when absent or
  unrecognized. The UNKNOWN-rate control may also **upgrade** a row
  the fetch ladder already parked: its candidate selection covers
  parked-and-re-probe-eligible rows, not only `active` ones, so a feed
  cannot escape the control by failing its way into a `fetch-failure`
  park first and then flooding UNKNOWN verdicts. Because that upgrade
  can land while a probe against the same row is in flight, the
  restoring UPDATE re-checks the full eligibility predicate (status,
  park reason, `deleted_at`, cap) in its own `WHERE` and no-ops when
  the row no longer qualifies — an automatic restore must never clear
  a park reason a security control wrote during the probe window. Automatic restores select on `deleted_at IS NULL`
  (a soft-deleted source is never probed or revived) and write an
  `audit_log` row for the transition in the same transaction as the
  UPDATE — the coalesced RECOVERED notification is not a substitute,
  since `audit_log` is the append-only, `/audit`-readable record. D42 is the HTTP-shaped mirror of
  D38's per-relay degradation commitment for stream sources.
- **Tagger** failure → fall back to `source.bootstrap_tags`, mark the post,
  throttled admin notify. (This is why `/add-source --tags` is mandatory:
  every source must have a deterministic fallback.) A clean empty proposal
  (`{"tags":[]}`) is an outcome, not a failure (`llm.md` §Failure
  handling), so it never fires this path — and a tagger answering empty
  to EVERY post would otherwise emit no signal at all: a sustained
  all-empty tagger output (the no-tags share of recent completions
  exceeding a configured threshold over a minimum sample) surfaces a
  throttled admin alert under the distinct error class
  `tagger.sustained_no_tags`, so a wholly non-functioning tagger stage
  remains a spec-committed observable condition.
- **Entity** failure → release without entities; cross-source linking
  degrades to embedding-only for that post (or skipped entirely if
  embedding also failed).
- **Embedding** failure → release without a vector; the post is otherwise
  normal and fully visible.
- **Compression failure (manual `/compress` or auto-compress).** LLM
  unreachable, timeout, or schema-violating reply after retry → the
  chat session is **held at the ceiling**: the user's next chat-mode
  message returns a localized friendly error
  ("memory checkpoint pending; please `/compress` manually or try
  again later"), and the session is never silently truncated.
  Manual `/compress` failure surfaces the same error and leaves the
  session unchanged. The escape hatch is `/clear` (which discards
  the live window — the user's choice, not the system's). Auto-compress
  fires when the chat session occupies a profile-driven percentage of
  the context-window ceiling (value in design notes).
- **Admin notifications** are coalesced per `(channel, error_class)` for a
  short window so an outage produces one summary message, not 200 individual
  alerts.

**Provider-side (user-facing) LLM failures.** The Provider's
LLM-invoking surfaces — chat-mode replies, on-demand `/summary`
prose generation, `/retry` re-rolls — degrade with the following
rules:

- **`/summary` (and `/retry --digest`) summarizer unreachable** →
  fall back to the headlines + URLs + post UIDs degraded form (the
  same fallback as a saturated periodic digest per decision D17).
  No prose, deterministic post selection unchanged. The friendly
  notice is a localization-bundle string (D43); the user is not
  shown a hung response. `/retry` after recovery re-rolls the
  prose with the original frozen post selection. See
  `commands.md` §Content (`/summary`).
- **Chat-mode replies** with the chat-agent LLM unreachable →
  return a localized "chat assistant is unavailable, try again
  later" friendly error from the bundle (D43); the turn is
   discarded with no `chat_session` advance, no `chat_memory`
   write, and no **model-initiated** tool call. The one exception is
   the deterministic digest-first semantic pre-fetch, which
   runs once before the LLM call by design (the D28 "always runs,
   folded in" pattern): on an LLM-unreachable turn that read-only
  retrieval may already have executed (one local embed + one
  scoped pgvector probe) before the failure surfaced. It is
  bounded — read-only, capped in time (the pgvector probe by
  `statement_timeout`, the embed HTTP call by
  `infochat.embeddings.timeout-ms`), and gated by the same per-user
  LLM rate bucket as the turn itself — and it writes nothing, so
  the "discard the turn" guarantee above is unaffected. **Query
  anchoring (M1-746, D58):** for a non-English scope the pre-fetch
  additionally fires ONE generative `ModelTask.TRANSLATOR` call
  (the query-anchoring leg, §Rate limiting) before the embed — it
  draws no per-user bucket token (the bucket counts turns, not
  generative calls inside them), is bounded by the translator
  transport timeouts and the per-endpoint circuit breaker (below,
  §Failure handling), falls back to the raw query text on any
  failure, and is cached per (scope, query, language) so a repeat
  of the same text costs nothing. Once the
   chat endpoint's circuit breaker (below) is OPEN, the pre-fetch is
   **skipped entirely** — no translation, no embed round-trip, no
   pgvector probe —
   so an outage window pays that bounded cost at most once per
    breaker cycle: only the failures that precede the trip (and the
    cooldown-expiry probe turn, which legitimately needs its
    grounding) run it.
- **Fail-fast on a known-unreachable provider (circuit breaker).** An in-memory circuit breaker keyed by resolved provider
   endpoint (base-url; all tasks routed to one endpoint share its
  state, matching the D56 one-LLM-service topology — the embedding
  endpoint is tracked separately) guards every LLM/embedding
  transport call. After a configured count of CONSECUTIVE
  transport-unreachable failures (connection refused, DNS failure,
  no route, connect/read timeout) the endpoint's breaker trips OPEN
  and subsequent calls short-circuit with the typed
  provider-unreachable signal **without an HTTP attempt**; after a
  configured cooldown a single probe is admitted — success closes
  the breaker, failure re-opens it. Only transport failures trip
  it: an application error (non-2xx status, over-cap body,
  unparseable reply) proves the endpoint answers and counts as
  reachable, and downstream non-LLM failures never reach the
  breaker (attribution happens at the provider-call boundary).
  Threshold and cooldown are `infochat.llm.breaker.*` properties;
  the shipped defaults (threshold 3, cooldown 30s) apply to all
  profiles. State is in-memory only — a restart resets to CLOSED
  and the first call re-probes. Fail-fast changes WHEN a doomed
  call fails, never WHERE it goes or how the task degrades: the
  short-circuit surfaces as the same failure the consumer already
  handles.
- **No router-side fallback in v1.** When an operator configures
  per-task providers (e.g. Anthropic for SUMMARIZER, Ollama for
  SECURITY_JUDGE), a per-task provider that is unreachable
  degrades **only that task** to its task-specific failure path
  above; the router does NOT silently switch to a different
  configured provider. The circuit breaker above is fail-FAST, not
  fail-OVER: an OPEN breaker short-circuits the doomed call, it
  never re-routes it — the task's degrade path is identical with
  the breaker tripped or not. Operators who require high
  availability for a per-task LLM must over-provision that
  provider; v1's per-task routing is a single resolution per call,
  not a fallback chain. Adding a fallback chain is a v2 candidate
  (`llm.md` §Per-task routing rules).

A complete LLM outage degrades quality, not safety.

### Re-evaluation job

Two kinds of posts feed the re-evaluation queue:

1. Posts released with Stage 1 redactions retained because of a
   **Stage 2 infrastructure failure** — these are `READY` and visible
   with redactions, awaiting a healthy verdict that may close the
   quarantine cleanly.
2. Posts marked **UNKNOWN** by Stage 2 — these are `QUARANTINED`
   (hidden) but the verdict is "judge couldn't classify," not
   "judge classified as hostile." Periodic re-eval gives a
   recovered or improved judge a chance to produce a definitive
   verdict before admin-review escalation.

The Collector runs a background job on a profile-driven cadence
(value in design notes) that re-submits these posts to Stage 2.
A per-post attempt counter bounds retries; the **infra-failure**
class and the **UNKNOWN** class carry **separate, independent
caps** (UNKNOWN's cap is the lower of the two so an UNKNOWN-flooding
model exhausts attempts faster than infrastructure failures).
After cap exhaustion the post transitions to `NEEDS_REVIEW`
(per `schema.md` §Posts and derivatives) and the admin notifier
fires.

Re-eval verdict handling:

- `BENIGN` on a Stage-2-infra-failure post → quarantine row
  transitions `PENDING → BENIGN_CLOSED`, **Stage 1 redactions are
  not lifted** (only `/quarantine approve` lifts them — this matches
  the §Quarantine workflow rule above), the post continues through
  tagger and embedding if those stages had not already run. The
  `stage2_failed` cursor flag is **cleared** on this transition:
  the post now has a clean Stage 2 verdict and the cursor returns
  to its non-failed state. (Schema invariant 5: per-stage flags
  are the durable cursor for in-flight evaluation.)
- `BENIGN` on an UNKNOWN post → post transitions
  `QUARANTINED → READY` with Stage 1 redactions retained and the
  quarantine row transitions `PENDING → BENIGN_CLOSED`; same rule
  as above for lifting (admin only). **The transition is
  audit-logged** as `RE_EVAL_RELEASED` with `actor='re_eval_job'`,
  `target_kind='post'`, `target_id=<post_uid>`, and
  `details_json={ prior_verdict, new_verdict='BENIGN', attempt }`,
  and a throttled admin notification fires (coalesced per
  `(channel, 're_eval_released')` on the same window as other admin
  notifications). Without this, posts auto-released from
  `QUARANTINED` after an UNKNOWN-then-BENIGN re-eval reach users
  with no human reviewer ever having seen the row — an attacker who
  crafts content that initially looks UNKNOWN to the judge but
  flips to BENIGN on a model swap or warm-up could otherwise quietly
  harvest user-visible state without an admin signal.
- `INJECTION`, `MALWARE`, or `UNKNOWN` on either class → post stays
  `QUARANTINED`, the `stage2_failed` flag is **preserved** (or set,
  if the prior verdict was UNKNOWN) alongside the new verdict, and
  the attempt counter increments.

**Throttled NEEDS_REVIEW notifications.** Admin notifications for
`NEEDS_REVIEW` transitions are coalesced per
`(channel, error_class)` over a profile-driven window so a Stage-2
outage that exhausts retries on hundreds of posts produces one
summary notification, not hundreds — mirroring the throttling
already in place for Stage 2 infra-failure notifications. Sustained
high UNKNOWN rate also triggers the operator alert
`Stage2UnknownRateHigh` defined in design notes.

**Per-source UNKNOWN auto-disable.** A source whose Stage 2 UNKNOWN
rate exceeds a profile-driven threshold over a profile-driven rolling
window has its `source.status` transitioned to `'failed'` (the same
status used for consecutive HTTP failures, decision D42 — but
**excluded from D42's automatic re-probe rung**: this park is a
security control, its park-reason discriminator marks it manual-only,
and recovery happens only via `/source-enable`),
the scheduler skips it on subsequent ticks, and a throttled admin
notification fires citing the source id, the observed UNKNOWN rate,
and the threshold. Because D42's re-probe rung makes recovery rights
depend on which control parked the row, this evaluator's candidate set
is **not** restricted to `status='active'` rows: it also covers rows
already parked with a re-probe-eligible reason, upgrading them to the
manual-only `unknown-rate` reason. Otherwise a feed that failed its way
into a `fetch-failure` park before its UNKNOWN verdicts landed would be
auto-readmitted on a timer by the very ladder this control overrides.
**Auto-disable only blocks new ingest** — it stops
the fetcher or stream-source worker from enqueueing new posts from
the source. Posts already in the outbox or re-evaluation queue
**continue through their current evaluation stage** unaffected; the
auto-disable affects only future picks from that source's feed. This bounds the **quarantine-exhaustion** attack
surface: an adversary controlling a feed (or able to inject into
one) cannot drown admin review capacity by crafting borderline
content that consistently triggers UNKNOWN — the system shifts the
cost from "admins must triage every post indefinitely" to "admins
re-enable a single source after diagnosis." The per-source cap is
independent of the global `Stage2UnknownRateHigh` alert: the global
alert fires on aggregate ratio (and can be evaded by mixing
attack content with legitimate content from other sources), while
the per-source cap fires on per-source ratio (which the attacker
cannot dilute without losing control of their own input). An admin
explicitly re-enables the source via `/source-enable <id>` after
diagnosis. This is the **only** recovery path for an UNKNOWN-rate
park: unlike an HTTP-failure park, which D42's amended ladder re-probes
automatically, this one is manual-only for as long as its park reason
stands — the control exists precisely to force a human diagnosis
before the feed is readmitted.

**Absolute NEEDS_REVIEW depth alert.** Operators also see an
absolute-depth alert when the `NEEDS_REVIEW` queue exceeds a
profile-driven threshold, **independent of any per-source ratio**.
This guarantees the operator notices a sustained backlog even if
the per-source UNKNOWN rate stays below the auto-disable threshold
across many sources simultaneously (the "many small fountains"
attack shape that ratio-based alerts miss).

## Rate limiting

Per-user token buckets bound, grouped explicitly so commands that
share a cost profile share a bucket:

- **Parser-only + DB-read paginated commands** — `/help`,
  `/status`, `/list-sources`, `/get-sources`, `/get-tags`,
  `/saved`, `/audit`, `/export`, `/quarantine list` and similar.
  One bucket; high cap; cheap.
- **Asset commands** — `/zcash`, `/monero` and friends. Share a
  cache-hit bucket (most calls within a freshness window are
  served from cache, so the limit guards against a flood that
  forces refetches).
- **`/add-source`** — its own bucket (encourages bulk via
  bootstrap JSON; surface for adding many sources in a short
  window).
- **Chat-mode message rate (transport-level)** — bounds inbound
  message volume regardless of cost.
- **LLM-triggering operations** (chat replies + on-demand
  `/summary` + `/retry` re-rolls) — its own bucket, capped lower,
  profile-driven. Transport rate is intentionally higher than this
  cap so a flooding user gets quick reject replies without burning
  the only LLM slot.
- **Query-anchoring translation (M1-746, D58)** — a disclosed
  exception to the "LLM-triggering operations" bucket: a non-English
  scope's retrieval issues generative `ModelTask.TRANSLATOR` calls —
  one per turn for the D28 pre-fetch, plus one per DISTINCT
  model-elected `semanticSearch` query — that draw NO per-user bucket
  token (the bucket counts turns, not generative calls inside them).
  Accepted v1 posture, stated rather than hidden: the per-turn
  tool-call cap bounds model-elected volume, every call is a small
  (~100-token) prompt, the
  translation leg is cached per (scope, query, language) so repeated
  queries cost nothing, and the shared breaker bounds outage
  behaviour. The call goes to whichever backend `ModelTask.TRANSLATOR`
  resolves to, which **may be remote** — unlike the embedding leg,
  which D54 pins local, nothing constrains this task's locality, so
  the cost bound is the remote provider's limits when it is routed
  remotely. What that exposes is disclosed under §Secrets handling. `cs` is enabled today, so this is the standing posture
  for the current user base, not a future trigger: the per-call
  generative budget the bucket shape cannot express would need a
  tool-path-shaped rate limit (the bucket lives on the inbound
  router's per-turn path) and is a follow-up decision when usage
  warrants. The leg shares `ModelTask.TRANSLATOR` with the ingest and
  presentation translation legs ("shares today", design 05 §5.4.6),
  so the greedy temperature-0 emission applies to all three — a
  determinism improvement, disclosed here and in the design doc.
- **Per-user interruptible concurrency** — not a rate
  bucket: a ceiling on one sender's CONCURRENT interruptible
  requests (the D35 interruptible class: chat replies, on-demand
  `/summary`, `/retry` re-rolls except `--digest`; queued +
  running) across all scopes, so group membership cannot let a
  single sender occupy every dispatch worker at one instant — the
  per-minute bucket bounds rate, this bounds share. `/retry
  --digest` is deliberately outside the cap (D61): D35 non-interruptible,
  dispatched inline on the transport thread, it can never take a
  pool worker, self-serializes to at most one concurrent call, and
  stays metered by the per-minute bucket — so a sender at cap can
  still hold one inline digest re-roll (accepted, 2026-07-17
  redteam). Checked at intake before any token draw or slot
  acquisition; a rejection is a fixed reply and consumes nothing.
  The reply lands in the scope the over-cap request arrived in, so
  in a group it necessarily reveals that the sender has concurrent
  bot activity in other scopes (accepted: metadata-only,
  self-triggered — no third party can probe another user's count —
  same class as the per-user rate-cap reply). Bounds share, never
  order — the per-user fair scheduler stays deferred
  (`docs/design/06-messaging.md` §6.3.7).
- **Tool calls per chat turn** — fixed cap. Tool results are cached
  within a single turn so identical calls don't re-query.
- **`/quarantine approve`** — per-admin bucket.
- **Per-group reply rate (D47)** — a single bucket per `groups` row
  bounding total outbound replies (fixed or command) within a
  sliding window. Applies to ALL approval states (pending,
  approved, rejected) so outbound cost is bounded even for
  unapproved groups. The per-user transport cap (step 1.5)
  fires before this; the per-group cap is the aggregate
  backstop. Profile-driven (values in design notes).
- **Per-group command rate (D47)** — a sub-bucket per approved group
  bounding total command volume from all members within a
  sliding window. Only meaningful for approved groups (pending/
  rejected groups never reach command dispatch). When the cap
  fires, the reply is a fixed "this group has reached its
  command rate limit" message. Profile-driven.
- **Per-group LLM rate (D47)** — a separate sub-bucket per approved
  group bounding LLM-triggering operations (chat replies +
  on-demand `/summary` + `/retry` re-rolls) across all group
  members. The per-user LLM cap fires first; the per-group cap
  is the backstop for groups with many active members. Profile-
  driven. Periodic digests do NOT count against user-initiated
  per-group LLM budget (they are system-initiated; the aggregate
  system LLM budget is the backstop for digest cost).

Exact numbers are profile-driven (decision D27) and live in
`docs/design/04-security.md` §4.9.

## DB roles

Three Postgres roles, least-privilege (decision D34):

- **Collector role** — `INSERT/UPDATE` on ingest-owned tables
  (including `asset_config`); **`INSERT`-only on `price_snapshot`**
  (snapshots are immutable once written, `schema.md` §Operational —
  no `UPDATE`); `SELECT` on the rest; `INSERT`-only on `audit_log`;
  `LISTEN/NOTIFY`.
- **Provider role** — write access on user-state tables, but **not on
  the privilege columns of the identity/authz tables**: per V62 (V68
  added `groups.digest_mode`) the
  Provider holds `SELECT` on `users`, `groups`, `group_membership` and
  `invite_code` plus a **column-scoped** `UPDATE` (`users.probation_until`,
  `users.save_count`; `groups.timezone`, `groups.digest_enabled`,
  `groups.digest_mode`, `groups.removed_at`;
  `group_membership.removed_at`) and a
  **column-scoped** `INSERT` (`groups (adapter, upstream_group_id,
  activated_by)`; `group_membership (group_id, user_id)`), and nothing
  else — no `INSERT` on `users`, and no `INSERT` or `UPDATE` at all on
  `invite_code`. This is the same reasoning V31 applied to `source`,
  carried to the tables where a foothold's payoff is full compromise:
  `users.is_admin`, `users.is_banned` and its ban metadata,
  `users.registration_state`, `groups.approval_status` and
  `group_membership.is_group_admin` are not **directly** writable by the
  role — the raw `UPDATE`/`INSERT` a SQL-injection foothold would reach
  for fails with `insufficient_privilege` — and every legitimate
  transition on those columns runs through a narrow, single-purpose
  `SECURITY DEFINER` routine the Provider holds `EXECUTE` on (V62). This
  is defense in depth *behind* the control that actually prevents the
  injection: all Provider SQL is parameterized, and the module-6 audit
  found no injectable statement in the codebase. What the column
  revocation buys, and what it does **not**, stated without overstatement
  (the residuals are deliberate):
  - **Genuinely closed.** The *arbitrary* privilege write is gone, and
    ban, unban, `groups.approval_status`, and forcing
    `registration_state = 'vouched'` now require a caller that names a bot
    admin through the `infochat.actor_id` GUC. These **admin-gated**
    routines resolve their actor from that GUC and check `is_admin` only,
    so against an attacker who already controls Provider SQL the gate
    raises the bar — they must name some admin's id, read via the retained
    `SELECT` on `users` — without being a true boundary. The control that
    bites is the column revocation, not the actor check.
  - **Not closed — three ungated conduits remain callable by the role.**
    The **system-actor** routines carry no DB-side actor gate, because
    each runs on a path with no human actor to name, and three of them
    each re-open exactly one transition the surrounding Java otherwise
    gates: `bootstrap_ensure_admin` mints or promotes a bot admin for any
    `(adapter, contact)` **at any time** — not first-admin-only, and with
    no `BOOTSTRAP_ADMIN` audit row (that row is written by
    `AdminBootstrap`, not the routine); `auto_promote_group_admin` grants
    group-admin on any group whose admin slot is free, skipping the D47
    eligibility rules that live in `GroupAutoPromoteService`; and
    `insert_invited_user` creates a registered `invited` row with no
    invite code presented. (`claim_simplex_admin` is the exception — it
    self-gates on `WHERE NOT EXISTS (… is_admin = TRUE)`.) So for the
    admin-mint and group-admin-grant goals V62 is a **narrowing** — the
    only remaining paths are these named, single-purpose routines rather
    than an arbitrary `UPDATE` — not an elimination. Removing these grants
    is not possible without breaking the bootstrap path the weak Provider
    role must reach at every start; fully closing them would require
    running admin bootstrap under a higher-privilege connection, a
    separate architectural change the Provider's "never hold owner
    credentials" rule currently precludes. Also: `SELECT` on
  collector-owned tables (including **`SELECT`-only on
  `price_snapshot`** and **`SELECT`-only on `asset_config`**: the
  Provider reads the latest snapshot per `(asset, sub_verb)` for
  `/zcash` and `/monero` and reads `asset_config` to gate `/help`,
  parse sub-verbs, and surface stale-data warnings; never writes to
  either); the source-management commands are the documented write
  exception to "`SELECT` on collector-owned tables" — per V31 the
  Provider holds `INSERT` on `source` and `tag` plus a **column-scoped**
  `UPDATE` on `source` (`status`, `consecutive_failures`, `deleted_at`,
  `deleted_by`, `bootstrap_tags` only; identity columns stay read-only so
  a Provider SQL-injection foothold cannot repoint a trusted source) for
  the deterministic `/add-source` / `/enable-source` / `/disable-source` /
  `/remove-source` commands. **The column list is a closed enumeration
  that grows only by explicit, column-scoped extension.** When a new
  `source` column must be written by one of those commands — as D42's
  park-reason discriminator and re-probe state are, since
  `/source-enable` resets them — the grant is extended by naming that
  column, the enumeration above is updated in the same change, and the
  identity columns (`kind`, `identifier`, `display_name`, `category`,
  `added_by`) stay revoked. A blanket `GRANT UPDATE ON source` is
  forbidden: it is the shortest way to make a failing migration test
  green and it silently hands a Provider SQL-injection foothold the
  ability to repoint a globally-shared, D7-trusted bootstrap source at
  attacker content under its original display name. `DELETE` stays
  revoked regardless (invariant 4, soft-delete only); `SELECT` on the quarantine review *view* (no
  raw original content); **`SELECT` on the redacted `audit_log_view`,
  not on `audit_log` itself** (`/audit` reads through the view, see
  below); `INSERT`-only on `audit_log`; `EXECUTE` on the
  `approve_quarantine` and `reject_quarantine` stored procedures
  (no `SELECT` on the raw-original quarantine column);
  `LISTEN/NOTIFY` (consumes `new_post` and `quarantine_review`
  channels per `architecture.md` §Inter-service communication).
- **Admin role** — the operator's least-privilege principal; never a
  service login. The role is `NOLOGIN`: it is a privilege bundle
  operators attach to via a personal LOGIN role granted membership
  (`GRANT` the admin role `TO` the operator's own login role), so no
  shared admin credential exists and psql actions stay attributable
  to a person. Granted surface: `SELECT` on the redacted
  `audit_log_view` (routine operator audit reads pass through the
  same redaction the Provider's do); `EXECUTE` on `approve_quarantine`
  / `reject_quarantine`; `SELECT` on the quarantine table (raw
  original inspection); `DELETE` on `heartbeat` rows; `TRUNCATE` on
  `invite_code_attempt` (the only purge path); hard-`DELETE` on
  `source` (the Invariant 4 escape hatch below). Ownership-level
  operations — partition drop (Invariant 6 retention), disabling the
  `audit_log` append-only trigger for retention sweeps, unredacted
  forensic reads of `audit_log`, and migrations — are intentionally
  NOT granted: Postgres cannot `GRANT` ownership-gated actions to a
  non-owner, so they remain owner-role (superuser psql) actions until
  a partition-rotation/retention feature exists to carry a definer
  wrapper.

**`audit_log_view`** is a Postgres view that exposes the same columns
as `audit_log` minus any redacted fields (raw secrets, full contact
ids — replaced with the redacted form per §Secrets handling).
`SELECT` on `audit_log_view` is granted to the Provider role (the path
`/audit` uses) and, per V43, to the operator Admin role — both routine
audit-read paths pass through the same redaction. Granting `SELECT`
directly on `audit_log` to the Provider would expose unredacted columns;
the view is the single read path for the Provider role.

The split means a SQL-injection bug in the Provider cannot delete
posts, mutate price snapshots, alter quarantine entries, read
unredacted audit rows, or read raw quarantine originals.

**Invariant 4 enforcement.** `DELETE` on `source` is **revoked**
from both Collector and Provider roles; only the Admin role
(operator psql) can hard-delete a source row, and that path is the
manual escape hatch that backs invariant 4 (soft-delete only for
sources). Application code uses the soft-delete column.

## Secrets handling

- LLM API keys are read from environment variables, not the DB.
- The post-setup `prod/switch-llm.sh` backend switcher records the LLM API key in
  `secrets.env` through the same dotenv-escaped channel and prints a per-task
  privacy disclosure naming exactly which generative tasks now call a remote
  provider and what each exposes — `chat` (private user messages) flagged loudest,
  the ingest tasks (`security`/`tagger`/`entity`/`classifier`) as topic-interest exposure over
  public posts — see `SETUP_GUIDE.md` §"Switching your AI backend later".
- **`translator` carries private user messages too (M1-746, D58).** The
  query-anchoring leg (§Rate limiting) sends the user's search query to
  `ModelTask.TRANSLATOR`, and on the D28 pre-fetch path that query IS the
  user's raw chat message (truncated, not redacted). For a scope on a
  non-English `/lang`, routing `translator` to a remote provider therefore
  exposes private user messages, exactly as `chat` does — it is not the
  bot-prose-only exposure the presentation and ingest translation legs
  imply. The disclosure text that `prod/switch-llm.sh` prints at switch
  time is updated to match by M1-758.
- The translation of that query is retained in an in-memory cache for 24h
  (per (scope, query-hash, language), M1-746). This is **user-authored
  content**, so it is deliberately outside the premise of the
  presentation-cache residual noted under §What's intentionally NOT in v1
  — no minimization lever reaches it: `/forget` clears chat memory, not
  this cache, and eviction is by TTL and capacity only. Accepted for v1
  because the store is process-local, never persisted, and bounded; a
  `/forget` that also drains the caller's translation entries is the
  follow-up if the retention window is judged too long.
- Audit-log writes pass through a redaction hook that masks values
  matching a **closed catalogue of API-key shapes**. The catalogue's
  v1 baseline (spec-level commitment) is:
  - OpenAI-style `sk-…` (and the long-form `sk-proj-…`, `sk-svcacct-…`).
  - Anthropic `sk-ant-…`.
  - GitHub `ghp_…`, `gho_…`, `ghu_…`, `ghs_…`, `ghr_…`.
  - AWS access keys: `AKIA[0-9A-Z]{16}` and `ASIA[0-9A-Z]{16}`.
  - Google API keys: `AIza[0-9A-Za-z_-]{35}`.
  - Slack `xox[abprs]-…`.
  - Generic 32+-character hex / base64 strings adjacent to the
    case-insensitive substrings `api[_-]?key`, `secret`, `token`,
    `password`, `bearer`.
  The exact regexes, locale-folding rules, and the test corpus that
  feeds the redactor unit tests live in
  `docs/design/04-security.md` — adding a shape to the catalogue
  is a design-note edit, **removing** a shape from the spec
  baseline is a spec amendment so the audit redactor cannot silently
  weaken across versions. The redactor is fail-closed on regex
  timeout (the same `java.util.regex`-plus-watchdog discipline as
  Stage 1, see §Ingest pipeline): a timed-out match treats the
  whole field as redacted rather than emitting it raw.
- Stdout console logs pass through the closed API-key catalogue
  redactor, fail-closed on regex timeout (whole message replaced with
  a fixed sentinel). The audit_log writer consumes the same Redactor
  utility so the two cannot drift.
- Contact IDs are logged in redacted form (prefix + ellipsis + suffix)                                                                                                                                                                                
  outside the audit log.
- **User-content logging.** `chat_memory` content, `saved_post` bodies
  and annotations, and the bodies of inbound chat-mode messages never
  appear in non-audit logs, at any log level (decision D37). Stage
  events, request IDs, scope IDs, and counts are loggable; the prose
  itself is not. The audit log records *intent* (command name, actor,
  scope, target), not user-authored prose.

### User content in exceptions

Exception messages and stack traces emitted via the application logger
MUST NOT contain user-authored prose (chat-mode message bodies, post
bodies, saved-post annotations, command arguments). The application
provides a `SafeLog` utility that drops the exception message body,
retains only the exception class name, and truncates the cause chain
to class names (depth-capped at 5). The original `Throwable` is never
passed to the underlying SLF4J logger.

`SafeLog` also applies the closed API-key catalogue redactor to the
caller-supplied message so an API key embedded in the log message is
caught by the same mechanism as regular log lines.

**Known framework-level logging risks not closed by this mechanism.**
The following framework-level logging paths can emit user content or
secrets if their log level is raised above the production baseline:

- **Hibernate parameter trace** (`org.hibernate.SQL` and
  `org.hibernate.type` at DEBUG/TRACE): emits SQL bind parameters
  including `chat_memory.content` values.
- **HTTP client body trace** (RESTEasy / Vert.x client logging at
  TRACE): emits request and response bodies which may carry API keys
  or user content.

These are operator-side risks. The production logger-level baseline
MUST keep these categories at WARN or above. Operators who lower
them for debugging accept the risk of user-content exposure in the
log stream.

## Source URL visibility

Source rows are global state (decision D7) — there is no per-user
source row. As a consequence, every URL added via `/add-source`
(DM or group) is visible to bot admins through `/list-sources --all`.
Users adding private feeds should treat the URL as visible to
operators. Hiding this would be dishonest to users; documenting it
explicitly lets users make an informed choice. v2 may add a
"private sources" feature with a per-user row and additional
operational complexity; v1 commits to global source rows.

## What's intentionally NOT in v1

(Catalogued in `docs/design/04-security.md` §4.12; spec-level summary:)

- DB-at-rest encryption — operator's responsibility (LUKS, managed-DB
  transparent encryption, etc.).
- **Per-user encryption with a user-supplied key.** Deferred (decision
  D37). The Provider must read plaintext to generate periodic digests,
  run the chat agent over `chat_memory`, and produce on-demand
  summaries; encrypting under a server-held key is obfuscation against
  casual DB dumps, not a real confidentiality boundary. Doing it
  honestly (key derived from user secret, server cannot reconstruct)
  would require disabling asynchronous features for opted-in users and
  is gated on a future product decision. v1 relies on minimization
  (chat-memory TTL, `/forget`, `/export`) instead.
- Per-group bans — only bot-wide ban in v1.
- User-controllable retention values — the chat-memory TTL is
  operator-configurable (profile-driven default, overridable per
  property in `deployment.md` §Configuration surface) but **not
  user-configurable** in v1. Users control purge via `/forget`
  (decision D37), not by tuning TTL.
- Two-factor confirmation for ban — single-step confirm-within-window                                                                                                                                                                                 
  is enough for v1.
- CAPTCHAs / human verification — invite-code registration and the slow-start
  tier are the v1 gates; CAPTCHA-style puzzles are not added on top.
- Heuristic/anomaly-based banning — admin acts manually.
- **Group auto-registration.** Removed by D47. The v1-pre-D47
  spec auto-registered unknown users on group @mention under
  `registration_state='group_only'`. D47 replaces this with
  registered-only interaction: only users who passed the DM
  invite gate can interact in groups. This is a hardening
  decision, not a deferral — the auto-registration path is
  permanently closed.
- **Sybil resistance across adapters.** A user banned on one adapter can
  present a fresh identity on another adapter; the bot has no cross-adapter
  correlation signal. The v1 levers are: invite codes (every new identity on
  every adapter needs its own admin-issued invite), the slow-start tier (bounds
  early resource damage per identity), the group authorization gate (D47 —
  admin approval per group, per-user activation cap, registered-only
  interaction), and manual `/ban`. Full Sybil
  resistance is deferred to v2.
- **Nostr publishing / signing.** Forever out of scope for v1 (decision
  D38). The Collector is read-only at the Nostr protocol layer: no key
  storage, no signing, no `EVENT` publishes. A future posting bot is a
  separate service with its own threat model.
- **NIP-65 relay-list auto-discovery.** Out of v1 (decision D38). The
  bot only sees content on the operator-configured relay list; content
  posted exclusively to relays outside that list is invisible. This is
  a deliberate trade-off, not a bug.
- **Nostr kinds beyond 1 and 6.** Out of v1: DMs (kind 4), reactions
  (kind 7), encrypted-content NIPs, relay-list events, and every other
  kind are dropped without parsing.
- **Translation cache cross-scope timing side-channel.** The
  presentation-layer translation cache (`llm.md` §Translation flow)
  is keyed by `(hash(text), target_language)` and is **shared across
  scopes** so a digest sent to multiple group members translates
  once. A user observing translation latency could in principle infer
  that another scope translated the same string moments earlier
  (cache hit vs. cache miss). v1 accepts this as a minor trade-off:
  the cached strings are presentation prose generated by the bot
  (cluster summaries, headers, status lines), not user-authored
  content; the translation key is a hash, not the plaintext; and the
  alternative — a per-scope cache — would multiply translation cost
  by the number of subscribers without a meaningful confidentiality
  benefit. Per-scope cache partitioning is a v2 candidate if a
  concrete attack surfaces.
- **Display-name-based `@mention` recognition.** v1 mention
  recognition is anchored to the cryptographic contact id only
  (`messaging.md` §Required SPI surface). Adapters whose protocol
  carries no mention primitive at all must disable group mode;
  string-matching the bot's display name in inbound message
  bodies is forever out of v1 because an attacker who spoofs or
  impersonates the bot's display name could otherwise suppress
  or fake mentions.
- **Boundless growth of soft-deleted source rows.** v1 never
  hard-deletes a `source` row (invariant 4). Across years of
  operation an operator can accumulate thousands of soft-deleted
  rows. The cleanup path is operator-side `psql` under the Admin
  role; the spec accepts this as bounded operational cost rather
  than introducing an automatic GC. A future v2 admin command may
  surface this in chat.

## What lives in design notes

- The Stage 1 regex catalogue and ReDoS mitigation specifics
- Stage 2 prompt template and label set
- Quarantine table columns and review-view shape
- Per-tier rate-limit numbers
- Per-profile "release on Stage 2 failure" defaults
- Nostr default relay list, per-relay rate cap, mark-bad threshold
  and cooldown values, reconnect backoff schedule
- Nostr config-block JSON shape inside `bootstrap-sources.json`
- The websocket library choice and the fake-relay test harness
- The exact NIP subset and the kind-filter implementation
- Prometheus counter names and recommended alert expressions
- DB role grant statements
- The structured refusal marker convention (literal token, prompt phrasing)
- Re-evaluation job cadence, per-post attempt cap, and re-eval status values
- Fetcher consecutive-failure threshold (*N*) and source re-enable procedure
- Invite-code TTL default and the exact drop-counter metric name
- Slow-start tier duration (per profile) and the exact allowed-command list 