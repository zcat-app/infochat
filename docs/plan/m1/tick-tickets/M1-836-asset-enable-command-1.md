---
id: M1-836
title: "/asset-enable admin command to reset a failed asset pair"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  AssetEnableCommandHandlerTest#failedPairResetWritesActiveStatusZeroCounterAndAuditRow
  (to-be-written — the handler bean does not exist; the RED evidence at start
  is the test failing against the absent AssetEnableCommandHandler class, the
  M1-818 shape). Live incident backing it (.scratch/setup-hurdles.md items
  11-12, 2026-08-11): the zcash/coingecko asset_config row sat at
  status='failed', consecutive_failures=5 with a HEALTHY upstream (HTTP 200
  probed by hand); the fetcher enumerates only
  `enabled = true AND status = 'active'` pairs
  (infochat-collector/.../assets/AssetSnapshotFetcher.java:314-318) so the
  pair was never retried; bare /zcash resolves to that default pair, making
  the whole command look broken while /zcash kraken and /zcash bitfinex
  worked. No chat command could recover it: there is no CommandHandler bean
  named "asset-enable" (CommandCatalogueParityTest gates the bean set against
  the commands.md index, which lists no /asset-enable), so a bot admin's
  /asset-enable zcash fell through to the unknown-command path and recovery
  required direct SQL (UPDATE asset_config SET status='active',
  consecutive_failures=0) — impossible for an operator in the field.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V*__asset_config_provider_reset_grant.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetEnableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AssetEnableCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AssetEnableGrantIT.java
  - docs/spec/commands.md
  - docs/spec/security.md
  - docs/spec/architecture.md
  - docs/design/10-asset-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    /asset-disable or any new writer of asset_config.status='disabled'.
    Nothing in v1 writes 'disabled' on asset_config (grep 'disabled' in
    infochat-collector/src/main/java/app/zcat/infochat/collector/assets/
    returns nothing); operator disablement is the bootstrap re-list path
    (enabled=false, commands.md §Asset commands "Soft-disable"). No incident
    demanded a chat-side disable.
  - >-
    Any UrlProbe / SSRF-gated reachability check. asset_config stores no
    fetch URL (only attribution_url, a human-facing page); the fetch URL is
    constructed inside the Collector's AssetDataSource beans. There is
    nothing correct for a provider-side probe to call (P1).
  - >-
    Any change to AssetSnapshotFetcher, the D42 asset ladder
    (failure-threshold, active→failed guard, notifyOnce key or throttle
    semantics), or the Collector's enumeration predicate.
  - >-
    Extending the M1-752/M1-754 re-probe rung to asset feeds.
    architecture.md §Ingest SPIs: that is "its own decision, not an
    automatic consequence of the amendment".
  - >-
    restore.sh surfacing of inherited failed pairs (setup-hurdles.md item
    11's improvement angle) — an ops-script change, separate ticket.
  - >-
    News-source ladders and any ConfirmStateService / PendingConfirm edit.
    Sources already have /source-enable + automatic re-probe; asset_config
    has no deleted_at, so there is no revive flow and no confirm gate (P10).
  - >-
    Editing any already-applied migration (V14 included). The grant lands as
    a new V<next> file only (P9).
acceptance:
  - "AssetEnableCommandHandlerTest.failedPairResetWritesActiveStatusZeroCounterAndAuditRow (the reproduction, written and run RED at start per the to-be-written marker) passes — a bot admin issuing `/asset-enable zcash coingecko` against a row seeded at status='failed', consecutive_failures=5 sees the success reply naming the pair, and the committed state carries status='active', consecutive_failures=0 plus exactly one audit_log row (action ASSET_ENABLE, target_kind 'asset', target 'zcash/coingecko', actor the admin) written BEFORE the UPDATE in the same transaction (implements docs/spec/commands.md §Asset commands as amended by this ticket; D42's every-transition-is-audited invariant)."
  - "AssetEnableGrantIT.providerRoleCanUpdateStatusAndCounterColumns passes — under SET ROLE infochat_provider the reset UPDATE on (status, consecutive_failures) succeeds, proving the new V<next>__asset_config_provider_reset_grant.sql migration applied cleanly through Flyway on the fresh Testcontainers DB and granted exactly the column-scoped write (P3, P9; docs/spec/security.md §DB roles as amended)."
  - "AssetEnableGrantIT.providerRoleCannotUpdateIdentityOrConfigColumns passes (P3 failure mode) — under SET ROLE infochat_provider, writes to enabled, is_default, and attribution_url each fail with SQLState insufficient_privilege (SourceEnableParkResetIT.java:148-175 is the SET ROLE pattern); expected output: three denied statements, zero rows changed."
  - "AssetEnableCommandHandlerTest.nonAdminGetsAdminOnlyErrorAndNoStateChange passes (P6, P8 failure mode) — a registered non-admin naming a real failed pair gets error.admin_only (leaking nothing about pair existence, M1-483 auth-order precedent), the row is untouched, and zero ASSET_ENABLE audit rows exist."
  - "AssetEnableCommandHandlerTest.enabledFalsePairRefusedNamingBootstrapPath passes (P5 failure mode) — a status='failed', enabled=false row is refused with the friendly error naming the bootstrap re-list path; no state change, no audit row, and enabled is never written."
  - "AssetEnableCommandHandlerTest.bareFormResolvesDefaultSubVerb, AssetEnableCommandHandlerTest.bareFormNoDefaultReturnsNotConfigured, and AssetEnableCommandHandlerTest.alreadyActivePairReturnsErrorNoAuditNoStateChange pass (P7) — the bare form flips ONLY the is_default pair of a two-pair asset; an asset with no default gets the not-configured friendly error with no state change; an already-active pair gets the already-active error with no audit row."
  - "The handler issues no outbound HTTP (P1): AssetEnableCommandHandlerTest boots its @QuarkusTest profile with no UrlProbe alternative bean and passes (any probe dependency on the handler fails CDI wiring at test boot — contrast SourceEnableCommandHandlerTest's required StubProbeProfile); Verify: grep -n 'UrlProbe' infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetEnableCommandHandler.java returns no match."
  - "Parity surfaces pass WITH the new command present (P4): CommandCatalogueParityTest (commands.md index ↔ CommandHandler bean set), LlmOutputSanitizerTest.matchSetEqualsSpecClosedList (spec privileged-tier list ↔ LlmOutputSanitizerCore.CLOSED_LIST, '/asset-enable' added to both), and BundleLoaderTest (every new BundleKeys constant valued in en/cs/es/ru/tr per D43) — each reds the build on its one missing surface."
  - "Spec amendments land, exact wording approved by the user at implementation time (engineering-rules §12; the M1-779 rides-the-diff shape): docs/spec/commands.md (index line, §Asset commands bullet, §Permission model bot-admin list), docs/spec/security.md §DB roles (column-scoped asset_config write exception), docs/spec/architecture.md §Ingest SPIs (provider grant sentence), docs/design/10-asset-commands.md §10.8b sync; Verify: git diff on those four files shows rule-text only — no dates, ticket IDs, or report citations in spec prose."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AssetEnableCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AssetEnableGrantIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/commands.md §Permission model
  - docs/spec/security.md §DB roles
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D34
  - D39
  - D42

reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-836: /asset-enable admin command to reset a failed asset pair

## Context

On 2026-08-11 a restore inherited a zcash/coingecko `asset_config` row at
`status='failed'`, `consecutive_failures=5` — the D42 ladder had tripped on
the source host and the state migrated with the backup unnoticed
(.scratch/setup-hurdles.md item 11). The upstream API was healthy; sibling
pairs fetched fine. But bare `/zcash` resolves to the default sub-verb
(`is_default=true`, the failed pair), so the whole command looked broken.
Recovery required direct SQL — an operator in the field cannot do that, and
no admin command touches `asset_config` (item 12). Sources have had this
recovery since M1-053 (`/source-enable`); asset pairs are the one persisted
failure ladder with no chat-side reset. This ticket adds
`/asset-enable <asset> [sub-verb]`, the asset-side mirror, with the
ladder-reset semantics the asset ladder actually consults — not a blind copy
of the source one.

## Root cause

Verified, with citations:

- **The asset ladder never self-recovers.** The Collector's asset fetcher
  enumerates only `enabled = true AND status = 'active'` rows
  (infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java:314-318),
  flips `active → failed` on threshold breach guarded by
  `AND status='active'` (AssetSnapshotFetcher.java:287-290), and its own
  javadoc states "Recovery from `failed` is operator-side per
  docs/design/10-asset-commands.md §10.8b" (AssetSnapshotFetcher.java:54-55).
  architecture.md §Ingest SPIs pins the spec side: "D42's automatic re-probe
  rung (amended by M1-752) does **not** apply to `asset_config` — a parked
  asset feed recovers only by operator action in v1" (line 175-178).
- **No operator-action surface exists.** design §10.8b (lines 414-429)
  documents the SQL runbook and defers a chat command to v2; the V14 table
  header repeats "no chat-command equivalent in v1"
  (infochat-core/src/main/resources/db/migration/V14__asset_config.sql:30-34).
  Grep for `asset-enable|asset_disable` across the repo hits only design
  notes and old tickets' out-of-scope clauses — no handler, no bundle key.
- **The asset ladder is simpler than the source ladder (census result).**
  `asset_config` carries `enabled`, `default_quote_currency`,
  `attribution_url`, `consecutive_failures`, `last_success_at`,
  `last_failure_at`, `is_default`, and `status ∈ {active, failed, disabled}`
  (V14__asset_config.sql:37-50). It has NONE of source's M1-754 park/re-probe
  columns (`park_reason`, `parked_at`, `reprobe_count`, `next_reprobe_at`,
  `reprobe_restored_at` — V75__source_park_reason.sql:19-25), and no
  `deleted_at`. What the ladder consults is exactly `status` +
  `consecutive_failures` — so the reset is exactly
  `SET status='active', consecutive_failures=0`, the runbook SQL that
  recovered the live incident within one tick (setup-hurdles.md item 11).
- **The Provider cannot write the table today.** V14 grants
  `SELECT, INSERT, UPDATE` to `infochat_collector` and `SELECT`-only to
  `infochat_provider` (V14__asset_config.sql:73-74); security.md §DB roles
  (line 2055-2060) commits to "SELECT-only on asset_config … never writes
  to either". The command therefore needs a new column-scoped grant
  migration, the V31/V75 pattern, and the security.md sentence must be
  amended (rides this diff; §12 approval).
- **Audit plumbing already fits.** `TargetKind.ASSET` exists
  (infochat-core/src/main/java/app/zcat/infochat/core/audit/TargetKind.java:26,
  mirrored by the V5 CHECK); `audit_log.action` is open-ended TEXT whose
  closure enforcer is the `AuditAction` enum (AuditAction.java:67), so the
  new `ASSET_ENABLE` action needs no DB constraint change.

Brief-vs-code discrepancies: none material. The brief's line cites
(SourceEnableCommandHandler.java ~105-120) match the reset UPDATEs at
lines 111-120. M1-782's ticket file is named
`M1-782-asset-24h-range-can-exclude-spot-price.md` (brief's paraphrase
differed slightly); it touched only AssetReplyRenderer + its test — asset
*command* dispatch lives in `command/asset/AssetHandler.java` behind
`AssetRegistry`/`AssetCommandFamilyOracle`, while `/asset-enable` is a
static admin command and belongs beside `SourceEnableCommandHandler` in
`provider/command/`, not in the dynamic asset family.

## Pitfalls

- P1: **Blind-copying /source-enable's UrlProbe pre-check.** The probe runs
  against `source.identifier`, a stored URL. `asset_config` stores no fetch
  URL — only `attribution_url` (a human-facing page, V14:42); fetch URLs are
  constructed inside the Collector's `AssetDataSource` beans
  (KrakenSnapshotSource.java:117 etc.). A provider probe would either test
  the wrong host or duplicate collector URL construction (scope drift + a
  new SSRF-gate surface, security.md §SSRF). Skipping the probe is safe:
  a still-broken upstream re-trips the D42 ladder within
  `infochat.assets.failure-threshold` ticks and re-fires the throttled
  admin notification (`notifyOnce` is window-throttled, not once-ever —
  ThrottledAdminNotifier.java:230-232), so no notifier-state reset is
  needed either. The proven live recovery (runbook SQL) did no probe.
- P2: **Blind-copying the source reset column set.** An UPDATE naming
  `park_reason`/`reprobe_*` fails at runtime — those columns do not exist
  on `asset_config` (V14:37-50). The reset must be exactly
  `status='active', consecutive_failures=0`, keyed `WHERE asset = ? AND
  sub_verb = ?`.
- P3: **Wholesale `GRANT UPDATE ON asset_config TO infochat_provider`.**
  Least-privilege (D34, security.md §DB roles): config columns (`enabled`,
  `is_default`, `default_quote_currency`, `attribution_url`) must stay
  read-only or a Provider SQL-injection foothold could silently re-enable
  bootstrap-removed pairs or move the default sub-verb. Column-scoped
  `GRANT UPDATE (status, consecutive_failures)` only, mirroring V75's
  named-column extension (V75__source_park_reason.sql:40-48) and M1-754's
  out_of_scope rule ("extended column-by-column or not at all").
- P4: **Missing one of the four parity surfaces.** A new `CommandHandler`
  bean reds `CommandCatalogueParityTest` without the commands.md index line;
  adding `/asset-enable` to the spec's closed privileged-tier list reds
  `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` without the matching
  `LlmOutputSanitizerCore.CLOSED_LIST` entry (M1-651's completeness-guard
  failure mode); `HelpCommandHandler.CATALOGUE` needs a
  `CommandHelp("asset-enable", …, HelpTier.BOT_ADMIN)` entry (bean set ↔
  catalogue ↔ tier parity is an engineering-rules §8 gate); and D43 bundle
  completeness reds the build unless every new `BundleKeys` constant has a
  value in en/cs/es/ru/tr (BundleLoaderTest reflective check).
- P5: **Reporting success on an `enabled=false` pair.** The fetcher never
  schedules `enabled=false` rows (AssetSnapshotFetcher.java:318), so a
  "re-enabled" reply on a bootstrap-removed pair promises recovery that
  never comes. Refuse with a friendly error naming the bootstrap re-list
  path; never write `enabled` — it is operator-curated (D39) and
  bootstrap-loader-owned (V14 header).
- P6: **Dropping audit-before-effect.** commands.md §Permission model:
  "any new command added to the system goes through the same permission
  matrix and the same audit-before-effect rule." D42's amended text makes
  the same point for `failed → active` transitions specifically (they were
  always admin-issued and audit-logged). The `ASSET_ENABLE` row
  (TargetKind.ASSET, target `asset/sub_verb`, actor + request id) is
  written BEFORE the UPDATE in the SAME transaction
  (SourceEnableCommandHandler.java:242-245 is the pattern); error branches
  write no audit row and change no state.
- P7: **Bare-form semantics drift.** Bare `/asset-enable zcash` must
  resolve the `is_default` pair — the same rule as bare `/zcash`
  (commands.md §Asset commands) and exactly the incident shape (the failed
  pair WAS the default, so the bare form is what the operator will type).
  Never reset ALL pairs of an asset (blast radius; the spec shape is
  per-pair) and never fall back to a non-default pair when no default
  exists (the not-configured friendly error fires, same as bare `/zcash`).
- P8: **Auth ordering / state leakage.** The bot-admin gate runs before any
  pair-existence reveal: a non-admin gets `error.admin_only` whether or not
  the pair exists (M1-483 auth-order precedent;
  SourceEnableCommandHandler.java:159-169 is the gate shape, including the
  group-scope pre-check at lines 153-157).
- P9: **Editing an applied migration.** V14 is shipped; setup-hurdles.md
  item 1 showed a comment-only edit to applied V50/V55 breaking restores on
  Flyway checksum mismatch. The grant is a new `V<next>` file, nothing else.
- P10: **Copying the confirm/soft-delete branches.** `asset_config` has no
  `deleted_at` and re-enabling a failed pair is non-destructive, so there
  is no confirm flow — and no `ConfirmStateService` sealed-permits edit
  (§7: no structure for states that cannot exist; the M1-053 outline
  escalation showed the cost of dragging PendingConfirm permits into a
  command that does not need them).

## Approach

Derived from `spec_refs:` — the spec already commits asset-feed recovery to
"operator action" (architecture.md §Ingest SPIs) and every admin mutation to
the permission matrix + audit-before-effect (commands.md §Permission model);
this ticket delivers the operator action as a chat command and extends the
already-documented column-scoped write-exception pattern (V31 on `source`,
extended by V75) to `asset_config`. That is the M1-754 shape — a spec
amendment riding the implementing diff with §12 wording approval — not a
SPEC-GAP: no existing promise is broken, one enumerated exception list grows
by one column-scoped entry, and §Permission model explicitly anticipates new
commands. All spec wording below is rule-text only and goes to the user for
approval at implementation time.

**Files to touch** (guidance, not an allowlist):

1. `infochat-core/src/main/resources/db/migration/V<next>__asset_config_provider_reset_grant.sql`
   — new file: `GRANT UPDATE (status, consecutive_failures) ON asset_config
   TO infochat_provider;` with a header comment citing security.md §DB
   roles and the never-wholesale rule (V75's header is the model). (P3, P9)
2. `infochat-core/.../core/audit/AuditAction.java` — add `ASSET_ENABLE`
   beside `SOURCE_ENABLE`. (P6)
3. `infochat-provider/.../provider/command/AssetEnableCommandHandler.java`
   — new `CommandHandler` bean, `name() = "asset-enable"`, shaped on
   `SourceEnableCommandHandler` minus probe/confirm/kind-gate:
   group-scope pre-check → bot-admin gate (P8) → parse `<asset>
   [sub-verb]` (bare form resolves `is_default`; unknown asset/sub-verb
   gets the friendly error with the asset's sub-verb list, P7) → one
   SELECT of the pair → branches: row absent → unknown-pair error;
   `enabled=false` → refusal naming the bootstrap re-list path (P5);
   `status='active'` → already-active error; else ONE transaction:
   `SELECT … FOR UPDATE` re-check (the SourceEnableCommandHandler.java:229
   TOCTOU pattern — the concurrent writer here is the Collector's ladder),
   `ASSET_ENABLE` audit row, then `UPDATE asset_config SET status='active',
   consecutive_failures=0 WHERE asset=? AND sub_verb=?` (P2, P6). Success
   reply names asset + sub-verb and that the next fetch tick resumes the
   pair. No `UrlProbe`, no `ConfirmStateService` (P1, P10).
4. `BundleKeys.java` + `bundles/{en,cs,es,ru,tr}.properties` —
   `reply.asset_enable.success`, `error.asset_enable.unknown_pair`,
   `error.asset_enable.not_enabled`, `error.asset_enable.already_active`,
   plus `help.cmd.asset-enable.{short,usage,examples}`; all five languages
   (D43, P4).
5. `HelpCommandHandler.java` — CATALOGUE entry, `HelpTier.BOT_ADMIN`. (P4)
6. `LlmOutputSanitizerCore.java` — `"/asset-enable"` in `CLOSED_LIST`,
   after `"/source-disable"`, matching the spec list's position. (P4)
7. Spec amendments (rule-text only; §12 approval):
   - commands.md command index: insert `/asset-enable` (sorted, between
     `/approve-group` and `/audit`).
   - commands.md §Asset commands, new bullet: `/asset-enable <asset>
     [sub-verb]` — bot-admin only; transitions a non-`active`
     `asset_config` pair to `status='active'` and zeroes
     `consecutive_failures` so the Collector's next per-host tick resumes
     the pair; bare form addresses the `is_default` pair, no default → the
     not-configured friendly error; `enabled=false` pairs are refused
     naming the bootstrap re-list path (the command never edits
     operator-curated enablement); no probe and no confirm (the fetch URL
     is collector-constructed, not stored on the pair; re-enabling is
     non-destructive; a still-failing upstream re-trips the D42 ladder);
     audit-logged before effect in the same transaction.
   - commands.md §Permission model bot-admin bullet: add `/asset-enable`
     after `/source-disable`.
   - security.md §DB roles: extend the write-exception passage — the
     Provider additionally holds column-scoped `UPDATE (status,
     consecutive_failures)` on `asset_config` for `/asset-enable`;
     `price_snapshot` stays SELECT-only and every other `asset_config`
     column stays read-only.
   - architecture.md §Ingest SPIs: the "Provider has `SELECT` on both"
     sentence gains the column-scoped `asset_config` exception; the
     "recovers only by operator action" sentence stands unchanged (the
     command IS the operator action).
8. `docs/design/10-asset-commands.md` §10.8b — sync the "no chat-command
   equivalent in v1" statement to name `/asset-enable`; keep the SQL as the
   host-level fallback. (Design doc; not §12-gated, but must not keep
   asserting the pre-ticket world.)
9. Tests (below).

**Implementation order:** migration first (the ITs boot through Flyway and
the handler's UPDATE needs the grant) → AuditAction + BundleKeys/bundles
(keys must exist before the handler references them; D43 gates the build)
→ handler → catalogue + sanitizer list + spec edits in the same commit (the
parity tests red on any partial state, P4) → tests last in the diff but run
RED first per workflow §0.

**Controls to preserve (engineering-rules §10):** this diff adds a path
rather than rerouting one, but it edits two gated surfaces whose existing
controls must keep passing unchanged: `LlmOutputSanitizerCore.CLOSED_LIST`'s
spec-list parity (`matchSetEqualsSpecClosedList`), the command-index/bean/
catalogue/tier parity (`CommandCatalogueParityTest`), and D43 bundle
completeness (`BundleLoaderTest`). The Collector's ladder (counter bump,
guarded status flip, notifyOnce) is untouched — `AssetSnapshotFetcher`'s
existing ITs pin it.

**Pitfall→mitigation mapping:** P1→file 3 has no probe + acceptance item 7;
P2→file 3's UPDATE column list + AssetEnableCommandHandlerTest
.failedPairReset…; P3→file 1 + AssetEnableGrantIT; P4→files 4-7 + parity
acceptance item 8; P5→branch order in file 3 + .enabledFalsePairRefused…;
P6→transaction shape in file 3 + audit assertions in the reproduction test;
P7→parse/resolve step + the bare-form tests; P8→gate-first handler
skeleton + .nonAdminGets…; P9→new-file-only migration; P10→no
ConfirmStateService in files_scope.

## Definition of done

Every `acceptance:` item, verified by its named test/command: the
reproduction test green (reset + same-transaction audit + success reply);
both grant ITs green (allowed columns writable, config columns denied under
`SET ROLE infochat_provider`); the failure-mode tests green (non-admin,
enabled=false, no-default bare form, already-active); the probe-absence
proof; the three parity/completeness gates green with the new command
present; the spec amendments landed as approved rule-text; `mvn verify`
from repo root green.

## Verification

- P1 → AssetEnableCommandHandlerTest (class level) — boots the handler
  with no UrlProbe alternative in the test profile; any probe dependency
  fails CDI wiring at test boot. Plus `grep -n 'UrlProbe'
  infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetEnableCommandHandler.java`
  → expected: no match.
- P2 → AssetEnableCommandHandlerTest.failedPairResetWritesActiveStatusZeroCounterAndAuditRow
  — seeds `status='failed', consecutive_failures=5`, issues the command,
  asserts the row reads back `active`/0 (Testcontainers PostgreSQL —
  a wrong column name fails for real, not against a stub).
- P3 → AssetEnableGrantIT.providerRoleCanUpdateStatusAndCounterColumns and
  AssetEnableGrantIT.providerRoleCannotUpdateIdentityOrConfigColumns —
  `SET ROLE infochat_provider`; the reset UPDATE succeeds and writes to
  `enabled` / `is_default` / `attribution_url` raise
  insufficient_privilege (SourceEnableParkResetIT.java:148-175 pattern).
- P4 → CommandCatalogueParityTest,
  LlmOutputSanitizerTest.matchSetEqualsSpecClosedList, BundleLoaderTest —
  all must be green WITH the new command added; each fails on its one
  missing surface.
- P5 → AssetEnableCommandHandlerTest.enabledFalsePairRefusedNamingBootstrapPath
  — feeds a `failed` + `enabled=false` row; asserts refusal, unchanged row,
  no audit row.
- P6 → the reproduction test's audit assertion (exactly one ASSET_ENABLE
  row, actor the admin, target `zcash/coingecko`) plus
  AssetEnableCommandHandlerTest.alreadyActivePairReturnsErrorNoAuditNoStateChange
  asserting error paths write nothing.
- P7 → AssetEnableCommandHandlerTest.bareFormResolvesDefaultSubVerb (seeds
  two pairs, one is_default; asserts ONLY the default pair flipped) and
  AssetEnableCommandHandlerTest.bareFormNoDefaultReturnsNotConfigured.
- P8 → AssetEnableCommandHandlerTest.nonAdminGetsAdminOnlyErrorAndNoStateChange
  — hostile input: a registered non-admin naming a real failed pair;
  asserts the fixed admin-only reply leaks nothing about pair existence and
  the row/audit state is untouched.
- P9 → AssetEnableGrantIT booting a fresh Testcontainers DB through Flyway
  proves the new migration applies cleanly; `git diff --name-only
  infochat-core/src/main/resources/db/migration/` → expected: exactly one
  new V<next> file, no edited V≤<current> file.
- P10 → files_scope contains no ConfirmStateService; any confirm-shaped
  argument (`/asset-enable zcash confirm`) is ordinary trailing text the
  parser routes to the unknown-pair error — covered by the unknown-pair
  branch of AssetEnableCommandHandlerTest.
- acceptance item 9 → `git diff docs/spec/commands.md docs/spec/security.md
  docs/spec/architecture.md docs/design/10-asset-commands.md` → expected:
  rule-text-only edits matching the user-approved wording, no dates or
  ticket IDs in spec prose.

## Out-of-scope

Per the YAML block: no `/asset-disable` (no v1 writer of `status='disabled'`
exists on asset_config — grep-verified — and operator disablement is the
bootstrap re-list path; adding a chat disable would invent a state writer
no incident demanded). No probe/SSRF addition. No change to the Collector's
ladder, threshold, or notifier. No re-probe extension to asset feeds
(architecture.md reserves that as its own decision). No restore.sh
surfacing of inherited failed pairs (setup-hurdles item 11 — separate
ops-script ticket). No source-ladder or ConfirmStateService edits. No edit
to any applied migration. No pre-existing test is modified by this ticket.

## Census

Class: **persisted failure ladders lacking an admin chat-side reset.**
Re-runnable enumeration: `grep -rn "SET status = 'failed'" --type java`
over production sources returns three writers —
NostrStreamSource.java:561 (table `source`, cycle-cap park → covered by
`/source-enable`, V75 park_reason 'stream-cycle-cap'),
PerSourceUnknownTracker.java:164 (table `source`, UNKNOWN-rate park →
covered by `/source-enable`, manual-only by design),
AssetSnapshotFetcher.java:289 (table `asset_config` → **this ticket**).
Disposition: source writers — already covered, out of scope; asset writer —
fixed here. No third persisted ladder surface exists: the LLM circuit
breaker (LlmCircuitBreakerRegistry) is in-memory, and invite/ban/quarantine
state machines are command-driven, not ladders. The brief's symmetry
question (`/asset-disable`) is disposed above: no writer, no incident,
bootstrap re-list already covers operator disablement — not needed.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-836-asset-enable-command-1.md
```
