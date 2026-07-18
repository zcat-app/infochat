---
id: M1-646
title: "Add /pending + /recover-pool to the help catalogue and guard catalogue coverage"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandCatalogueParityTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The three misstated help strings (summary flags, retry --digest, welcome
    probation clause) — M1-645. That ticket edits existing values; this one adds
    new keys.
  - >-
    Command discoverability / suggestion ranking — M1-647.
  - >-
    Asset commands (/zcash, /monero, ...). They are rendered dynamically from
    AssetRegistry, are deliberately absent from the commands.md canonical index
    (docs/spec/commands.md:125), and must be excluded from the new coverage
    guard rather than added to CATALOGUE.
  - >-
    Changing the behaviour, permissions, or output of /pending or /recover-pool.
    This ticket documents them; it does not touch their handlers.
acceptance:
  - >-
    HelpCommandHandler.CATALOGUE contains entries for "pending" and
    "recover-pool" at HelpTier.BOT_ADMIN. Both handlers gate on the bot-admin
    flag today (PendingCommandHandler:87 and RecoverPoolCommandHandler:104 both
    test the actor's isAdmin), so BOT_ADMIN is the tier that matches the code.
  - >-
    Twelve new bundle keys exist with en+cs twins (D43) — short/usage/examples
    for each of the two commands — and each has a BundleKeys constant, so
    BundleLoaderTest's reflection over BundleKeys resolves them in both locales.
  - >-
    CommandCatalogueParityTest.everyCommandHandlerHasAHelpCatalogueEntry passes —
    it asserts the runtime Instance<CommandHandler> name() set equals the
    CATALOGUE command set, with asset commands excluded. This is a DIFFERENT
    axis from the existing parity check, which compares handlers against the
    marker-delimited index in docs/spec/commands.md and would not have caught
    this gap.
  - >-
    HelpCommandHandlerTest.hiddenTierCommandIsIndistinguishableFromUnknown
    passes for /help pending invoked by a non-admin — the reply is the
    unknown-command reply, not a permission-denied reply. Adding these entries
    must not create an admin-command existence leak (docs/spec/commands.md:226).
  - >-
    A bot admin running /help sees both commands in the flat list, and
    /help pending and /help recover-pool each return usage + examples.
  - >-
    The canonical command index in docs/spec/commands.md lists both commands, so
    the pre-existing CommandCatalogueParityTest stays green.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandCatalogueParityTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/commands.md §Permission model
decision_refs:
  - D43
---

# M1-646: Add /pending + /recover-pool to the help catalogue and guard catalogue coverage

## Context

`HelpCommandHandler.CATALOGUE` holds 39 entries, but the provider registers more
`CommandHandler` beans than that. Two — `/pending` and `/recover-pool` — are
dispatchable at runtime yet appear nowhere in `/help`: no flat-list line, no
`/help <cmd>` detail. A grep for `pending` or `recover` in
`HelpCommandHandler.java` returns nothing.

This is worse than an omission. `en.properties:122` instructs admins to "copy a
usable id from /pending" — the help surface points at a command it never
documents, so an admin who has not read the source cannot learn its arguments
from the bot at all.

The existing `CommandCatalogueParityTest` did not catch this because it checks a
different pair: runtime handler beans against the marker-delimited index in
`docs/spec/commands.md`. Nothing checks handler beans against the *help*
catalogue, which is why these two could drift out unnoticed. The durable fix is
the guard, not the two entries.

## Acceptance

See `acceptance`. Two catalogue entries, twelve bilateral bundle keys, one new
coverage guard, and a pinned no-existence-leak assertion.

## Out-of-scope

See `out_of_scope`. Do not touch either handler's behaviour or permissions, and
do not fold asset commands into `CATALOGUE` — they are dynamic by design and the
guard must exclude them.

## Notes

**Why security_relevant.** `docs/spec/commands.md:226` commits to a
"caller-visible command set (no admin-command existence leak)". `visible()`
already enforces this and `detailBody` routes a hidden command to the
unknown-command reply, so adding BOT_ADMIN entries is the safe shape — but the
diff must PIN that property with a test rather than assume it, because the whole
point of this ticket is that unpinned help properties drift.

**Tier choice is evidence-based, not stylistic.** Both handlers deny on
`!isAdmin`, so BOT_ADMIN is the tier that matches enforcement. If either handler
turns out to have a second, looser path, the tier must follow the code — a
catalogue entry looser than the gate would advertise a command the caller cannot
run, which is the same defect class M1-645 fixes.

**Guard-test placement.** Adding the new assertion to
`CommandCatalogueParityTest` keeps both parity axes (handlers↔spec index,
handlers↔help catalogue) in one file, so a future command author sees both
obligations together. The asset-command exclusion needs a comment naming
`docs/spec/commands.md:125` as the reason, otherwise the exclusion reads as an
unexplained carve-out.

**Bundle keys are new, so D43 applies.** Unlike M1-645, this ticket adds keys —
each needs a `cs.properties` twin or `BundleLoaderTest` fails on keyset
equality.
