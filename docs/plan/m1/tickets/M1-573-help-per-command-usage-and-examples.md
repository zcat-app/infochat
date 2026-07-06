---
id: M1-573
title: "In-app per-command help: /help <command> shows usage, flags, and examples"
status: done
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: [M1-575, M1-576]
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    A universal `--help` FLAG on every command. Evaluated and rejected this
    session: a flag must be intercepted in each of ~30 handlers ahead of their
    own arg-validation (e.g. `/vouch --help` would otherwise fail "missing
    contact" first), and it introduces a second help idiom alongside the existing
    `/help` command. `/help <command>` is one change to one handler and reuses the
    per-command bundle architecture that already exists.
  - >-
    Rich/markdown formatting. Output stays plain-text per the project convention
    (single-backtick inline at most); adapters assert supportsMarkdownLinks=false.
  - "Adding, removing, or renaming any command. This ticket only documents the existing surface."
  - >-
    Changing bare `/help` (the context-aware list). It stays exactly as-is; this
    ticket only adds the `<command>` argument branch.
  - >-
    Redirecting the existing reactive `Usage:` error strings at `/help
    <command>`. Dropped at clarity pre-flight: those strings are inline literals
    in 13+ handler files outside files_scope, and MissingArgumentUsageReplyTest
    exact-matches them. This ticket is purely additive — no handler `Usage:`
    literal changes; the redirect is a candidate follow-up ticket.
  - >-
    Per-command detail blocks for asset commands (`/zcash`, `/monero`, …).
    Assets render through the single dynamic `help.cmd.asset.line` key and have
    no per-command bundle-key structure. `/help <enabled-asset>` replies with
    that same existing short line (no detail block); asset detail — including
    the spec's "only enabled sub-verbs appear in per-command help" promise
    (commands.md §Asset commands) — stays a follow-up.
  - >-
    Adding `/pending` to the help catalogue. Pre-existing M1-575 gap (no
    `help.cmd.pending.short` key, absent from bare `/help`); fixing it here
    would change bare `/help` output, which this ticket's acceptance freezes.
    `/help pending` therefore resolves to the unknown-command friendly error in
    this ticket; the catalogue addition is a follow-up ticket.
acceptance:
  - >-
    `/help <command>` (e.g. `/help summary`) replies, in plain text, with a
    block containing: the command's signature/usage line, each argument and flag
    with a one-line meaning, and at least one concrete example. `/help summary`
    must show the `-w <duration>` flag with its accepted values and default.
  - >-
    The detail is context-aware, consistent with bare `/help`: it renders only
    the sub-verbs/flags the caller is permitted to use (a non-admin asking
    `/help list-sources` does not see `--all`; a probation caller sees only what
    they may invoke).
  - >-
    Bare `/help` (no argument) is unchanged — still the context-aware command
    list composed from the per-command short-help bundle keys.
  - >-
    `/help <unknown>` returns the existing friendly-error shape with a fuzzy
    suggestion, pointing the user back at `/help`.
  - >-
    Every command in HelpCommandHandler's closed CATALOGUE (the static commands
    that already carry a `help.cmd.<name>.short` key — currently 39; asset
    commands and `/pending` excluded per out_of_scope) gains two new
    localization-bundle keys (a usage/arguments key and an examples key) in
    BOTH en.properties and cs.properties (D43 bilateral keyset). The existing
    bundle-completeness check (BundleLoaderTest) stays green — every added en
    key has a cs twin.
  - >-
    HelpCommandHandlerTest asserts: `/help summary` output contains the usage
    line, the `-w` flag description, and an example; `/help <unknown>` returns the
    friendly error; bare `/help` output is unchanged.
  - >-
    `mvn verify` is green from the repo root: the new tests pass and the full
    pre-existing suite still passes (report regressions, not just the new checks).
test_plan:
  adds: []
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java — existing file (11 tests); new cases for /help <command>, /help <unknown>, permission-filtered detail."
    - "infochat-provider/src/main/resources/bundles/en.properties — usage/example keys per catalogue command (cs.properties twins added in the same change)."
  preserves:
    - "all tests currently green on main; bare /help behavior unchanged."
    - "MissingArgumentUsageReplyTest — untouched; no handler Usage: literal changes (reactive-usage redirect is out of scope)."
spec_refs:
  - "docs/spec/commands.md §Command catalogue"
  - "docs/design/03-commands.md §/help"
decision_refs:
  - "D43 (bilateral localization keyset)"
reviews:
  - round: 1
    date: 2026-07-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 856
      removed: 59
escalations:
  - date: 2026-07-06
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (round 2, after one bounded self-refine)
      BLOCKERS:
        1. files_scope omits infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java.
           Acceptance item 5 relies on BundleLoaderTest's completeness check to
           prove every new usage/example key has an en+cs pair, but that check
           only inspects keys registered as BundleKeys constants (the
           test.fallback.probe key demonstrates the converse — unregistered
           keys are never inspected). Without BundleKeys.java in files_scope
           the implementer either has no authorized file for the ~78 new
           constants (39 commands x 2 keys) or adds keys without constants,
           making item 5's "BundleLoaderTest stays green" vacuously true.
           Fix: add BundleKeys.java as the sixth files_scope entry (fits the
           existing files_budget: 6 unchanged); optionally sharpen acceptance
           item 5 to require the new keys be registered as BundleKeys
           constants.
overrides: []
revisions:
  - date: 2026-07-06
    reason: clarity-fail rework (bounded self-refine via /m1-tick run) — drop Approach bullet 4 (reactive Usage: redirect), anchor "each command" to the closed help CATALOGUE, exclude asset detail and the pre-existing /pending gap
    prior_values: |
      out_of_scope: 4 entries (universal --help flag; rich/markdown formatting;
        add/remove/rename commands; bare /help changes)
      acceptance[5] (bundle-keys item): "Each command gains two new
        localization-bundle keys (a usage/arguments key and an examples key) in
        BOTH en.properties and cs.properties (D43 bilateral keyset). ..."
      test_plan.adds: listed HelpCommandHandlerTest.java as a NEW file (it
        already exists with 11 tests); test_plan.modifies: en.properties only
      Approach bullet 4 (body): "Point the reactive `Usage:` strings at
        `/help <command>` for the fuller view."
      (Clarity pre-flight FAIL, 3 blockers: (1) Approach bullet 4 implied edits
       to 13+ handler files outside files_scope/files_budget; (2) those edits
       would change MissingArgumentUsageReplyTest's exact-match literals without
       authorization; (3) "each command" was ambiguous re: asset commands, which
       have no per-command bundle-key structure to hang two keys on. Fix
       narrows, never widens: bullet 4 dropped and excluded, acceptance anchored
       to the 39-entry CATALOGUE, asset detail and /pending catalogue addition
       explicitly out of scope as follow-up candidates.)
  - date: 2026-07-06
    reason: clarity-fail rework (user-directed refine, escalation option 1) — add BundleKeys.java to files_scope so the ~78 new bundle-key constants are an authorized edit and BundleLoaderTest's completeness check actually covers the new keys
    prior_values: |
      files_scope: 5 entries (HelpCommandHandler.java, en.properties,
        cs.properties, HelpCommandHandlerTest.java, docs/spec/commands.md) —
        omitted infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
      (Clarity round-2 FAIL, 1 blocker: BundleLoaderTest's completeness walk
       reflects over BundleKeys' constant field set only — keys added to the
       .properties files without constants are invisible to it, making
       acceptance item 5's "BundleLoaderTest stays green" vacuous. files_budget
       stays 6; the new sixth entry fits without a budget change.)
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-07-06
  verdict: WARN
  warnings:
    - >-
      SECURITY-FLAG-CONSISTENT: security_relevant: false alongside an
      acceptance item whose entire point is hiding admin-only command surface
      from non-admins in a new rendering path. Consider flipping to true, or
      adding a one-line note distinguishing "reuses existing gate, does not
      create one" so the reviewer doesn't have to infer it.
    - >-
      FILES-BUDGET-PLAUSIBLE: docs/spec/commands.md is in files_scope but no
      acceptance item names what changes there; worth a one-line acceptance
      addition (e.g. "the §Discovery /help entry notes the new <command>
      argument mode") so the implementer isn't guessing at scope for that
      file.
  blockers: []
---

# M1-573: In-app per-command help (`/help <command>`)

## Context

A user driving the bot from a phone (SimpleX/Signal) has no way to learn how a
single command works without invoking it wrong first. Today the surface is:
bare `/help` (a context-aware list with a one-line short-help per command), and
a reactive `Usage:` string that only appears on a malformed call. Neither gives
a proactive "what does this command do, what are its flags, show me an example"
answer — which is exactly what a mobile user needs, since an out-of-band web
reference does not help inside the chat.

This ticket adds `/help <command>`: on demand, in the chat, the bot returns the
command's usage line, its arguments/flags with meanings, and worked examples.

## Approach

- Extend `HelpCommandHandler` to branch on an optional command-name argument.
  Bare `/help` keeps its current list behavior; `/help <name>` renders the
  detail block for that command.
- Add two per-command bundle keys alongside the existing short-help key: a
  usage/arguments key and an examples key. Compose the detail from
  header + usage + per-flag lines + examples, all plain text.
- Reuse the existing permission/context filtering so the detail only shows
  flags and sub-verbs the caller may actually use.

Illustrative output for `/help summary`:

```
/summary [tag] [-w <duration>]

A digest of recent posts from sources you follow.

Arguments
  [tag]          optional — narrow to one tag
  -w <duration>  time window: 24h, 7d, 30d (default 24h)

Examples
  /summary
  /summary -w 7d
  /summary ai -w 30d
```

## Notes

- **Localization cost is real (D43).** Every usage/example string needs an
  `en` and `cs` twin, CI-enforced. Keep the strings tight and within the SimpleX
  4000-byte outbound cap; the adapter already chunks over-cap output, but a help
  block should not need chunking.
- Companion to the discoverability work from the same session: `/help vouch`
  is the natural place to explain how to obtain a contact id (see M1-574 /
  M1-575), so its examples key should reference those once they land.
- **Follow-up candidates surfaced at clarity pre-flight (2026-07-06),
  deliberately not in this ticket:** (a) pointing the reactive `Usage:` error
  strings (inline literals in 13+ handlers) at `/help <command>`; (b) per-asset
  detail blocks fulfilling the spec's "only enabled sub-verbs appear in
  per-command help" promise; (c) adding `/pending` (M1-575) to the help
  CATALOGUE — it currently has no `help.cmd.pending.short` key and is missing
  from bare `/help`.
