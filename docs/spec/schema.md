# Data model

This file describes the entities, the relationships between them, and the
invariants the schema is required to uphold. Concrete DDL — column types,
indices, partitioning strategy, triggers, denormalized counters — lives in
`docs/design/02-schema.md`.

The two services share one schema; they differ only in the DB role they
connect with (see decision D34 and `security.md`).

## Entities

### Identity and access

- **User.** A person, identified by the messaging adapter's cryptographic
  contact ID. Carries the bot-admin flag, the ban flag (with reason and
  audit), the `probation_until` timestamp (null means full access; non-null
  means the user is in the slow-start tier until that instant — decision D45),
  and informational metadata (display name, last-seen, save count).
- **Group.** A messaging-adapter group the bot is a member of. Carries
  a per-group timezone for digest scheduling (defaults to the
  operator-configured default — `UTC` out of the box; mutated at
  runtime by `/group-timezone`), a nullable `removed_at` timestamp
  set when the bot is removed from the group and cleared on re-add
  (`messaging.md` §Failure handling), and informational metadata.
  Group state (subscriptions, `scope_tag`, `chat_memory`,
  `chat_session`, members' saves) is preserved across remove/re-add
  cycles.
- **Group membership.** The (user, group) join, with a per-group
  admin flag. Schema must enforce *at most one* group admin per
  group at any time (decision D9).
  **User-departure lifecycle.** When the messaging adapter signals
  that a user has left a group (a `user_left_group` adapter event,
  or a permanent send failure to that specific user surfaced by
  the adapter), the row is **soft-cleared, not deleted**: a
  nullable `removed_at` timestamp is set, mirroring the
  `groups.removed_at` semantics for the bot's own membership. The
  row is preserved against accidental leave/rejoin cycles (the
  user's per-(user, group) `chat_memory`, `chat_session`, and
  `summary_anchor` rows are likewise preserved). On rejoin the
  adapter signal clears `removed_at` and the prior state is
  visible again.
  **Interaction with the group-admin slot.** When the user being
  cleared was the group admin (`is_group_admin = true` on this
  row), the soft-clear **also clears `is_group_admin`** in the
  same transaction, freeing the partial unique index slot. The
  group is then admin-less until the next bot-admin `/promote` or
  the next first-mention auto-promote; this matches the existing
  rule (security.md §Authorization model) that "first non-banned,
  non-probation @mention wins" applies whenever the group has zero
  admins. A user who left and rejoins as a regular member does
  **not** automatically reclaim `is_group_admin`; the slot is
  refilled by the standard mechanisms.
  **Eligibility for the auto-promote.** A row with
  `removed_at IS NOT NULL` is **not eligible** as a "first
  @mention" winner — the auto-promote only fires when the user
  rejoins (`removed_at` cleared) and a fresh @mention arrives
  while the slot is empty. This avoids ghost rows from
  long-departed members claiming the slot on a stale row alone.
- **Invite code.** A single-use token that gates DM registration
  (decision D44). Keyed by a UUID code value. Carries:
  - `invite_type ∈ {CONTACT_BOUND, OPEN_ADAPTER}` — discriminator
    for the two issuance flavours (decision D44: `--contact <id>`
    vs. `--open`).
  - `adapter` — adapter name the code is bound to.
  - `expected_contact_id` — nullable; non-null iff
    `invite_type = CONTACT_BOUND`. CHECK constraint enforces this
    iff-relation at the schema layer.
  - `status ∈ {PENDING, USED, REVOKED}`. **Note:** there is no
    stored `EXPIRED` status. The intake path treats a row with
    `status = 'PENDING' AND expires_at < NOW()` as expired — same
    fixed friendly reply as a missing code — without ever writing
    a state transition. Removing the denormalized status keeps the
    state machine to one column with one allowed transition path
    (`PENDING → USED` or `PENDING → REVOKED`).
  - `created_by` — the issuing bot admin's user id.
  - `created_at`, `expires_at` (null = no expiry), `used_at` (null
    until consumed), `used_by_contact_id` (null until consumed).

  Invariants: a `USED` or `REVOKED` code can never transition back to
  `PENDING`. Hard delete is forbidden — invite codes are audit
  artifacts. Single-use atomicity (the consume race) is enforced by a
  conditional UPDATE: `UPDATE invite_code SET status = 'USED',
  used_at = NOW(), used_by_contact_id = $1 WHERE code = $2 AND
  status = 'PENDING' AND (expires_at IS NULL OR expires_at > NOW())
  AND adapter = $3 AND (invite_type = 'OPEN_ADAPTER' OR
  expected_contact_id = $1) RETURNING id`. A returning-row count
  of 0 means the code was already consumed, revoked, expired, or
  bound to a different contact — all surface as the same fixed
  rejection reply (no information leak about which condition failed).
- **Audit log.** Append-only record of every privileged action and
  bootstrap. Indexed for time-range and per-actor lookup.

### Sources and tags

- **Source.** A globally-unique feed, keyed by `(kind, identifier)` where
  `kind` is the ingest type (e.g. `rss`, `bluesky`, `nostr`) and
  `identifier` is the URL for HTTP-shaped sources or the filter spec for
  stream sources (decision D38). Carries an opaque per-kind `config`
  block, category, bootstrap tags (the tagger's deterministic fallback
  per decision D22), status, and soft-delete state. Hard delete is
  forbidden in v1 so saved-post references always resolve.

  **Status state machine.** A source is in exactly one of three statuses:
  `active` (the fetcher / stream worker schedules it normally), `failed`
  (consecutive ingest failures crossed the per-kind threshold —
  `security.md` §Failure handling and decision D42 for HTTP-shaped
  sources, decision D38 for stream sources — so the worker stops
  scheduling it and the throttled admin notifier has been pinged),
  `disabled` (operator or admin paused it without removing it). Status is **orthogonal** to
  the soft-delete column `deleted_at`: the latter records "user removed
  this from a scope and the source has no remaining subscribers" and
  hides the row from listings. The fetcher / StreamSource scheduler
  selects rows where `status = 'active'` AND `deleted_at IS NULL`.
  Transitions: `active → failed` is set by the worker on threshold
  crossing; `failed → active` is set by an admin recovery command or by
  a successful manual probe; `active ↔ disabled` is set by an admin
  command; `disabled → failed` cannot happen (a disabled source isn't
  scheduled, so it can't fail).
- **Source subscription.** A (scope, source) link. DM scope is per user;
  group scope is shared.
- **Tag.** A row in the controlled vocabulary (Tier 1, decision D5).
  Seeded by the bootstrap loader and extended by `/add-source --tags`.
  **Stored form.** Every tag value is stored in its
  post-normalization form per `commands.md` §Surface conventions
  (NFC, lower-cased via `Locale.ROOT`, character class
  `[a-z0-9][a-z0-9-]{0,47}`). The same normalization runs at
  ingest and at every read/write site, so two values that hash
  differently before normalization (e.g. NFC vs NFKC homoglyph
  variants, mixed-case duplicates) collapse to a single row.
- **Scope tag.** Per-scope follow / unfollow preference for digest content.
  Each row corresponds 1-1 with a `/follow-tag` user action; `/unfollow-tag`
  removes the row. Default for a fresh scope is "all tags from subscribed
  sources" (decision D15) — the absence of any rows in this entity for a
  scope means "all tags," not "no tags." The set of legal tag values is
  the controlled vocabulary.

  **Vocabulary lifecycle (v1).** The controlled vocabulary is
  **append-only in v1**: tags enter via the bootstrap loader's
  `tags[]` union (decision D8) and `/add-source --tags` on a fresh
  insert (decision D14, decision D5); **nothing removes a tag row**.
  Reducing the JSON, soft-deleting a source, and `/remove-source`
  are all silent on the vocabulary. A bot admin replacing
  `bootstrap_tags` on an existing source row (commands.md §Source
  management) **adds** any new values to the vocabulary and leaves
  any removed values in place. This is a deliberate v1
  simplification: an automatic GC trigger that walked
  `bootstrap_tags ⋃ scope_tag` and removed unreferenced rows
  would race with concurrent `/follow-tag` writes and require a
  separate locking story; the operational cost of a slowly
  growing vocabulary is bounded and acceptable for v1. Removal
  is a v2 candidate (likely an admin command surfaced via
  `/vocab prune` or similar). Until v2, `/follow-tag` may accept a
  tag whose only contributing source was removed long ago — the
  digest query intersects the vocabulary against
  `bootstrap_tags` of currently-subscribed sources, so a stale
  vocabulary entry with no current contributor matches no posts
  and produces no user-visible content.

### Posts and derivatives

- **Post.** The fetched, sanitized, evaluated unit. Carries status
  (`RAW`, `READY`, `QUARANTINED`, `NEEDS_REVIEW`), Stage-1 / Stage-2 /
  tagger / embedding outcome flags, body, title, URL, timestamps, and
  Tier-1 tags. The status state machine: ingest enters `RAW`; Stage 2
  verdict `BENIGN` (or no Stage 2 because Stage 1 was clean) → `READY`;
  Stage 2 verdict `INJECTION` / `MALWARE` / `UNKNOWN` → `QUARANTINED`;
  re-evaluation queue (security.md §Re-evaluation job) exhausting its
  per-post attempt cap → `QUARANTINED → NEEDS_REVIEW`. Transitions
  from `NEEDS_REVIEW`: only `/quarantine approve` lifts to `READY`; a
  later non-`BENIGN` re-eval verdict (rare — only if the post is
  re-queued by an admin) returns it to `QUARANTINED`. When the
  admin-review TTL (Invariant 6) fires on a quarantine row attached
  to a `NEEDS_REVIEW` post, the row auto-transitions to `REJECTED`
  and the post transitions `NEEDS_REVIEW → QUARANTINED` (the
  placeholder body becomes permanent). No admin notification is
  sent for the TTL-driven auto-reject — the throttled notifier
  already paged when the post entered `NEEDS_REVIEW`. `NEEDS_REVIEW`
  is the durable signal that "the system gave up trying to classify
  this; it stays hidden until an admin acts" — distinct from
  `QUARANTINED` which is "the system has classified this as
  hostile/unknown and the re-eval job may still touch it." Each post
  has a stable **UID** derived deterministically from `(source_id,
  upstream_identifier)` — the per-source canonical id (RSS `<guid>`,
  Nostr event id, Bluesky AT-URI, etc.) — with a content-hash
  fallback when the source provides no usable upstream identifier
  (canonicalization rules: §UID derivation below). The UID is unique
  globally and is the dedup key for refetches and cross-relay
  redelivery (decision D38). It is also the user-visible handle for
  `/save`, `/unsave`, and quarantine review.
- **Post entity.** Named entities extracted from a post; used for Tier-2
  cross-source linking (decision D6: hybrid named-entity match for
  precision plus cosine similarity over embeddings for recall).
- **Post embedding.** Vector for a post; profile-driven index type
  (decision D27). **Optional** — a post may reach `READY` without an
  embedding when the embedding stage exhausted retries and was released
  per decision D22. Semantic-similarity queries (cross-source linking,
  hybrid recall) MUST filter `WHERE embedding IS NOT NULL`; deterministic
  retrieval (`/summary`, `/saved`) is unaffected and still returns
  embedding-less posts.
- **Post reference.** Edges in the cross-source link graph
  (`link_type`, `score`). TTL'd by partition drop (decision D33).
- **Quarantine.** One row per Stage-1 or Stage-2 hit, holding span offsets,
  the verbatim original, and a review status `∈ {PENDING, APPROVED,
  REJECTED}`. Original content is reachable only by the admin DB role
  (decision D34). `/quarantine list` shows `PENDING` rows by default;
  `--all` (bot-admin only) shows every status.

#### UID derivation

The post UID is stable globally across Collectors and across
re-fetches; it is the dedup key for refetches and cross-relay
redelivery (decision D38).

- When the source provides a stable upstream identifier (RSS `<guid>`,
  Nostr event id, Bluesky AT-URI, etc.) the UID is
  `sha256(source_id || '|' || upstream_identifier)` lower-case
  hex-encoded.
- When the source provides no usable upstream identifier, the UID
  falls back to `sha256(source_id || '|' || canonical_body)`
  lower-case hex-encoded.
- The **canonical body** is the Unicode-NFKC-normalized text body
  with source-kind-specific volatile sections stripped (e.g. for
  RSS: ad-tracking query parameters and the `<pubDate>` element are
  removed before hashing). The per-kind canonicalization rules live
  in design notes; the requirement that the rule exists per kind is
  spec.

The canonicalization step closes the brute-mutation evasion path:
two minimally different bodies that share semantic content (because
only volatile metadata changed) hash to the same UID and dedup
correctly.

UID derivation runs **before Stage 1**, against the raw fetched body
(after transport decoding but before HTML sanitization, NFKC
normalization, or regex redaction). Canonicalization strips
source-kind volatile metadata only, never system-generated artifacts
such as `[REDACTED:<id>]` placeholders. This guarantees UID
stability across refetch and across `/quarantine approve` lifting
redactions: the same upstream content produces the same UID
regardless of how many quarantine cycles it has been through.

### Per-user state (scope-independent)

- **Saved post.** Per-user library entries with personal tags
  (decision D13). **`/save` is per-user-globally** — a save made in
  DM is visible in every group the user is in, and vice versa.
  Saves are intentionally personal bookmarks, not scoped to the
  conversation in which they were made. Saved bodies are
  **snapshotted** so retention TTL on the underlying post does not
  break the bookmark (decisions D13, D33). This is the single
  documented exception to invariant 1 (per-(user, scope) isolation):
  `saved_post` carries a user id only and no scope discriminator.
  See also `/forget` (purges all of the caller's saves regardless
  of calling scope) and `/export` (includes the caller's full save
  list regardless of calling scope).

### Per-scope state

- **Scope preferences.** Per-(scope) language, subscription
  versions (counters used to invalidate cached digests on subscription
  changes), and `tag_mode ∈ {ALL, EXPLICIT}` defaulting to `ALL`
  (the digest-tag selection mode that backs the dynamic-default rule
  in commands.md §Per-scope tag preferences). Per-tag digest
  preferences live in **Scope tag** below (the v1 entity backing
  `/follow-tag` / `/unfollow-tag`); this row does not duplicate them.
- **Summary anchor.** Per-(user, scope) row capturing the last
  summary-producing command's deterministic payload: command name,
  argument hash, post UIDs (ordered), cluster mapping,
  `generated_at`, and a `command_kind` discriminator (`personal` /
  `digest`). Read by `/retry` to replay deterministic post selection
  and clustering (decision D19, D36). Cleared by any non-`/retry`
  input from the same `(user, scope)`. Survives Provider restart for
  the bounded retry window — this is what makes `/retry` work after
  a controlled bounce. The anchor is a separate entity from
  `chat_session` so the live context window's TTL/clear semantics
  do not couple to retry storage. Anchor rows carry the same
  retention discipline as `chat_memory` (Invariant 9): a
  profile-driven horizon, removed by the same scheduled pruner.
  The "bounded retry window" framing above is the user-facing
  semantics; the pruner is the operator-facing reclamation that
  guarantees a user who walks away does not leave anchor rows
  behind indefinitely. Chat-mode interactions that internally
  call a summarization tool do **not** write a `summary_anchor`
  row and are not replayable via `/retry`. Only top-level
  summary-producing commands (`/summary`, periodic digests,
  `/retry` itself) write anchors.
- **Chat memory.** Per-(user, scope) compressed memory entries created
  by `/compress` and consumed by the chat agent's recall path. Subject
  to a fixed TTL (decision D37); `/save`d posts are independent and
  not affected. The per-(user, group) shape is **per decision D26**:
  there is no shared group memory in v1 — each member of a group has
  their own memory rows, scoped to (user, group), the same privacy
  model as `/save`. **Cross-scope isolation invariant.** Recall in
  scope `S` retrieves only rows whose scope key equals `S`; DM
  memory never surfaces in any group, and one group's memory never
  surfaces in another. Verified end-to-end (verification.md).
- **Chat session / context window.** Per-(user, scope) live context state,
  **persisted in the database** (not in-process). Persistence is required
  by two spec commitments: auto-`/compress` near the context-window
  ceiling (decision D24) needs the full live history to summarize, and
  the `/retry` anchor (decision D36) survives Provider restart for the
  bounded retry window. `/clear` wipes only this entity for the calling
  `(user, scope)`; `chat_memory` is independent (decision D25). Per-(user,
  scope) isolation is enforced by the same scope discriminator as every
  other user-state row (invariant 1).

### Operational

- **Provider state.** Catch-up high-water marks for the
  `LISTEN/NOTIFY` reconciler (see `architecture.md`). **One row per
  channel** keyed by `channel`, holding `(channel, last_ready_at,
  last_post_id, updated_at)`. A `UNIQUE` constraint on `channel`
  enforces the singleton-row-per-channel semantics at the schema
  layer. The first-boot insert uses
  `INSERT INTO provider_state (channel, last_ready_at, last_post_id,
  updated_at) VALUES (:ch, ...) ON CONFLICT (channel) DO NOTHING`;
  two fresh Provider instances starting concurrently both attempt
  the insert, exactly one wins, and the winning instance owns the
  cursor — no duplicate rows can be produced by the first-insert
  race. Updates are compare-and-swap so a slow processor cannot
  roll back a fast one's mark:
  `UPDATE provider_state SET last_ready_at = :new_ready_at,
  last_post_id = :new_post_id, updated_at = NOW()
  WHERE channel = :ch AND (last_ready_at, last_post_id) <
  (:new_ready_at, :new_post_id)`. The cursor is the
  `(ready_at, post_id)` pair (not `ready_at` alone) so two posts
  sharing a `ready_at` are both processed on catch-up — the
  earlier-id post advances the mark to itself, the later-id post
  advances it to itself in the same transaction as its side effect.
- **Summary cache.** Pre-generated periodic-digest output keyed by group,
  slot, and subscription versions, with a short TTL (decision D17).
- **Admin notification state.** Backing store for the throttled admin
  notifier (decision D22).
- **Asset config.** One row per `(asset, sub_verb)` pair (decision
  D39). Carries `enabled` flag, `default_quote_currency`,
  `attribution_url`, `consecutive_failures`, `last_success_at`,
  `last_failure_at`, an `is_default` flag (true on **at most one**
  row per `asset` — enforced by a partial unique index — marks
  which sub-verb resolves bare `/zcash` / `/monero` per
  `commands.md` §Asset commands; absent default → bare invocation
  returns the "not configured" friendly error), and
  `status ∈ {active, failed, disabled}` — same status taxonomy as
  `source.status`. The bootstrap loader
  upserts entries from `bootstrap-assets.json` at Collector
  startup; entries absent from the latest bootstrap are
  `enabled = false` (soft-disable), never hard-deleted, so prior
  `price_snapshot` rows remain queryable for audit. The Collector's
  asset Fetchers schedule from this table; D42's per-source
  failure-counter model applies. The Provider has `SELECT` on this
  table and uses it to (a) decide which asset commands appear in
  `/help`, (b) accept or reject sub-verb arguments at parse time,
  (c) surface stale-data warnings when `last_success_at` is too
  old per the freshness contract.
- **Price snapshot.** One row per `(asset, sub_verb, captured_at)`
  (decision D39). Columns: `asset` (FK to `asset_config`),
  `sub_verb`, `captured_at`, `price`, `currency`, `source_url`,
  `raw_payload` (JSONB — exactly the upstream response's relevant
  fragment, kept for forensic replay). **INSERT-only**; no updates.
  Partitioned on `captured_at` and aged out by partition drop
  (Invariant 6) on a profile-driven retention horizon long enough
  that "the latest snapshot for an enabled `(asset, sub_verb)`" is
  always present and short enough that the table does not
  unbounded-grow. The "latest snapshot" query reads the row with
  the largest `captured_at` for the given `(asset, sub_verb)`,
  backed by an index on `(asset, sub_verb, captured_at DESC)`.
  Provider role has `SELECT`-only as already specified in
  `security.md` §DB roles. NOTIFY `new_price_snapshot` is the
  latency optimization; the table read is the correctness
  guarantee.

## Invariants

These are non-negotiable; the schema, triggers, and queries must enforce
them together. They are tested in CI (see `verification.md`).

1. **Per-(user, scope) isolation.** Every row that holds user state carries a
   scope discriminator (`'dm'` or `'group'`) and a scope id (or equivalent
   FKs). Every query against user state filters on both. **Exception:**
   `saved_post` is per-user-globally (decision D13) — it carries a user
   id only, no scope discriminator. This is the only documented carve-out;
   any new user-state entity defaults to per-(user, scope) isolation
   unless an explicit decision row exempts it.
2. **Last-admin protection.** It is impossible to leave the system with zero
   bot admins. Enforced at the trigger layer, not just the command layer,
   on both the **UPDATE** path (revoking `is_admin`, setting `is_banned`
   on the only admin) and the **DELETE** path (a hard-delete that would
   leave zero rows with `is_admin = true`). Hard-delete of users is a
   privileged operator action and the trigger fails it with the same
   error a `/revoke-admin` of the only admin produces.
3. **At most one group admin per group.** Enforced by a partial unique index
   so the "first @mention wins" auto-promote path is race-safe (decision D9).
4. **Soft-delete only for sources.** `source` is never hard-deleted; FKs from
   `post` and `saved_post` rely on this.
5. **Outbox.** Posts are persisted before they are enqueued for evaluation.
   A startup rehydrator picks up any post left in `RAW` after a crash.
   Posts in `RAW` with one or more stage-outcome flags already set
   resume from the next uncompleted stage; the per-stage flags
   (`stage1_done`, `stage2_done`, `tagger_done`, `embedding_done`,
   `stage2_failed`, etc.) are the durable cursor. There is no
   distinct "evaluating" status — `RAW` plus the flag bitmap is the
   complete representation of in-flight evaluation state.
6. **TTL by partitioning.** `post`, `post_reference`, `post_embedding`,
   `price_snapshot`, and similar bulk-derived rows are partitioned and
   aged out by partition drop, not row delete. `post` carries a fixed,
   profile-driven retention horizon (decision D33); saved-post
   snapshots (decision D13) are exempt because the snapshot is copied
   into `saved_post` at `/save` time and never re-resolved against
   `post`. `price_snapshot` (decision D39) carries its own
   profile-driven retention horizon — long enough that `/zcash` /
   `/monero` always have a recent row, short enough that the table
   does not unbounded-grow.
   **Quarantine rows are exempt** from the post-derivative TTL: a
   quarantine row survives until explicitly approved or rejected by
   an admin so a post awaiting review can never silently disappear.
   A separate, longer **admin-review TTL** (profile-driven; value in
   design notes) bounds an indefinitely-pending queue: a quarantine
   row aged past the admin-review TTL is **not** auto-released, it
   auto-`reject`s and the placeholder becomes permanent. The
   admin-review TTL is intentionally long enough that an attentive
   operator never trips it.
7. **Audit-before-effect.** Privileged actions write to `audit_log` *before*
   their side effects, so an interrupted command leaves a record of intent.
8. **No LLM-writable rows.** Tables that influence authorization
   (`users.is_admin`, `users.is_banned`, `group_membership.is_group_admin`)
   are not reachable from any LLM tool surface. Enforced at the SPI boundary
   (see `security.md`).
9. **Chat-memory TTL.** `chat_memory` rows carry a fixed retention horizon
   (value in design notes) after which they are removed by a scheduled
   pruner (decisions D37, D40). `chat_session` rows carry the same TTL
   and are removed by the same pruner; a stale `chat_session` row on
   the next user message is treated as absent (equivalent to `/clear`).
   `summary_anchor` rows are subject to the same pruner (see
   §Per-scope state — Summary anchor). `/save`d posts are stored
   separately (decision D13) and are not affected. `/forget`
   (decision D37) is a user-initiated immediate purge of the caller's
   `(user, scope)` chat memory and saved-list and is audit-logged like
   any other privileged action against user state.

## What lives in design notes

- Every column type, default, and index
- Trigger bodies and partial-index predicates
- Partition cadence and pruner schedule
- The exact `chat_memory` TTL value and pruner cadence
- Denormalized counters and the triggers that maintain them
- Profile-specific vector index choices and their build parameters
- Migration-file layout

If a question is "what column type is `body`?" the answer is in
`docs/design/02-schema.md`. If a question is "do post bodies survive a
source soft-delete?" — that's an invariant and lives here.
