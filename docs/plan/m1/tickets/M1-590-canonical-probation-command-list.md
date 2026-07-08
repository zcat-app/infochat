---
id: M1-590
title: "Provider: one canonical source for the probation-allowed command list (welcome + /help + rejection can no longer drift; rejection wrongly omits /zcash /monero)"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ProbationCommandListConsistencyTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Changing WHICH commands are allowed during probation. The allowed set is
    the CommandPermissions.ALLOWED static list plus the operator-enabled
    asset family, verbatim from security.md §Slow-start tier — this ticket
    only unifies how that already-decided set is RENDERED across the three
    surfaces; it adds/removes NO command from the probation allow-list and
    does NOT touch the allowedDuringProbation predicate's semantics.
  - >-
    The /help per-command short-help LINE format and its per-line rendering.
    /help already derives its probation listing from CommandPermissions (it
    is the reference implementation — see Context); this ticket does not
    restyle /help output, it only pins /help to the canonical set via the
    new consistency test. Consistency is asserted over the command-name SET,
    not the display format (welcome/rejection render a comma list; /help
    renders one short-help line per command — both are fine).
  - >-
    The probation TIMER, graduation/lazy-clear, /vouch, and the intake step
    ordering in InboundRouter. Untouched. Only the two hard-coded bundle
    command LISTS (welcome + rejection) and the code that fills them change.
  - >-
    Adding or removing bundle KEYS. The fix restructures the VALUES of two
    EXISTING keys (reply.welcome.dm_fresh, error.probation.blocked) to carry
    a MessageFormat placeholder for the command list, plus their cs twins.
    No new BundleKeys constant; the D43 bilateral keyset is unchanged in
    membership (only two existing values, both en+cs, are edited).
  - >-
    Asset-command enablement/config and AssetRegistry semantics. Reused
    unchanged — the canonical renderer only READS the enabled asset names
    (the same source /help already reads via AssetRegistry.getEnabledAssets).
acceptance:
  - >-
    The probation-rejection reply (error.probation.blocked) lists /zcash and
    /monero when those assets are operator-enabled — fixing the live bug
    (verified 2026-07-08, SimpleX DM) where a probation user was told asset
    commands were unavailable while /zcash in fact ran successfully. The
    rejection's command list is rendered from the canonical
    CommandPermissions probation-allowed set (the ALLOWED static list plus
    AssetCommandFamilyOracle's enabled-asset names), NOT a hard-coded bundle
    substring. Symmetrically, the welcome reply (reply.welcome.dm_fresh),
    which today wrongly OMITS /get-sources and /stop, is rendered from the
    same canonical source.
  - >-
    The three probation-command surfaces — the welcome list, the rejection
    list, and /help's probation-visible command set — now agree exactly: all
    three enumerate the SAME set of slash commands, derived from the one
    CommandPermissions probation-allowed source, so they cannot drift again.
    /help's existing behavior is unchanged (it already derives from
    commandPermissions.allowedDuringProbation + enabled assets); this ticket
    brings welcome and rejection onto that same source.
  - >-
    NAMED TEST: ProbationCommandListConsistencyTest, exercising the
    enabled-asset case (an AssetRegistry with /zcash and /monero enabled),
    asserts (a) the rendered error.probation.blocked output contains /zcash
    and /monero (red-before on today's hard-coded string, which omits them;
    green-after); (b) the rendered reply.welcome.dm_fresh output contains
    /get-sources and /stop (red-before on today's string, which omits them);
    and (c) the set of slash tokens in the welcome list, the set in the
    rejection list, and the /help probation-visible command set are all equal
    to the canonical CommandPermissions probation-allowed set (static allowed
    names plus the enabled asset names).
  - >-
    mvn verify is green from the repo root, including the existing
    BundleLoaderTest (D43 bilateral keyset) and the InboundRouter probation
    tests — the two restructured bundle values still MessageFormat-resolve
    (error.probation.blocked keeps its {0} time-until-unlock arg and gains a
    {1} command-list arg; reply.welcome.dm_fresh gains a {0} command-list
    arg), and their cs twins carry the same placeholders.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ProbationCommandListConsistencyTest.java
      — pins that the welcome list, the rejection list, and the /help
      probation-visible set are all rendered from the single canonical
      CommandPermissions probation-allowed source (static allow-list +
      enabled assets); explicitly asserts /zcash + /monero appear in the
      rejection reply and /get-sources + /stop appear in the welcome reply
      when the assets are enabled. Red-before/green-after on the two
      currently-wrong strings.
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest's D43 en/cs keyset-parity assertions (no key added or
      removed; two existing values are edited in both en and cs).
    - >-
      the existing InboundRouter probation-path tests
      (InboundRouterProbationClockTest, InboundRouterProbationOrderingTest)
      — the rejection still fires on the same authorization decision; only
      the reply's command-list substring is now canonically sourced.
    - >-
      HelpCommandHandler's existing probation filtering (it already consults
      commandPermissions.allowedDuringProbation) — behavior unchanged.
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Asset commands
  - docs/spec/security.md §Slow-start tier
decision_refs:
  - D45
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-590: one canonical source for the probation-allowed command list

## Context

Found 2026-07-08 during live testing (SimpleX DM, a user in D45 slow-start
probation). The list of commands allowed during probation is rendered by
**three independent sources that disagree**, and two of them are factually
wrong:

| surface | commands listed | defect |
|---|---|---|
| registration welcome (`reply.welcome.dm_fresh`) | `/help /status /summary /saved /list-sources /get-tags /export /forget /lang /zcash /monero` (11) | **omits** `/get-sources`, `/stop` (both are allowed) |
| `/help` output | the 11 above **plus** `/get-sources` and `/stop` (13) | correct — it is canonical-driven |
| rejection (`error.probation.blocked`) | `/help /status /summary /saved /get-tags /get-sources /list-sources /export /forget /lang /stop` (11) | **omits** `/zcash`, `/monero` (both are allowed) |

The rejection defect actively misinforms: it tells a probation user
`Allowed during probation: ...` with the asset commands absent, yet we ran
`/zcash` successfully while on probation. So a probation user is told asset
commands are unavailable when they are.

Root cause: the probation allow-list is the source of truth in exactly one
place — `CommandPermissions.allowedDuringProbation` (the ALLOWED static set
plus the `AssetCommandFamilyOracle` enabled-asset family) — but only `/help`
consults it. `HelpCommandHandler.visible(...)` already filters its listing
through `commandPermissions.allowedDuringProbation(entry.command())` and
appends `AssetRegistry.getEnabledAssets()`, which is why `/help` is the one
correct surface (13). The welcome and rejection strings each embed a *hand-typed*
command list in a bundle value, so they drifted — each in a different direction.

## The fix

Make the welcome and rejection lists derive from the same canonical source
`/help` already uses, so all three cannot drift:

1. Give `CommandPermissions` a renderer for the ordered probation-allowed
   command names — the ALLOWED static set (make it ordered) plus the
   enabled-asset names. `CommandPermissions` already holds the
   `AssetCommandFamilyOracle`; add a thin `enabledAssetCommandNames()`
   accessor to the oracle (it already wraps `AssetRegistry`) so the renderer
   can enumerate the enabled asset family without `CommandPermissions`
   gaining a new dependency.
2. In `InboundRouter`, format that canonical list into the two replies:
   `error.probation.blocked` gains a `{1}` command-list arg (keeping its
   existing `{0}` time-until-unlock arg); `reply.welcome.dm_fresh` gains a
   `{0}` command-list arg (its two render sites switch from a bare
   `bundleLoader.get(...)` to `MessageFormat.format(...)`).
3. Edit the two bundle VALUES in `en.properties` and their `cs.properties`
   twins (D43) to replace the hand-typed command list with the placeholder.
   No bundle KEY is added or removed — the D43 keyset is unchanged.

`/help` needs no change — it is already canonically sourced and serves as the
reference. The new `ProbationCommandListConsistencyTest` pins all three
surfaces to the one source (over the command-name SET, not the display
format) so a future hand edit that reintroduces a hard-coded list fails CI.

## Out-of-scope

See frontmatter. Notably: no change to WHICH commands probation allows (only
how the already-decided set is rendered), no restyle of `/help`'s per-line
format, no probation-timer / `/vouch` / intake-ordering change, no new bundle
key (two existing values edited, en+cs), and no change to AssetRegistry
semantics (the renderer only reads enabled asset names).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX probation-user
  walkthrough). Not a red-team finding.
- **Why `/help` is already right.** `HelpCommandHandler.visible` delegates the
  probation case to `commandPermissions.allowedDuringProbation` and appends
  `AssetRegistry.getEnabledAssets`; its class javadoc calls out keeping "the
  welcome message, the /help listing, and the probation reply mutually
  consistent" — an intent the two hard-coded bundle strings silently broke.
  This ticket makes that stated intent structurally enforced.
- **D43 keyset.** Only VALUES change (a placeholder replaces the inline list),
  in both `en.properties` and `cs.properties`; membership is untouched, so
  BundleLoaderTest's bilateral-keyset assertion stays green. Both restructured
  values must still parse under `MessageFormat` (the rejection keeps `{0}` and
  gains `{1}`; the welcome gains `{0}`).
- **Enabled-asset path in the test.** The consistency test must exercise an
  AssetRegistry with `/zcash` and `/monero` enabled so the asset-family
  omission in `error.probation.blocked` is actually caught — asserting only
  the static set would miss the exact live bug.
