---
id: M1-651
title: "Guard HelpTier against the spec's closed bot-admin list"
status: done
created: 2026-07-18
last_updated: 2026-07-18
revisions:
  - date: 2026-07-18
    reason: refine ticket spec (round 2 rework) — redteam re-audit, spec designation
    prior_values: |
      round_cap was 2 (now 3), files_budget was 3 (now 4), and files_scope held
      only the two provider test files (docs/spec/commands.md added). The
      round-2 re-audit found the remediation's group-admin tier derivation
      rests on a prose qualifier the spec never designates as tier-bearing;
      closing that honestly requires amending the spec, which is a third file
      and a third review round. Two acceptance items added (spec designation;
      structural anchoring + honest javadoc + non-misdirecting failure
      messages). out_of_scope is UNCHANGED and still forbids any file under
      src/main/ — a docs/spec/ edit is not production code, and this ticket
      still changes no HelpTier value and no permission gate.
  - date: 2026-07-18
    reason: refine ticket spec (round 1 rework) — redteam-finding scope widening
    prior_values: |
      out_of_scope carried a first bullet excluding "the group-admin bullet of
      the spec's closed privileged-tier list", on the stated grounds that its
      entries map to TWO HelpTier values depending on an "in groups" qualifier
      and "a correct mapping needs a design decision this ticket does not
      make". Redteam finding 2 showed that exclusion leaves the group-admin
      half of the same closed list unguarded against exactly the mis-tier slip
      the bot-admin half exists to catch. The deferred design decision was
      then verified DETERMINATE — the spec's own "in groups" qualifier is the
      discriminator and matches CATALOGUE for all 8 entries — so the bullet was
      dropped and a corresponding acceptance item added. A second acceptance
      item was added for redteam finding 1 (parser subset-truncation).
      files_budget unchanged at 3: the estimate is still 2 implementation
      files, both already in files_scope. No other frontmatter changed.
redteam_findings:
  - date: 2026-07-18
    category: INFO-LEAK
    severity: low
    promise: |
      security.md §LLM output sanitizer, "Match-set derivation": the sanitizer's
      match set is derived from the closed privileged-tier list at spec level,
      making "admin commands never leak through LLM output" a structural
      property rather than a discipline. commands.md:1295-1302 commits that the
      closed set "cannot silently shrink across versions". The diff makes
      HelpTier a second consumer of that same closed list, but derives it in a
      way that CAN silently shrink.
    gap: |
      parseBotAdminBaseCommands' only completeness check is a non-emptiness
      assertion, which prevents TOTAL disablement but not PARTIAL truncation.
      The bullet-end detection breaks on the first line whose strip() is empty,
      starts with "- ", or starts with "#", so any of those appearing
      mid-bullet after a future spec reformat silently reduces the guarded set
      to a non-empty subset and the test still passes. The sibling parser in
      LlmOutputSanitizerTest is immune because its consumer asserts
      BIDIRECTIONAL equality against the hardcoded runtime CLOSED_LIST; the new
      guard has no reverse-direction check and deliberately declines to pin a
      count, so subset-truncation is indistinguishable from a correct parse.
  - date: 2026-07-18
    category: INFO-LEAK
    severity: low
    promise: |
      security.md §Authorization model (D9) treats both admin tiers as
      load-bearing, and §LLM output sanitizer states every command in the
      bot-admin AND group-admin tiers of the closed list is in the sanitizer
      set. The threat model treats both tiers of the closed privileged-tier
      list as equally load-bearing; the diff derives a guard from one tier only.
    gap: |
      The parse anchors solely on "**Bot-admin only:**" and breaks at the next
      "- " line, which is exactly the group-admin bullet. That bullet's eight
      entries are never compared against HelpTier.GROUP_ADMIN /
      USER_OR_GROUP_ADMIN. A spec amendment adding a new group-admin command
      whose CATALOGUE entry is typed HelpTier.USER passes every existing guard,
      and any plain member of an approved group can then read its short line and
      full usage block via /help — the identical mistake the bot-admin half of
      this diff exists to catch.
escalations:
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS — critical=0 high=0 medium=0 low=2,
      out-of-model=3. Both findings are INFO-LEAK/low. Full verdict:
      docs/plan/m1/redteam/M1-651-2026-07-18.md
      (Round-1 code review was APPROVE, 0 rework items, all six checks PASS.)
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RE-AUDIT after the round-1 remediation. RED-TEAM VERDICT: FINDINGS —
      critical=0 high=0 medium=0 low=2, out-of-model=4. Both ORIGINAL findings
      are confirmed CLOSED by this audit. The two new findings concern the
      remediation's own soundness: (A) the "in groups" prose qualifier is used
      as a tier discriminator although the spec designates the closed list as
      membership-bearing only and relegates per-actor splits to design tier —
      so a NEW group-only command authored in the bullet's dominant house style
      would be derived as USER_OR_GROUP_ADMIN and blessed by the guard, and the
      failure message misdirects remediation at the enum rather than the prose;
      (B) a residual narrow truncation window — a line inside the bot-admin
      bullet that merely MENTIONS the bolded string "**Group-admin" ends the
      region early — plus a javadoc that overstates the guarantee as "it can
      never degrade quietly". Full verdict:
      docs/plan/m1/redteam/M1-651-2026-07-18-r2.md
      (Round-2 code review was APPROVE, 0 rework items, all six checks PASS,
      must-shrink mandate verified against commit 4119d0ed.)
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 3
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 981
      removed: 17
blocked_by: []
remediates: M1-646
files_budget: 4
files_scope:
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
  - >-
    parseBotAdminBaseCommands bounds its scan region by the group-admin
    bullet label rather than by "first line starting with '- '", and asserts
    that terminator was found. A blank line or nested sub-bullet appearing
    mid-bullet after a spec reformat must NOT silently truncate the guarded
    set; a missing terminator must fail loudly. Closes redteam finding 1
    (reproduced: a mid-bullet blank line truncated the guarded set 19 -> 8
    and a nested "- " sub-bullet truncated it to 9, both passing the
    non-emptiness vacuity guard).
  - >-
    CommandCatalogueParityTest.groupAdminSpecCommandsCarryGroupTier passes —
    for every base command in the "Group-admin (or bot admin acting in the
    group):" bullet of the same closed list, the CATALOGUE entry carries
    HelpTier.USER_OR_GROUP_ADMIN when the spec token is qualified "in groups"
    and HelpTier.GROUP_ADMIN when it is bare. This is the design decision the
    original out_of_scope bullet 1 deferred; it is determinate — 6 qualified
    (add-source, unfollow-source, follow-all-sources, lang, follow-tag,
    unfollow-tag) and 2 bare (group-timezone, digest), matching CATALOGUE
    exactly today. Closes redteam finding 2.
  - >-
    docs/spec/commands.md §Permission model DESIGNATES the "in groups"
    qualifier as tier-bearing, so the guard's derivation rests on a spec
    commitment rather than on incidental house style. The amendment states
    that a qualified entry means "any user in DM, group admin in a group"
    and a bare entry means group-only, and that adding or removing the
    qualifier is therefore a tier change — a spec amendment, like adding or
    removing a command. Placed AFTER the closing "The full per-actor-tier"
    paragraph so it falls outside both closed-list parse regions (this
    guard's and LlmOutputSanitizerTest.parseSpecClosedList's) and cannot
    perturb either token set. Closes redteam round-2 finding A.
  - >-
    closedListRegion anchors STRUCTURALLY: each label must match at the START
    of a stripped line, and the region must contain no other list item, so a
    prose mention of "**Group-admin" inside the bot-admin bullet can no
    longer terminate the region early. A structural intrusion fails LOUDLY
    with a message naming the offending line rather than silently yielding a
    subset. The javadoc states the guarantee precisely instead of the
    absolute "it can never degrade quietly". The mis-tier failure messages
    name BOTH sides (spec prose and CATALOGUE enum) as candidate causes
    rather than directing remediation at the enum. Closes redteam round-2
    finding B and its misdirection sub-point.
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
