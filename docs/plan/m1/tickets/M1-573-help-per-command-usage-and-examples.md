---
id: M1-573
title: "In-app per-command help: /help <command> shows usage, flags, and examples"
status: draft
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
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
    Each command gains two new localization-bundle keys (a usage/arguments key
    and an examples key) in BOTH en.properties and cs.properties (D43 bilateral
    keyset). The existing bundle-completeness check (BundleLoaderTest) stays green
    — every added en key has a cs twin.
  - >-
    HelpCommandHandlerTest asserts: `/help summary` output contains the usage
    line, the `-w` flag description, and an example; `/help <unknown>` returns the
    friendly error; bare `/help` output is unchanged.
  - >-
    `mvn verify` is green from the repo root: the new tests pass and the full
    pre-existing suite still passes (report regressions, not just the new checks).
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java — cases for /help <command>, /help <unknown>, permission-filtered detail."
  modifies:
    - "infochat-provider/src/main/resources/bundles/en.properties — usage/example keys per command (cs.properties twins added in the same change)."
  preserves:
    - "all tests currently green on main; bare /help behavior unchanged."
spec_refs:
  - "docs/spec/commands.md §Command catalogue"
  - "docs/design/03-commands.md §/help"
decision_refs:
  - "D43 (bilateral localization keyset)"
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
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
- Point the reactive `Usage:` strings at `/help <command>` for the fuller view.

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
