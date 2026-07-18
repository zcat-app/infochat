---
id: M1-651
title: "Guard HelpTier against the spec's closed bot-admin list"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
remediates: M1-646
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandCatalogueParityTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The group-admin bullet of the spec's closed privileged-tier list. Its
    entries map to TWO different HelpTier values depending on an "in groups"
    qualifier (/group-timezone and /digest are GROUP_ADMIN; "/add-source in
    groups" is USER_OR_GROUP_ADMIN), so a correct mapping needs a design
    decision this ticket does not make. Bot-admin is the security-relevant
    axis and maps one-to-one.
  - >-
    Changing any HelpTier value in CATALOGUE, or any handler's permission
    gate. If the new guard reveals an existing mis-tier, ESCALATE — do not
    silently re-tier. A wrong tier discovered here is a separate finding with
    its own disclosure history, not a lint fix.
  - >-
    Deriving the tier from each handler's own authorization code. The gates
    are arbitrary Java (PendingCommandHandler:87, RecoverPoolCommandHandler:104
    are plain !isAdmin branches); static extraction is a much larger problem
    than the spec-list cross-check this ticket buys.
  - >-
    The three out-of-model items in the M1-646 redteam audit (group-scope
    advertisement of DM-only admin commands, direct-invocation existence
    oracle, unbounded echo of the requested /help token). All are pre-existing
    and none were introduced by M1-646.
  - >-
    Production code. This ticket adds test-tier guards only; no file under
    src/main/ may change.
acceptance:
  - >-
    CommandCatalogueParityTest.botAdminSpecCommandsCarryBotAdminHelpTier
    passes — for every base command derived from the "Bot-admin only:" bullet
    of the closed privileged-tier list (docs/spec/commands.md §Permission
    model), the matching HelpCommandHandler.CATALOGUE entry carries
    HelpTier.BOT_ADMIN. Derivation must normalize sub-verb and flag tokens to
    the base command (/invite create, /quarantine approve, /list-sources --all
    → invite, quarantine, list-sources); the bullet's 26 tokens reduce to 19
    base commands.
  - >-
    /list-sources is the single documented exemption, held in a named constant
    with a comment stating WHY (flag-as-identity: the base command is
    HelpTier.USER and open to everyone; only the --all / --include-deleted
    flags are bot-admin, and they render from the separate
    HELP_CMD_LIST_SOURCES_USAGE_ADMIN suffix key). The test asserts the
    exemption set equals exactly {list-sources}, so widening it later is a
    deliberate, reviewable edit rather than a silent addition.
  - >-
    CommandCatalogueParityTest.tierGuardRejectsAMisTieredCommand passes — the
    comparison is factored into a static helper taking (spec base-command set,
    command→tier map) and is exercised with a SYNTHETIC map in which one
    bot-admin command carries HelpTier.USER; the helper reports that command as
    a violation. Without this, the guard could pass vacuously and nobody would
    know.
  - >-
    Every spec bot-admin base command resolves to a CATALOGUE entry (no ghost
    token), with a failure message naming the missing command.
  - >-
    HelpCommandHandlerTest.hiddenTierCommandIsIndistinguishableFromUnknown
    additionally asserts the usage block is ABSENT from the non-admin reply for
    both /pending and /recover-pool (assertFalse on
    HELP_CMD_PENDING_USAGE / HELP_CMD_RECOVER_POOL_USAGE), matching the
    strength of the pre-existing
    HelpCommandHandlerTest.helpDetailOfCommandHiddenFromCallerIsUnknownCommandError,
    which already asserts the /ban signature does not leak.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandCatalogueParityTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Permission model
  - docs/spec/commands.md §Discovery
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D9
---

# M1-651: Guard HelpTier against the spec's closed bot-admin list

## Context

M1-646 added `everyCommandHandlerHasAHelpCatalogueEntry`, which forces every
dispatchable `CommandHandler` to carry a `/help` catalogue entry. Its redteam
audit (`docs/plan/m1/redteam/M1-646-2026-07-18.md`, one low finding) observed
that the new guard reads only the `command()` accessor, so it constrains the
NAME axis while the `HelpTier` of each entry stays hand-picked and
machine-unchecked. No test in the repo reads the `tier()` field at all.

The direction of failure is what makes this worth closing. Before M1-646, a
developer who forgot an admin command in the help catalogue failed **safe** —
the command was simply undocumented. After M1-646, CI fails until a `CATALOGUE`
entry exists, and the failure message tells the developer to add one while
saying nothing about which tier to pick. A bot-admin command typed
`HelpTier.USER` goes fully green: name parity passes, spec-index parity passes,
the LLM sanitizer reads its own separate list, and no per-command spot check
names the new command. Any registered, post-probation non-admin then reads its
short line via `/help` and its full argument syntax via `/help <cmd>`.

Execution still requires `is_admin=true`, so this is disclosure and recon, not
privilege escalation — hence the low severity and the test-tier fix.

## Why this is buildable

`LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` already reads the same
spec markdown at test tier and asserts equality against a code list, so the
pattern is established. More importantly, the data is already CI-forced
accurate: that test pins `LlmOutputSanitizer.CLOSED_LIST` equal to the spec's
closed privileged-tier list, so a command declared privileged is guaranteed
present in the bullet this guard reads.

The bot-admin bullet maps cleanly: 26 tokens reduce to 19 base commands, of
which 18 are `HelpTier.BOT_ADMIN` in `CATALOGUE` and exactly one
(`/list-sources`) is legitimately `HelpTier.USER`. One documented exemption is
the whole delta.

## Known limitation — state it, do not oversell it

This guard closes the case where a developer correctly declares a command in
the spec's closed list but slips on the enum. It does **not** close the case
where a new privileged command is never declared privileged anywhere: such a
command is absent from the spec bullet, so the guard has nothing to compare
against — and it would also escape the LLM output sanitizer. Closing that would
require deriving the tier from handler code (out of scope above) or forcing
every handler to declare its own tier. The ticket body and the guard's javadoc
must both say so, so a future reader does not mistake this for the structural
property `security.md` §Prompt-injection defenses describes.

## Implementation note

Prefer a narrow parser local to `CommandCatalogueParityTest` over reusing
`LlmOutputSanitizerTest.parseSpecClosedList`. The two need different things:
the sanitizer wants both bullets with sub-verbs and flags preserved verbatim
(they are what it matches on), while this guard wants one bullet normalized to
base commands. Sharing would force one parser to serve two incompatible
shapes. Read `CATALOGUE` reflectively, as
`ProbationCommandListConsistencyTest` and M1-646's own guard already do — do
not widen production visibility for a test.
