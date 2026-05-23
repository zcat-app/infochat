# Session handoff — Tier 2 Group B: DM commands on entities (saved-posts + source management + tag preferences)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-B ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- Tier 0 and Tier 1 are done and merged on main. T2-A
  (onboarding / auth) is done MODULO M1-046 (see next bullet) —
  M1-044 umbrella + 044a/b/c/d/e subs and M1-045 are merged.
  The process-fix umbrella (M1-047 + 048/049/050) and M1-051
  (ConfirmStateService) are also done.
- T2-A's third standalone ticket — M1-046 (/grant-admin +
  /revoke-admin + last-admin trigger consumption) — is
  **in-progress on `main`** at the time this brief was authored. It
  is NOT yet merged. The ticket-authoring work in this session does
  NOT touch any T2-A surface and does NOT block on M1-046's merge;
  the per-ticket sections below take care to flag the one possible
  collision (next-free migration number) so the authoring session
  can re-grep at the moment of writing rather than locking the
  number in this brief.
- STATUS.md as of the brief's authoring: pending=0, in-progress=1
  (M1-046), done=61, deferred=6, total=68.
- The full history is reproducible from `git log --grep "^M1-"`.
- Branch is main, otherwise clean modulo M1-046's in-flight work.
- All Tier-1 deferred tickets (M1-019/020/021/031/041 etc.) are
  out of T2-B's path; the §LLM output sanitizer (M1-041) consolidation
  is done.

## What's NOT yet on disk that T2-B creates

T2-B authors three independent DM-command families. Each family
adds: a new CommandHandler bean (or two), bundle keys, and
tests. Schema and audit-enum work is family-specific:

  - **T2-B.1** adds a new V<N> migration (`saved_post`) — the
    only T2-B migration. No new AuditAction verbs (saves are
    not audit-logged in v1).
  - **T2-B.2** adds NO migration (V6 already shipped
    `source.status` + `source.deleted_at`) and NO new
    AuditAction verbs (`REMOVE_SOURCE` / `SOURCE_ENABLE` /
    `SOURCE_DISABLE` already exist).
  - **T2-B.3** adds NO migration (V7 already shipped
    `scope_tag` + `scope_preferences.tag_mode`) and NO audit
    verbs (tag-pref mutations are user-preference, not
    privileged).

None of the three touches InboundRouter's intake-step splice
— the router already iterates `Instance<CommandHandler>` and
matches by `handler.name()`, so adding a new command is purely
a new bean, NOT a router case-edit.

**Verify at the moment of authoring** (do not trust this brief's
numbers if `main` has moved):

  - Next free migration version under
    `infochat-core/src/main/resources/db/migration/`. At the time
    this brief was authored, V1..V13 existed; the next free is V14.
    M1-046 is currently in-progress and MAY consume V14. Re-run:
    ```
    ls infochat-core/src/main/resources/db/migration/ | sort -V
    ```
    and pick the first integer past the last `V<N>__*.sql`. Only
    **T2-B.1** needs a migration (`saved_post`) — it claims the
    next free integer. T2-B.2 reuses V6 (`source.status` +
    `deleted_at`); T2-B.3 reuses V7 (`scope_tag` +
    `scope_preferences.tag_mode`). Net new migrations from T2-B: 1.
  - Next free M1 ticket id under `docs/plan/m1/tickets/`. At
    authoring time M1-051 was the last allocated id. T2-H is
    being authored in PARALLEL with T2-B and may or may not have
    landed when this session runs — see §"ID allocation" below
    for the parameterized cases. Re-run `ls docs/plan/m1/tickets/
    | sort -V | tail` to confirm before assigning.

What does NOT yet exist (T2-B creates / extends):

  - `saved_post` table (per-user-globally — Decision D13, spec
    §Per-user state). Carries `user_id`, `post_uid` (snapshot key
    independent of `post.id` per Invariant 6's UID-rebind rule),
    `body_snapshot`, `personal_tags`, `saved_at`, plus the cap
    counter mechanism the spec assigns (`SELECT … FOR UPDATE` on
    a per-user save-counter row, OR a CHECK on a derived
    counter — spec §Content `/save`). The exact column list and
    cap mechanism are design-tier; the authoring session
    references `docs/design/02-schema.md` rather than re-deriving.
  - SavedPostCommandHandler beans: `/save`, `/saved`, `/unsave`.
    Permission: `/save` and `/unsave` are non-admin (any
    non-banned user); `/saved` is non-admin. None is in the
    closed bot-admin set in spec §Permission model.
  - `source` table row-status read paths for `/list-sources`
    (already exists — V6 added `source` columns). T2-B's source
    management ticket only ADDS the four new commands; the
    schema is in place.
  - ListSourcesCommandHandler + RemoveSourceCommandHandler +
    SourceEnableCommandHandler + SourceDisableCommandHandler.
    All four are bot-admin only per spec §Permission model
    "Bot-admin only" closed list. /remove-source and
    /source-enable (soft-delete revival path) require confirm,
    so they integrate with M1-051's ConfirmStateService —
    the BAN / INVITE_CREATE / INVITE_REVOKE precedent. The
    `--all` and `--include-deleted` admin-only flag handling
    on `/list-sources` per spec §Permission model
    "Admin-only flags are part of command identity."
  - **`scope_tag` and `scope_preferences.tag_mode` are
    already on disk** in `V7__joins_post.sql` (lines 64 and
    84-95). `scope_tag (scope_kind, scope_id, tag_id)` carries
    the exact composite-PK shape the spec requires;
    `scope_preferences.tag_mode TEXT NOT NULL DEFAULT 'ALL'
    CHECK (tag_mode IN ('ALL','EXPLICIT'))` is also in place.
    T2-B.3 does NOT add a migration — it only wires the
    handlers + bundle keys + tests against the existing
    schema. Re-verify at authoring time via
    `grep -nE 'CREATE TABLE (scope_tag|scope_preferences)' infochat-core/src/main/resources/db/migration/`.
  - FollowTagCommandHandler + UnfollowTagCommandHandler +
    TagModeCommandHandler (or merge follow/unfollow into one
    handler keyed by sub-verb — author-time call).
    Permission: DM = own scope, Group = group-admin only
    (spec §Permission model "Group-admin").

## What you do this session

Author three standalone T2-B ticket files in
`docs/plan/m1/tickets/`. Per session-grouping-plan.md row T2-B
(line ~139):

  T2-B.1 — Saved-post commands (/save + /saved + /unsave) +
           `saved_post` table + per-user save-cap enforcement
  T2-B.2 — Source-management admin commands (/list-sources +
           /remove-source + /source-enable + /source-disable) +
           confirm integration on /remove-source +
           soft-deleted revival path on /source-enable
  T2-B.3 — Per-scope tag preferences (/follow-tag + /unfollow-tag +
           /unfollow-tag --all + /tag-mode helper) — handlers
           only; `scope_tag` + `scope_preferences.tag_mode`
           already on disk in V7

The session-grouping-plan estimate (3 tickets) is the
**spec-sentence count**, not the implementation-files count.
Verify the per-ticket implementation-files count by reading the
spec sections (anchored below) and the M1-044c (admin command
handlers) precedent for handler size BEFORE committing to the
shape. If any subticket exceeds the `files_budget: 12` threshold
that the M1-035 / M1-008 / M1-044 umbrella+subs pattern protects
against, restructure that ONE ticket into the
umbrella+subs pattern (the other two stay as standalone). T2-B.1
and T2-B.3 are likely to fit single tickets; T2-B.2 is the most
likely candidate for umbrella+subs because the four admin
commands share the confirm-integration surface plus
soft-deleted revival has unique semantics.

## Where you are in the milestone

Tier 1 is complete. Tier 2 is mid-flight (T2-A done modulo
M1-046's in-flight commit). T2-B begins with this session.

  T2-A onboarding / auth          (done modulo M1-046 in-progress)
  T2-B DM commands on entities    (THIS SESSION — 3 tickets,
                                   T2-B.2 optionally umbrella+subs)
  T2-C translation                (next: TranslationProvider, /lang)
  T2-D chat-mode                  (chat agent + memory + /compress)
  T2-E privacy                    (/forget, /export)
  T2-F groups                     (group support + digests)
  T2-G quarantine                 (/quarantine list/approve/reject)
  T2-H assets                     (/zcash, /monero, bootstrap-assets)

After T2-B, the next session authors T2-C's detailed handoff JIT.
See `docs/plan/m1/drafts/session-grouping-plan.md` for the full
plan.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T2-B gets fresh IDs
at the tail. At the time this brief was authored, M1-051 was the
last allocated id. **T2-H is the sibling Tier 2 group being
authored in PARALLEL with T2-B** — it may or may not have
landed when this session runs. T2-C..T2-G have not been
authored yet. Verify at authoring time via
`ls docs/plan/m1/tickets/ | sort -V | tail`.

Likely allocation, parameterized on what's landed:

  - **If T2-H has NOT landed**: T2-B.1 → M1-052,
    T2-B.2 → M1-053 (or M1-053 umbrella + M1-053a/b if
    oversized), T2-B.3 → M1-054 (or M1-055 if T2-B.2 took
    umbrella+subs).
  - **If T2-H has landed first** (M1-052 + 052a/b/c consumed
    per T2-H's umbrella+subs allocation): T2-B.1 → M1-053
    (or next-free), subsequent T2-B IDs follow at the next
    free integers.

Per-ticket title shapes (use these verbatim, modulo final
imperative-summary tightening):

  T2-B.1 → "Saved-post library — /save + /saved + /unsave +
            saved_post snapshot (per-user-globally, save-cap)"
  T2-B.2 → "Source-management admin commands — /list-sources +
            /remove-source + /source-enable + /source-disable"
  T2-B.3 → "Per-scope tag preferences — /follow-tag +
            /unfollow-tag + /unfollow-tag --all + tag-mode
            state machine"

## Per-ticket framing

### T2-B.1 (M1-05X) — Saved-post library

**Spec anchors** (cite verbatim in `spec_refs:`):

  - `docs/spec/commands.md` §Content — the `/save`, `/saved`,
    `/unsave` paragraphs including the per-user-global semantics,
    the cap enforcement (`SELECT … FOR UPDATE`), the
    visibility-of-target rules (`READY` vs `QUARANTINED` vs
    `NEEDS_REVIEW`), and the cap-exceeded friendly error.
  - `docs/spec/schema.md` §Per-user state — the `saved_post`
    entity (per-user-globally, no scope discriminator, the
    Invariant 1 exception).
  - `docs/spec/schema.md` §Invariants — Invariant 6 (UID rebind
    discipline; `/save` snapshots `post_uid` so retention TTL on
    `post` does not break the bookmark).
  - `docs/spec/commands.md` §Permission model — `/save`,
    `/saved`, `/unsave` are non-admin (NOT in the closed
    bot-admin list).

**Design references** (read but cite only if locking a behavior):

  - `docs/design/02-schema.md` — `saved_post` DDL + per-user
    save-cap mechanism (`SELECT … FOR UPDATE` vs derived counter)
    + profile-driven cap value table.
  - `docs/design/03-commands.md` §3.4 — handler organization +
    bundle-key naming convention (already followed by M1-036's
    /add-source handler and M1-044c's admin handlers).

**Locked decisions for this ticket**:

  - **`saved_post` is per-user-globally** — the row carries
    `user_id` only, no scope discriminator. This is the
    documented exception to Invariant 1; the migration's CHECK
    constraints and the `/saved` query MUST NOT filter on
    scope.
  - **Snapshot-on-save**, not foreign-key-on-save. The save
    captures `post_uid` + `body_snapshot` so a later retention
    TTL drop on `post` (Invariant 6) does not break the
    bookmark. The exact column list (title, url, source,
    captured_at, etc.) is design-tier — read
    `docs/design/02-schema.md` for the locked shape.
  - **Cap enforcement is atomic.** Two concurrent `/save` calls
    at cap-1 admit exactly one; the second receives the
    cap-exceeded friendly error. The mechanism (`SELECT … FOR
    UPDATE` on a per-user counter row vs CHECK on a derived
    counter) is design-tier.
  - **Visibility-of-target.** A `/save` against a
    `QUARANTINED` (Stage-2 hidden) or `NEEDS_REVIEW` post is
    treated as an unknown UID — same friendly error as a UID
    that does not exist. The handler queries `post WHERE id =
    $1 AND status = 'READY'`; rows in other statuses are
    indistinguishable from "no such UID" at the user surface.
  - **`/saved` discloses per-user-global scope.** Per spec
    §Content: the reply header must say saves are visible
    across DM + groups so a user invoking `/saved` from a group
    is not surprised by DM-only saves appearing in the list.
    Ship the disclosure as a bundle key (`reply.saved.header.global`
    or similar).
  - **No interaction with confirm.** None of `/save`, `/saved`,
    `/unsave` is in the closed bot-admin set — they do NOT
    integrate with M1-051's ConfirmStateService. `/unsave` is
    explicit per spec ("no confirmation").

**Out-of-scope (template for the ticket's frontmatter)**:

  - any change to the spec — §Content + §Per-user state are
    already complete and committed
  - any /forget interaction beyond "/forget purges saved_post
    rows" — T2-E territory; T2-B.1 just lands the table so
    T2-E has something to purge
  - any /export interaction beyond "saved_post rows are
    exportable" — T2-E territory; same reasoning
  - any /quarantine flow change — T2-G territory; T2-B.1's
    visibility filter consumes the existing `post.status`
    column without modifying quarantine semantics
  - any audit-log writer changes — `/save` and `/unsave` are
    NOT in the spec's audit-logged set; only privileged
    actions write audit rows
  - any TranslationProvider interaction — T2-C territory;
    T2-B.1's bundle keys ship in English only
  - any change to InboundRouter intake-step splice from
    T2-A — handlers register as new CommandHandler beans and
    the router picks them up via `Instance<CommandHandler>`
    iteration; no router edit required
  - any chat-mode interaction — T2-D territory

**Acceptance shape**:

  - 4-6 acceptance items covering: `/save` happy path
    (READY post → row written, reply names the UID + tag
    optionally provided); `/save` visibility filter
    (QUARANTINED / NEEDS_REVIEW → unknown-UID friendly error);
    `/save` cap enforcement (two concurrent saves at cap-1
    admit exactly one — IT with two threads); `/saved`
    per-user-global semantics (a save made in DM appears in
    /saved from a group); `/saved` pagination (`--page N`);
    `/unsave` removes the row + decrements the counter
    atomically.
  - One @QuarkusTest IT exercising the full happy path via
    InMemoryAdapter.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 8-11. New migration + 3 handlers (or one
handler with sub-verb dispatch) + 1 service for the cap
counter + bundle keys + 4-5 tests + 1 IT.

**security_relevant: false** — saves are user-owned data with no
authorization-state implications.

### T2-B.2 (M1-05X+1) — Source-management admin commands

**Spec anchors**:

  - `docs/spec/commands.md` §Source management — the full
    `/list-sources`, `/remove-source`, `/source-enable`,
    `/source-disable` paragraphs including admin-only scoping,
    the soft-deleted revival path on `/source-enable` (requires
    confirm), the cascade-delete of subscriptions on
    `/remove-source`, the explicit "No subscriptions were
    restored" disclosure on soft-delete revival, and the
    URL-visibility caveat on `/list-sources --all`.
  - `docs/spec/commands.md` §Permission model — the closed
    bot-admin set including these four commands plus the
    "Admin-only flags are part of command identity" rule for
    `/list-sources --all` and `--include-deleted`.
  - `docs/spec/schema.md` §Sources and tags — the `source`
    entity's three-status state machine (`active` / `failed` /
    `disabled`) orthogonal to `deleted_at`, plus the transition
    rules (`failed → active` via `/source-enable`,
    `active → disabled` via `/source-disable`, `disabled →
    failed` cannot happen).
  - `docs/spec/security.md` §Source URL visibility — the
    operator-visibility disclosure that `/list-sources --all`
    surfaces.

**Design references**:

  - `docs/design/03-commands.md` §3.4 §Admin — handler
    organization + bundle-key naming for admin commands.
  - `docs/design/02-schema.md` — `source.status` column
    definition + the soft-delete column convention.
  - M1-051's ConfirmStateService (already on disk) for the
    confirm-gate integration on `/remove-source` and
    `/source-enable` (soft-deleted revival).

**Locked decisions**:

  - **All four commands are bot-admin only.** Permission check
    runs in the handler against the M1-040 InboundContext
    (the `(adapter, contact_id)` lookup pattern established by
    M1-044c). The handler returns a friendly bot-admin-only
    error before any state mutation.
  - **`/remove-source` requires confirm + cascade-deletes
    subscriptions.** Integrate with M1-051's ConfirmStateService:
    register a pending entry on first call; the second call
    (the confirm-shape body) executes the soft-delete + the
    cascade-delete of `source_subscription` rows in one
    transaction. AuditAction: `REMOVE_SOURCE`.
  - **`/source-enable` has two paths.** Recovery from `failed`
    or `disabled` does NOT require confirm (no broader
    implication than the operator's `/source-enable` call
    itself); recovery from soft-deleted (`deleted_at IS NOT
    NULL`) DOES require confirm AND emits the "No subscriptions
    were restored" disclosure in the success reply. The probe
    (HEAD for HTTP-shaped, single-relay connection attempt for
    StreamSource) runs on every `/source-enable` invocation;
    probe failure leaves the source in its prior state with a
    friendly error.
  - **`/source-disable` does not probe** — the operator is
    intentionally pausing the source.
  - **`/list-sources --all` and `--include-deleted` are
    admin-only flag splits** per spec §Permission model. A
    non-admin caller passing `--all` receives a friendly
    permission error; the parser MUST NOT silently strip the
    flag and run the unflagged variant.
  - **`/list-sources` reply for non-admin in DM** lists only
    the calling user's `source_subscription` rows. In a group
    context, lists the group's subscriptions (visible to every
    group member per Decision D7).
  - **URL-visibility caveat on `--all`** — the reply header
    includes the spec's required URL-visibility disclosure
    when `--all` is in play.
  - **New AuditAction entries** required: `REMOVE_SOURCE`
    (already exists in AuditAction at the time of authoring —
    `grep -n REMOVE_SOURCE infochat-core/src/main/java/.../audit/AuditAction.java`),
    `SOURCE_ENABLE` (already exists), `SOURCE_DISABLE`
    (already exists). **No new AuditAction needed** for this
    ticket — verify in case M1-046's commit adds or shifts
    enum entries.

**Out-of-scope**:

  - any change to the `source` table or its status/deleted_at
    columns — V6 already shipped them
  - any new fetcher / StreamSource scheduler behavior — the
    scheduler reads `WHERE status = 'active' AND deleted_at IS
    NULL` and the status transitions performed by these
    handlers feed it; the scheduler itself is not modified
  - any /add-source change — that handler is done (M1-036)
  - any /unfollow-source handler — also T2-B territory but
    NOT in this ticket; defer to a follow-up or fold into
    the umbrella shape if the +1 handler is small
  - any TranslationProvider interaction
  - any change to ConfirmStateService — the service is
    consumed unchanged from M1-051

**Acceptance shape**:

  - 5-7 acceptance items covering: `/list-sources` non-admin
    DM (own subscriptions only); `/list-sources --all` bot-
    admin (every source globally where deleted_at IS NULL);
    `/list-sources --all` non-admin (friendly permission
    error — flag NOT silently stripped); `/remove-source`
    confirm-gate roundtrip via ConfirmStateService;
    `/remove-source` cascade-deletes subscriptions in one
    transaction; `/source-enable` from `failed` (no confirm,
    probe runs); `/source-enable` from soft-deleted (confirm
    required, success reply includes "No subscriptions were
    restored" disclosure); `/source-disable` happy path
    (`active → disabled`).
  - One @QuarkusTest IT exercising `/remove-source` with
    confirm + cascade-delete.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 10-13 — pushing the threshold. The four
handlers + confirm-gate integration on two of them + the probe
call (re-using the M1-036 probe path if available) + bundle
keys + 6-8 tests + 1 IT. **If during authoring the
files_budget exceeds 12, restructure into the M1-008 / M1-044
umbrella+subs pattern**: an umbrella ticket carrying ONE
cross-cutting IT (the `/remove-source` confirm + cascade
roundtrip) plus three subs split by family:

  T2-B.2-u → umbrella (IT + bundle keys + shared decisions)
  T2-B.2a → /list-sources (with --all + --include-deleted
            admin-flag handling)
  T2-B.2b → /remove-source (confirm + cascade)
  T2-B.2c → /source-enable + /source-disable (status machine
            + soft-delete revival)

**security_relevant: true** — every admin command is a
permission commitment; the admin-flag-as-identity rule is a
spec-load-bearing security invariant.

### T2-B.3 (M1-05X+2) — Per-scope tag preferences

**Spec anchors**:

  - `docs/spec/commands.md` §Per-scope tag preferences — the
    full `/follow-tag`, `/unfollow-tag`, `/unfollow-tag --all`
    paragraphs including the dynamic-default rule (D15), the
    `ALL ↔ EXPLICIT` mode transitions, the `scope_tag`
    seeding semantics on `ALL → EXPLICIT` flip, the
    digest-query rule.
  - `docs/spec/schema.md` §Per-scope state — the
    `scope_preferences.tag_mode ∈ {ALL, EXPLICIT}` column,
    defaulting to `ALL`.
  - `docs/spec/schema.md` §Sources and tags — the `scope_tag`
    entity definition + the vocabulary-lifecycle append-only
    rule (v1).
  - `docs/spec/commands.md` §Permission model — DM = own
    scope; Group = group-admin only (the "Group-admin" closed
    list explicitly lists `/follow-tag in groups`,
    `/unfollow-tag in groups`).

**Design references**:

  - `docs/design/03-commands.md` §3.4 — handler organization.
  - `docs/design/02-schema.md` — `scope_tag` / `scope_preferences`
    shape (READ-ONLY for this ticket; the V7 migration already
    matches; cite only to ground the queries the handlers
    issue).

**Locked decisions**:

  - **`tag_mode` is recorded explicitly** on
    `scope_preferences`. The dynamic default (an empty
    `scope_tag` set + `tag_mode = ALL` = "all tags from
    subscribed sources at digest time") is spec-load-bearing
    — implicit-mode logic ("any rows in `scope_tag`?") is
    explicitly forbidden by the spec.
  - **Mode transitions are atomic** per spec:
    - `ALL` + `/follow-tag <tag>` → flip to `EXPLICIT` and
      seed `scope_tag` rows for **the followed tag only**.
    - `ALL` + `/unfollow-tag <tag>` → flip to `EXPLICIT` and
      seed rows for **all currently subscribed-source
      `bootstrap_tags` minus the unfollowed tag**.
    - `EXPLICIT` + `/follow-tag` / `/unfollow-tag` → add or
      remove the row in place; when row count drops to 0,
      flip back to `ALL`.
    All transitions in a single transaction (the user's
    intent is "I want this digest behavior next time" — the
    flip + seed MUST not crash halfway).
  - **`/unfollow-tag --all` is a bulk reset** requiring
    confirm via ConfirmStateService. Deletes all `scope_tag`
    rows for the scope, sets `tag_mode = ALL`. NOT
    audit-logged (per next bullet — tag-pref mutations are
    user-preference, not privileged).
  - **No AuditAction additions** — tag-pref mutations do not
    write audit rows in v1 (they're user-preference, not
    privileged action). Verify by re-reading spec §Audit
    invariants — if the spec assigns an audit verb to one
    of these commands, fix at the spec level via a separate
    spec: commit BEFORE authoring this ticket.
  - **Permission**: DM = own-scope only; Group = group-admin
    only. Handler queries `(group_membership.is_group_admin =
    true)` for group scope via the same pattern M1-036's
    /add-source group-admin check uses.
  - **Tag values are validated against the controlled
    vocabulary.** A `/follow-tag <tag>` against a tag not in
    the controlled vocabulary returns a friendly fuzzy-
    suggestion error (same shape as `/add-source --tags`).
    Per spec §Sources and tags §Vocabulary lifecycle:
    `/follow-tag` may accept a tag whose only contributing
    source was removed long ago — the vocabulary is
    append-only — and that's expected behavior; only tags
    that NEVER entered the vocabulary are rejected.

**Out-of-scope**:

  - any change to the `source.bootstrap_tags` column — already
    on disk
  - any change to the digest scheduler — T2-F territory; this
    ticket only writes the `scope_tag` + `tag_mode` state,
    the scheduler reads it when T2-F lands
  - any /add-source change — that handler is done (M1-036)
  - any TranslationProvider interaction
  - any vocabulary-removal mechanism — append-only is spec
    commitment for v1
  - any chat-mode interaction

**Acceptance shape**:

  - 4-6 acceptance items covering: `/follow-tag` against an
    `ALL`-mode scope (flips to `EXPLICIT`, seeds one row);
    `/unfollow-tag` against an `ALL`-mode scope (flips to
    `EXPLICIT`, seeds all-minus-one rows);
    `/follow-tag`/`/unfollow-tag` against an `EXPLICIT`-mode
    scope (adds/removes in place; row count → 0 flips back
    to `ALL`); `/unfollow-tag --all` confirm-gated bulk
    reset; `/follow-tag` against a vocabulary-unknown tag
    (friendly fuzzy-suggestion error); per-scope permission
    (group-admin only in groups; own-scope only in DM —
    a non-admin attempting group `/follow-tag` receives the
    permission error).
  - One @QuarkusTest IT exercising the `ALL → EXPLICIT → ALL`
    round-trip.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 5-7. No migration (scope_tag +
scope_preferences.tag_mode already on disk in V7). 2-3
handlers (follow + unfollow + bulk-reset OR a single handler
with sub-verb dispatch) + bundle keys + 4-5 tests + 1 IT.

**security_relevant: false** — tag preferences are user/scope
preferences with no authorization-state implications.

## Locked decisions (cross-cutting across T2-B)

- **No InboundRouter edits.** All three tickets add new
  CommandHandler beans. The router's `Instance<CommandHandler>`
  iteration picks them up automatically — verified by reading
  `infochat-provider/src/main/java/.../messaging/InboundRouter.java`
  §handleSlash at the time of authoring. No router case-edit;
  no intake-step change; no `case` arm.
- **All three tickets reuse the M1-040 InboundContext.**
  Handlers `@Inject InboundContext` for the `(adapter,
  contact_id)` lookup pattern. Same as M1-036 and M1-044c.
- **Audit-log writer is M1-041's AuditLogWriter** (done). The
  source-management ticket consumes `REMOVE_SOURCE`,
  `SOURCE_ENABLE`, `SOURCE_DISABLE` from the existing
  `AuditAction` enum. Verify enum contents at authoring time
  via `grep -nE 'REMOVE_SOURCE|SOURCE_ENABLE|SOURCE_DISABLE'
  infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`.
- **All confirm-gated commands consume M1-051's
  ConfirmStateService.** No new confirm machinery; only the
  registration of new `(commandName, sweepPrefix)` keys.
  Verify the ConfirmStateService API surface at authoring
  time so the per-command sweep-prefix matches the
  established pattern (M1-051 + M1-044c).
- **Bundle keys**: every new user-visible reply ships through
  a bundle key under
  `infochat-provider/src/main/resources/bundles/en.properties`
  + a `public static final String` constant on
  `app.zcat.infochat.provider.bundle.BundleKeys`. NO inline
  string literals in handler code. The
  `BundleLoaderTest` reflection assertion catches typos.
- **Migration numbering is sequential** under
  `infochat-core/src/main/resources/db/migration/`. Re-grep at
  authoring time; M1-046 in-progress may consume V14 first.
- **No `--no-verify`, no test disables.** Standard
  engineering rules apply.
- **Spec edits are forbidden in T2-B.** Every acceptance item
  must trace to spec text already on main HEAD. If a
  sentence the handler depends on is missing or ambiguous,
  escalate to `spec-amend` BEFORE implementing.

## Shared-surface chokepoints (flag for the future session)

- **InboundRouter dispatch**: NOT a chokepoint — the router
  iterates `Instance<CommandHandler>` and matches by
  `handler.name()`. Adding a new command is a new
  CommandHandler bean, NOT a router edit. (Verified at brief
  authoring time by reading
  `infochat-provider/.../messaging/InboundRouter.java` lines
  559-568.)
- **AuditAction enum**:
  `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`.
  At brief-authoring time, the verbs T2-B needs
  (`REMOVE_SOURCE`, `SOURCE_ENABLE`, `SOURCE_DISABLE`) already
  exist. M1-046 may not touch this file; re-grep before
  writing the ticket. T2-B.3 (tag prefs) does NOT add audit
  verbs.
- **Flyway migration numbering**:
  `infochat-core/src/main/resources/db/migration/`. Next free
  at brief-authoring time is V14. **M1-046 is in-progress and
  may consume V14** — flag in each per-ticket section that
  the integer is re-grepped at authoring time.
- **Bundle keys + properties file**:
  `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java`
  and
  `infochat-provider/src/main/resources/bundles/en.properties`.
  Every new command adds keys to both. The
  `BundleLoaderTest` reflection check enforces alignment.
- **ConfirmStateService consumption**: M1-051 established the
  pattern for `/ban`, `/invite create --open`, `/invite revoke`.
  T2-B.2's `/remove-source` and `/source-enable` (soft-delete
  revival) ADD to this list. The service is consumed
  unchanged — no edits to ConfirmStateService itself; only
  new keyspace entries.
- **CommandPermissions**:
  `infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java`
  carries the slow-start probation allowed-set. **None of the
  T2-B commands are in the allowed-during-probation set per
  spec §Slow-start tier** — `/save`, `/saved`, `/unsave`,
  `/list-sources`, `/remove-source`, `/source-enable`,
  `/source-disable`, `/follow-tag`, `/unfollow-tag`,
  `/unfollow-tag --all`, `/tag-mode` are all blocked during
  probation. Verify against spec §Slow-start tier at authoring
  time; the allowed-set is closed (spec amendment to add).

## Parallel-development collision plan with T2-H

T2-H (asset commands) is being authored in a parallel session;
its handoff is at `docs/plan/m1/drafts/handoff-tier2-H-assets.md`.
The two groups touch three shared seams:

1. **Flyway migration sequence.** T2-B.1 (`saved_post`) and
   T2-H.a (`asset_config` + `price_snapshot`) both claim the
   next-free `V<N>__*.sql`. M1-046 is also in-flight and may
   consume V14 first. **Whichever ticket MERGES first claims
   its V<N>; the second rebases its migration filename(s) and
   any V<N> references in tests.** No reservation is
   pre-allocated.

2. **BundleKeys.java + en.properties.** Both groups append
   new message-bundle entries (T2-B: command labels +
   friendly errors; T2-H: asset reply layout + friendly
   errors). The second-merging ticket rebases en.properties
   (append) and `BundleKeys.java` (append new
   `public static final String` constants). The
   `BundleLoaderTest` reflection check enforces alignment —
   a rebased branch that drops a key will fail it.

3. **STATUS.md.** Both authoring sessions regenerate the
   status board. The second-committing session rebases then
   re-runs `scripts/regen-status.py`.

T2-B's handlers do NOT read `asset_config` / `price_snapshot`;
T2-H's handlers do NOT read `saved_post` / `scope_tag` /
`scope_preferences`. No runtime coupling — the two groups can
implement in either order.

**Recommended merge order if both land around the same time**:
T2-B.1 first (smaller migration set, no umbrella+subs cascade),
then T2-H (vertical slice). Reverse is fine; just costs T2-B.1
a single migration rename.

## After authoring all tickets

1. Verify each ticket's `spec_refs:` anchors actually exist
   with `grep -nE '^## |^### ' docs/spec/<file>` (clarity-check
   pre-flight blocks otherwise).
2. Verify each ticket's `files_scope:` paths exist or are
   plausibly new (relative-path under one of the modules).
3. Verify the next-free Flyway migration integer and the
   next-free M1 ticket id by re-grepping the worktree —
   M1-046's in-flight commit may have shifted both.
4. Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and confirm pending count rose by
   exactly the number of new tickets, all marked Runnable
   (T2-B's three tickets are independent of each other — no
   `blocked_by` between them — and independent of M1-046's
   in-flight work because none of them edits the auth/admin
   surface).
5. Leave the new ticket files UNTRACKED on main. Do NOT
   commit them. The workflow rule: drafts ride untracked
   through `/m1-tick start`.
6. Update `docs/plan/m1/drafts/session-grouping-plan.md`
   Tier 2 row for T2-B to record the actual IDs (whichever
   the re-grep step yielded). Commit that single edit as
   `process: Record T2-B ID allocation (<actual-IDs>)`.
7. Print a one-screen summary in chat listing the three (or
   more if umbrella+subs taken on T2-B.2) ticket IDs and
   titles. Recommended start order: any order — the three
   are independent. If a parallel pair runs, prefer T2-B.1
   + T2-B.3 first (smallest, fewest shared chokepoints) and
   leave T2-B.2 (largest, confirm-state integration) for a
   focused session.

## What you do NOT do in this session

- Do NOT author any T2-A/C/D/E/F/G/H tickets. Those are
  separate sessions.
- Do NOT implement any T2-B code. No `src/` edits anywhere.
- Do NOT amend any spec or design file. T2-B's spec is
  already complete on main HEAD per the §Content +
  §Source management + §Per-scope tag preferences sections.
- Do NOT touch M1-046's in-flight work. Even though M1-046
  may consume V14 before T2-B's migrations land, the
  authoring session does NOT pre-resolve the conflict —
  it leaves the migration number as "next free at the
  moment of `/m1-tick start`" and the implementation
  session resolves it then.
- Do NOT run `mvn verify`. Ticket authoring does not touch
  Java code.
- Do NOT commit the new ticket files; they ride untracked
  into `/m1-tick start`.

## Engineering rules in force

The full rules live in `CLAUDE.md` §Engineering rules and
`docs/process/engineering-rules-verbatim.md`. The ones that
bite for this session:

- **Surgical changes.** Each commit touches only the files
  its task needs. The session-grouping-plan edit in step 6
  is one separate `process:` commit.
- **No defensive code for impossible scenarios.** Validation
  belongs at system boundaries; adapter inbound is a
  boundary (already handled by M1-038), but internal calls
  between T2-B's services are trusted.
- **No workarounds, no shortcuts.** If a constraint blocks
  ticket authoring, escalate via the workflow — never reach
  for destructive shortcuts or guess at a spec the brief
  did not resolve.
- **Push back when simpler exists.** If the brief's 3-ticket
  default split has a materially simpler alternative (e.g.
  T2-B.3 fits cleanly under T2-B.2's umbrella, or T2-B.1's
  three handlers collapse into one with sub-verb dispatch),
  surface it in chat BEFORE committing the files.
- **Read spec files only when something is unclear.** The
  brief cites the spec anchors with section names; the
  authoring session reads those sections directly rather
  than re-deriving state from the codebase.

## Outputs

By the end of this session:

- Three (or more, if umbrella+subs on T2-B.2) new ticket
  files exist UNTRACKED under `docs/plan/m1/tickets/`. They
  appear in STATUS.md as `pending` and Runnable (no
  `blocked_by` from any in-flight ticket).
- One `process:` commit on main updates the
  session-grouping-plan's T2-B row.
- Working tree contains the new ticket files (untracked)
  and STATUS.md (committed via the process: commit). No
  code changes.

The natural next step is `/m1-tick start <id>` against
whichever ticket the operator picks first.
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Three (or more) new ticket files appear UNTRACKED under
      `docs/plan/m1/tickets/`. Status: pending, Runnable.
- [ ] STATUS.md regenerates with pending count up by the new
      ticket count.
- [ ] One `process:` commit on main updates the
      session-grouping plan's T2-B row.
- [ ] No `src/` edits anywhere.
- [ ] No spec or design edits.
- [ ] No interaction with M1-046's in-flight work.

If the session deviates (touches code, amends the spec, or
authors T2-A/C/D... tickets), it has misread the brief — abort
and start over with the same prompt.
