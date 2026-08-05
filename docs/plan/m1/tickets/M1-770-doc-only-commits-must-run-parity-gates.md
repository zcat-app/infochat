---
id: M1-770
title: "Doc-only commits must run the parity gates"
status: done
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
    THE TRIGGER IS CONTENT-KEYED, NOT PATH-KEYED. `CLAUDE.md`
    §"Commit prefixes" and `docs/process/workflow.md` §Non-ticket commits
    both state that a non-ticket commit runs the parity gates before
    landing only when its diff can actually trip one — measured per test,
    not assumed per directory:
    (a) it ADDS a line containing an `infochat.`-prefixed token under
    `docs/spec/**`, `docs/design/**` or a root-level `*.md`
    (`DocumentedConfigKeyParityTest`); (b) it touches
    `docs/spec/commands.md` (`CommandCatalogueParityTest`); or (c) it
    touches `docs/spec/security.md` (`ChatToolAllowlistSpecParityTest`).
    EVERY other doc edit keeps the existing zero-verify bypass —
    including prose anywhere in `docs/spec/**`, `docs/design/**` or a
    root guide that adds no such token. A path-keyed rule would charge
    the gates to the large majority of doc edits that provably cannot
    fail them, which is the cost that made the bypass attractive in the
    first place.
  - >-
    THE DECIDING GREP IS NAMED, AND SO IS THE COMMAND. Both documents
    give the one-liner that answers "does THIS commit need the gates?"
    and, separately, the module-scoped invocation that runs them — so
    applying the rule costs seconds, and the ~2 minutes is paid only when
    the rule actually fires. A rule too expensive to follow is the
    failure mode being replaced, not a fix for it.
  - >-
    THE WHY IS RECORDED WITH THE RULE, FOR ALL FOUR READERS. Both edits
    name the mechanism — `DocumentedConfigKeyParityTest` scrapes
    `infochat.*` tokens out of `docs/spec/**`, `docs/design/**` and the
    root-level `*.md` guides; `CommandCatalogueParityTest` and
    `LlmOutputSanitizerTest` both read `docs/spec/commands.md` (the
    catalogue and the sanitizer's closed list respectively);
    `ChatToolAllowlistSpecParityTest` reads the chat-tool allowlist in
    `docs/spec/security.md` — so a future reader can tell WHICH doc
    content is load-bearing and does not re-derive the bypass from "docs
    cannot break a build". The fourth reader does NOT widen the trigger
    (it reads a file leg (b) already covers), so the deciding one-liner
    is unchanged by its inclusion. Both also
    record WHY deletions are exempt (the vacuity floors are 50 real / 50
    documented keys, far below the repo's counts) and WHY no cheaper
    `-Dtest`-filtered invocation is offered (the parent POM sets
    surefire's `failIfNoTests` in `<configuration>`, which outranks the
    `-D` override, so a filtered cross-module run fails on every module
    with no matching class).
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
reviews:
  - round: 1
    date: 2026-08-05
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 4
      added: 98
      removed: 29
  - round: 2
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 157
      removed: 41
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-05
  verdict: PASS
  warnings:
    - >-
      lint: PASS (0 blockers, 0 warnings).
    - >-
      Self-check, census truth: the §Census grep returns exactly the three
      tests in the table, all under `infochat-provider/src/test/java`.
    - >-
      Self-check, ticket-vs-code truth: `DocumentedConfigKeyParityTest`
      `docFiles()` walks `docs/spec/**` and `docs/design/**` recursively AND
      every root-level `*.md` (10 guides today). Acceptance items 1 and 3 had
      omitted the root-guide leg and gated only `spec:` commits, while
      `workflow.md`'s prefix table assigns `CLAUDE.md` / `AGENTS.md` /
      `CONTRIBUTING.md` to `process:` — so the rule as drafted would have left
      the same test's root-guide input ungated. Raised as a blocking question;
      the user chose to cover root guides. Acceptance items 1–3 amended
      accordingly (path-keyed, not prefix-keyed) before the status flip, and
      the stale "two parity gates" count corrected to three per the body's own
      "must name all three" directive.
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
the working directory, so every such test reaches the repo root by a
relative path — but there is more than one way to spell that, so the
handle must cover all of them. Re-runnable:

```
grep -rlE 'Path\.of\("\.\.|Paths\.get\("\.\.|new File\("\.\.|"\.\./' \
  --include=*.java infochat-*/src/test/java
```

| Test | Reads | Fails when | Disposition |
|---|---|---|---|
| `DocumentedConfigKeyParityTest` | `docs/spec/**`, `docs/design/**`, root guides (walks from `Path.of("..")`) | a doc names an `infochat.*` key that is neither real nor exempt | COVERED — the gate that caught `835ab637` |
| `CommandCatalogueParityTest` | `docs/spec/commands.md` | the documented command set, its permission tiers, or its closed-list formatting drifts from the dispatched one | COVERED — same bypass, same exposure |
| `ChatToolAllowlistSpecParityTest` | `docs/spec/security.md` | the chat-tool allowlist in the spec drifts from the registry | COVERED — same bypass, same exposure |
| `LlmOutputSanitizerTest` (`matchSetEqualsSpecClosedList`, via `locateSpec()`) | `docs/spec/commands.md` | the sanitizer's closed list drifts from the spec's | COVERED — reached by the same trigger leg as `CommandCatalogueParityTest` |
| `LiveSimpleXScenarioSuiteIT`, `LiveSimpleXRoundTripIT` | `../prod/runtime/simplex-clients` | — | NOT IN CLASS — relative path, but not a `docs/` fixture |

The census has now been wrong twice, in the same direction, and both
misses are handle defects rather than judgment calls:

- The **first draft** asserted "two tests" from memory; the linter's
  `CENSUS-PRESENT-IF-CLASS-SCOPED` WARN forced the grep that found
  `ChatToolAllowlistSpecParityTest`.
- The **`Path\.of\("\.\.` handle** then missed `LlmOutputSanitizerTest`,
  which reaches the same file via `Paths.get("..", ...)`. Round-1 review
  caught it. Enumerating by one spelling of an idiom is the recurring
  failure — the handle above alternates over all of them.

The rule the ticket writes must name all four, or its "which doc content
is load-bearing" claim understates the surface a reader is asked to
trust. Note that the fourth does NOT widen the trigger: it reads
`docs/spec/commands.md`, which trigger leg (b) already covers, so the
deciding one-liner is unchanged.

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

## Round 1 rework

1. Fix the deciding one-liner so it matches an added line that BEGINS with an
   `infochat.` token, in BOTH copies: `CLAUDE.md` and
   `docs/process/workflow.md`. Replace the second alternation
   `^\+[^+].*infochat\.` with an ERE that still excludes the `+++` header but
   admits a token at column 0 — e.g. `^\+([^+].*)?infochat\.` (the optional
   group keeps `+++ b/…` out of that arm while letting `+infochat.foo=bar`
   match). Update the explanatory clause in `docs/process/workflow.md` —
   "with `[^+]` excluding the `+++` header from that arm" — to describe
   whatever form is chosen, so the prose and the command stay in agreement.
   Re-verify against the ticket's own precedent claim ("flags that commit on
   three added lines") that the revised pattern still flags `835ab637` and
   still finds nothing in the prose-only `75ef6d29`.
