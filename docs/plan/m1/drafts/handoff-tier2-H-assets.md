# Session handoff — Tier 2 Group H: asset commands (/zcash + /monero + bootstrap-assets + asset_config + price_snapshot)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-H ticket files and stop. Do
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
  (ConfirmStateService — confirm-gate machinery T2-H does NOT
  consume because asset commands are non-destructive) are also
  done.
- M1-046 (/grant-admin + /revoke-admin) is **in-progress on
  `main`** at the time this brief was authored. It is NOT yet
  merged. T2-H does NOT touch any T2-A surface; the per-ticket
  section flags the one cross-cutting touch point: the
  AssetCommandFamilyOracle seam M1-045 left in place for T2-H to
  displace.
- STATUS.md as of the brief's authoring: pending=0, in-progress=1
  (M1-046), done=61, deferred=6, total=68.
- The full history is reproducible from `git log --grep "^M1-"`.
- Branch is main, otherwise clean modulo M1-046's in-flight work.
- T2-H is positioned LAST in the Tier 2 order per
  session-grouping-plan: it depends on no other T2 group, but its
  late position lets it consume the deepest M1 codebase context
  (bootstrap loader, fetcher SPI, scheduler, DB roles all
  exercised by prior tickets).

## What's NOT yet on disk that T2-H creates

T2-H is a single ticket (per session-grouping-plan row, line ~145)
covering the full asset-commands vertical slice: bootstrap loader,
DB tables, fetchers (one per data-source host), command handler,
reply renderer, snapshot reader, and AssetCommandFamilyOracle
displacement. It is the largest single-ticket scope in Tier 2 by
file count — if implementation-file budget breaks 12 during
authoring, **fall back to the M1-008 / M1-044 umbrella+subs
pattern**.

**Verify at the moment of authoring** (do not trust this brief's
numbers if `main` has moved):

  - Next free migration version under
    `infochat-core/src/main/resources/db/migration/`. At brief-
    authoring time, V1..V13 existed; the next free was V14. M1-046
    is in-progress and MAY consume V14. T2-B (DM commands) is
    sibling Tier 2 work and may also be in-flight when T2-H is
    authored; re-run:
    ```
    ls infochat-core/src/main/resources/db/migration/ | sort -V
    ```
    and pick the first integer past the last `V<N>__*.sql`.
  - Next free M1 ticket id under `docs/plan/m1/tickets/`. At
    brief-authoring time, M1-051 was the last allocated id. T2-B
    is being authored in PARALLEL with T2-H and may or may not
    have landed when this session runs — see §"ID allocation"
    below for the parameterized cases. Re-run `ls
    docs/plan/m1/tickets/ | sort -V | tail` to confirm.

What does NOT yet exist (T2-H creates / extends):

  - `bootstrap-assets.json` — operator-supplied config file.
    Schema fixed in `docs/design/10-asset-commands.md` §10.6.
    Path configurable via `infochat.bootstrap.assets-file`.
    Absent file → asset commands disabled, `/help` does not
    list them.
  - `BootstrapAssetsParser` + `BootstrapAssetsEntry` records
    under `infochat-collector/src/main/java/.../bootstrap/` —
    mirroring the existing `BootstrapSourcesParser` +
    `BootstrapSourcesEntry` pattern (verified at brief-
    authoring time:
    `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParser.java`
    and `BootstrapSourcesEntry.java` are on disk and set the
    precedent).
  - `asset_config` table (V<N> migration). Columns per
    `docs/spec/schema.md` §Operational — Asset config:
    `(asset, sub_verb)` PK, `enabled` flag,
    `default_quote_currency`, `attribution_url`,
    `consecutive_failures`, `last_success_at`, `last_failure_at`,
    `is_default` (partial unique index `WHERE is_default = true`
    so at most one row per `asset` carries the default flag),
    `status ∈ {active, failed, disabled}`. The migration
    includes the **default-row consistency** CHECK: a row with
    `is_default = true AND enabled = false` is rejected by the
    bootstrap loader at Collector startup with a fatal log
    message.
  - `price_snapshot` table (same V<N> migration or a sibling
    V<N+1>). Per spec §Operational — Price snapshot:
    `INSERT-only`, partitioned on `captured_at`, aged by
    partition-drop per Invariant 6. Columns:
    `(asset, sub_verb, captured_at, price, currency,
    source_url, raw_payload)`. Index on
    `(asset, sub_verb, captured_at DESC)` backing the
    latest-snapshot query.
  - DB-role grants for `asset_config` and `price_snapshot`. Per
    `docs/spec/security.md` §DB roles: Collector has
    `INSERT/UPDATE/SELECT` on both (writes the rows); Provider
    has `SELECT`-only. The role-grant SQL belongs in the same
    migration that creates the tables (M1-006 + V2 + V4
    precedent).
  - `AssetSnapshotFetcher` + `AssetDataSource` SPI under
    `infochat-collector/src/main/java/.../assets/` — per
    `docs/design/10-asset-commands.md` §10.2 class layout. One
    impl per supported public-endpoint host as cited in
    design §10.2; the brief does NOT lock the class names.
    Each impl is a polled `Fetcher` (re-using the M1-007a
    `Fetcher` SPI verified at brief-authoring time — read the
    SPI before authoring to confirm the method shape).
    Per-host tick cadence — one interval per source host, NOT
    per-(asset, sub_verb). Profile-driven values per design
    §10.4.
  - `PriceSnapshotStore` (Collector-side INSERT path) writes
    directly to `price_snapshot`; emits `NOTIFY
    new_price_snapshot` with `(asset, source)` payload. No
    outbox, no Stage 1/2, no tagging, no embedding.
  - `AssetCommandRouter` + `AssetHandler` + `AssetReplyRenderer`
    + `AssetSnapshotReader` under
    `infochat-provider/src/main/java/.../command/asset/` (or
    `.../commands/asset/` to match the design's path — the
    Java package layout is design-tier per §10.2; the spec is
    silent on the directory name). Two `CommandHandler` beans
    (`name()` returns `"zcash"` and `"monero"` respectively) —
    OR a single dispatcher bean keyed by sub-verb (author's
    call; the spec is shape-agnostic).
  - `AssetCommandFamilyOracle` impl swap. The bean exists at
    `infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java`
    (verified at brief-authoring time). It currently returns
    `false` for every input — the M1-045 seam. T2-H replaces
    the body so it consults the bootstrap-fed registry
    (`asset_config` table or an in-memory cache loaded at
    startup). **The interface MUST remain unchanged**: M1-045's
    `CommandPermissions` consumes
    `oracle.isAssetCommand(slashCommand)` and must continue to
    work without modification.
  - **Collector** startup wiring: a `@Startup` bean (or
    `@Observes StartupEvent`) on the Collector side loads
    `bootstrap-assets.json` and upserts `asset_config` rows
    per design §10.6 + spec §Operational asset_config
    "soft-disable" rule. Per the DB-role split above, only
    the Collector has `INSERT/UPDATE` on `asset_config` —
    the JSON parser lives in `infochat-collector/` and
    runs at Collector boot.
  - **Provider** startup wiring: a separate `@Startup` bean
    on the Provider side reads `asset_config` (SELECT-only)
    to register CommandHandler beans and populate the
    AssetCommandFamilyOracle registry. No JSON parsing on
    the Provider side.
  - Bundle keys for the asset reply layout (`reply.asset.header`,
    `reply.asset.price_line`, friendly-error keys for unknown
    sub-verb / sub-verb-not-enabled-for-asset / unsupported --vs).
    The exact key names follow the M1-036 /
    M1-044c convention.

## What you do this session

Author the T2-H ticket file(s) in `docs/plan/m1/tickets/`. The
**default split** (from session-grouping-plan.md §Tier 2 → T2-H)
is ONE ticket. The session-grouping estimate (1 ticket) reflects
the spec-sentence count, NOT the implementation-file count. The
implementation-file count is the largest in Tier 2:

  - 2 migration files (or 1 with both tables)
  - 1 bootstrap parser + 1 entry record
  - 1 AssetDataSource SPI + N impls (one per public-endpoint
    host per design §10.2; N is author's call)
  - 1 PriceSnapshotStore
  - 1 AssetSnapshotFetcher (possibly one per source host)
  - 1 AssetCommandRouter (or 2 handlers)
  - 1 AssetHandler base + 0-2 asset-specific subclasses
  - 1 AssetReplyRenderer + 1 AssetSnapshotReader
  - 1 AssetCommandFamilyOracle impl swap
  - 1 startup loader bean
  - bundle keys
  - 8-12 unit tests + 1-2 IT
  - JSON parsing tests

This easily exceeds files_budget 12. **Plan to author T2-H as an
umbrella+subs split from the start**, mirroring M1-008 / M1-044's
shape. Recommended split:

  T2-H-u → umbrella ticket carrying the cross-cutting IT
           (bootstrap-load → Collector fetcher tick → snapshot
            row insert → NOTIFY emit → Provider `/zcash` reply
            with attribution) + bundle keys + the
            AssetCommandFamilyOracle impl swap
  T2-H.a → bootstrap-assets.json parser + asset_config + the
           default-row consistency check + soft-disable on
           absent-from-bootstrap
  T2-H.b → Collector side: AssetDataSource SPI + one impl
           per public-endpoint host (set per design §10.2) +
           AssetSnapshotFetcher + PriceSnapshotStore +
           per-host tick cadence + NOTIFY emit
  T2-H.c → Provider side: AssetCommandRouter +
           handlers + AssetReplyRenderer + AssetSnapshotReader +
           stale-marker logic + friendly errors

If during authoring the umbrella+subs shape feels wrong for one
of the families (e.g. the Provider side fits a single ticket and
the Collector side fits its own single ticket without an umbrella),
adapt — the spec-sentence count is what session-grouping-plan
fixed, not the file layout. **Surface the deviation in chat
BEFORE committing the files** per the engineering "Push back when
simpler exists" rule.

## Where you are in the milestone

Tier 2 is mid-flight. T2-H is the LAST Tier 2 group; after T2-H,
the milestone moves to Tier 3 (adapters and breadth: SimpleX,
Signal, polled fetchers for non-asset sources, Nostr StreamSource,
Anthropic LLM).

  T2-A onboarding / auth          (done modulo M1-046 in-progress)
  T2-B DM commands on entities    (separate session, may be done
                                   or in-flight when T2-H authors)
  T2-C translation                (separate session)
  T2-D chat-mode                  (separate session)
  T2-E privacy                    (separate session)
  T2-F groups                     (separate session)
  T2-G quarantine                 (separate session)
  T2-H assets                     (THIS SESSION — umbrella + 3 subs
                                   recommended)

After T2-H, Tier 2 is complete. The next session authors the
first Tier 3 group's detailed handoff JIT. See
`docs/plan/m1/drafts/session-grouping-plan.md` for the full
plan.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T2-H gets fresh IDs
at the tail. At the time this brief was authored, M1-051 was the
last allocated id. **T2-B is the sibling Tier 2 group being
authored in PARALLEL with T2-H** — it may or may not have
landed when this session runs. T2-C..T2-G have not been
authored yet. Verify at authoring time via
`ls docs/plan/m1/tickets/ | sort -V | tail`.

Likely allocation, parameterized on what's landed:

  - **If T2-B has NOT landed**: T2-H-u → M1-052,
    T2-H.a/b/c → M1-052a/b/c (umbrella+subs share the digit
    slot per M1-007 / M1-008 / M1-035 / M1-044 precedent).
  - **If T2-B has landed first** (M1-052..M1-054 consumed):
    T2-H-u → M1-055 (or next-free), subs at the same digit.

Lowercase-suffix subticket convention follows the M1-007 / M1-008
/ M1-035 / M1-044 precedent on the same digit slot.

Per-ticket title shapes (use these verbatim, modulo final
imperative-summary tightening):

  T2-H-u  → "Asset commands umbrella — /zcash + /monero +
            bootstrap-assets + asset_config + price_snapshot
            roundtrip IT"
  T2-H.a  → "bootstrap-assets.json parser + asset_config table
            + default-row consistency check"
  T2-H.b  → "Asset fetchers (one per public-endpoint host
            per design §10.2) + price_snapshot store +
            per-host tick cadence"
  T2-H.c  → "/zcash + /monero handlers + reply renderer +
            AssetCommandFamilyOracle impl swap"

## Per-ticket framing

### T2-H-u (umbrella) — Asset commands integration

**Spec anchors** (cite verbatim in `spec_refs:`):

  - `docs/spec/commands.md` §Asset commands — the entire section
    (lines 209-326 on main HEAD at brief-authoring time;
    verify via `grep -n '^### Asset commands$'
    docs/spec/commands.md`). The cross-cutting rules
    (D39): "Data is not posts", "Polled, cached, refreshed
    on a tick", "Provider/Collector contract", "Freshness
    contract", "Mandatory attribution", "Stale-data honesty",
    "Public endpoints only in v1", "Retention", "Friendly
    errors", "/help is context-aware", "Enable / disable
    lifecycle".
  - `docs/spec/schema.md` §Operational — the Asset config +
    Price snapshot entity definitions.
  - `docs/spec/security.md` §DB roles — Provider `SELECT`-only
    on `price_snapshot` and `asset_config`; Collector
    `INSERT/UPDATE/SELECT`.
  - `docs/spec/security.md` §Slow-start tier — the closed
    allowed-during-probation set carves out "every top-level
    asset command registered via `bootstrap-assets.json`",
    which is the M1-045 AssetCommandFamilyOracle seam this
    ticket displaces.

**Design references** (read but cite only if locking a
behavior):

  - `docs/design/10-asset-commands.md` whole file (verified at
    brief-authoring time — 344 lines, sections §10.1 through
    §10.11). Cites: class layout, DDL, reply layout,
    bootstrap schema, ToS attribution, friendly errors,
    retention.

**Locked decisions for this umbrella**:

  - **Operator-optional feature.** Absent
    `bootstrap-assets.json` → asset commands disabled, `/help`
    does not list them, `AssetCommandFamilyOracle.isAssetCommand`
    returns `false` for every input. A deployment without
    `bootstrap-assets.json` is conformant.
  - **The umbrella's cross-cutting IT** exercises:
    bootstrap-load → AssetSnapshotFetcher tick →
    PriceSnapshotStore INSERT → NOTIFY emit → Provider
    `/zcash` reply containing the attribution URL bare per D30
    + capture timestamp + cache age. The IT pins the
    `Collector → Provider via NOTIFY` path even though the
    freshness contract makes the cache-read the correctness
    mechanism — the IT proves both pieces. Test-harness shape
    (adapter selection, fake vs. real AssetDataSource) is the
    author's call.
  - **AssetCommandFamilyOracle impl swap is umbrella-scoped.**
    The seam exists on disk; T2-H displaces the
    `isAssetCommand` body to consult the loaded asset
    registry. The impl swap is one method body change + a
    constructor field for the loaded set; it lives in the
    umbrella so the cross-cutting IT can exercise the
    probation interaction (a probation user typing
    `/zcash <sub-verb>` should be ALLOWED per spec §Slow-start
    tier).
  - **No outbox, no Stage 1/2, no tagging, no embedding.**
    Asset snapshots are NOT posts. The Collector-side path is
    a sibling to the post-ingest pipeline, NOT a participant.

**Out-of-scope** (template for the umbrella's frontmatter):

  - any change to the spec — §Asset commands + §Operational
    asset_config + §Operational price_snapshot are already
    complete and committed
  - any v2 surface — websocket "live" mode, on-chain verbs,
    historical queries, auth-gated exchanges, alerts /
    thresholds (all in design §10.9)
  - any new asset beyond /zcash + /monero — v1 ships exactly
    those two; adding /bitcoin or similar is a spec-amend +
    bootstrap-assets entry, not a code change
  - any TranslationProvider interaction — T2-C territory;
    asset replies ship English-only bundle keys
  - any /quarantine / /summary / /save interaction — spec
    §Asset commands explicitly excludes asset snapshots
    from those surfaces
  - any change to ConfirmStateService — asset commands are
    not destructive; no confirm gate
  - any change to InboundRouter intake-step splice — the
    router picks up new CommandHandler beans via
    `Instance<CommandHandler>` iteration

**Acceptance shape** (for the umbrella):

  - 2-3 acceptance items focusing on the cross-cutting IT:
    bootstrap-load → fetcher tick → snapshot row INSERT →
    NOTIFY emit → Provider `/zcash` reply contains the
    attribution URL bare; the
    AssetCommandFamilyOracle.isAssetCommand returns true for
    `"zcash"` and `"monero"` after bootstrap-load and false
    for unknown assets; a probation user's `/zcash <sub-verb>`
    is ALLOWED (the M1-045 interaction).
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 6-9 (umbrella IT + bundle keys + the
oracle impl swap + startup loader wiring + IT helpers; most
implementation files live in the subtickets).

**security_relevant: true** — Provider `SELECT`-only DB role
on `asset_config` and `price_snapshot` is a least-privilege
commitment; the bootstrap-load path is a system boundary that
validates operator JSON.

### T2-H.a — bootstrap-assets.json + asset_config

**Spec anchors**:

  - `docs/spec/commands.md` §Asset commands — "Enable /
    disable lifecycle" paragraph + the
    "Default-but-disabled fallback" paragraph.
  - `docs/spec/schema.md` §Operational — Asset config entity
    definition + the partial unique index on `is_default`.

**Design references**:

  - `docs/design/10-asset-commands.md` §10.6
    `bootstrap-assets.json` schema (verified at brief-
    authoring time — JSON shape locked).

**Locked decisions**:

  - **Mirror the existing `BootstrapSourcesParser` pattern.**
    Verified at brief-authoring time:
    `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParser.java`
    + `BootstrapSourcesEntry.java` exist and set the
    precedent. The new parser/entry pair follows the same
    naming + idempotent-upsert-by-id semantics.
  - **Default-row consistency check at boot.** Per spec: a
    row with `is_default = true AND enabled = false` is
    rejected at Collector startup with a fatal log message
    naming the `(asset, sub_verb)` pair. This is a
    system-boundary validation; no defensive code, but the
    check IS a spec commitment and the boot fails fast on
    inconsistency.
  - **Soft-disable on absent-from-bootstrap.** An entry
    present in a prior bootstrap and absent from the latest
    bootstrap is set to `enabled = false` — never
    hard-deleted. Historical `price_snapshot` rows remain
    queryable for audit per spec.
  - **AuditAction**: `BOOTSTRAP_ASSET_LOAD` (already exists
    in `AuditAction` enum at brief-authoring time — verified
    in
    `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`).
    No new enum entries needed for this subticket.

**Out-of-scope**:

  - any fetcher impl — T2-H.b territory
  - any Provider command handler — T2-H.c territory
  - any test of the runtime fallback for
    `is_default = true AND enabled = false` — that's a
    Provider-side defense-in-depth tested in T2-H.c

**files_budget hint**: 5-7.

**security_relevant: true** — the parser is a system
boundary; JSON injection / oversize input must fail safely.

### T2-H.b — Collector fetchers + price_snapshot

**Spec anchors**:

  - `docs/spec/commands.md` §Asset commands — "Polled,
    cached, refreshed on a tick" + "Provider/Collector
    contract" + "Public endpoints only in v1" + "Retention"
    + "Freshness contract" (the Collector-side commitments).
  - `docs/spec/schema.md` §Operational — Price snapshot
    entity (INSERT-only, partitioned, profile-driven
    retention).
  - `docs/spec/security.md` §SSRF and outbound connections —
    the asset fetchers MUST go through the shared SSRF
    library for outbound HTTP, same as RSS fetchers.
  - `docs/spec/security.md` §DB roles — Collector
    `INSERT/UPDATE/SELECT` on `price_snapshot` +
    `asset_config`.

**Design references**:

  - `docs/design/10-asset-commands.md` §10.2 class layout
    + §10.3 storage + §10.4 refresh & cache + §10.7 ToS
    attribution.

**Locked decisions**:

  - **Per-host tick cadence**, NOT per-(asset, sub_verb).
    One interval per supported public-endpoint host (set per
    design §10.2). Profile-driven values per design §10.4.
  - **One impl per supported public-endpoint host.** v1 ships
    the set cited in design §10.2; the brief deliberately does
    NOT lock the class names — author's call at implementation
    time. Per-source field availability is asymmetric (design
    §10.5 table) — the `PriceSnapshot` record carries optional
    fields and the renderer omits absent ones.
  - **PriceSnapshotStore writes directly to `price_snapshot`**
    — no outbox, no eval pipeline. Emits
    `NOTIFY new_price_snapshot` with `(asset, source)` as
    payload per spec.
  - **Per-source consecutive-failure counter** in
    `asset_config.consecutive_failures` per D42 (HTTP-shaped
    source failure-counter model). On threshold-crossing,
    flip `status = 'failed'` and ping the throttled admin
    notifier; recovery is operator-side per design §10.8b
    (no chat-command equivalent in v1).
  - **All fetchers go through the SSRF library** — same
    library RSS fetchers use. No bespoke HTTP client.

**Out-of-scope**:

  - any auth-gated exchange (KuCoin, Gemini, CoinGecko Pro) —
    v1 ships public-endpoint-only sources
  - any /asset-enable command — operator-side recovery only
    in v1 (design §10.8b)
  - any websocket "live" mode — v2
  - any Provider command handler — T2-H.c territory

**files_budget hint**: 8-11. AssetDataSource SPI + per-host
impls (count per design §10.2) + AssetSnapshotFetcher +
PriceSnapshotStore + NOTIFY emission + retention partition
mechanism + 6-8 tests.

**security_relevant: true** — outbound fetcher traffic must
respect SSRF policy + the failed-source state machine is a
safety invariant.

### T2-H.c — Provider handlers + reply renderer + oracle swap

**Spec anchors**:

  - `docs/spec/commands.md` §Asset commands — the `/zcash`,
    `/monero`, "Mandatory attribution", "Stale-data
    honesty", "Freshness contract" (Provider-side
    commitments), "Friendly errors mirror the tag
    convention", "/help is context-aware", "Enable /
    disable lifecycle" (Provider-side enforcement).
  - `docs/spec/messaging.md` §Capability flags — the
    `supportsCodeFormatting` flag (verified at brief-
    authoring time:
    `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java`
    line 93 `supportsCodeFormatting`; line 48
    `supportsMarkdownLinks`). The renderer DOES NOT
    branch on `supportsCodeFormatting` for asset replies —
    per design §10.5 the layout is plain text with bare URLs
    (D30). The flag is named here so the brief is unambiguous
    about WHICH capability the future session might mistakenly
    branch on (none).
  - `docs/spec/security.md` §Slow-start tier — the asset-
    command allowed-set carve-out + the
    AssetCommandFamilyOracle seam M1-045 left for this
    ticket.

**Design references**:

  - `docs/design/10-asset-commands.md` §10.2 class layout
    + §10.5 reply layout (per-source field availability +
    default reply examples + rendering rules) + §10.7 ToS
    attribution + §10.8 friendly errors.

**Locked decisions**:

  - **Two CommandHandler beans** (or one keyed by the
    asset name — author's call). `handler.name()` returns
    `"zcash"` and `"monero"` so InboundRouter's
    `Instance<CommandHandler>` iteration picks them up.
  - **AssetCommandFamilyOracle impl swap.** Replace the
    method body so it consults the loaded asset registry
    (in-memory cache or an `asset_config` SELECT per call —
    author's call; the IT proves correctness either way).
    The interface MUST remain unchanged: M1-045's
    `CommandPermissions` consumes
    `oracle.isAssetCommand(slashCommand)`.
  - **Reply layout per design §10.5.** Plain text per D30,
    bare URLs (no markdown link syntax — `supportsMarkdownLinks`
    is false for every v1 adapter, validated at startup per
    spec §Capability flags). The renderer omits absent fields
    — does not invent zeros. Glyph-level formatting choices
    (sign character, separator, alignment) are design-tier;
    follow design §10.5.
  - **Stale marker logic.** When the latest snapshot's
    `captured_at` is older than `2 * refresh_interval`, the
    reply prepends ` ⚠ stale` to the header. The
    `refresh_interval` lookup reads `asset_config`'s
    per-source row.
  - **Bare invocation routing.** Bare `/zcash` resolves to
    the per-asset `default_sub_verb` from `asset_config`
    where `is_default = true`. Absent default → "not
    configured" friendly error. Default-but-disabled (runtime
    defense-in-depth) → "default sub-verb is currently
    disabled; pass an explicit sub-verb" friendly error
    with the enabled sub-verbs listed.
  - **No LLM call, no post-table read.** Per spec: asset
    snapshots never go through Stage 1/2, tagging,
    embedding. The handler's path is a single SQL read from
    `price_snapshot` plus an `asset_config` lookup for
    sub-verb validity + freshness window. Pin a unit test
    that asserts the handler path makes ZERO LLM calls.
  - **`/help` is context-aware.** Only operator-enabled
    assets appear in `/help`; only enabled sub-verbs appear
    in per-command help. The `/help` integration is part of
    this ticket — extend the existing /help handler (already
    on disk; verified by the M1-035c precedent).

**Out-of-scope**:

  - any fetcher impl — T2-H.b territory
  - any bootstrap parser — T2-H.a territory
  - any auth-gated exchange — v1 ships public-endpoint-only
  - any websocket "live" mode — v2
  - any TranslationProvider interaction — T2-C territory
  - any change to ConfirmStateService — asset commands are
    not destructive
  - any change to InboundRouter — handlers register as new
    CommandHandler beans
  - any /list-assets / /asset-disable admin command — v1
    ships operator-side enable/disable only

**Acceptance shape**:

  - 5-7 acceptance items covering: `/zcash <sub-verb>` happy
    path against a sub-verb whose data source carries every
    optional field (reply contains header, price line,
    attribution URL bare, capture timestamp, cache age); a
    second `/zcash <sub-verb>` happy path exercising the
    asymmetric-field rendering case (no delta lines per design
    §10.5 table); bare `/zcash` resolves to the configured
    default sub-verb; bare `/zcash` against absent-default
    returns "not configured" friendly error; `/zcash
    <misspelled-sub-verb>` returns the friendly fuzzy-
    suggestion error; `/zcash --vs jpy` against an unsupported
    quote currency returns the friendly fuzzy-suggestion error;
    stale-marker fires when latest snapshot is older than
    `2 * refresh_interval`; AssetCommandFamilyOracle.isAssetCommand
    returns true for `"zcash"` and `"monero"` after
    bootstrap-load; `/help` lists only enabled assets;
    handler path makes ZERO LLM calls (test instruments the
    LLM SPI mock to fail-loud on any call). Concrete sub-verb
    strings used in tests are author's call.
  - One @QuarkusTest IT exercising the bare `/zcash` →
    snapshot row → rendered reply path via InMemoryAdapter
    (the umbrella ticket carries the cross-Collector IT;
    this ticket's IT is Provider-internal).
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 9-12. Two handlers (or one dispatcher)
+ AssetReplyRenderer + AssetSnapshotReader + oracle impl
swap + /help integration + bundle keys + 7-9 tests + 1 IT.

**security_relevant: true** — the bare-invocation /
default-row resolution is spec-load-bearing; the
AssetCommandFamilyOracle swap is a slow-start probation
permission commitment.

## Locked decisions (cross-cutting across T2-H)

- **No InboundRouter edits.** Handlers register as new
  CommandHandler beans. The router's
  `Instance<CommandHandler>` iteration picks them up. (Verified
  at brief-authoring time:
  `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`
  §handleSlash lines 559-568.)
- **All handlers reuse the M1-040 InboundContext.** No new
  request-scoped beans.
- **Audit-log writer is M1-041's AuditLogWriter** (done).
  T2-H.a consumes `BOOTSTRAP_ASSET_LOAD` (already in
  AuditAction enum). T2-H.b and T2-H.c do NOT write audit
  rows — asset reads + fetcher INSERTs are not audit-logged
  per spec (the audit-log table is for privileged user
  actions and operator boot events, not for read-mostly
  bulk-derived rows).
- **MessagingAdapter capability flag**: `supportsCodeFormatting`
  is on
  `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java`
  line 93. The asset reply renderer DOES NOT branch on it —
  plain text + bare URLs per D30 is the universal layout.
  The brief names the flag so the future session is not
  tempted to add a richer rendering branch.
- **Bundle keys**: every new user-visible reply ships through
  a bundle key under
  `infochat-provider/src/main/resources/bundles/en.properties`
  + a constant on
  `app.zcat.infochat.provider.bundle.BundleKeys`. NO inline
  string literals.
- **Migration numbering is sequential** under
  `infochat-core/src/main/resources/db/migration/`. Re-grep at
  authoring time; M1-046 and T2-B sibling tickets may consume
  V14+ before T2-H lands.
- **AssetCommandFamilyOracle is the ONE class T2-H modifies
  that another ticket (M1-045) authored.** The interface is
  unchanged; the body changes. M1-045's `CommandPermissions`
  is unaffected — no edit there. Verified at brief-authoring
  time that the seam is in place
  (`AssetCommandFamilyOracle.isAssetCommand` returns `false`
  for every input and is documented as "T2-H displaces this
  class's implementation … without changing the interface").
- **No `--no-verify`, no test disables.** Standard
  engineering rules apply.
- **Spec edits are forbidden in T2-H.** Every acceptance
  item must trace to spec text already on main HEAD. If a
  sentence the implementation depends on is missing or
  ambiguous, escalate to `spec-amend` BEFORE implementing.

## Shared-surface chokepoints (flag for the future session)

- **InboundRouter dispatch**: NOT a chokepoint — the router
  iterates `Instance<CommandHandler>` and matches by
  `handler.name()`. Two new handlers, no router edit.
- **AuditAction enum**:
  `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`.
  At brief-authoring time, `BOOTSTRAP_ASSET_LOAD` already
  exists; T2-H.a consumes it. No new enum entries needed
  unless the boot-failure path or runtime fallback adds an
  audit row (spec is silent — author's call to either omit
  the row or add a new enum entry; if adding, verify
  spec/design for the verb name).
- **Flyway migration numbering**:
  `infochat-core/src/main/resources/db/migration/`. Next free
  at brief-authoring time is V14. M1-046 + T2-B may consume
  intermediate integers. Re-grep at authoring time.
- **Bundle keys + properties file**:
  `infochat-provider/.../bundle/BundleKeys.java` +
  `infochat-provider/.../resources/bundles/en.properties`.
  T2-H adds keys for the asset reply layout + friendly
  errors.
- **MessagingAdapter CapabilityFlags**: read-only — the
  renderer reads `supportsCodeFormatting` would be the
  branch site, but per the cross-cutting decisions the
  asset renderer does NOT branch on it. No CapabilityFlags
  edit.
- **AssetCommandFamilyOracle**: the M1-045 seam. T2-H
  modifies the impl; the interface is held stable.
  `CommandPermissions` is untouched.
- **CommandPermissions** allowed-during-probation set: T2-H
  changes NOTHING here. The set lists `/help`, `/stop`, and
  the asset-command family (resolved via the oracle). After
  T2-H's oracle swap, probation users can invoke `/zcash`
  and `/monero` and any operator-configured future asset
  command.

## Parallel-development collision plan with T2-B

T2-B (DM commands on entities) is being authored in a parallel
session; its handoff is at
`docs/plan/m1/drafts/handoff-tier2-B-dm-commands.md`. The two
groups touch three shared seams:

1. **Flyway migration sequence.** T2-H.a (`asset_config` +
   `price_snapshot`) and T2-B.1 (`saved_post`) both claim the
   next-free `V<N>__*.sql`. M1-046 is also in-flight and may
   consume V14 first. **Whichever ticket MERGES first claims
   its V<N>; the second rebases its migration filename(s) and
   any V<N> references in tests.** No reservation is
   pre-allocated.

2. **BundleKeys.java + en.properties.** Both groups append
   new message-bundle entries (T2-H: asset reply layout +
   friendly errors; T2-B: command labels + friendly errors).
   The second-merging ticket rebases en.properties (append)
   and `BundleKeys.java` (append new
   `public static final String` constants). The
   `BundleLoaderTest` reflection check enforces alignment —
   a rebased branch that drops a key will fail it.

3. **STATUS.md.** Both authoring sessions regenerate the
   status board. The second-committing session rebases then
   re-runs `scripts/regen-status.py`.

T2-H's handlers do NOT read `saved_post` / `scope_tag` /
`scope_preferences`; T2-B's handlers do NOT read
`asset_config` / `price_snapshot`. No runtime coupling — the
two groups can implement in either order.

**Recommended merge order if both land around the same time**:
T2-B.1 first (smaller migration set), then T2-H (umbrella +
subs cascade renumbers cleanly if any subs each add their own
migration). Reverse is fine.

## After authoring all tickets

1. Verify each ticket's `spec_refs:` anchors actually exist
   with `grep -nE '^## |^### ' docs/spec/<file>` (clarity-check
   pre-flight blocks otherwise). T2-H's anchors live in
   `docs/spec/commands.md` §Asset commands, `docs/spec/schema.md`
   §Operational, `docs/spec/security.md` §DB roles + §Slow-start
   tier + §SSRF and outbound connections, and
   `docs/spec/messaging.md` §Capability flags.
2. Verify each ticket's `files_scope:` paths exist or are
   plausibly new (relative-path under one of the modules).
3. Verify the next-free Flyway migration integer and the
   next-free M1 ticket id by re-grepping the worktree —
   M1-046's in-flight commit and any T2-B..T2-G sibling
   sessions may have shifted both.
4. Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and confirm:
   - pending count rose by 4 (umbrella + 3 subs) if
     umbrella+subs taken
   - the umbrella ticket is Runnable
   - the three subs are blocked_by the umbrella (per
     M1-008 / M1-044 umbrella+subs convention)
5. Leave the new ticket files UNTRACKED on main. Do NOT
   commit them.
6. Update `docs/plan/m1/drafts/session-grouping-plan.md`
   Tier 2 row for T2-H to record the actual IDs and the
   umbrella+subs shape (the row currently estimates "1
   ticket" — this update reflects reality). Commit as
   `process: Record T2-H ID allocation (M1-06X umbrella +
   subs)`.
7. Print a one-screen summary in chat listing the ticket
   IDs and titles. Recommended start order: umbrella first
   (so the IT shape is locked), then T2-H.a + T2-H.b in
   either order (independent), then T2-H.c (consumes both).

## What you do NOT do in this session

- Do NOT author any T2-A/B/C/D/E/F/G tickets. Those are
  separate sessions.
- Do NOT implement any T2-H code. No `src/` edits anywhere.
- Do NOT amend any spec or design file. T2-H's spec is
  already complete on main HEAD per the §Asset commands +
  §Operational + §Capability flags + §Slow-start tier
  sections + the entire `docs/design/10-asset-commands.md`.
- Do NOT touch M1-046's in-flight work. Even though M1-046
  may consume V14 before T2-H's migrations land, the
  authoring session does NOT pre-resolve the conflict.
- Do NOT touch AssetCommandFamilyOracle's interface or
  CommandPermissions. The acceptance criteria pin the swap
  shape; the implementing session executes it, not this
  one.
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
  belongs at system boundaries; the bootstrap-assets.json
  parser IS a boundary (operator input), but internal calls
  between T2-H's services are trusted.
- **No workarounds, no shortcuts.** If a constraint blocks
  ticket authoring, escalate via the workflow.
- **Push back when simpler exists.** If the brief's
  umbrella+3-subs default has a materially simpler
  alternative (e.g. all of T2-H fits cleanly in two
  tickets — Collector-side + Provider-side — without the
  umbrella IT), surface it in chat BEFORE committing the
  files. The IT can ride in either non-umbrella ticket if
  needed.
- **Read spec files only when something is unclear.** The
  brief cites the spec anchors with section names; the
  authoring session reads those sections directly rather
  than re-deriving state from the codebase.

## Outputs

By the end of this session:

- Four (umbrella + 3 subs) new ticket files exist UNTRACKED
  under `docs/plan/m1/tickets/`. The umbrella is Runnable;
  the subs are blocked_by the umbrella.
- One `process:` commit on main updates the
  session-grouping-plan's T2-H row.
- Working tree contains the new ticket files (untracked)
  and STATUS.md (committed via the process: commit). No
  code changes.

The natural next step is `/m1-tick start <umbrella-id>`
against the umbrella ticket.
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Four (umbrella + 3 subs) new ticket files appear
      UNTRACKED under `docs/plan/m1/tickets/`. Umbrella is
      Runnable; subs are blocked_by umbrella.
- [ ] STATUS.md regenerates with pending count up by 4.
- [ ] One `process:` commit on main updates the
      session-grouping plan's T2-H row.
- [ ] No `src/` edits anywhere.
- [ ] No spec or design edits.
- [ ] AssetCommandFamilyOracle's interface remains
      unchanged (only the impl swaps); CommandPermissions
      is untouched.

If the session deviates (touches code, amends the spec, or
authors T2-A/B/C/D/E/F/G tickets), it has misread the brief —
abort and start over with the same prompt.
