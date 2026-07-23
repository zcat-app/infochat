---
id: M1-676
title: "Canonicalize before closed-list match in LLM sanitizer"
status: done
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/IngestTextNormalizer.java
  - docs/spec/security.md
  # The M1-680 skeleton the out_of_scope entry defers the flag-position
  # finding to. ticket-template.md §FORWARD-REFERENCE RULE requires it to
  # exist as a file before this ticket may defer to it, so the skeleton is
  # an orphan this ticket's own out_of_scope edit created — declared here
  # rather than deleted (round-1 review, SCOPE-DRIFT rework item 2).
  - docs/plan/m1/tickets/M1-680-sanitizer-flag-position.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The CLOSED_LIST membership itself. The list is spec-mirrored and
    CI-pinned against commands.md (LlmOutputSanitizerTest
    .matchSetEqualsSpecClosedList); this ticket changes the MATCHING
    representation, not which tokens are privileged.
  - >-
    The intake-side normalization (InboundRouter.appendNormalized,
    step 1.7). It already canonicalizes correctly — the finding is the
    ASYMMETRY that the sanitizer matches raw text while dispatch consumes
    canonical text. Do not "fix" the router.
  - >-
    Case-folding the command NAME token or a flag token. Both are
    falsified as vectors and folding them would redact legitimate prose
    for no gain: the name is dispatched by exact match
    (InboundRouter.java:1414, handler.name().equals) so /Grant-Admin and
    /Invite create never become commands, and flags are compared with
    equals (ListSourcesCommandHandler ListSourcesArgs.parse) so
    --ALL never dispatches. Only the SUBCOMMAND token folds, because only
    it is lower-cased by the parser — see the acceptance items.
  - >-
    The --all / --include-deleted flag-POSITION asymmetry (redteam low
    finding 2): ListSourcesArgs.parse accepts the admin flag at any
    argument index while CLOSED_LIST_PATTERNS requires it adjacent to the
    command word, so /list-sources --page 1 --all evades the strip. It
    is pre-existing, independent of representation, and needs a different
    pattern shape than canonicalization. Filed as M1-680.
  - >-
    Widening the sanitizer to non-case detection classes (homoglyph
    confusables beyond NFKC, leetspeak). Non-folding homoglyphs (U+2215,
    U+2044) never parse as commands at intake. NFKC + bidi/zero-width
    canonical matching plus subcommand case-folding covers exactly what
    canonicalizes into real commands; anything broader is a policy
    decision for a spec amendment.
acceptance:
  - >-
    New tests in LlmOutputSanitizerTest prove the probed evasion set is
    now redacted: `／grant-admin <aci>` (U+FF0F), all-fullwidth
    `／ｇｒａｎｔ－ａｄｍｉｎ`, ZWSP-embedded `/g​rant-admin`,
    bidi-embedded `/grant-ad⁦min⁩`, and U+3000-joined `/invite　create`
    each produce output containing `[redacted command]` and NOT the
    matched token's canonical form — with one audit-row-worthy match per
    occurrence (the per-occurrence durability commitment is unchanged).
  - >-
    A new test proves the no-match fast path is byte-identical: LLM
    output containing no canonical-form closed-list token is returned
    EXACTLY as input (no NFKC reflow of legitimate Unicode prose —
    ligatures, Czech diacritics, and fullwidth text that does not fold
    into a closed-list token all pass through unchanged).
  - >-
    A new test proves multi-word entries still match their canonical
    spacing forms (`/invite  create` with doubled ASCII space redacts,
    as today) AND that a command-NAME case variant (`/Grant-Admin`) is
    still NOT redacted (name dispatch is exact-match; redacting it would
    corrupt legitimate prose for no security gain).
  - >-
    New tests prove a case-varied SUBCOMMAND token IS redacted for the
    multi-word subcommand entries — `/invite CREATE`, `/quarantine
    APPROVE`, and the fullwidth `/invite ＣＲＥＡＴＥ` (NFKC-folds to
    `/invite CREATE`) — because the parser lower-cases that token before
    dispatch (InviteCommandHandler.java:232, QuarantineCommandHandler
    .java:135 both `split[1].toLowerCase(Locale.ROOT)`). This is the
    2026-07-23 redteam medium finding: matching the subcommand
    case-sensitively left 8 of the 34 closed-list entries evadable by
    changing one word's case, with no WARN and no audit row.
  - >-
    A new test proves the fold is scoped to the subcommand and does NOT
    leak into the two flag-bearing entries: `/list-sources --ALL` is NOT
    redacted (ListSourcesArgs.parse compares flags with equals, so it
    never dispatches), while `/list-sources --all` still is.
  - >-
    Delivered sanitizer output NEVER contains `](` — the adjacency a
    renderer requires to resolve a link — whether the syntax arrived in
    the LLM output or was synthesized by canonicalization. Two mechanisms
    compose: a link the flatten pass can PARSE is flattened to
    `label (url)` exactly as today; any residual `](` it could not parse
    has the adjacency broken (`](` → `] (`). Breaking adjacency loses no
    characters — label and URL both stay visible — and is sufficient
    because CommonMark resolves a link only on the adjacent pair. This is
    the absolute property the round-3 redteam falsified when it was
    claimed of the flatten alone
    (docs/plan/m1/redteam/M1-676-2026-07-23-r3.md); it is made TRUE here
    rather than narrowed, which is why neutralization replaces the
    marker-specific `separateMarkerFromFollowingParen` instead of sitting
    beside it.
  - >-
    New tests prove all three routes a delivered link can arise, each
    asserting the output carries no `](` AND that the closed-list
    redaction is unaffected (the two passes are independent — the match's
    word-boundary lookahead admits `)`, so a token inside a link TARGET is
    redacted, WARNed and audit-logged whether or not the flatten fired):
    (a) fullwidth brackets NFKC-folded into a real `[label](url)` the
    raw-byte pass could not match, which comes back FLATTENED to
    `label (url)`; (b) the `[redacted command]` marker landing against a
    following `(` (`/ban(url)`), separated with the marker intact; and
    (c) a label carrying balanced nested brackets, which no regular
    expression can parse — both as it arrives
    (`[Read [the] report](url)`, pre-existing) and in the fullwidth-closed
    form (`[Read [the] report ］（url）`) that canonicalization would
    otherwise turn INTO a working link on the match path. Route (c) is
    verified against real CommonMark rules, not asserted: the
    fullwidth-closed form renders as `<a href=...>` before the fix.
  - >-
    The markdown-link pass keeps running FIRST and its parsing is
    unchanged (a hostile `[Click](/grant-admin)` still flattens to
    `Click (/grant-admin)` before the closed-list pass sees it). What is
    added is only the neutralization of what that parsing could not
    match — no link the pass flattens today is flattened differently.
  - mvn -pl infochat-provider verify is green
  - >-
    IngestTextNormalizer's class javadoc records that its bidi/zero-width
    strip is shared with the provider-side LLM output sanitizer, not the
    ingest path alone. Reusing it (rather than declaring the codepoint
    set a third time) is what keeps "exactly one declaration" true, so
    the scoping line that says "shared across the ingest path" must
    stop under-stating its consumers.
  - >-
    docs/spec/security.md §LLM output sanitizer records that the
    closed-list match runs on the canonical (NFKC + bidi/zero-width
    stripped, subcommand token case-folded) form of the output — the
    same representation the command parser consumes — closing the
    representation-asymmetry evasion. The spec must state the per-token
    rule (name exact, subcommand folded, flag as its own handler parses
    it) rather than the blanket "case is not folded" claim the redteam
    falsified — and must NOT replace it with a second over-broad claim:
    flag parsing is not uniformly exact across the codebase
    (QuarantineCommandHandler.java:526 lower-cases flag tokens), so the
    rule is derived per entry from its handler, not asserted globally.
    The spec must also record the delivered-output property and the two
    mechanisms that make it true — flatten what is parseable, break the
    adjacency of what is not — stated as the absolute it now IS, with the
    reason it is safe to state absolutely: the property is about two
    adjacent characters, not about parsing markdown, so it does not
    inherit the flatten regex's limits. It must NOT be written as a claim
    about the flatten alone; that form is what the round-3 redteam
    falsified, and a false absolute in the threat model is exactly the
    defect class this ticket exists to close.
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
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1579
      removed: 44
  - round: 2
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1658
      removed: 46
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-23
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer, including the paragraph
      this ticket adds: the closed-list pass matches "the same
      representation the deterministic command parser consumes", and
      "Case is deliberately not folded: dispatch compares the command
      name exactly".
    gap: |
      The parity claim is false for every multi-word closed-list entry
      whose second token is a SUBCOMMAND: the parser case-folds
      subcommands and the sanitizer does not.
      InviteCommandHandler.java:232 and QuarantineCommandHandler.java:135
      both do split[1].toLowerCase(Locale.ROOT) before switching, so 8 of
      the 34 CLOSED_LIST entries (/invite create|list|revoke|bot-contact|
      pending-contacts, /quarantine list|approve|reject) are evaded by
      changing the case of the second word alone. No match also means no
      WARN and no LLM_OUTPUT_SANITIZED audit row.
    repro: |
      An injected model emits "an admin should run /invite CREATE --open"
      (or the fullwidth /invite ＣＲＥＡＴＥ, which NFKC folds to
      /invite CREATE and still evades the case-sensitive pattern).
      canonicalizeForMatching finds no match, the matches.isEmpty() early
      return ships the original bytes, and a bot admin who copy-pastes the
      line dispatches the privileged path: handleSlash resolves "invite"
      by exact-case NAME match, then InviteCommandHandler lower-cases
      CREATE to create.
    suggested_fix_class: input-sanitization
  - date: 2026-07-23
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer §Match-set derivation —
      "admin commands never leak through LLM output" as a structural
      property — plus this ticket's new parser-parity claim.
    gap: |
      The two flag-bearing entries are matched as fixed adjacent token
      sequences (compileClosedListPattern joins words with \s+), but
      ListSourcesCommandHandler.ListSourcesArgs.parse:479-495 loops over
      every token from index 1 and sets all=true on any --all token, so
      the flag dispatches from any argument position. Pre-existing, but
      this ticket raises parser parity to a spec-level commitment and
      leaves this instance open and untested.
    repro: |
      Injected model emits "/list-sources --page 1 --all". Neither
      closed-list pattern matches (the flag is not adjacent), so the line
      ships verbatim with no audit row; a pasting admin gets the global
      source catalogue on the admin flag path.
    suggested_fix_class: input-sanitization
  - date: 2026-07-23
    category: INJECTION
    severity: low
    promise: |
      LlmOutputSanitizer's documented pass ordering — the markdown-link
      flatten "Runs FIRST so a hostile [Click for admin](/grant-admin)
      flattens BEFORE the closed-list pass sees it" — backing the
      plain-text/bare-URL surface convention.
    gap: |
      The markdown pass runs on the RAW bytes (MARKDOWN_LINK is
      ASCII-bracket-only) while canonicalization now happens later inside
      the closed-list pass. NFKC folds U+FF3B/U+FF3D/U+FF08/U+FF09 to
      []() , so on ANY closed-list hit the returned canonical text can
      contain markdown link syntax the flatten pass never saw. Pre-diff
      the fullwidth brackets reached the user as fullwidth characters; the
      sanitizer now synthesizes real link syntax in the delivered message.
    repro: |
      Model emits "Run /ban spammer, then see ［important notice］（https://evil.example/x）".
      Markdown pass sees no ASCII [..](..) so does not flatten; the
      closed-list pass matches /ban and returns the canonical form, which
      now carries [important notice](https://evil.example/x). Bounded
      today only because both v1 production adapters assert
      supportsMarkdownLinks=false.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-07-23
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer, "Markdown flattening
      survives canonicalization" — a paragraph this ticket ADDS: "The
      delivered result of a match therefore never contains `](`, by either
      route."
    gap: |
      The no-`](` property is stated absolutely but holds only for the two
      SANITIZER-MANUFACTURED routes the acceptance item covers (both of
      which the auditor re-verified as genuinely closed). A third route
      leaves it false: MARKDOWN_LINK's link-text group `[^\]]+` cannot span
      a nested `]`, so a CommonMark-valid link whose label carries balanced
      brackets is invisible to BOTH flatten invocations (raw and canonical)
      and is delivered verbatim alongside the redaction. The limitation is
      pre-existing; what is new is the spec sentence that promises the
      absolute property.
    repro: |
      Injected model emits "Run /ban spammer. [Read [the] report](https://
      evil.example/phish)". Neither flatten matches (group 1 stops at the
      inner `]`, and the following `\(` then sees a space); /ban matches and
      the delivered text carries `](` as part of a label-hiding link a
      CommonMark renderer resolves. Bounded today only because both v1
      production adapters assert supportsMarkdownLinks=false.
    suggested_fix_class: input-sanitization
  - date: 2026-07-23
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Match-set derivation — "admin commands never
      leak through LLM output" as a structural property.
    gap: |
      Re-report of the round-1 low finding 2 (--all accepted at any argument
      index while CLOSED_LIST_PATTERNS requires adjacency). Already
      dispositioned: named verbatim in this ticket's out_of_scope and filed
      as M1-680. Recorded here only so the audit index stays faithful to
      what the auditor returned; it carries no new action.
    repro: |
      See the round-1 entry above and M1-680.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-07-23
    verdict: FINDINGS
    base: f8ab20593ae84ec34f1a92dad6484c120e6f97df
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-676-2026-07-23.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      Gate ran ahead of review per run.md step 4. All three findings were
      confirmed against the cited code before escalation. The medium is the
      load-bearing one: it does not merely note a pre-existing gap, it shows
      that the spec paragraph and code comment this ticket ADDS state a
      parser-parity property the implementation does not hold for
      subcommand-bearing entries. The two lows are pre-existing asymmetries
      (--all flag position) and a new side effect of matching on the
      canonical form (fullwidth brackets folding into real markdown links on
      a match).
  - date: 2026-07-23
    verdict: FINDINGS
    base: f8ab20593ae84ec34f1a92dad6484c120e6f97df
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-676-2026-07-23-r3.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Round 3, re-triggered because the round-2 remediation touched src/
      (run.md step 5). Both findings confirmed before escalation. Only one
      is actionable: the spec paragraph this ticket adds states the no-`](`
      property absolutely, and a nested-bracket CommonMark link evades both
      flatten passes, so the absolute form is false. The auditor confirmed
      the two manufacture routes the ticket set out to close ARE closed —
      the defect is the claim's scope, not the code. The second finding is a
      re-report of round 1's --all position asymmetry, already out_of_scope
      and filed as M1-680.

      Round 2's verdict file was never persisted (the previous session
      folded its findings into commit a0a9d123 but stopped before the
      redteam skill's step 7), so this index has no round-2 entry.
  - date: 2026-07-23
    verdict: FINDINGS
    base: f8ab20593ae84ec34f1a92dad6484c120e6f97df
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-676-2026-07-23-r4.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Round 4, re-triggered because the option-2 neutralization changed
      production source. The one finding is the --all flag-POSITION asymmetry
      reported in rounds 1 and 3 — already out_of_scope and filed as M1-680,
      re-derived independently, no new action. Materially this round is a
      remediation-verification pass: canonical-form parity with intake
      (codepoint-for-codepoint), the per-token case rule (every consumer, plus
      a probe for an NFKC-surviving codepoint that lower-cases to bare ASCII —
      none reachable), and the no-`](` guarantee on BOTH exits were each
      confirmed against the code, along with the inverse hazard that flattening
      could destroy a still-dispatchable token (impossible: no CLOSED_LIST
      entry contains a bracket or paren). Out-of-model item 1 is being fixed
      in-branch — the spec heading said "Delivered output" where the mechanism
      covers sanitizer output only (DigestRenderer.java:127-129 ships degraded
      prose unsanitized by design); the acceptance already said "sanitizer
      output", so the heading merely dropped the scoping word.
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-676: Canonicalize before closed-list match in LLM sanitizer

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
PROV-2) verified that `LlmOutputSanitizer`'s closed-list strip
(`LlmOutputSanitizer.java:152-161`) matches the privileged-command list
only in raw ASCII form, while the command parser consumes text AFTER
intake canonicalization (`InboundRouter.appendNormalized:1728-1755` —
NFKC + bidi strip + zero-width strip per non-fence line, then
case-sensitive dispatch at `:1414`). Any closed-list token therefore has
Unicode variants that survive sanitization verbatim yet canonicalize into
a valid privileged command when a bot admin copy-pastes the bot's reply
line — runtime-probed against the real compiled class
(`.scratch/probe-src/app/zcat/infochat/provider/llm/SanitizerEvasionProbe.java`):
`／grant-admin` (U+FF0F), all-fullwidth, ZWSP- and bidi-embedded tokens,
and U+3000-joined multi-word entries (Java `\s` does not match U+3000;
NFKC folds it to a space) all pass the sanitizer unchanged and all parse
as commands at intake. Two variants were falsified as vectors and stay
out of scope: case (`/Grant-Admin` — case-sensitive dispatch never
canonicalizes it) and ZWSP-as-word-separator (`/invite​create` →
`/invitecreate`, not a command). The sanitizer is the sole documented
defense on the LLM-output channel (security.md §LLM output sanitizer),
the attacker tier is any registered user who asks the chat agent to echo
the line, and the project precedent (M1-659) is explicit: constrain the
value, never trust the operator — and run representation-sensitive checks
on the canonical form the consumer sees.

## Acceptance

See the frontmatter. The probed evasion set redacts; no-match output is
byte-identical (legitimate Unicode prose is never reflowed); case
variants stay untouched; markdown flatten unchanged; the per-occurrence
audit commitment holds; the spec records canonical-form matching.

## Out-of-scope

CLOSED_LIST membership, the intake normalizer, and detection classes
beyond NFKC-canonicalization (case, confusables, leetspeak — falsified
or inert per the audit). See the frontmatter.

## Notes

- Implementation shape the audit suggests: build the canonical copy
  (NFKC, then the same bidi/zero-width strip sets the router uses), run
  CLOSED_LIST_PATTERNS against it; on no match return the ORIGINAL bytes
  (the byte-identical fast path); on a match, emit the canonical form
  with each matched region replaced by `[redacted command]` and write
  the per-occurrence WARN + audit rows as today. A match is the only
  case where output bytes change form — acceptable, since a match means
  the text carried a canonicalizable command token.
- Consider `UNICODE_CHARACTER_CLASS` on the `\s+` join as belt-and-suspenders
  only; canonicalization is the load-bearing fix (it also covers the
  U+3000 case the flag alone would not, since U+3000 in the ORIGINAL is
  not `\s` anyway until folded).
- Finding detail, the full probe matrix (8 variants, 2 falsified), and
  falsification history: the audit report (`kimi-audit.md` under
  `.scratch/`) §PROV-2 (module 6).

## Round 1 rework

Reviewer verdict: REWORK (2 items). Verbatim:

1. Add one test pinning the no-match delivery path for link syntax the
   flatten cannot parse — the route that currently has zero coverage.
   Minimal shape: assert that
   `LlmOutputSanitizer.applyMarkdownLinkStrip("See [Read [the] report](https://evil.example/phish)")`
   (input carrying NO closed-list token) contains no `](` while both the
   label and the bare URL survive; or equivalently assert the same on
   `sanitize()` in `LlmOutputSanitizerAuditRowIT` alongside the existing
   `noHitsProduceNoAuditRow`. Either kills the surviving mutation named
   above (deleting the `neutralizeResidualLinkSyntax` wrapper at
   LlmOutputSanitizer.java:243). No production change is required — the
   behavior is already correct; only the assertion is missing.
2. Add `docs/plan/m1/tickets/M1-680-sanitizer-flag-position.md` to the
   ticket's `files_scope` (and bump `files_budget` to 6 if you want the
   advisory count to stay consistent). Do NOT delete the M1-680 skeleton —
   `docs/process/ticket-template.md:44-51` requires the follow-up ticket to
   exist as a file before this ticket's `out_of_scope` may defer the
   flag-position finding to it, and STATUS.md already reflects it.

Both addressed:

1. `LlmOutputSanitizerTest.unparseableLinkIsNeutralizedWithNoClosedListTokenPresent`
   drives `applyMarkdownLinkStrip` with an un-parseable link and no
   closed-list token — the exact route the surviving mutation exposed. No
   production change; the behavior was already correct.
2. `files_scope` now declares the M1-680 skeleton, `files_budget` 5 → 6.
   The skeleton is kept, per the reviewer's explicit instruction and the
   FORWARD-REFERENCE RULE.

The NEGATIVE-SPACE WARN (`LlmOutputSanitizerAuditRowIT` untouched) is
informational and not a rework item. It stays untouched deliberately: the
IT's subject is audit-row durability at the `sanitize()` boundary, and the
per-occurrence match count the acceptance commits to is asserted directly
via `result.matches()` in the unit tier. Rework item 1 offered the IT as an
alternative home for the missing assertion; the unit-tier site was chosen
because it pins the exact pass the surviving mutation would have deleted,
without booting a DB.
