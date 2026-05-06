# Decisions log

Cross-cutting choices that shape the rest of the spec. Each row is a settled
*direction*, not an implementation detail. Concrete values (DDL, class names,
property keys, retry counts, model names, sizes) live in `docs/design/`.

If a row here changes, the spec changes; if a value in `docs/design/` changes,
the spec does not.

| # | Decision | Choice |
|---|---|---|
| D1 | Stack | Quarkus + PostgreSQL + pgvector + LangChain4j + Java 21 + Maven multi-module |
| D2 | Service split | Two services: headless **Collector** (ingest/eval/store) and user-facing **Provider** (chat). Shared DB. |
| D3 | Eval queue (v1) | In-process channels + outbox pattern (post persisted before enqueue). External broker is a v2 swap. |
| D4 | Collector → Provider events | Postgres `LISTEN/NOTIFY` for push, plus a high-water mark for catch-up after restarts. No external broker in v1. |
| D5 | Tag tiers | Tier 1 controlled vocabulary (exact match, user-facing); Tier 2 entities + embeddings (internal linking only, never shown). |
| D6 | Cross-source linking | Hybrid: named-entity match (precision) + cosine similarity over embeddings (recall). |
| D7 | Source ownership model | Global `source` rows + per-scope subscriptions. DM sources private to user; group sources shared, writable only by group admin. |
| D8 | Source bootstrap | Idempotent loader from a config-pointed JSON file at Collector startup. Seeds the controlled-vocab tag set. |
| D9 | Admin tiers | **Bot admin** (global) + **Group admin** (per group). Bot admin bootstrapped from config; group admin bootstrapped by first @mention in a new group, overridable by bot admin. |
| D10 | User identity | The messaging adapter's cryptographic contact ID is the trust anchor. Display names are informational only. |
| D11 | User ban | Bot-wide flag + reason/audit. Banned users get one fixed reply, no LLM/DB access beyond the ban check. Per-group ban deferred to v2. |
| D12 | Command surface | Slash-prefix only. No mode toggle. Single `-w` time flag. Friendly errors with fuzzy suggestions. Confirmation token for destructive commands. |
| D13 | `/save` semantics | Per-user only (private even in groups), free-form personal tags, retention exemption (snapshot copy), capped per user. |
| D14 | `/add-source` rules | DM: any non-banned user. Group: group admin only. Tags are mandatory (≥1) so the tagger always has a deterministic fallback. |
| D15 | Per-scope tag prefs | `/follow-tag` / `/unfollow-tag` control which tags appear in periodic summaries; default is "all tags from subscribed sources". |
| D16 | Group bot behavior | Replies only on @mention; group-admin-only destructive ops; periodic morning/evening summary by per-group timezone. |
| D17 | Periodic summary scheduling | Staggered start within the slot window; results cached so a follow-up `/summary` is served from cache; degraded fallback (headlines + sources, no LLM prose) when worker is overloaded. |
| D18 | Summary mode | On-the-fly for user `/summary`; pre-generated + cached for periodic group digests. |
| D19 | Determinism boundary | All retrieval is deterministic SQL. LLMs only generate prose or extract structured fields at ingest. Same query → same posts twice in a row. |
| D20 | Ingest security | Layered: deterministic Stage 1 (HTML sanitization, prompt-injection regex, Unicode normalization, SSRF guard) + LLM Stage 2 judge, only invoked on Stage 1 hits. |
| D21 | Prompt-injection defense | Untrusted-content delimiter convention with per-call random marker; chat-output sanitizer for admin commands; LLM never has admin tools or arbitrary SQL. |
| D22 | Eval failure policy | Per-stage. Stage 2 *verdict* (INJECTION/MALWARE/UNKNOWN) → quarantined until admin review. Stage 2 *infrastructure* failure → release with Stage 1 redactions retained, flagged for re-evaluation. Tagger fallback → source's bootstrap tags. Embedding/entity failure → release without that artifact. Admin notifications throttled. |
| D23 | Onboarding | Auto-register on first message, welcome with `/help`. Banned users blocked at message intake. |
| D24 | `/compress` | Per-(user, scope) memory checkpoint (summary + keywords + post references). Auto-triggered near the context-window ceiling; explicit command available. |
| D25 | `/clear` semantics | Wipes only the active context window for the calling (user, scope). Long-term memory is independent. |
| D26 | Group memory | Per-(user, group) — same privacy model as `/save`. No shared group memory in v1. |
| D27 | Hardware profiles | Named profile (e.g. `laptop`, `vps`, `pi`, `remote`) drives context-window size, default models, eval concurrency, and vector-index choice. Per-property override allowed. |
| D28 | Memory retrieval | Hybrid: deterministic keyword pre-fetch (cheap, always) plus an agent tool for deeper recall. |
| D29 | Translation | English by default. Per-scope language opt-in via `/lang`. Source bodies are never translated; translation is presentation-only. Pluggable provider SPI; v1 ships English + Czech. |
| D30 | Output formatting | Plain text. Inline code in single backticks; multi-line in triple backticks; URLs bare. Adapters expose a capability flag for richer rendering. |
| D31 | Progress notifications | Long-running requests publish stage events to a cross-cutting notifier; the notifier renders them via adapter capabilities (in-place edit / typing indicator) and falls back to a single final send when the adapter doesn't support edits. User input is never interpolated into progress strings. |
| D32 | Adapters as SPIs | `LlmProvider`, `EmbeddingProvider`, `MessagingAdapter`, `TranslationProvider` are pluggable interfaces. Concrete impls picked by config. v1 ships SimpleX + an in-memory test adapter. |
| D33 | TTL strategy | Old post-derived rows expire by partition drop, not row-level DELETE. Post bodies have a fixed retention horizon; saves are exempt via snapshot copy. |
| D34 | DB roles | Least-privilege split per service role; LLM-reachable code paths run under read-only roles where possible. |

## How to evolve this list

- **Adding a row** is a spec change: open it for review, then update the
  affected spec section.
- **Removing a row** requires identifying what replaces it; "we changed our
  minds" is fine, but the replacement direction goes in a new row so the
  history is legible.
- **Refining values** (e.g. retry count, model name, schema column) is *not*
  a decision change — that lives in `docs/design/`.
