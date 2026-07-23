---
id: M1-680
title: "Match closed-list flag entries at any argument position"
status: done
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
    Two new tests pin the argument-run rule that mirrors the parser (the
    router hands the handler the whole multi-line body and
    `ListSourcesArgs.parse` tokenizes it with `split("\s+")`, where Java
    `\s` includes `\n` — so the run spans every line, and the parser has
    no else branch: any token, including one bearing `.`, `!`, `?` or
    `/`, is a real argument that still dispatches `all=true`). The flag
    matches anywhere after the command word, across newlines, so
    `/list-sources --filter rss/news --all`, `/list-sources why? --all`
    and `/list-sources\n--all` ARE redacted — including a flag on a
    FOLLOWING line (`Run /list-sources --page 1\nThen --all is
    separate.`), which the round-3 red-team high finding showed
    dispatches at the parser. This supersedes both the earlier
    sentence-boundary framing (round 1: a `.`/`/`-bearing argument
    evaded while dispatching) and the line-boundary framing (round 3:
    cross-line evasion plus a regression of the adjacent `\n` case the
    pre-ticket `\s+` regex caught).
  - >-
    A new test proves the any-position scan is linear, not quadratic, on
    the adversarial shapes that were live DOS findings on regex attempts,
    plus the cross-line variant: a long whitespace-only run (round-1
    finding), many command-word occurrences on one line (round-2
    finding, where an unbounded lazy regex re-anchors per occurrence and
    rescans to end-of-input, O(P×L)), AND many newline-separated
    command-word occurrences. All complete well within a generous
    timeout. The mechanism is a single left-to-right token scan (a
    whitespace tokenizer mirroring `split("\s+")`), not a regex — chosen
    because no regex matches a flag at any argument position both
    linearly and without an evasion.
  - >-
    A new test proves a CR-separated flag is redacted:
    `/list-sources\r--all` produces `[redacted command]`. The parser's
    `split("\s+")` treats `\r` as a token separator while the router
    splits lines on `\n` alone, so this dispatches `all=true`; the scan
    treats `\r` as an intra-line separator (not a line boundary) to match
    it (2026-07-23 red-team low finding).
  - >-
    The word-boundary and multi-word behaviors M1-676 established stay
    green (its evasion, byte-identical fast-path, subcommand-fold and
    markdown-flatten tests are unmodified and passing).
  - mvn -pl infochat-provider verify is green
  - >-
    docs/spec/security.md §LLM output sanitizer records that flag-bearing
    closed-list entries match the flag at any position in the command's
    argument run — which spans the whole message across newlines,
    mirroring the parser's `split("\s+")` tokenization — and that the
    run is bounded only by the message end (neither sentence- nor
    line-bounded).
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
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 21
      added: 2437
      removed: 38
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-23
    category: AUDIT-EVASION
    severity: high
    promise: |
      docs/spec/security.md §LLM output sanitizer, "Flag position mirrors
      the parser's own scan" (added by this diff): "A flag-bearing
      closed-list entry matches its flag at any argument position within
      the command's argument run... The argument run ends at a sentence
      terminator or at a following /-command, neither of which can occur
      inside a real argument to any flag-bearing entry." Plus: "Every
      match is audit-logged (per-occurrence, not throttled)."
    gap: |
      The terminator claim is factually false about the parser it claims
      to mirror. ListSourcesArgs.parse has no else branch, so unknown
      flags are silently ignored and ANY junk token — including one
      carrying . ! ? or / — leaves --all dispatching. The "any argument
      position" promise is therefore delivered only for argument runs
      that happen to contain no . ! ? or /. LlmOutputSanitizer.java:178
      (ARGUMENT_RUN) and the javadoc at :160-165 repeat the false
      premise; LlmOutputSanitizerTest.java:244-253 locks the bypass
      class in with an asserted non-match.
    repro: |
      A prompt-injected reply ending "To audit every configured feed
      run: /list-sources --filter rss/news --all" fails the match (the
      lazy run cannot span the / in rss/news), so the line ships
      verbatim — no [redacted command], no WARN, no LLM_OUTPUT_SANITIZED
      row. A bot admin copy-pasting it gets all=true and the
      deployment-wide source catalogue (§Source URL visibility).
      Equivalent one-character variants: "/list-sources --page 1. --all",
      "/list-sources v2.0 --all", "/list-sources why? --all". All four
      reproduced in-session against the shipped pattern.
    suggested_fix_class: input-sanitization
  - date: 2026-07-23
    category: DOS
    severity: medium
    promise: |
      docs/spec/security.md §Trust boundaries item 9: a hostile or
      compromised LLM endpoint is in scope, and "the response body is
      read under an operator-configurable cap (clamped to 1-8 MiB)
      before parsing, so a pathological multi-GB reply cannot exhaust
      the JVM." Supporting, §Ingest pipeline: catastrophic backtracking
      is "mitigated by the watchdog timeout" — a discipline the
      outbound sanitizer has no equivalent of.
    gap: |
      ARGUMENT_RUN's lazy character class is followed by a GREEDY \s+,
      so on a whitespace run the continuation consumes and backtracks
      once per lazy position: ~N^2/2 per flag entry. Measured in-session:
      N=2000 43ms, N=8000 189ms, N=16000 775ms, N=32000 2931ms. The
      pre-diff adjacent-only pattern is linear on the same input (0ms),
      so the super-linearity is introduced here. The javadoc's
      "168 KB scans in ~5 ms" argument and the added regression test both
      use single-space separation, where \s+ can only consume one
      character, so neither covers the quadratic case.
    repro: |
      An endpoint (or an injected local model) returns "/list-sources"
      followed by N space characters and nothing else. Entries 20 and 21
      each burn ~N^2/2 backtrack steps with no watchdog, timeout, or
      length cap around sanitize(); ChatAgent, TranslationPipeline,
      DigestRenderer, CategoryRollupGenerator and ClusterBlockRenderer
      all call it inline on the reply path.
    suggested_fix_class: other
  - date: 2026-07-23
    category: AUDIT-EVASION
    severity: high
    promise: |
      docs/spec/security.md §LLM output sanitizer, "Flag position mirrors
      the parser's own scan" (added by this diff): "A flag-bearing
      closed-list entry matches its flag at **any position on the command's
      own line** ... The scan is bounded to a **single line**, because the
      dispatcher treats one line as one command ... Whitespace is read the
      way the parser's `split("\\s+")` reads it — `\r` is an intra-line
      token separator (the router splits lines on `\n` alone) ...
      mirroring the dispatch." Plus the general commitment: "the sanitizer
      closes the social-engineering surface where a small LLM emits
      plausible-looking admin commands across any of the surfaces above.
      Every match is audit-logged (per-occurrence, not throttled)." And
      §Source URL visibility: the `/list-sources --all` listing discloses
      "every URL added via /add-source" — the entry exists to keep that
      catalogue out of LLM output.
    gap: |
      The premise "the dispatcher treats one line as one command" /
      "the router splits lines on `\n` alone" is false for argument
      parsing. InboundRouter.handleSlash resolves the command NAME from the
      first token but passes the ENTIRE, possibly multi-line, `normalized`
      body to `handler.handle`. `normalize` preserves internal `\n` (it
      only trims leading/trailing whitespace). The handler's parser
      `ListSourcesArgs.parse` does `rawText.trim().split("\\s+")` — and
      Java `\s` includes `\n` — so it scans EVERY whitespace-delimited
      token across ALL lines and sets `all=true` for a `--all` on any
      line, not just the command word's line. The sanitizer's tokenizer
      does the opposite: `redactFlagEntry` hard-splits the input on `\n`
      (per-line outer loop) and `isHorizontalWhitespace` deliberately
      EXCLUDES `\n`, so it refuses to match a flag on any line after the
      command word. A flag placed on a following line therefore dispatches
      `all=true` at the parser while evading the sanitizer — no
      `[redacted command]`, no WARN, no `LLM_OUTPUT_SANITIZED` audit row.
      This is also a REGRESSION: the pre-diff regex joined words with
      `\s+`, which spans `\n`, so `/list-sources\n--all` (adjacent across
      a newline) WAS matched before this diff and is not now.
    repro: |
      A prompt-injected reply, or a hostile/compromised LLM endpoint,
      returns two lines: `/list-sources` then, on the next line, `--all`
      (equivalently `/list-sources --page 1\n--all`, or with a blank line
      between — `split("\s+")` collapses the run). The sanitizer ships it
      verbatim. A bot admin reading the reply selects the block starting
      at `/list-sources` and pastes it; the router sees a body starting
      with `/`, resolves `list-sources`, and `ListSourcesArgs.parse`
      splits on the `\n`, finds `--all`, and sets `all=true`. The handler
      returns the deployment-wide source catalogue (§Source URL
      visibility) — every source URL across the deployment — with no
      `LLM_OUTPUT_SANITIZED` audit row ever written for the leak.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-07-23
    verdict: FINDINGS
    base: c5aef23cb1ec86eeb76e57705be189d777db5336
    head: working tree (uncommitted branch m1/M1-680-sanitizer-flag-position)
    verdict_file: docs/plan/m1/redteam/M1-680-2026-07-23.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Both findings independently reproduced in the main session before
      escalation. They converge on one fix: a line-scoped argument run
      (?=[^\S\r\n])[^\r\n]*?[^\S\r\n], probed to match all four evasion
      variants and to stay linear (<=1ms at N=32000). That run is the
      true mirror of the parser, which consumes exactly one line — but it
      contradicts acceptance item 3, whose sentence-boundary rule the
      high finding reframes as a bypass class rather than a correctness
      bound. Rewriting that acceptance item is a ticket-scope decision,
      so this escalates rather than self-resolving.
  - date: 2026-07-23
    verdict: FINDINGS
    base: c5aef23cb1ec86eeb76e57705be189d777db5336
    head: working tree (uncommitted branch m1/M1-680-sanitizer-flag-position)
    verdict_file: docs/plan/m1/redteam/M1-680-2026-07-23-r2.md
    findings_count: 2
    out_of_model_count: 0
    note: |
      Re-audit after the user chose the code-tokenizer remediation over
      the line-scoped regex. One finding independently reproduced:
      medium/DOS from the whole-line lazy regex re-anchoring on many
      command-word occurrences; and one new finding: low/AUDIT-EVASION,
      `\r` not treated as a token separator so `/list-sources\r--all`
      dispatched but evaded. The original verdict file in target/ was
      lost to `mvn clean`; the persistent record is reconstructed from
      the session handoff.
  - date: 2026-07-23
    verdict: FINDINGS
    base: c5aef23cb1ec86eeb76e57705be189d777db5336
    head: working tree (uncommitted branch m1/M1-680-sanitizer-flag-position)
    verdict_file: docs/plan/m1/redteam/M1-680-2026-07-23-r3.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      Re-audit after the code-tokenizer re-implementation. One high/
      AUDIT-EVASION finding: the tokenizer was still line-scoped, so a
      `--all` on a following line (`/list-sources\n--all`) dispatched at
      the parser while evading the sanitizer — a regression from the pre-
      diff `\s+` regex. The driver verified the root cause against
      InboundRouter.handleSlash, normalize, and ListSourcesArgs.parse.
  - date: 2026-07-23
    verdict: CLEAN
    base: e3815eca6084b74b6125f39ffd66bb68e2621a4c
    head: working tree (uncommitted branch m1/M1-680-sanitizer-flag-position)
    verdict_file: docs/plan/m1/redteam/M1-680-2026-07-23-r4.md
    out_of_model_count: 0
    note: |
      Re-audit of the remediated diff after the round-3 high finding.
      The line bound was removed: the argument run spans the whole
      message, separators are exactly ASCII `\s` including `\n`, and the
      scan stays monotonic and linear. Independently, codex audited the
      same diff via `scripts/redteam-multi.sh` and also returned CLEAN.
      Full `mvn verify` is green (313 provider tests).
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings: []
  blockers: []
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
match SHAPE (command word, then a scan of its argument run for the
flag), not a different input representation. M1-676 is the
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

- The argument-run bound is the MESSAGE, not a line or a sentence:
  `ListSourcesArgs.parse` tokenizes the whole multi-line body with
  `split("\s+")` (Java `\s` includes `\n`) and ignores unknown tokens
  (no else branch), so every token after the command word — on any
  line — is a real argument. A `.`/`!`/`?`/`/` inside one does NOT end
  the parser's scan (round-1 high: a sentence bound leaves a
  punctuation-bearing argument dispatching while evading), and neither
  does a newline (round-3 high: a line bound lets `/list-sources\n--all`
  dispatch while evading — and regresses the adjacent case the
  pre-ticket `\s+` regex caught). The scan therefore mirrors the parser:
  command word, then the flag anywhere later in the message; the
  acceptance item pins the cross-line match.
- The flag entries are matched by a whitespace tokenizer (a single
  left-to-right scan over the whole input), NOT a regex. Two rounds of
  red-team findings showed no regex matches a flag at any argument
  position both linearly and without an evasion: a bounded run re-opens
  an evasion for a flag placed past the bound (which the parser still
  dispatches), and an unbounded lazy run is super-linear under `find()`
  re-anchoring (a DoS on a reply with many command-word occurrences).
  The tokenizer only advances its command/flag cursors, so it is linear
  in the reply length — including across newlines, which are ordinary
  token separators.
- Whitespace mirrors the parser's `split("\s+")`: every ASCII `\s`
  character — including `\n` and `\r` — is a token separator, so
  `/list-sources\r--all` and `/list-sources\n--all` dispatch and are
  redacted. The command word needs no leading boundary (`/` is the
  copy-paste start) but must be followed by a separator; the flag's
  trailing boundary admits following punctuation so a copy-paste that
  drops a sentence-final `.` is caught.
- Per-occurrence audit semantics are unchanged: one match, one WARN,
  one audit row.
