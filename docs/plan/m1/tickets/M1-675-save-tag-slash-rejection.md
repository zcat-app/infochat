---
id: M1-675
title: "Reject slash-bearing personal tags at the /save boundary"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The post-title echo on the same reply line ({1} in
    REPLY_SAVED_LINE). Titles are feed-authored and reach the line only
    through the scope's own subscription world — an admin-tier opt-in the
    attacker does not control directly (unlike the personal tag, which any
    registered group member writes). The audit dispositioned the title
    surface as adjacent, one tier removed; tightening title handling is a
    separate decision, not this fix.
  - >-
    A DB-level CHECK constraint on personal_tags (V15). The app-side
    write-boundary rejection closes the finding; a defense-in-depth CHECK
    may be proposed as a follow-up but is not required here (and would
    make this ticket migration_touch).
  - >-
    ListSavesTool's read of personal_tags into the chat prompt. That path
    is covered by the LLM pipeline's own defenses (per-call random
    delimiters, output sanitizer) per the audit; the deterministic /saved
    reply channel is the unfiltered one this ticket closes.
acceptance:
  - >-
    A new test in SaveCommandHandlerTest proves
    `/save <uid> -t "/grant-admin <uuid>"` is REJECTED with a friendly
    error (a new error.save.tag_invalid bundle key, en + cs), storing
    nothing — the M1-659 acceptableOverride shape applied to the tag
    write boundary: each candidate tag is NFKC-normalized first, and any
    tag containing '/' after normalization rejects the whole /save (the
    absolute, non-boundary rule — M1-659's lesson that every Unicode
    partition has blank-rendering members on both sides).
  - >-
    The same test file proves the rejection is self-sufficient (does not
    rely on the router's upstream normalization): a tag carrying U+FF0F
    FULLWIDTH SOLIDUS is rejected, since NFKC folds it to a real '/'
    before the test.
  - >-
    Existing length/count caps keep their behavior: a slash-free tag
    within the caps still saves, an over-long or over-count tag set still
    fails with its current error. SaveCommandHandlerTest and
    SavedCommandHandlerTest stay green with no behavioral edit beyond the
    new rejection arm.
  - >-
    The new error key ships in BOTH en.properties and cs.properties (the
    bundle-completeness CI invariant).
  - mvn -pl infochat-provider verify is green
  - >-
    docs/spec/commands.md §Content records that personal tags may not
    contain a slash (D12 makes '/' the only command sigil, so a
    slash-free tag cannot carry a command token into the group-visible
    /saved reply), and docs/spec/security.md §LLM output sanitizer names
    the /save personal-tag echo as a CLOSED instance of the
    deterministic-reply reflection class alongside M1-656/M1-659.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
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

# M1-675: Reject slash-bearing personal tags at the /save boundary

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
PROV-1) verified that `/save -t` personal tags are stored with only
length/count caps (`SaveCommandHandler.java:188-199`, default 64
chars/tag, 20 tags; `parseTagList` at `:388-397` is trim-only, no charset
validation, no DB CHECK on `personal_tags`) and echoed verbatim into the
group-visible `/saved` reply (`SavedCommandHandler.java:244-249` →
`REPLY_SAVED_LINE`, `bundles/en.properties:364`). A registered group
member runs `/save <uid> -t "/grant-admin <own-ACI>"` (49 chars with a
Signal ACI — under the cap; `/ban <ACI>` is 45), then `/saved` in an
approved group, and the bot broadcasts a syntactically valid privileged
command mid-line to every member — including any bot admin who
copy-pastes it (full compromise, or a targeted ban). This is precisely
the deterministic-reply social-engineering class the repo remediated
twice before: M1-656 (no reflecting inbound text) and M1-659 (slash-
absolute rejection, because "a name containing no slash cannot carry a
command token"). The personal tag is the same surface with the weakest
attacker tier yet — no admin-tier or source-add step needed — and escaped
constraint because the write-side caps were designed for size, not
content shape. The deterministic-reply channel is the one
security.md §LLM output sanitizer leaves unfiltered by design, so no
output-side filter catches it.

## Acceptance

See the frontmatter. Slash-bearing tags (ASCII or compatibility-folded)
are rejected at the write boundary with a friendly, localized error;
slash-free tags and the size caps behave exactly as before; the spec
records the closure.

## Out-of-scope

The post-title echo on the same line (admin-tier-gated; separate
decision), a DB CHECK constraint (follow-up candidate), and the
ListSavesTool prompt path (already defended by the LLM pipeline). See
the frontmatter.

## Notes

- Follow the M1-659 `acceptableOverride` shape literally: NFKC-fold each
  candidate tag, then reject the whole `/save` when any tag contains `/`
  — absolute, not boundary-sensitive. Apply the
  `IngestTextNormalizer.stripMetadataField` control-strip for consistency
  with the display-name fix, per the audit's remediation note.
- Group scope is intended, tested behavior
  (`SaveCommandHandlerTest.save_succeedsInGroupScope`,
  `SavedCommandHandlerTest.saved_succeedsInGroupScope`); the orphaned
  `error.*.group_not_in_v1` bundle keys confirm it. Do not "fix" this by
  making /saved DM-only.
- Finding detail, falsification history, and the SimpleX-vs-Signal
  payload-size analysis: the audit report (`kimi-audit.md` under
  `.scratch/`) §PROV-1 (module 6).
