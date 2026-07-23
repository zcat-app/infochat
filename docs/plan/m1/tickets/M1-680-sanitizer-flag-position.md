---
id: M1-680
title: "Match closed-list flag entries at any argument position"
status: pending
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: [M1-676]
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The CLOSED_LIST membership itself. It is spec-mirrored and CI-pinned
    against commands.md (LlmOutputSanitizerTest.matchSetEqualsSpecClosedList);
    this ticket changes the MATCH SHAPE for the two flag-bearing entries,
    not which tokens are privileged.
  - >-
    ListSourcesCommandHandler and ListSourcesArgs.parse. The permissive
    any-position flag parsing is the PARSER's documented behavior
    ("no-op on unknown flags"); the sanitizer is what must mirror it.
    Do not tighten the parser to make the sanitizer's job easier — that
    would change a user-facing command surface to patch an output filter.
  - >-
    Case-folding of flag tokens. Falsified in M1-676: ListSourcesArgs
    .parse compares flags with equals, so --ALL never dispatches and
    redacting it would corrupt prose for no gain.
  - >-
    The subcommand-token case fold and the NFKC/bidi canonicalization
    themselves — both land in M1-676, which this ticket builds on.
acceptance:
  - >-
    New tests in LlmOutputSanitizerTest prove the admin-only flag is
    redacted at any argument position: `/list-sources --page 1 --all`
    and `/list-sources --page 2 --include-deleted` each produce output
    containing `[redacted command]`, with one audit-row-worthy match per
    occurrence.
  - >-
    A new test proves the non-admin form is untouched: `/list-sources`
    alone, and `/list-sources --page 2` with no admin flag, are returned
    unchanged (the closed list privileges the FLAG forms, not the bare
    command — redacting the bare form would strip a non-privileged
    command from legitimate prose).
  - >-
    A new test proves the match does not run past the command's own
    argument list: an admin flag appearing after a sentence boundary
    (`Run /list-sources --page 1. Then --all is separate.`) does not
    trigger the flag entry.
  - >-
    The word-boundary and multi-word behaviors M1-676 established stay
    green (its evasion, byte-identical fast-path, subcommand-fold and
    markdown-flatten tests are unmodified and passing).
  - mvn -pl infochat-provider verify is green
  - >-
    docs/spec/security.md §LLM output sanitizer records that flag-bearing
    closed-list entries match the flag at any argument position, mirroring
    the parser's own any-position flag scan.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D12
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-680: Match closed-list flag entries at any argument position

## Context

The 2026-07-23 red-team audit of M1-676
(`docs/plan/m1/redteam/M1-676-2026-07-23.md`, low finding 2) found that
the two flag-bearing closed-list entries — `/list-sources --all` and
`/list-sources --include-deleted` — are compiled by
`LlmOutputSanitizer.compileClosedListPattern` as fixed adjacent token
sequences (`\Q/list-sources\E\s+\Q--all\E`), so the flag must
immediately follow the command word. The parser does not agree:
`ListSourcesCommandHandler.ListSourcesArgs.parse` loops over every
token from index 1 and sets `all = true` on any token equal to
`--all`, so `/list-sources --page 1 --all` dispatches the admin-only
global listing identically.

The consequence is the same class of failure M1-676 closed for Unicode
representation, in a different dimension: an LLM-authored line that
still dispatches ships verbatim, with no `[redacted command]`, no WARN,
and no `LLM_OUTPUT_SANITIZED` audit row. The exposure is the global
source catalogue (every source URL, per security.md §Source URL
visibility) rendered on an admin flag path the closed list exists to
keep out of LLM output.

This is pre-existing and independent of representation, which is why
M1-676 scoped it out rather than folding it in: it needs a different
pattern SHAPE (command word, then a bounded scan of its argument run
for the flag), not a different input representation. M1-676 is the
`blocked_by` because it is the ticket that raises "the sanitizer
matches what the parser dispatches" to a spec-level commitment, and it
rewrites the same method this ticket changes.

## Acceptance

See the frontmatter. The flag matches at any argument position; the
bare non-privileged command is untouched; the scan does not run past
the command's argument list; M1-676's behaviors stay green; the spec
records the any-position rule.

## Census

The affected class is "CLOSED_LIST entries whose non-first token is a
flag". Enumerate mechanically (re-run at `start`):

```
grep -n '^ *"/[a-z-]* --' \
  infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
```

| Entry | Parser site | Any-position? | Disposition |
|---|---|---|---|
| `/list-sources --all` | `ListSourcesArgs.parse`, `tok.equals("--all")` in the index-1..n loop | yes | fix — match at any argument position |
| `/list-sources --include-deleted` | `ListSourcesArgs.parse`, `tok.equals("--include-deleted")` in the same loop | yes | fix — match at any argument position |

Two entries, both on the same command and the same parse loop. If the
grep returns a third entry at `start`, the closed list grew and this
table must be re-derived before implementing.

## Out-of-scope

CLOSED_LIST membership, the parser itself, flag case-folding, and
M1-676's canonicalization/subcommand-fold. See the frontmatter.

## Notes

- The bounded-scan design is the delicate part: `--all` must be found
  anywhere in the command's own argument run, but the run has to END
  somewhere, or a `--all` three sentences later would match. A sentence
  terminator or a following `/`-command are the natural terminators;
  the acceptance item for the sentence-boundary case pins whichever
  rule is chosen.
- Per-occurrence audit semantics are unchanged: one match, one WARN,
  one audit row.
