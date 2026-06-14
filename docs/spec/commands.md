# Commands and chat

This file describes the command surface the bot exposes, the rules every
command obeys, and the permission model that gates them. Concrete usage
strings, exact wording of replies, output formats, and pagination sizes
live in `docs/design/03-commands.md`.

## Surface conventions

- **Slash-prefix only.** Anything starting with `/` is a command. Anything
  else is chat-mode input routed to the chat agent. There is no "command
  mode" toggle.
- **Empty and whitespace-only messages are dropped** before any further
  processing — neither command parsing nor chat-mode routing runs on them.
  **Leading whitespace is trimmed** before the slash-prefix check, so `  /help`
  parses as `/help` rather than as chat-mode input. Both transforms happen in
  the same normalization pass that strips bidi overrides and zero-width
  characters (see `security.md` §Authorization model step 1.7); they are not
  separate gates.
- **Group rule.** In a group the bot only sees messages that `@mention` it.
  The mention is stripped before parsing.
- **Single time flag.** Every command that takes a time window uses the same
  `-w <duration>` flag with the same accepted forms (decision D12). Concrete
  forms are documented in design notes.
- **Tag arguments are exact-match** against the controlled vocabulary,
  after a fixed normalization pipeline applied at every read and
  write site:
  1. Trim leading and trailing whitespace.
  2. Apply Unicode NFC normalization (NFC, not NFKC — NFKC's
     compatibility folding would silently merge visually-distinct
     tags like `①` and `1`, which is a different design choice).
  3. Lower-case the result via `String.toLowerCase(Locale.ROOT)`
     (locale-independent so `İ`/`I` do not split between locales).
  4. Reject any value whose post-normalization form does not match
     the character class `[a-z0-9][a-z0-9-]{0,47}` — ASCII
     alphanumerics plus internal hyphens, leading character must
     be alphanumeric, total length 1–48 characters. Whitespace,
     non-ASCII letters, and control characters are rejected at the
     parser with a friendly error.
  Normalization runs identically at ingest (bootstrap loader,
  `/add-source --tags`, tagger output validation) and at command
  parse (`/follow-tag`, `/unfollow-tag`, `/summary <tag>`,
  `/saved <tag>`). The character-class restriction closes the
  NFC-vs-NFKC homoglyph evasion path. Unknown tags produce
  friendly errors with fuzzy suggestions over the controlled
  vocabulary.
- **Confirmation for destructive commands.** Destructive actions
  (e.g. `/clear`, `/remove-source`, `/ban`) require a follow-up
  `<command> confirm` within a **fixed, profile-tunable timeout**.
  The timeout is the same for every confirmable command in a given
  deployment (no per-command bespoke values); the exact duration is
  a profile-driven value (design notes). A late `confirm` past the
  timeout is rejected with the same wording as a missing pending
  state. The confirmation is scoped to (user, scope) and any other
  input cancels it with an explicit acknowledgement — including
  `/stop`, which cancels a pending confirmation as a side effect
  of its "any other input" treatment and replies with the standard
  cancellation acknowledgement, even when no LLM work is in flight.
  (Per-command-
  category split timeouts are recorded as a v2 candidate; v1's
  one-timeout-fits-all keeps the state machine simple.)
  **Confirmation state is in-memory only.** Pending confirmations are
  not persisted to the database. A Provider restart cancels all
  pending confirmations; a `confirm` issued after the restart receives
  the same "no pending action" reply as a late or unmatched confirm.
  This is intentional: persisting confirmation tokens across restarts
  would require a cleanup sweep and a TTL gate identical to the
  in-memory timeout, with no gain in UX.
- **At most one in-flight interruptible request per (user,
  scope).** A second request from the same caller while one is in
  flight returns a localized "request already in progress; use
  `/stop` to cancel" reply. This prevents a single user from
  multiplying the LLM-trigger cost on shared workers; once the
  first request completes (or is cancelled by `/stop`) the next is
  accepted normally.
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
  LLM, or any DB *write*. The transport-layer byte cap fires at
  intake, before any DB connection is borrowed, so a genuinely
  hostile large payload is dropped query-free. The character-based
  body caps above are evaluated after the authorization gates'
  single `users`-row read (and, in group scope, the scope-language
  and group-approval reads), so the ban / invite / D47-invisibility /
  per-group reply-bucket precedence is preserved — an oversized
  banned-user or unapproved-group message still gets the
  authorization reply, not a body-too-large reply. The cap *values*
  are tuning and live in `docs/design/`.

## Command catalogue

The catalogue below is the *spec-level commitment* — these commands exist in
v1, with the listed permissions. Argument shapes, defaults, exact reply
text, and output structure are in `docs/design/03-commands.md`.

### Discovery

- `/help` — context-aware list of commands available to the caller.
  DM and group; any non-banned user. The list is filtered by the
  exact set the caller is currently permitted to invoke: a
  probation-tier caller (decision D45) sees **only** the slow-start
  allowed subset, with a one-line note that fuller access — and
  free-form chat-mode replies (chat mode is also blocked during
  probation, `security.md` §Slow-start tier) — unlocks when
  probation ends; a non-admin caller does not see admin
  commands; a group member who is not group admin does not see
  group-admin-only commands. Showing a wider list with "blocked
  during probation" annotations would contradict the
  probation-reply text the user receives if they try to invoke
  one of the blocked commands; filtering keeps the welcome
  message, the `/help` listing, and the probation reply mutually
  consistent.

  **Bundle composition.** `/help` output is **composed from
  per-command bundle entries** (one localization-bundle key per
  command, holding that command's short-help line), not a single
  monolithic bundle string per actor tier. The header, the
  probation-tier footer, and any inter-section dividers are
  **separate bundle keys**; `/help` concatenates the header, then
  the per-command lines for the caller's permitted set in a
  fixed order, then the footer. CI's bundle-completeness check
  asserts that every command in the catalogue has a help-line key
  in every shipped language bundle (`en` and `cs` in v1 per
  `llm.md` §Translation flow), and that the header / footer keys
  exist. This commitment fixes the bundle structure so adding a
  third language is a deterministic drop-in.
- `/status` — runtime status (active profile, uptime, scope-specific
  counts; admin sees more). DM and group; any non-banned user. Bot
  admin view includes a count of pending groups
  (`approval_status = 'pending'`) so the admin has passive discovery
  of groups awaiting approval without running `/list-groups`.
- `/get-tags` — controlled vocabulary, marking the scope's followed
  tags. DM and group; any non-banned user (read-only, scope-filtered).
- `/get-sources` — alias of `/list-sources` accepting the same
  flags **except `--all`** (and therefore not `--include-deleted`
  either, since that requires `--all`). DM and group; any
  non-banned user (read-only, scope-filtered).

### Content

- `/summary [tag] [-w …]` — on-the-fly summary of READY posts in the
  window (decision D18: on-the-fly for user `/summary`, distinct
  from the pre-generated cached path used for periodic group
  digests). **Summarizer LLM unreachable** (provider down,
  timeout, or schema-violating reply after retry per `llm.md`
  §Failure handling) → `/summary` falls back to the same degraded
  form as a saturated periodic digest (decision D17): headlines +
  source URLs + post UIDs, no prose. The friendly degraded notice
  (localization-bundle string per D43) replaces the prose block;
  the deterministic post selection is unaffected. `/retry` against
  this degraded run regenerates the prose if the LLM has recovered
  (the deterministic post selection is reused per D19 / D36).
  Clusters (connected components of the `post_reference`
  graph) are **computed by deterministic SQL traversal before any
  LLM call** — the LLM only writes prose per pre-computed cluster.
  This keeps `/summary` inside the determinism boundary (decision
  D19): the cluster set is reproducible and depends only on the DB
  state, not on the LLM. A profile-driven cluster cap (value in
  design notes) bounds per-call work; clusters beyond the cap are
  truncated with a "+N more" footer. **Empty window**: when no
  eligible posts exist in the window, `/summary` returns a friendly
  "no posts yet" reply (deterministic localization-bundle string,
  no LLM invocation, no empty summary block). If the calling scope
  has zero active subscriptions, `/summary` returns the same "no
  posts yet" reply regardless of tag mode or window size — the
  empty-eligible-set path covers both "subscribed but nothing
  arrived" and "nothing subscribed."
  **Posts with Stage 1 redactions retained** (`stage2_failed=true`,
  released `READY` per `security.md` §Failure handling) are included
  in the eligible set; the LLM summarizer sees the redacted body
  as-is. `[REDACTED:<id>]` placeholders are **not** stripped before
  the prompt — the placeholder serves the same defensive purpose at
  summarize time as it does at delivery time (it tells the model
  that text was removed without revealing what, and it preserves
  the visual cue when the prose is read back to the user).
- `/save <uid> [-t personal-tags]` — bookmark a post into the calling
  user's library. **Saves are per-user-globally** (decision D13): a
  save made in DM is visible from any group, and vice versa. Personal
  tags are free-form and never join the controlled vocabulary. Each
  user's saved-post library is **bounded by a profile-driven per-user
  cap** (decision D13); the exact value lives in design notes. The cap
  is enforced **atomically**: the implementation uses
  `SELECT … FOR UPDATE` on the user's save-counter row (or an
  equivalent CHECK constraint on a derived counter) so two concurrent
  `/save` calls at cap-1 admit exactly one. A `/save` that would
  exceed the cap returns a friendly error pointing the user at
  `/unsave`; the cap is the same for every user on a given
  deployment (no per-user bespoke values).
  **Visibility-of-target rules.** `/save` on a `READY` post snapshots
  the visible body — including any Stage 1 redactions still in place
  (a Stage 2 `BENIGN` verdict transitions the post to `READY` while
  leaving Stage 1 redactions in the body until `/quarantine approve`
  lifts them; `schema.md` §Posts and derivatives). `/save` on a
  `QUARANTINED` post (Stage 2 `INJECTION`, `MALWARE`, or `UNKNOWN`,
  hidden — invisible to the user) is treated as an unknown UID.
  `/save` on a `NEEDS_REVIEW` post is treated as an unknown UID:
  `NEEDS_REVIEW` posts are never user-visible — they are reachable
  only by bot admins via `/quarantine list --all`. A `READY` post is
  additionally subject to an **any-caller-scope visibility filter**:
  `/save` admits the post only if its source is subscribed in at
  least one of the caller's scopes — the caller's own DM
  subscriptions, or a group subscription of an approved, non-removed
  group in which the caller holds an active membership
  (`group_membership.removed_at IS NULL`). The filter is evaluated
  against the caller, not the calling scope — a post visible in a
  group the user belongs to may be saved from DM, consistent with
  saves being per-user-globally (decision D13). A `READY` post
  outside that union is treated as an unknown UID: the reply is
  identical to the nonexistent-UID case, so the
  existence-vs-no-access distinction is never exposed (mirroring the
  `getPost` tool contract, `security.md` §Prompt-injection defenses).
  The `/save` flow
  never lets a user bookmark content they cannot see.
- `/saved [tag] [-w …] [--page N]` — list saved posts with optional
  filters and pagination. **Saves are per-user-globally** (decision
  D13): the list shows all saves regardless of which scope they were
  created from. The command's `/help` line and reply header must
  disclose this so users are not surprised to see DM saves appear
  when running the command in a group context.
- `/unsave <uid>` — remove from library (no confirmation).

### Asset commands

Per-asset top-level commands expose price and market data for a fixed,
operator-configured set of cryptocurrencies (decision D39). v1 ships
`/zcash` and `/monero`; the per-asset sub-verb shape is the spec-level
commitment so future asset-specific verbs (shielded-pool stats, ring
size, on-chain queries) can land without a new top-level command per
verb.

- `/zcash [sub-verb] [--vs <currency>]` — Zcash market data. Sub-verbs
  in v1: `coingecko` (aggregated snapshot), `kraken`, `bitfinex`.
  Bare `/zcash` resolves to the **per-asset default sub-verb**
  marked in `bootstrap-assets.json` (the `asset_config.is_default`
  flag — `schema.md` §Operational); when no row carries
  `is_default = true` for the asset, bare `/zcash` returns the same
  friendly "not configured" error as an unknown sub-verb (no
  implicit fallback to `coingecko` or any other source — silent
  fallback would mask operator misconfiguration).
  **Default-but-disabled fallback.** The bootstrap loader rejects a
  row with `is_default = true AND enabled = false` at Collector
  startup (`schema.md` §Operational — Default-row consistency), so
  the inconsistency normally cannot reach runtime. As
  defense-in-depth, if it nonetheless does (e.g. a future runtime
  admin mutation that bypasses the invariant), bare `/zcash`
  returns the friendly "default sub-verb is currently disabled;
  pass an explicit sub-verb" error with the asset's enabled
  sub-verbs listed; no implicit fallback to a non-default sub-verb
  fires.
  Optional `--vs` selects the quote currency (USD by default; the
  per-source allowlist of accepted quote currencies lives in
  design notes). DM and group; any non-banned user.
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
- **Polled, cached, refreshed on a tick.** Polled data sources use the
  dedicated asset-fetch SPI (`architecture.md` §Ingest SPIs — Output
  type), separate from the post `Fetcher`: snapshots are written
  **directly** to `price_snapshot` and never enter the post outbox or
  Stage 1/2. **The refresh interval is keyed
  per-data-source-host** (i.e., per `sub_verb` family — one interval
  for `coingecko`, another for `kraken`, another for `bitfinex`),
  not per-`(asset, sub_verb)` and not per-asset. This mirrors the
  per-`kind` interval model for source Fetchers
  (`architecture.md` §Ingest SPIs) and aligns with upstream
  rate-limit budgets, which are imposed per upstream API host, not
  per asset. All `kraken` snapshots across every enabled asset
  share one tick cadence; same for `coingecko` and `bitfinex`. The
  per-host interval values are profile-driven and live in design
  notes. Repeated user calls within the cache window are served
  from cache, not refetched per request.
- **Provider/Collector contract.** The Collector owns
  `price_snapshot`: its asset Fetchers `INSERT` new rows on every
  successful poll. The Provider has **`SELECT`-only**
  permission on `price_snapshot` (least-privilege, decision D34); on
  every `/zcash` / `/monero` invocation it reads the latest row for
  `(asset, sub-verb)` directly from the table — a stale read here is
  acceptable and bounded by the freshness contract below.
  Correctness comes from the table read: the latest-snapshot query
  is a single indexed `(asset, sub_verb, captured_at DESC)` lookup,
  so the Provider reads the table directly on each invocation with
  no notification path or in-process cache. Asset Fetchers write
  **directly** to `price_snapshot` and do **not** go through the
  post outbox.
- **Freshness contract.** A reply uses the latest snapshot for
  `(asset, sub-verb)` whose age is within a profile-driven freshness
  window. This window is a **Provider-owned** property, independent
  of the Collector's per-host refresh cadence above: the Provider has
  no fetch loop, so it judges staleness against its own window rather
  than mirroring the Collector's `infochat.assets.refresh.*` keys —
  an operator tightening one side cannot desync the other. If no row
  is within the window — Fetcher hasn't run yet, source is failing,
  last successful poll is too old — the Provider serves the most
  recent row available with an explicit "data is N minutes old" line,
  and degrades to a friendly error only when no row exists at all for
  that `(asset, sub-verb)`. The freshness window value lives in design
  notes.
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
- **Enable / disable lifecycle.** Asset commands are enabled only
  when `bootstrap-assets.json` is configured and contains the
  asset; the per-`(asset, sub_verb)` enabled flag lives in
  `asset_config` (`schema.md` §Operational). When disabled, the
  command does not appear in `/help` and an attempted invocation
  returns the "not configured" friendly error. **Soft-disable**
  (asset present in bootstrap, then later removed): on the next
  bootstrap reload the loader sets `asset_config.enabled = false`;
  the row and historical `price_snapshot` data are preserved for
  audit, never hard-deleted.

### Source management

Source rows are global; subscriptions are per-scope (decision D7). DM
subscriptions are private to the user; group subscriptions are
shared across the group and writable only by group admins.

- `/add-source <url> --tags … [--type <kind>] [--category <name>]` —
  DM: any non-banned user adds to their own scope. Group: group admin
  only. Tags are mandatory (decision D14). Identity is
  `(kind, identifier)` per decision D38; the per-kind `config`
  block defaults to NULL when not supplied. The `--category` flag
  selects one of `news` / `blog` / `social`; default is `news`
  for user-added sources. **Note:** `category` is informational
  metadata in v1 and is not used for retrieval, filtering, or
  permission decisions; v2 may attach behavior to it (e.g., a
  chat-agent tool filter), v1 commits to nothing beyond
  storing it.

  **Kind resolution.** The source `kind` is determined deterministically:

  1. An explicit `--type <kind>` always wins. The value is matched
     case-insensitively against the closed `source.kind` enum
     (SPEC.md §Glossary "Source kind"); unknown values produce the
     friendly-error path with fuzzy suggestions over the enum, the
     same path as an unknown tag argument. The value never reaches
     a SQL query as free-form text — the enum check is the validation
     boundary.
  2. With no `--type`, the kind is inferred from the URL by the
     following closed table, applied in order. Host comparisons are
     case-insensitive against the URL's authority component:
     - Scheme is `wss://` or `ws://` → `nostr` (the only
       StreamSource-shaped kind in v1).
     - Host is `bsky.app`, `bsky.social`, or any subdomain
       thereof → `bluesky`.
     - Host is `reddit.com`, `redd.it`, or any subdomain
       thereof → `reddit`.
     - Host is `youtube.com`, `youtu.be`, or any subdomain
       thereof → `youtube`.
     - Host is `odysee.com` or any subdomain thereof → `odysee`.
  3. **RSS auto-detection.** A URL whose path ends in `.xml`,
     `.rss`, or contains `/feed` (or `/feed/`, `/feed.xml`, etc.)
     resolves to `rss` without `--type`. The URL-validation probe
     (below) inspects the response `Content-Type`; a probe that
     returns `application/rss+xml`, `application/atom+xml`, or
     `application/xml` confirms the inferred kind. A probe that
     contradicts the URL-pattern hint (returns `text/html`) falls
     through to the ambiguous-URL path below. This covers the
     common case (a plain RSS URL with `--tags`) without forcing
     `--type rss` in 95% of `/add-source` invocations.
  4. URLs that match none of the rows above are **ambiguous**: the
     call is rejected with a friendly error that lists the supported
     kinds and instructs the caller to supply `--type`. There is
     no silent fallback for self-hosted Nitter instances (no
     canonical host), non-canonical mirrors, or other RSS-shaped
     feeds without the path/Content-Type signal — those require an
     explicit `--type`. A URL silently routed to the wrong SPI
     (Fetcher vs. StreamSource) and clashing with an existing row
     on the `(kind, identifier)` unique key is a worse failure
     mode than asking the caller for `--type`.

  The host-pattern table above is **closed at spec level** —
  additions are spec amendments, not design tweaks — so two
  implementations cannot diverge on routing. IDN/Punycode folding
  rules and the exact case-folding implementation live in design
  notes.
  **URL validation before insert.** For HTTP-shaped kinds, the
  Provider performs a lightweight `HEAD` (or, for servers that reject
  `HEAD`, a small-range `GET`) reachability probe through the
  shared `infochat-ssrf` library (`security.md` §SSRF), identical
  to the one Collector uses for fetcher traffic, **before** the
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
  the call is idempotent on the source row. **A non-admin caller never
  rewrites `bootstrap_tags`** on an existing source row — source rows
  are global per D7, so allowing any DM user to mutate the
  deterministic-tagger fallback for every other subscriber would
  silently change the ingest behaviour for users who never asked for
  it. For a non-admin caller against an existing row: the caller's
  `source_subscription` is upserted, the user-visible reply is
  "subscribed; tags unchanged on existing source," and the supplied
  `--tags` are quietly ignored. **Only a bot admin** may supply
  `--tags` to replace `bootstrap_tags` on an existing row; this is
  the single path that mutates the global row's tags and is
  audit-logged. **Admin `--tags` replacement also requires ≥1 tag.**
  A bot admin supplying `--tags` on an existing row must supply at
  least one non-empty, non-rejected tag value — the same ≥1 constraint
  as a fresh insert (decision D14). An empty replacement would leave
  a source with zero `bootstrap_tags`, which in turn produces zero
  eligible posts for any scope in `ALL` mode (the tagger's fallback
  would be an empty set). The friendly error for a zero-tag attempt
  is the same as for a fresh insert with no tags. Per-scope tag preferences continue to flow through
  `scope_tag` (`/follow-tag` / `/unfollow-tag`), not through
  `/add-source`. On a fresh insert (whether bot-admin or non-admin),
  the supplied `--tags` populate `bootstrap_tags` and are unioned into
  the controlled vocabulary (decision D5) before the row write so
  they are addressable by `/follow-tag` immediately. The user-visible
  reply distinguishes outcomes: "source added" (fresh insert),
  "source already existed, tags updated" (bot-admin tag replacement),
  "subscribed; tags unchanged on existing source" (non-admin against
  existing row). The caller's `source_subscription` is upserted in
  the same transaction in all three cases.

  **URL visibility disclosure.** On a fresh insert (the only outcome
  that adds a URL the bot did not previously hold), the reply **must
  surface that source URLs are visible to bot admins** — e.g.,
  `"Note: source URLs are global state and are visible to bot admins
  via /list-sources --all."` Without this, a user adding a private
  feed for personal tracking has no signal that the URL is
  operator-visible; the trust-boundary disclosure
  (`security.md` §Source URL visibility) is too easy to miss in
  documentation alone. The disclosure is omitted on the
  already-existed paths because the URL was already in the global
  set; subscribing to a known URL exposes nothing new.
- `/list-sources [--all] [--include-deleted] [--page N]` — sources
  subscribed by the calling scope; `--all` is bot-admin only and
  lists **every source row globally where `deleted_at IS NULL`**
  (across all kinds, all scopes, regardless of subscription).
  `failed` and `disabled` status rows are included with their
  status flagged in the output so an admin can see what is
  currently unhealthy without a separate command.
  `--include-deleted` (bot-admin only, valid only with `--all`)
  also lists soft-deleted source rows for forensic / cleanup
  workflows. **URL visibility caveat.** Source URLs are global
  state and visible to bot admins via `--all`. Users adding
  private feeds should treat URLs as operator-visible
  (`security.md` §Source URL visibility).
- `/unfollow-source <id>` — per-scope unsubscribe. Different from
  `/remove-source`: does not touch the global source row.
  **Permission (v1).** DM: the caller's own subscription only.
  Group: **group admin or bot admin only** — a plain group member
  cannot unfollow a group subscription. The earlier "any group
  member may unfollow a subscription they added" exception is not
  in v1: it requires per-contributor ownership tracking
  (`source_subscription.added_by`, contributor sub-tables, and a
  "last contributor leaves" edge case) that no review report
  flagged as necessary. v2 may revisit if user requests
  materialize.
- `/remove-source <id>` — bot-admin only, requires confirm. Soft-delete
  only. The source row's `source_subscription` rows are
  cascade-deleted in the same transaction (the source is gone, so
  scope-level subscriptions to it must go too). `/unfollow-source`
  in contrast deletes only the caller's subscription; the source
  row stays. The "no remaining subscribers" state does **not**
  auto-soft-delete the source — sources can exist without
  subscribers and be re-followed later.
- `/source-enable <id>` — bot-admin only. Transitions a `failed`,
  `disabled`, **or soft-deleted** (`deleted_at IS NOT NULL`) source
  row back to `active`. Emits a probe (HEAD for HTTP-shaped,
  single-relay connection attempt for StreamSource-shaped) before the
  transition; probe failure leaves the source in its prior state with
  a friendly error. Audit-logged. Resets the consecutive-failure
  counter on success. **Soft-deleted sources require `confirm`**
  (reviving a deliberately-removed source has broader implications
  than re-enabling an operationally-failed one; the confirm step is
  the operator's explicit acknowledgement). On success, `deleted_at`
  is cleared and the row is returned to `active` status.
  **Subscriptions are not restored.** `/remove-source` cascade-deletes
  the source's `source_subscription` rows in the same transaction;
  `/source-enable` against a soft-deleted row clears `deleted_at` on
  the source row only and leaves the subscription rows deleted. The
  user-visible reply discloses this explicitly
  (e.g., `"Source re-enabled. No subscriptions were restored — affected
  scopes must /add-source again to re-subscribe."`) so the executing
  admin and re-subscribing users are not surprised by an empty
  per-scope subscription list. The disclosure is omitted on
  enable from `failed` or `disabled` (those transitions never
  cascade-deleted subscriptions). There is no
  separate `/source-undelete` command: `/source-enable` is the single
  admin recovery path for all non-active-non-hard-deleted states.
- `/source-disable <id>` — bot-admin only. Transitions an `active`
  source row to `disabled` (the operator-paused status, distinct
  from `failed`; `schema.md` §Sources and tags). The scheduler
  stops scheduling the source on the next tick; existing posts
  remain visible and `/list-sources --all` continues to list the
  row with its `disabled` status flagged. No probe is required (the
  operator is intentionally pausing the source). Audit-logged. The
  reverse path is `/source-enable`. This closes the `active ↔
  disabled` half of the source-status state machine that
  `schema.md` already commits to but that no command reached
  before this row.

### Per-scope tag preferences

The scope's tag-selection mode is recorded explicitly on
`scope_preferences.tag_mode ∈ {ALL, EXPLICIT}` (default `ALL`),
not implicitly via "any rows in `scope_tag`?" — implicit-mode logic
breaks down on edge cases like `/unfollow-tag` against an empty set
and makes the digest query depend on row presence.

- `/follow-tag <tag>` / `/unfollow-tag <tag>` — controls which tags
  appear in the scope's periodic digest. **Default for a fresh scope
  is "all tags from subscribed sources" (decision D15) — and the
  default is dynamic, recomputed at each digest run.** A scope in
  `ALL` mode opts into the union of tags currently attached to its
  subscribed sources at digest time, so a `/add-source` that
  introduces a new tag to that union takes effect on the next
  digest without requiring an explicit `/follow-tag`.

  Mode transitions:

  - `ALL` mode + `/follow-tag <tag>` → flip to `EXPLICIT` and seed
    `scope_tag` rows for **the followed tag only**. Matches the user
    mental model: "I asked for X, only X."
  - `ALL` mode + `/unfollow-tag <tag>` → flip to `EXPLICIT` and seed
    rows for **all currently subscribed-source `bootstrap_tags`
    minus the unfollowed tag**. Matches the user mental model: "I
    want everything except X."
  - `EXPLICIT` mode + `/follow-tag` / `/unfollow-tag` → add or
    remove the row in place. When the row count drops to 0, the
    mode flips back to `ALL`.

  Digest query: `ALL` mode uses the union of subscribed-source
  `bootstrap_tags`; `EXPLICIT` mode uses only the tags whose
  `scope_tag` rows exist for that scope.

- `/unfollow-tag --all` — bulk reset. Requires confirm. In any
  mode, deletes all `scope_tag` rows for the scope and sets
  `tag_mode = ALL`. The single command for "I want the dynamic
  default back."

### Conversation control

- `/clear` — wipes the calling (user, scope) active context window only.
  Chat memory is untouched (decision D25). Requires confirm — the live
  window is the only bridging context for an evolving conversation,
  so an accidental `/clear` is irrecoverable. The confirm step is the
  operator-friendly equivalent of "are you sure?"
- `/compress` — forces an immediate `chat_memory` checkpoint for the
  calling (user, scope). On success, **`chat_session` rows for
  `(user, scope)` are truncated**: the entire live context window is
  cleared after the memory entry is written. The truncation is the
  spec commitment; the exact retained-row count (0 vs. last-N) is
  design-tier and lives in design notes. Failure behavior is in
  `security.md` §Failure handling (session held at ceiling; no silent
  truncation).
  **Auto-compress** fires when the `chat_session` for `(user, scope)`
  occupies a profile-driven percentage of the context-window ceiling
  (value in design notes). The trigger is **deterministic** (a token-
  or byte-count threshold), never LLM-judged. It runs **between
  turns** — after the current reply is delivered and before the next
  message is processed — so a reply is never interrupted mid-stream
  by an auto-compress. On auto-compress, a **one-line system message**
  (localization-bundle string, not a prose summary) is sent to the
  user confirming the checkpoint was created. Probation users have
  chat mode blocked, so their `chat_session` cannot grow; auto-compress
  never fires during probation (correct by construction).
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
- `/digest on|off` — pauses (`off`) or resumes (`on`) the group's
  periodic morning/evening digest. Group only; group admin or bot
  admin. The `on`/`off` sub-verb is matched case-insensitively; a
  missing or unrecognized sub-verb produces a friendly usage error
  naming the two sub-verbs — never a silent no-op, never a
  fall-through. Mutates the per-group `groups.digest_enabled` flag
  (default `true`), audit-logged before effect. A call that requests
  the state the group is already in is a friendly no-op: it replies
  "already on"/"already off", performs no write, and writes no audit
  row, so repeated toggles do not spam the audit log. Pausing affects
  only the **scheduled** push: data collection is unaffected (the
  Collector ingests regardless) and on-demand `/summary` keeps working
  for a paused group. While a group is paused, `/retry --digest` is
  also rejected (friendly error) so a stale cached digest cannot be
  regenerated and re-sent around the pause.
- `/forget` — immediate purge of everything kept on the calling
  user's behalf. Per decision D37, this is the user-facing privacy
  lever. The exact purge set, called from any scope, is:
  - `chat_memory` rows for `(caller, calling_scope)`;
  - `chat_session` rows for `(caller, calling_scope)` (the live
    context window — without this, a user who `/forget`s to escape
    a runaway thread still sees it next time they message);
  - `summary_anchor` rows for `(caller, calling_scope)` with
    `command_kind = 'personal'` only (defensive — no leftover
    anchor pointing at posts from the prior session). The
    group-wide digest anchor (`command_kind = 'digest'`,
    `user_id IS NULL`) is **not** touched by `/forget`: it is
    not user-owned data — it is computed from the group's
    subscriptions and the global post set, and the same digest
    is sent to every group member; clearing it on one user's
    `/forget` would invalidate the next digest for the entire
    group;
  - `saved_post` rows for the caller — **globally, regardless of
    calling scope** (saves are per-user-globally per D13;
    `/forget` from any scope wipes the whole library).

  Does **not** touch `users.is_admin`, `users.is_banned`,
  `group_membership`, or any `audit_log` row (the audit log is
  append-only by invariant). Hard purge is the v1 contract; soft-
  delete tombstones would silently violate D37's privacy
  commitment. Audit-logged before effect like every privileged
  action against user state. **The audit row records counts only**
  — `chat_memory_count`, `chat_session_count`,
  `summary_anchor_count`, `saved_post_count` — never UID lists,
  personal tags, or any user-authored content. This satisfies the
  append-only audit invariant without leaking user-content into
  the audit surface (per `security.md` §Secrets handling
  user-content rule). Requires confirm. Idempotent: a
  second `/forget` with nothing to remove returns a friendly
  no-op reply (no audit row written for the no-op).

  **Remaining-scopes disclosure.** Because `/forget` is per-scope
  for `chat_memory` / `chat_session` / `summary_anchor` (and
  global only for `saved_post`), a user in multiple scopes will
  retain data outside the calling scope after the command runs.
  The `/forget` reply **must explicitly disclose** the count of
  other scopes (DM + groups) where chat-tier rows still exist for
  the calling user, and instruct them to issue `/forget` from each
  of those scopes to clear them — e.g., `"Cleared this conversation.
  You still have data in N other conversations; run /forget from
  each to clear them."` A user who treats `/forget` as a complete
  privacy purge without this disclosure would otherwise believe
  their data was fully removed when only the calling-scope slice
  was. The reply does **not** name the other scopes (naming a DM
  would leak existence to a co-admin running the command;
  enumerating groups is unnecessary for the user's privacy
  decision) — the count is sufficient. When the count is **zero**
  (the calling scope is the only scope holding the caller's
  chat-tier rows) the disclosure clause is omitted and the reply
  is the bare confirmation, e.g. `"Cleared this conversation."` —
  surfacing "you have data in 0 other conversations" is noise.
- `/export` — returns the calling user's own data. **Delivery is
  in-band**: the export is sent as a reply message (or paginated
  reply messages) on the same adapter channel as the command. No
  external URLs or out-of-band download links are generated. The
  output format and per-message size cap are in design notes; if
  the total export size exceeds the per-message cap, the reply is
  split into pages. Audit-logged before effect (same rule as every
  privileged action against user state). Rate-limited in the
  "parser-only + DB-read paginated" bucket (`security.md` §Rate
  limiting). Group output is
  defined by an **explicit table list and a field-level positive
  list**, not by a vague "the user's contributions" rule, so the
  boundary is testable.
  Tables included:
  - `chat_memory`, `scope_preferences`, `scope_tag`,
    `chat_session`, `source_subscription`, `summary_anchor` whose
    scope key matches the calling scope;
  - `saved_post` rows for the caller — **the full library
    regardless of calling scope** (saves are per-user-globally per
    D13);
  - the caller's own `users` row in full **except** `is_admin`,
    `banned_by`, `ban_reason`, `banned_at`, `probation_until`
    (these are authorization-state fields about the user, not data
    held on the user's behalf — exposing them is at best
    redundant, at worst a vector for confusion);
  - the caller's own `audit_log` rows (rows where
    `actor = self`); audit rows that mention the caller as a
    target without being authored by them are **not** included.
  Group `/export` is scoped to the calling `(user, group)` for
  per-scope tables and to the caller for `saved_post` — never
  another user's rows in any of those tables, never group-wide
  content (other members' messages, the group's `groups` row
  beyond `id` and `timezone`, audit log entries about other users),
  never any row outside the listed tables. DM `/export` follows
  the same shape with DM as the scope key. Output format and size
  cap are in design notes.
- `/stop` — cancels the calling (user, scope)'s currently in-flight
  interruptible request **immediately**, so the worker is freed for
  others. **Interruptible operations:** chat-mode agent loops,
  user-issued `/summary` prose generation, and user-issued `/retry`
  re-rolls (decision D35). **Not interruptible:** periodic group
  digests, the ingest pipeline, already-completed work, and
  `/retry --digest` (a group-admin operation that replaces the
  group's shared cached digest — `/stop` against an in-flight
  `/retry --digest` returns a friendly no-op with the reason; the
  digest retry continues). Mutating commands (source adds, ban,
  etc.) are never interruptible because their side effects may
  already be partially committed. The in-flight LLM
  stream is closed and any in-flight read-only tool call is
  abandoned: the worker discards the in-flight result, releases the
  DB connection, and moves on. **In v1 every tool in the closed
  allowlist (`security.md` §Prompt-injection defenses —
  `searchPosts`, `getPost`, `getReferences`, `recallMemory`,
  `listSaves`) is a read-only DB query**, so the cancellation
  primitive is `pg_cancel_backend(pid)` at the released connection,
  best-effort because Postgres may complete the query before the
  cancel takes effect. Tools added in future spec amendments MUST
  define their own cancellation primitive before being added to
  the registry; `/stop` semantics are spec-load-bearing and a new
  tool with no cancellation story would silently weaken the
  guarantee. As an additional safety net, every interruptible
  read-only query (chat-mode tool calls, on-demand `/summary`)
  runs under a profile-driven `statement_timeout` that bounds the
  worst case even when `pg_cancel_backend` fails. Once outbound
  delivery has begun the message is not unsent. Idempotent (no-op
  with a friendly reply when nothing is in flight).
  Audit-before-effect still holds — any audit row written before
  cancellation stays. The progress notifier (decision D31) renders
  a final "stopped" state on the in-place message. See decision
  D35.
- `/retry` [`--digest`] — regenerates the prose for the last
  summary-producing command. Re-runs the LLM stage only;
  deterministic post selection and clustering are reused unchanged
  (decision D19, schema.md §Summary anchor). **New posts that
  arrived since the original run are not pulled in** — the frozen
  UID set and cluster mapping recorded in `summary_anchor` are the
  complete input to the retry; the retry window is a design-tier
  value (in design notes). Bounded by a small fixed retry cap
  (value in design notes) anchored to that most-recent
  summary-producing command. **When the retry cap is exhausted,
  a friendly error is returned and the anchor is left intact** (not
  cleared); the user must issue a non-`/retry` command to move past
  the cap. Any non-`/retry` input from the same (user, scope) clears
  the anchor; `/retry` itself never advances or resets it. No effect
  (friendly error) when no eligible anchor exists, when the anchor
  has been cleared, when the prior command was cancelled by `/stop`,
  or when the prior command was not summary-producing.

  **Status filter on the frozen UID set.** The frozen UID set
  recorded in `summary_anchor` is **filtered against current post
  status** at retry time: any UID whose `post.status` is no longer
  `READY` (e.g., the post has since been quarantined by an admin)
  is excluded from the retry's prose input. This avoids two
  failure modes — (a) regenerating prose that mentions content the
  user can no longer see, and (b) leaving silent gaps with no
  user-visible explanation. If the filtered set differs from the
  original by more than a profile-driven threshold (or drops to
  empty), the user is told that the retry result differs from the
  original because content status changed since the prior run; the
  threshold and exact reply text live in design notes. An empty
  filtered set produces a friendly error and no LLM call.

  **Routing rules in groups.** A group has both per-member personal
  anchors (one per `(user, group)` from the user's last `/summary`)
  and a group-wide cached digest (per decision D17). The
  `summary_anchor` row's `command_kind` discriminator
  (`personal` vs. `digest`) drives routing:

  - Regular group member's `/retry` → matches the member's own
    most recent `personal` summary anchor in this group, if it
    exists. Group-admin-only access does not apply because the
    member is regenerating their own personal summary.
  - Group admin's `/retry`, with no personal anchor of their own
    → resolves to the group's cached `digest` anchor, if present
    and within the retry window.
  - Group admin's `/retry` with both a personal anchor and a
    cached digest → defaults to the personal anchor (the user's
    most recent action wins). Use `/retry --digest` to disambiguate
    explicitly when the admin wants the cached digest specifically.
  - Non-admin's `/retry --digest` → friendly error
    (group-admin or bot-admin only for digest replacement).
  - **DM `/retry --digest`** → friendly error: digest replacement
    applies in groups only (no group-wide cached digest exists in
    DM). `/retry` without the flag operates against the user's
    personal `/summary` anchor as usual.

  For periodic group digests, the retry replaces the cached digest
  (decision D17); the post selection is the **frozen** selection
  captured when the original digest was generated (the digest's
  slot window, not the wall-clock window at the moment `/retry`
  runs) — only the prose layer is re-rolled, so the digest does
  not silently drift forward as time passes.

  **Concurrent `/retry --digest` is per-group serialized.** At
  most one `/retry --digest` is in flight per group at any time.
  A second admin issuing `/retry --digest` while another admin's
  retry is still running receives the localized "a digest retry
  is already in progress for this group" friendly error
  (deterministic localization-bundle string per D43); no LLM
  call, no anchor read, no second `summary_cache` write. The
  in-flight retry runs to completion or is cancelled by the
  acting admin's `/stop`-equivalent (digest retries are
  non-interruptible per D35, so practically the second admin
  waits for the first retry to finish on its own). The
  serialization is keyed on the group, not on the issuing user
  — `/retry --digest` is a group-wide operation that mutates
  shared state (`summary_cache`), so two admins racing each
  other would otherwise spend two LLM calls and produce a
  last-writer-wins replacement with no signal to either caller.
  This rule extends the per-(user, scope) "at most one in-flight
  interruptible request" rule from §Surface conventions to the
  group-wide digest case.

  **Cached digest message handle.** The handle is held in process
  memory only (`messaging.md` §Message handles forbids handle
  persistence). After a Provider restart, a `/retry --digest`
  posts a *new* message (with prose noting it replaces the prior
  cached digest for subsequent reads); the original message is
  not edited because the handle is gone. See decision D36 and
  `messaging.md` §Failure handling for adjacent delivery rules.

### Admin (bot admin)

**Unknown-contact rule.** Unless explicitly stated otherwise (`/ban`,
which mints a `preban` row per `security.md` §User ban; `/invite
create --contact <id>`, which writes an `invite_code` row but no
`users` row), an admin command targeting an unknown
`(inbound_adapter, contact_id)` returns a friendly "contact is not
registered" error and writes no row. This applies uniformly to
`/grant-admin`, `/revoke-admin`, `/promote`, `/demote`, `/vouch`,
`/unban` (against a contact with no `users` row at all — distinct
from a `preban` row, which is the path covered by the `/unban`
deletion carve-out in `security.md` §User ban). Silently creating a
row on these paths would bypass the invite gate for elevated
contacts and contradict the registration-state model
(`schema.md` §Identity and access — User entity).

- `/promote <contact>` / `/demote <contact>` — group admin
  promote/demote, used inside a group. **The target must have an
  active `group_membership` row (`removed_at IS NULL`) in the
  group.** A contact with no active membership (never joined, or
  soft-cleared via left-group event) cannot be promoted; the
  reply is a friendly "contact is not an active member of this
  group" error. **Scoped to the inbound adapter** — both the
  targeted user and the targeted group must be on the inbound
  adapter; the `<contact>` argument resolves to a
  `(adapter, contact_id)` row on the same adapter the command came
  from. Cross-adapter group-admin operations are not exposed in v1
  (same convention as `/grant-admin`, for the same blast-radius
  reason). **Banned target rejection.** `/promote <X>` against a
  `users.is_banned = true` target is rejected with a friendly error
  directing the admin at `/unban` first; promoting a banned user
  to group admin would be incoherent (the banned-user check
  short-circuits every inbound message before any group-admin
  permission would matter). The banned-admin lockout escape hatch
  in `security.md` §Authorization model handles the orthogonal
  case where the *current* group admin is banned and a *different*
  member is being promoted to take the slot.
- `/grant-admin <contact>` / `/revoke-admin <contact>` — **scoped to
  the inbound adapter** (one Provider may run multiple adapters per
  `deployment.md` §Topology, and admin grants do not cross adapters
  in v1). The `<contact>` argument refers to a contact id on the
  same adapter the command came from; mutating an admin row on a
  different adapter requires running the command from that adapter.
  This bounds the blast radius of an adapter compromise: a
  compromised admin on one adapter cannot silently elevate a
  contact on another. Last-admin protection applies **globally
  across adapters** — at least one `is_admin = true` row must
  exist anywhere on the deployment after any revoke.
- `/ban <contact> [--reason …]` / `/unban <contact>` — bot-wide ban
  flag, but the `<contact>` argument is **scoped to the inbound
  adapter** (same convention as `/grant-admin`): the targeted row is
  the `(inbound_adapter, contact_id)` users row, and a contact with
  the same byte value on a different adapter is a different `users`
  row that this command does not touch. The ban itself is bot-wide
  in the sense that the targeted row is blocked across every scope
  (DM and groups) on its adapter; banning the same human on a
  second adapter requires running `/ban` from that adapter. Cannot
  ban self or last admin. The `/unban` reply **must enumerate the
  side-effects** so the executing admin understands the post-condition:
  - if the row's `registration_state = 'preban'`, the reply states the
    pre-ban-only row was deleted and a fresh invite is required for DM;
  - otherwise, the reply lists every group whose `is_group_admin = true`
    is being reinstated for this user, with a `/demote <contact>` hint
    for cases where group-admin restoration was unintended (see
    `security.md` §User ban). An `/unban` of a row with neither
    pre-ban status nor restored group-admin rows produces the plain
    "user unbanned" reply. The audit row carries the same details
    under `details_json`.
- `/invite create --adapter <name> --contact <id>` — generate a single-use
  UUID invite code bound to the given (contact\_id, adapter) pair (decision
  D44). The code is displayed once in the reply and stored as PENDING. No
  confirmation required (risk is bounded to one specific identity).
  Audit-logged before effect.
- `/invite create --adapter <name> --open` — generate a single-use UUID invite
  code bound to the adapter only; the first unknown contact on that adapter to
  present the code is registered. Requires confirm (broader blast radius than
  `--contact`). Audit-logged before effect.
- `--contact` and `--open` are mutually exclusive. Providing neither returns a
  hint listing both flags and their trade-offs; no invite is created.
- **Cross-adapter invite creation is permitted by design.** Unlike
  `/grant-admin` and `/vouch` (which are restricted to the inbound adapter),
  `/invite create` carries an explicit `--adapter <name>` argument and may
  name any adapter supported by the deployment. A SimpleX admin may create a
  Signal invite for a contact they want to onboard. The security trade-off is
  acceptable because the invite code is bound to the named `(adapter,
  contact_id)` pair — it creates no elevated access; it only opens the
  registration gate on that adapter. The cross-adapter action is constrained by
  the per-adapter PENDING caps (`security.md` §Invite-code registration) and
  by the audit trail. `--adapter <name>` is validated against the set of
  currently-enabled adapters at parse time; naming an unknown adapter is a
  friendly error.
- `/invite list [--page N]` — list PENDING invite codes with target contact,
  adapter, and expiry.
- `/invite revoke <code>` — immediately transition a PENDING code to REVOKED.
  Requires confirm.
- `/vouch <contact>` — immediately graduate a user from the slow-start
  probation tier to full access (decision D45). Sets
  `probation_until = NULL`. No-op with a friendly reply if the user
  is already past probation. **Scoped to the inbound adapter**
  (same convention as `/grant-admin`): the targeted row is
  `(inbound_adapter, contact_id)`. Vouching the same human on a
  second adapter requires running `/vouch` from that adapter.
  Audit-logged.
- `/approve-group <group_id>` — approve a pending group for bot
  interaction (D47). The `<group_id>` is the internal UUID shown in
  the pending-group notification the admin receives (or in
  `/list-groups` output). On approval: `approval_status` transitions
  from `'pending'` (or `'rejected'`) to `'approved'`. The bot sends a
  one-time "group approved" message to the group. Periodic digests
  begin scheduling for this group. Auto-promote fires for the
  first eligible registered, non-probation member on the next
  @mention (with `activated_by` priority per security.md §step 3.5).
  Audit-logged. Approving an already-approved group is a no-op
  with a friendly reply. No confirm required (constructive action).
  Bot-admin only; DM or group context (the admin need not be a
  member of the target group).
- `/reject-group <group_id>` — reject a pending (or approved)
  group (D47). On rejection: `approval_status` transitions to
  `'rejected'`. The bot sends a one-time "group rejected by admin"
  message to the group. Periodic digests stop for this group (if
  they were active). Members can still see the bot in the group
  but @mentions from registered users receive the fixed "rejected"
  reply. Audit-logged. **Requires confirm** (destructive — stops
  digests, blocks interaction). Rejecting an already-rejected
  group is a no-op with a friendly reply. Bot-admin only; DM or
  group context.
- `/list-groups [--page N]` — list all groups the bot is aware
  of, with `approval_status`, `activated_by` (redacted contact id),
  member count, and timezone. Bot-admin only. Useful for auditing
  which groups are pending/approved/rejected.
- `/quarantine list [-w …] [--all] [--page N]` — bot-admin only
  (closed list below; the whole command is privileged, so `--all`
  is not a tier-changing flag — it changes the row filter, not
  the permission). The review-status enum is
  `{PENDING, BENIGN_CLOSED, APPROVED, REJECTED}`
  (`schema.md` §Posts and derivatives, Quarantine entry). Default
  lists `PENDING` rows only — the active admin queue.
  `BENIGN_CLOSED` rows (Stage 2 cleared, redactions retained) are
  not surfaced by default; `--all` lists every status for
  forensic / audit workflows.
- `/quarantine approve <id>` / `/quarantine reject <id>` — review
  action. Both run as stored procedures (`security.md` §DB roles)
  so the Provider role does not need `SELECT` on the raw-original
  column. Approve transitions `PENDING → APPROVED` (or
  `BENIGN_CLOSED → APPROVED`), restores the redacted span, and
  fires `NOTIFY new_post` for the post (so the Provider re-renders
  the now-unredacted body via the standard high-water-mark path —
  `architecture.md` §Inter-service communication); reject
  transitions to `REJECTED` (from `PENDING` for the routine path,
  from `BENIGN_CLOSED` for the forensic path) and leaves the
  placeholder permanently.
- `/audit [-w …] [--actor <contact>] [--action <verb>] [--page N]`
  — bot-admin only (closed list below). Reads `audit_log_view`
  (the redacted view; `security.md` §DB roles) with filters. The
  view is not scoped to the calling scope; a bot admin sees the
  deployment-wide audit history. **Argument shapes.**
  `--actor <contact>` accepts a contact id resolved against
  `(inbound_adapter, contact_id)` (same shape as `/promote`,
  `/ban`, `/vouch` — the inbound adapter is the one the command
  arrives on); cross-adapter actor lookup is not supported in v1.
  `--action <verb>` is one of the closed audit-action enum
  (values in design notes); an unknown verb returns a friendly
  error listing the accepted values. `--page N` is 1-indexed;
  page size is profile-driven. **Unknown actor id** (a
  well-formed contact id with no matching `users` row on the
  inbound adapter) returns the same "no audit rows" reply as a
  known id with no rows in the window — the
  existence-vs-no-rows distinction is not exposed. v1 ships no
  `/list-users` command; bot admins enumerate via the existing
  audit history.

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
- **Admin-only flags are part of command identity.** A flag listed
  in the closed bot-admin set below (e.g. `/list-sources --all`,
  `/list-sources --include-deleted`, `/quarantine list --all`) is
  treated as inseparable from its command for permission purposes.
  A non-admin caller passing such a flag receives a friendly
  permission error — the parser **never** silently strips the flag
  and runs the un-flagged variant. Silent flag-strip would mask the
  caller's intent and produce a result they did not ask for; the
  spec commits to the explicit-error behavior.

**Closed list of privileged-tier commands (spec level).** The
following enumeration is the load-bearing closed set used by the
LLM output sanitizer (`security.md` §LLM output sanitizer
"Match-set derivation") and the slow-start probation classifier
(`security.md` §Slow-start tier). Adding a command to or removing
one from these tiers is a **spec amendment**, not a design-tier
edit, so the sanitizer match set and the probation deny set
cannot silently shrink across versions.

- **Bot-admin only:** `/grant-admin`, `/revoke-admin`, `/ban`,
  `/unban`, `/promote`, `/demote`, `/vouch`, `/invite create`,
  `/invite list`, `/invite revoke`, `/quarantine list`,
  `/quarantine approve`, `/quarantine reject`, `/audit`,
  `/remove-source`, `/source-enable`, `/source-disable`,
  `/list-sources --all`, `/list-sources --include-deleted`,
  `/approve-group`, `/reject-group`, `/list-groups`.
- **Group-admin (or bot admin acting in the group):**
  `/add-source` in groups, `/unfollow-source` in groups,
  `/lang` in groups, `/group-timezone`, `/digest`,
  `/follow-tag` in groups, `/unfollow-tag` in groups.

The full per-actor-tier matrix (which DM / group-member commands
are allowed to non-privileged users, plus the per-flag splits like
`/list-sources --all`) lives in `docs/design/03-commands.md`; the
**closed set above** is the spec-level commitment that the
sanitizer and probation classifier read from.

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

Once registered (via DM invite), the user enters the slow-start
probation tier (decision D45). The welcome message informs the user of the
probation window and the reduced command set available until it elapses.

The welcome message branches on two modes (DM-fresh, DM-returning)
so the user is steered toward an action that will not be empty (decision D23).
The pre-D47 'group-first-mention' branch is removed — under D47, a user's
first group interaction is not an onboarding event (they were already
registered via DM). No group-side welcome message is sent; the user's DM
welcome covered the onboarding. Exact wording in design notes.

**Previously-banned, now-unbanned users.** When a banned user is
`/unban`ed, the bot does **not** proactively send a "you were
unbanned" message — proactive contact would surface the existence of
the ban to a user who has not chosen to interact again, and would
also ping a user who never knew they were banned in the first place.
The next inbound message from the unbanned user is treated as the
DM-returning case. Since D47 removed group auto-registration, the
unbanned user's next group @mention is a normal group interaction
(no welcome branch fires in group context). The `/unban` action
itself is audit-logged as always; surfacing it to the affected user
is deferred to v2 if it surfaces at all.

## Operator note: group-admin race

Under D47, only registered users (`registration_state IN
('invited', 'vouched')`) can interact with the bot in groups.
The `activated_by` user has auto-promote priority in an approved
group (if eligible: registered, non-probation, non-banned);
otherwise the first eligible @mention wins the slot. In a group
where no member is registered with the bot, the bot is
invisible — no auto-promote race is possible. The operator must
ensure at least one group member is registered (via DM invite)
before the group can be activated.

## Periodic group digests

The digest scheduler selects groups where
`approval_status = 'approved' AND removed_at IS NULL` (D47).
A group whose `approval_status` transitions away from `'approved'`
(via `/reject-group`) is excluded from the next scheduling pass;
no catch-up digest is emitted when a group transitions to
`'approved'` (same skip-not-catch-up rule as missed-slot
behavior). Pending and rejected groups never receive periodic
digests.

Groups receive a morning and evening digest at per-group local times
(decision D16). The digest **slot hours** are
**operator-configured globally** — two settings, "morning slot
center hour" and "evening slot center hour", expressed in 24-hour
local time of the group (`groups.timezone`). Both values live in
`deployment.md` §Configuration surface §Groups; the defaults are
profile-driven and live in design notes. v1 has **no per-group
override** for the slot hours — every group on the deployment
fires its morning digest at the same local hour and its evening
digest at the same local hour, each interpreted in that group's
own timezone. (Per-group hour overrides are a v2 candidate; v1
keeps the configuration surface narrow because adding a per-group
override requires a new command, a new `groups` column, and a
matching audit row, none of which any review report flagged as
needed.)

Each digest fires within a **profile-driven window centered on
the configured local hour** (window width is in design notes), so
the worker pool isn't slammed by hundreds of groups all asking at
the same minute. The overload fallback (headlines + sources, no
LLM prose, decision D17) fires when the window-end is reached
without the digest having started — the operator's "the worker
pool is saturated" recovery path. Results are cached briefly so a
follow-up `/summary` from the same group during the cache TTL is
served from cache (no second LLM call).

**Zero-eligible-posts digest.** When a digest slot fires and there
are no eligible posts for the group (no active subscriptions, or
subscribed but nothing arrived in the window), the digest sends a
fixed **"no posts yet"** reply — the same deterministic
localization-bundle string as `/summary` §Content empty window —
rather than silently sending nothing. A silent digest slot would
be indistinguishable from a missed slot, leaving the group admin
with no signal that the bot ran and simply found nothing.

**Missed slot behaviour.** When the Provider is down for the entire
slot window of a group, the missed slot is **skipped, not caught
up**: digests are time-of-day signals, not strictly-once events,
and a digest delivered at noon for an 08:00 slot is operator
noise, not user value. The skip is recorded as a per-group
`digest_slot_missed` audit row (one per missed slot, no
throttling — sustained misses indicate a deployment problem the
operator must see) and increments a `digest_slots_missed_total`
counter labelled by group. The next slot fires normally on its
own schedule. There is no operator-visible chat surface for
missed digests in v1 — operators read the audit log or counter.
A slot window the group was **paused through** via `/digest off`
(re-enabled only after the window had already ended) is likewise
neither caught up nor recorded as missed — symmetric to the
approval carve-out above: the group was intentionally disabled for
that window, so the absent digest is expected, not a missed slot.

**Degraded-fallback exit.** A degraded slot (headlines + sources,
no LLM prose) **does not** affect any subsequent slot: each slot's
mode is decided independently when its own window ends, so the
next slot runs full-prose unconditionally if the worker pool is
healthy at that point. A degraded slot writes to the same
`summary_cache` row as a full-prose slot would, with the **same
TTL**, so a follow-up `/summary` during the cache window is served
from the degraded cache (no silent re-render). `/retry --digest`
on a degraded slot regenerates **full prose** if the worker pool
is now free (the retry replaces the cached digest per D17 and the
cluster set is the frozen original), and degrades again to
headlines+sources only if the retry itself hits the same
saturation. There is no saturation back-off across slots — a
sustained-overload signal is the throttled admin notification
already in `security.md` §Failure handling, not a per-group
slot-skipping policy.

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
