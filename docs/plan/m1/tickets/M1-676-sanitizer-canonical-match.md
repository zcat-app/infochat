---
id: M1-676
title: "Canonicalize before closed-list match in LLM sanitizer"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  - docs/spec/security.md
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
    Widening the sanitizer to new detection classes (case-insensitivity,
    homoglyph confusables beyond NFKC, leetspeak). Case variants are
    falsified as a vector (dispatch is case-sensitive:
    InboundRouter.java:1414); non-folding homoglyphs (U+2215, U+2044)
    never parse as commands at intake. NFKC + bidi/zero-width canonical
    matching covers exactly the codepoints that canonicalize into real
    commands; anything broader is a policy decision for a spec amendment.
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
    as today) AND that a case variant (`/Grant-Admin`) is still NOT
    redacted (dispatch is case-sensitive; redacting it would corrupt
    legitimate prose for no security gain).
  - >-
    The markdown-link flatten pass keeps running first and its behavior
    is unchanged (a hostile `[Click](/grant-admin)` still flattens
    before the closed-list pass sees it).
  - mvn -pl infochat-provider verify is green
  - >-
    docs/spec/security.md §LLM output sanitizer records that the
    closed-list match runs on the canonical (NFKC + bidi/zero-width
    stripped) form of the output — the same representation the command
    parser consumes — closing the representation-asymmetry evasion.
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
