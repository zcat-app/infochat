---
id: M1-770
title: "Doc-only commits must run the parity gates"
status: pending
created: 2026-08-05
last_updated: 2026-08-05
blocked_by: []
files_budget: 3
files_scope:
  - CLAUDE.md
  - docs/process/workflow.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE PARITY TESTS THEMSELVES.
    `DocumentedConfigKeyParityTest` and `CommandCatalogueParityTest` are
    working as designed — they caught real drift. Narrowing what they
    scan, exempting `docs/design/future/**` wholesale, or relaxing either
    assertion is the "weaken the test to make the obstacle disappear"
    move `engineering-rules-verbatim.md` §8 forbids. The defect is in the
    COMMIT RULE that lets a doc edit skip them, not in the gates.
  - >-
    THE EXISTING EXEMPTION LEDGER. `documented-config-key-exemptions.txt`
    entries are per-key decisions with their own rationale; this ticket
    changes no entry. The `infochat.image.base-url` entry that motivated
    it already landed on `main`.
  - >-
    THE INERT-DIFF GATE in `.claude/skills/m1-tick/SKILL.md`. That rule
    governs `mvn verify` inside a TICKET's review cycle and already
    classifies by changed path. This ticket is about NON-ticket commits,
    which never reach that gate at all.
acceptance:
  - >-
    THE BYPASS IS NARROWED, NOT REMOVED. `CLAUDE.md` §"Commit prefixes"
    and `docs/process/workflow.md` §Non-ticket commits both state that a
    `spec:` commit touching `docs/spec/**` or `docs/design/**` must run
    the two parity gates before landing, because those trees are TEST
    INPUT. Doc edits that touch neither tree (`docs/process/**`,
    `docs/plan/**`, `.claude/**`) keep the existing zero-verify bypass —
    the point is to price the gates where they can actually fire, not to
    make every typo fix cost a full suite.
  - >-
    THE CHEAP COMMAND IS NAMED. Both documents state the module-scoped
    invocation that exercises the two gates rather than "run `mvn
    verify`", so the rule is followable at doc-edit cost. A full
    repo-root verify for a one-line prose change is what made the bypass
    attractive in the first place; a rule too expensive to follow is the
    failure mode being replaced.
  - >-
    THE WHY IS RECORDED WITH THE RULE. Both edits name the mechanism —
    `DocumentedConfigKeyParityTest` scrapes `infochat.*` tokens out of
    `docs/spec/**` and `docs/design/**`; `CommandCatalogueParityTest`
    does the same for the command catalogue — so a future reader can
    tell WHICH doc trees are load-bearing and does not re-derive the
    bypass from "docs cannot break a build".
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds: []
  preserves:
    - >-
      The non-ticket commit taxonomy itself: `spec:` / `process:` /
      `text:` keep their meanings and their `git log --grep "^M1-"`
      enumeration property. This ticket adds a precondition to one
      prefix, it does not repartition them.
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-770: Doc-only commits must run the parity gates

## Context

Filed 2026-08-05 out of M1-765's first full-suite run, which came back
red on a failure M1-765 did not cause.

`835ab637` ("spec: /image design note") is a textbook `spec:` commit —
one new file under `docs/design/future/`, no code. `CLAUDE.md`
§"Commit prefixes" says such a commit bypasses the ticket-readiness
pre-flight, the reviewer, `mvn verify` and STATUS regen, on the
reasoning that a pure-doc edit cannot break the build.

For that repo, on that day, the reasoning was false. The note contains:

```
| 12 | **Optional by configuration** — no `infochat.image.base-url`, no command. |
```

`DocumentedConfigKeyParityTest` (M1-708) scrapes every `infochat.*`
token out of `docs/spec/**`, `docs/design/**` and the root guides, and
asserts each one either exists in a real `@ConfigProperty` site /
`application.properties`, or carries an entry in
`documented-config-key-exemptions.txt`. The note's sentence *asserts the
key's absence* — but the regex sees a key reference, and prose intent is
not something it can read. `main` went red and stayed red until the next
ticket ran a full suite and tripped over it.

## The actual defect

Not the doc, and not the gate. The gate did its job: it exists precisely
because ~15 phantom config keys had accumulated across three design
notes and three root guides before the 2026-07-27 audit found them.

The defect is that **`docs/spec/**` and `docs/design/**` are test
input**, and the commit rule treats every doc tree as inert. See
§Census for the three tests that read them.

Any future design note naming a not-yet-built config key reproduces this
exactly. `docs/design/future/` currently holds two notes; the sibling
(`public-ipfs-publishing.md`) names no keys and so happens to pass,
which is luck, not design.

## Census

The class is **tests that resolve a repo-root-relative path and read a
`docs/` file as test input**. Surefire runs with the module directory as
the working directory, so every such test reaches the repo root via
`Path.of("..")` — which makes that construction the enumeration handle.
Re-runnable:

```
grep -rlE 'Path\.of\("\.\.' --include=*.java infochat-*/src/test/java
```

| Test | Reads | Fails when | Disposition |
|---|---|---|---|
| `DocumentedConfigKeyParityTest` | `docs/spec/**`, `docs/design/**`, root guides (walks from `Path.of("..")`) | a doc names an `infochat.*` key that is neither real nor exempt | COVERED — the gate that caught `835ab637` |
| `CommandCatalogueParityTest` | `docs/spec/commands.md` | the documented command set, its permission tiers, or its closed-list formatting drifts from the dispatched one | COVERED — same bypass, same exposure |
| `ChatToolAllowlistSpecParityTest` | `docs/spec/security.md` | the chat-tool allowlist in the spec drifts from the registry | COVERED — same bypass, same exposure |

The third was missed on this ticket's first draft, which asserted "two
tests" from memory; the linter's `CENSUS-PRESENT-IF-CLASS-SCOPED` WARN
is what forced the grep that found it. The rule the ticket writes must
name all three, or a `spec:` edit to `docs/spec/security.md` keeps the
exact bypass this ticket exists to close.

## Why not just exempt `docs/design/future/**`

It was considered and rejected. Excluding a whole tree from the scan
removes real coverage — a future note is exactly where a
plausible-but-wrong key name gets written down, then copied into an
implementation ticket months later. The exemption ledger's own header
makes the same argument for per-key entries: it is "a LEDGER, not a
suppression list", and each entry states why THAT key is absent. A
directory-wide exclusion states nothing and expires never.

## Notes

- The immediate red was fixed on `main` by the `fix:` commit that added
  the `infochat.image.base-url` entry to the exemption ledger's
  "Deliberate 'this key does not exist' statements" section. That commit
  also introduced the `fix:` prefix for non-ticket repairs of a red
  `main`; `CLAUDE.md`'s prefix table gained the row in the accompanying
  `process:` commit. This ticket does not revisit either.
- **ID reuse.** M1-770 was briefly claimed by a different ticket during
  M1-769's redteam round 2 and deleted the same day when that finding
  was fixed in-branch instead. `docs/plan/m1/redteam/M1-769-2026-08-05-r2.md`
  still refers to "M1-770" in that historical sense — a reader chasing
  that reference lands here, on something unrelated. The ID was refilled
  deliberately (2026-08-05) rather than left as a gap. The out-of-model
  item that deleted ticket would have carried (shared endpoint-string
  breaker keying vs `releaseProbeForTask`) is still awaiting a
  disposition and is NOT this ticket.
- A pre-commit hook is the obvious alternative to a documented rule and
  is NOT pre-judged here: it enforces rather than asks, but it also
  taxes every doc commit with JVM startup, and the repo currently has no
  hook infrastructure. Whoever implements this should weigh both and
  record the choice.
