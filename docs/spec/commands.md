# Commands and chat

This file describes the command surface the bot exposes, the rules every
command obeys, and the permission model that gates them. Concrete usage
strings, exact wording of replies, output formats, and pagination sizes
live in `docs/design/03-commands.md`.

## Surface conventions

- **Slash-prefix only.** Anything starting with `/` is a command. Anything
  else is chat-mode input routed to the chat agent. There is no "command
  mode" toggle.
- **Group rule.** In a group the bot only sees messages that `@mention` it.
  The mention is stripped before parsing.
- **Single time flag.** Every command that takes a time window uses the same
  `-w <duration>` flag with the same accepted forms (decision D12). Concrete
  forms are documented in design notes.
- **Tag arguments are exact-match** against the controlled vocabulary,
  case-insensitive. Unknown tags produce friendly errors with fuzzy
  suggestions.
- **Confirmation for destructive commands.** Destructive actions
  (e.g. `/clear`, `/remove-source`, `/ban`) require a follow-up
  `<command> confirm` within a **fixed, profile-tunable timeout**.
  The timeout is the same for every confirmable command in a given
  deployment (no per-command bespoke values); the exact duration is
  a profile-driven value (design notes). A late `confirm` past the
  timeout is rejected with the same wording as a missing pending
  state. The confirmation is scoped to (user, scope) and any other
  input cancels it with an explicit acknowledgement.
- **Output formatting** follows decision D30 (plain text, backticks for
  code, bare URLs). Adapters with richer rendering get richer output via the
  capability flag.
- **Input length caps** are applied at the parser before any LLM or
  DB work. The spec commits to **two cap categories**, each with its
  own profile-driven value (in design notes):
  - **Command body cap** — total slash-command line length, applied
    before parsing. Beyond the cap → friendly error, no parse
    attempt, no audit row beyond the rejection counter.
  - **Chat-mode body cap** — total inbound chat-mode message length,
    applied at intake. Beyond the cap → friendly error, no chat
    agent invocation, no LLM call.

  Both caps exist before this section's other guarantees apply; an
  oversized message never reaches the parser, the chat agent, the
  LLM, or any DB query past the rate-limit counter increment. The
  cap *values* are tuning and live in `docs/design/`.

## Command catalogue

The catalogue below is the *spec-level commitment* — these commands exist in
v1, with the listed permissions. Argument shapes, defaults, exact reply
text, and output structure are in `docs/design/03-commands.md`.

### Discovery

- `/help` — context-aware list of commands available to the caller.
  DM and group; any non-banned user.
- `/status` — runtime status (active profile, uptime, scope-specific
  counts; admin sees more). DM and group; any non-banned user.
- `/get-tags` — controlled vocabulary, marking the scope's followed
  tags. DM and group; any non-banned user (read-only, scope-filtered).
- `/get-sources` — alias of `/list-sources` without `--all`. DM and
  group; any non-banned user (read-only, scope-filtered).

### Content

- `/summary [tag] [-w …]` — on-the-fly summary of READY posts in the
  window (decision D18: on-the-fly for user `/summary`, distinct
  from the pre-generated cached path used for periodic group
  digests). Clusters (connected components of the `post_reference`
  graph) are **computed by deterministic SQL traversal before any
  LLM call** — the LLM only writes prose per pre-computed cluster.
  This keeps `/summary` inside the determinism boundary (decision
  D19): the cluster set is reproducible and depends only on the DB
  state, not on the LLM. A profile-driven cluster cap (value in
  design notes) bounds per-call work; clusters beyond the cap are
  truncated with a "+N more" footer.
- `/save <uid> [-t personal-tags]` — bookmark a post into the calling
  user's library (per-user, even in groups). Personal tags are
  free-form and never join the controlled vocabulary. Each user's
  saved-post library is **bounded by a profile-driven per-user cap**
  (decision D13); the exact value lives in design notes. A `/save`
  that would exceed the cap returns a friendly error pointing the
  user at `/unsave`; the cap is the same for every user on a given
  deployment (no per-user bespoke values).
- `/saved [tag] [-w …] [--page N]` — list saved posts with optional
  filters and pagination.
- `/unsave <uid>` — remove from library (no confirmation).

### Asset commands

Per-asset top-level commands expose price and market data for a fixed,
operator-configured set of cryptocurrencies (decision D39). v1 ships
`/zcash` and `/monero`; the per-asset sub-verb shape is the spec-level
commitment so future asset-specific verbs (shielded-pool stats, ring
size, on-chain queries) can land without a new top-level command per
verb.

- `/zcash [sub-verb] [--vs <currency>]` — Zcash market data. Sub-verbs
  in v1: `coingecko` (default, aggregated snapshot), `kraken`,
  `bitfinex`. Bare `/zcash` uses the operator-configured default
  sub-verb. Optional `--vs` selects the quote currency (USD by default;
  the per-source allowlist of accepted quote currencies lives in design
  notes). DM and group; any non-banned user.
- `/monero [sub-verb] [--vs <currency>]` — Monero market data. Same
  shape as `/zcash`. The enabled sub-verb set is **not** the same:
  exchanges that do not list XMR (Binance, Coinbase, Gemini) are not
  exposed for `/monero`. Asymmetric availability across assets is
  permitted by design — `bootstrap-assets.json` configures the
  per-asset sub-verb allowlist.

Cross-cutting rules for asset commands (D39):

- **Data is not posts.** Snapshots are stored in a collector-owned
  table outside the post pipeline. They never go through Stage 1/2,
  tagging, entity extraction, or embedding, and they are never
  surfaced via `/summary`, `/save`, or `/saved`.
- **Polled, cached, refreshed on a tick.** Polled data sources reuse
  the existing `Fetcher` SPI. The refresh interval is profile-driven
  and lives in design notes. Repeated user calls within the cache
  window are served from cache, not refetched per request.
- **Provider/Collector contract.** The Collector owns
  `price_snapshot`: its asset Fetchers `INSERT` new rows on every
  successful poll and emit `NOTIFY new_price_snapshot` with `(asset,
  source)` as the payload. The Provider has **`SELECT`-only**
  permission on `price_snapshot` (least-privilege, decision D34); on
  every `/zcash` / `/monero` invocation it reads the latest row for
  `(asset, sub-verb)` directly from the table — a stale read here is
  acceptable and bounded by the freshness contract below. The
  Provider may keep an in-process cache keyed by `(asset, sub-verb)`
  and warm/invalidate it from the `NOTIFY` payload, but the cache is
  an optimization; correctness comes from the table read, not from
  the notification. Asset Fetchers write **directly** to
  `price_snapshot` and do **not** go through the post outbox.
- **Freshness contract.** A reply uses the latest snapshot for
  `(asset, sub-verb)` whose age is within a profile-driven freshness
  window. If no row is within the window — Fetcher hasn't run yet,
  source is failing, last successful poll is too old — the Provider
  serves the most recent row available with an explicit "data is N
  minutes old" line, and degrades to a friendly error only when no
  row exists at all for that `(asset, sub-verb)`. The freshness
  window value lives in design notes.
- **Mandatory attribution.** Every reply names the data source in the
  header (e.g. `Zcash (kraken)`) and includes the source URL bare per
  D30. This satisfies per-source ToS attribution and lets the user
  reconcile small price differences between sub-verbs.
- **Stale-data honesty.** Every reply includes the snapshot's
  capture timestamp and the cache age. The bot does not pretend to
  be live; the websocket "live" mode is deferred to v2.
- **Public endpoints only in v1.** Only data sources reachable
  without an API key or auth token are eligible (Kraken public REST,
  Bitfinex public REST, CoinGecko free tier). Auth-gated exchanges
  (KuCoin, Gemini for most endpoints) require the operator-secret
  SPI and are out of v1.
- **Retention.** `price_snapshot` rows are aged out by partition
  drop on the same TTL discipline as other bulk-derived rows
  (decision D33, schema invariant 6). The retention horizon is
  profile-driven and lives in design notes; the retention mechanism
  is **not** row-level DELETE. Asset Fetchers write directly to the
  current partition; no outbox, no eval pipeline, no quarantine.
- **Friendly errors mirror the tag convention.** Unknown sub-verb,
  asset not enabled, sub-verb not enabled for this asset, or
  unsupported `--vs` currency → friendly error with fuzzy
  suggestions (commands.md §Friendly errors).
- **`/help` is context-aware.** Only operator-enabled assets appear
  in `/help`; only enabled sub-verbs appear in per-command help.

### Source management

Source rows are global; subscriptions are per-scope (decision D7). DM
subscriptions are private to the user; group subscriptions are
shared across the group and writable only by group admins.

- `/add-source <url> --tags …` — DM: any non-banned user adds to
  their own scope. Group: group admin only. Tags are mandatory (decision
  D14). The source `kind` (rss / bluesky / nostr / …) is inferred from
  the URL shape; an explicit `--type <kind>` override is accepted but
  not required, and defaults to `rss` when inference is ambiguous.
  Identity is `(kind, identifier)` per decision D38; the per-kind
  `config` block defaults to NULL when not supplied.
  **URL validation before insert.** For HTTP-shaped kinds, the
  Provider performs a lightweight `HEAD` (or, for servers that reject
  `HEAD`, a small-range `GET`) reachability probe through the
  Collector's SSRF allowlist (`security.md` §SSRF) **before** the
  source row is written. The probe runs under the same allowlist,
  redirect cap, and timeout caps as fetcher traffic; a 4xx/5xx
  response, an SSRF rejection, or a timeout produces a friendly error
  and **no row is written**. For StreamSource-shaped kinds (Nostr in
  v1) the equivalent check is a single connection attempt against the
  first relay in the supplied `config`; failure produces the same
  friendly error. The probe is bypassed for the bootstrap loader (the
  operator is trusted) but not for `/add-source`.
  **Tag-conflict resolution.** When the source `(kind, identifier)`
  already exists (because of bootstrap seeding or a prior `/add-source`),
  the call is idempotent on the source row but **the new call's `--tags`
  replace the existing `bootstrap_tags`** for that source row. The
  user-visible reply distinguishes the two outcomes: "source added" for
  a fresh insert, "source already existed, tags updated" for a tag
  replacement. The replaced tags are unioned into the controlled
  vocabulary (decision D5) before the row write so they are addressable
  by `/follow-tag` immediately. The caller's `source_subscription` is
  upserted in the same transaction.
- `/list-sources [--all] [--page N]` — sources subscribed by the
  calling scope; `--all` is bot-admin only and lists **every source
  row globally where `deleted_at IS NULL`** (across all kinds, all
  scopes, regardless of subscription). `failed` and `disabled`
  status rows are included with their status flagged in the output
  so an admin can see what is currently unhealthy without a separate
  command.
- `/unfollow-source <id>` — per-scope unsubscribe. Different from
  `/remove-source`: does not touch the global source row.
  **Permission.** DM: the caller's own subscription only. Group: any
  group member may unfollow a subscription **they** added; a group
  admin (or bot admin acting in the group) may unfollow any
  subscription on behalf of the group. The "last group subscription"
  case (the row being removed is the group's only remaining
  subscriber for that source) is restricted to group admin or bot
  admin — a plain group member cannot leave the group with zero
  subscribed sources.
- `/remove-source <id>` — bot-admin only, requires confirm. Soft-delete
  only.

### Per-scope tag preferences

- `/follow-tag <tag>` / `/unfollow-tag <tag>` — controls which tags
  appear in the scope's periodic digest. **Default for a fresh scope
  is "all tags from subscribed sources" (decision D15) — and the
  default is dynamic, recomputed at each digest run.** A scope with
  no `scope_tag` rows opts into the union of tags currently attached
  to its subscribed sources at digest time, so a `/add-source` that
  introduces a new tag to that union takes effect on the next digest
  without requiring an explicit `/follow-tag`. The first
  `/follow-tag` or `/unfollow-tag` call from a scope **switches the
  scope to explicit mode**: the digest then uses only the tags whose
  `scope_tag` rows exist for that scope. Returning to "all tags" is
  done by removing the explicit rows (one `/unfollow-tag` per
  followed tag, or a future `/reset-tags` command — out of v1).

### Conversation control

- `/clear` — wipes the calling (user, scope) active context window only.
  Chat memory is untouched (decision D25). Requires confirm.
- `/compress` — forces an immediate `chat_memory` checkpoint for the
  calling (user, scope). Auto-triggered near the context-window ceiling
  (decision D24).
- `/lang <code>` — sets per-scope output language. v1 ships English and
  Czech. DM: own scope. Group: group admin only. An unsupported code
  produces a friendly error that lists the supported codes — never a
  silent no-op and never a fall-through to the default.
- `/group-timezone <tz>` — sets the group's timezone for periodic
  digest scheduling (decision D16). IANA zone name (e.g.
  `Europe/Prague`, `UTC`). Group only; group admin or bot admin.
  An unset group's timezone defaults to `UTC` (the operator-side
  default in `deployment.md`); `/group-timezone` mutates the
  per-group `groups.timezone` value, audit-logged before effect.
  Unknown zone names produce the friendly-error path with fuzzy
  suggestions over the IANA tzdb names.
- `/forget` — immediate purge of the calling `(user, scope)`'s chat
  memory and saved-post list. Per decision D37, this is the user-facing
  privacy lever: anything kept on the user's behalf is removed. Does not
  touch `users.is_admin`, `users.is_banned`, group membership, or any
  audit row (the audit log is append-only by invariant). Audit-logged
  before effect like every privileged action against user state.
  Requires confirm. Idempotent: a second `/forget` with nothing to
  remove returns a friendly no-op reply.
- `/export` — returns the calling user's own data. Group output is
  defined by an **explicit table list**, not by a vague "the user's
  contributions" rule, so the boundary is testable: rows from
  `chat_memory`, `saved_post`, `scope_preferences`, `scope_tag`,
  `chat_session`, and `source_subscription` whose scope key matches
  the calling `(user, group)`, plus the caller's own `users` row
  (excluding fields derived from the authorization state of *other*
  users — last-admin counters, etc.). DM: full self-export under the
  same table list, scoped to the calling user's DM scope. Group:
  scoped to the calling `(user, group)` only — never another user's
  rows in any of those tables, never group-wide content (other
  members' messages, the group's `groups` row beyond `id` and
  `timezone`, audit log entries about other users), never any row
  outside the listed tables. Output format and size cap are in design
  notes.
- `/stop` — cancels the calling (user, scope)'s currently in-flight
  interruptible request **immediately**, so the worker is freed for
  others. Applies to chat-mode agent loops and user-issued `/summary`
  prose generation; does not affect periodic group digests, the
  ingest pipeline, or already-completed work. The in-flight LLM
  stream is closed and any in-flight read-only tool call is
  abandoned: the worker discards the in-flight result, releases the
  DB connection, and moves on. The DB-side query itself may continue
  to completion server-side (the spec does not promise that the
  Postgres backend is killed); what the spec promises is that the
  *worker* and the *user-visible state* are released without waiting
  for it. Once outbound delivery has begun the message is not
  unsent. Idempotent (no-op with a friendly reply when nothing is in
  flight). Audit-before-effect still holds — any audit row written
  before cancellation stays. The progress notifier (decision D31)
  renders a final "stopped" state on the in-place message. See
  decision D35.
- `/retry` — regenerates the prose for the last summary-producing
  command in the calling (user, scope). Re-runs the LLM stage only;
  deterministic post selection and clustering are reused unchanged
  (decision D19). Bounded by a small fixed retry cap (value in
  design notes) anchored to that most-recent summary-producing
  command. Any non-`/retry` input from the same (user, scope) clears
  the anchor; `/retry` itself never advances or resets it. No effect
  (friendly error) when no eligible anchor exists, when the anchor
  has been cleared, when the prior command was cancelled by `/stop`,
  or when the prior command was not summary-producing. For periodic
  group digests, `/retry` is group-admin or bot-admin only and
  replaces the cached digest (decision D17); the post selection is
  the **frozen** selection captured when the original digest was
  generated (the digest's slot window, not the wall-clock window at
  the moment `/retry` runs) — only the prose layer is re-rolled, so
  the digest does not silently drift forward as time passes. See
  decision D36.

### Admin (bot admin)

- `/promote <contact>` / `/demote <contact>` — group admin
  promote/demote, used inside a group.
- `/grant-admin <contact>` / `/revoke-admin <contact>` — bot-wide. Last-
  admin protection applies.
- `/ban <contact> [--reason …]` / `/unban <contact>` — bot-wide ban. Cannot
  ban self or last admin.
- `/invite create --adapter <name> --contact <id>` — generate a single-use
  UUID invite code bound to the given (contact\_id, adapter) pair (decision
  D44). The code is displayed once in the reply and stored as PENDING. No
  confirmation required (risk is bounded to one specific identity).
- `/invite create --adapter <name> --open` — generate a single-use UUID invite
  code bound to the adapter only; the first unknown contact on that adapter to
  present the code is registered. Requires confirm (broader blast radius than
  `--contact`).
- `--contact` and `--open` are mutually exclusive. Providing neither returns a
  hint listing both flags and their trade-offs; no invite is created.
- `/invite list [--page N]` — list PENDING invite codes with target contact,
  adapter, and expiry.
- `/invite revoke <code>` — immediately transition a PENDING code to REVOKED.
  Requires confirm.
- `/vouch <contact>` — immediately graduate a user from the slow-start
  probation tier to full access (decision D45). Audit-logged. No-op with a
  friendly reply if the user is already past probation.
- `/quarantine list [-w …] [--page N]` — pending review queue.
- `/quarantine approve <id>` / `/quarantine reject <id>` — review action.
  Approve restores the redacted span; reject leaves the placeholder.
- `/audit [-w …] [--actor …] [--action …] [--page N]` — read `audit_log`
  with filters.

## Permission model

The full per-command matrix (DM / group member / group admin / bot admin)
lives in `docs/design/03-commands.md`. The spec-level commitment:

- Permission is evaluated by deterministic Java *before* any LLM call.
- Banned users get one fixed reply and never reach the parser, the chat
  agent, or any DB query past the ban check (decision D11).
- Group destructive operations require group admin (or bot admin acting
  inside the group). Bot-wide destructive operations require bot admin.
- Any new command added to the system goes through the same permission
  matrix and the same audit-before-effect rule.

## Chat mode

Anything not starting with `/` is routed to the chat agent. The agent has a
strict, fixed tool surface (read-only, scope-filtered) — see `security.md`
and decision D21. The agent is never allowed to mutate authorization state
or perform admin actions; admin commands are dispatched by the
deterministic command path only.

Chat-mode replies and user-issued `/summary` runs can be interrupted by
`/stop` (decision D35). Cancellation observes the same per-(user, scope)
isolation as every other state in the system: a `/stop` from one user
never affects another user's in-flight request, even within the same
group.

## Onboarding

**DM first interaction** requires a valid invite code (decision D44). An
unknown DM contact's first message is checked against the invite table:
if it matches a PENDING code bound to that (contact\_id, adapter), the user is
registered and the welcome message is sent; otherwise a fixed "access requires
an invitation" reply is returned and no registration occurs.

Once registered (via invite or group @mention), the user enters the slow-start
probation tier (decision D45). The welcome message informs the user of the
probation window and the reduced command set available until it elapses.

The welcome message branches on three modes (DM-fresh, DM-returning,
group-first-mention) so the user is steered toward an action that will not be
empty (decision D23). Exact wording in design notes.

**Previously-banned, now-unbanned users.** When a banned user is
`/unban`ed, the bot does **not** proactively send a "you were
unbanned" message — proactive contact would surface the existence of
the ban to a user who has not chosen to interact again, and would
also ping a user who never knew they were banned in the first place.
The next inbound message from the unbanned user is treated as the
DM-returning case (or the group-first-mention case if it arrives in a
group), reusing the existing welcome branch. The `/unban` action
itself is audit-logged as always; surfacing it to the affected user
is deferred to v2 if it surfaces at all.

## Periodic group summaries

Groups receive a morning and evening digest at per-group local times
(decision D16). Generation is staggered, results are cached briefly, and a
degraded fallback (headlines + sources, no LLM prose) kicks in when the
worker pool can't keep up (decision D17).

## What lives in design notes

- Exact argument grammar and accepted `-w` forms
- Per-command exact reply wording
- Pagination page size
- Confirmation timeout duration (the cap categories are spec; the
  exact value is design)
- Input-length cap values (the categories — command body cap,
  chat-mode body cap — are spec; the exact byte/character values
  are design)
- Per-user `/save` cap value (the existence of a profile-driven cap
  is spec; the exact value is design)
- Cluster cap and per-profile cluster-cap values
- Welcome message text (including the probation-aware variant)
- Invite-code display format in the `/invite create` reply
- Slow-start allowed-command list (the exact set is spec; the list text is design)
- Permission matrix rows
- Friendly-error suggestion ranking and cap (including the IANA
  tzdb fuzzy-suggestion list for `/group-timezone`)
- `/export` output format (e.g. JSON shape, attachment vs. inline) and
  size cap
- `bootstrap-assets.json` schema and example file
- Per-asset sub-verb allowlist (which sub-verbs are enabled for `/zcash`
  vs `/monero`)
- Per-source allowlist of accepted `--vs` quote currencies
- Per-profile snapshot refresh interval and cache TTL
- `price_snapshot` table shape (column names, indexes, retention)
- Reply layout (line ordering, BTC-denominated price, 7d Δ%, verbose
  fields)
- Per-exchange ToS-compliant attribution string and citation URL
