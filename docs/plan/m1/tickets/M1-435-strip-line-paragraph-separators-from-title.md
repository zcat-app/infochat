---
id: M1-435
title: "Strip U+2028/U+2029 line/paragraph separators in the metadata-field strip"
status: pending
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/IngestTextNormalizer.java
  - infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestTextNormalizerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterNormalizationIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The body path is NOT changed: IngestTextNormalizer.stripBidiAndZeroWidth (which Stage1Pipeline.unicodeNormalize calls) must keep emitting U+2028/U+2029 unchanged, because the body is legitimately multi-line. The two new codepoints are added ONLY to stripMetadataField (the single-line title/url strip). Stage 1 body output stays byte-identical."
  - "No broadening to category-based stripping (a Cc/Cf/Zl/Zp sweep) or to any other format/space codepoint. U+2028 (the only Zl codepoint) and U+2029 (the only Zp codepoint) are the exact and complete set of forced-line-break codepoints that escape the current strip — every other line break (LF, CR, VT, FF, NEL) is already removed by the Character.isISOControl pass. Adding just these two fully closes the line-break-injection class; anything wider is scope creep and risks stripping legitimate format characters."
  - "normalizeUrlForStorage in PostPersister is NOT modified. A url carrying U+2028/U+2029 already binds NULL today because new URI(...) rejects the codepoint with URISyntaxException; the stripMetadataField change applies to url harmlessly but is relied upon only for title."
  - "NFKC is not added to title/url; no Flyway migration; asset/price snapshot paths untouched."
  - "Existing assertions in IngestTextNormalizerTest and PostPersisterNormalizationIT are NOT altered or weakened — this ticket only ADDS new test methods. The existing stripBidiAndZeroWidth-preserves-control-characters assertion in particular must stay green."
acceptance:
  - "IngestTextNormalizer.stripMetadataField additionally removes U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) from its input, in addition to the bidi-control, zero-width and ISO-control codepoints it already strips. IngestTextNormalizer.stripBidiAndZeroWidth is unchanged and still emits U+2028/U+2029 verbatim."
  - "A new test method in IngestTextNormalizerTest asserts stripMetadataField removes an embedded U+2028 and an embedded U+2029 (and still leaves ordinary text byte-identical)."
  - "A new test method in IngestTextNormalizerTest asserts stripBidiAndZeroWidth PRESERVES U+2028 and U+2029 — pinning the body-path byte-identical contract so a future edit cannot silently extend the line-separator strip to the body."
  - "A new test method in PostPersisterNormalizationIT asserts that persisting a NormalizedPost whose title carries an embedded U+2028 results in a stored title with the separator stripped."
  - "All existing assertions in IngestTextNormalizerTest and PostPersisterNormalizationIT remain unchanged and green; all tests currently green on main remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestTextNormalizerTest.java   # ADD new test methods only; no existing assertion altered
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterNormalizationIT.java   # ADD new test method only; no existing assertion altered
  preserves:
    - all tests currently green on main
    - all existing IngestTextNormalizerTest and PostPersisterNormalizationIT assertions
    - Stage 1 body output (byte-identical; stripBidiAndZeroWidth unchanged)
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-435: Strip U+2028/U+2029 line/paragraph separators in the metadata-field strip

## Context

M1-433 (done, commit d5d7d3df) added a control-character strip to post
`title`/`url` at the ingest convergence point so an embedded newline
could not inject a misleading apparent line into a bare-emitted bot
reply. Its pre-commit `/redteam` pass (CLEAN, recorded in
`docs/plan/m1/redteam/M1-433-2026-06-23.md`) surfaced one advisory
**OUT-OF-MODEL** observation: `IngestTextNormalizer.stripMetadataField`
removes ISO control characters via `Character.isISOControl`, which
covers only U+0000..U+001F and U+007F..U+009F (category Cc). The two
Unicode separators U+2028 (LINE SEPARATOR, category Zl) and U+2029
(PARAGRAPH SEPARATOR, category Zp) are NOT control characters, so they
survive the strip — yet UAX #14 classifies both as mandatory line
breaks, so a title carrying one renders as an extra apparent line on a
conformant client, the same obfuscation class M1-433 set out to close.

This was correctly classed advisory (the threat model scopes Stage 1
normalization to "the body"; title/url normalization is net-new
hardening with no spec-level completeness promise), so it landed as a
follow-up rather than a finding on M1-433. Verified at source on
2026-06-23 by running the compiled helper: a title containing
U+2028/U+2029 passes through `stripMetadataField` unchanged; the `url`
path is already safe because `new URI(...)` rejects the codepoint and
`normalizeUrlForStorage` binds NULL; and no provider/adapter output
layer normalizes line separators (titles reach the send boundary
verbatim through the digest and summary renderers, which place each
title on its own list line). The residual is therefore real and
title-only, and closing it requires exactly the two codepoints.

## Acceptance

See frontmatter. Add U+2028 and U+2029 to the `stripMetadataField`
strip (and ONLY there — `stripBidiAndZeroWidth`, which the Stage 1 body
path reuses, stays unchanged so the body remains byte-identical and
keeps its legitimate line structure). Three new test methods: the
metadata strip now removes both separators; the body-path strip still
preserves them; and a persisted title with an embedded U+2028 is stored
stripped.

## Out-of-scope

See frontmatter. The body path, `normalizeUrlForStorage`, NFKC, and any
broadening beyond the two Zl/Zp codepoints are all explicitly excluded.
This ticket only adds new test methods; it does not alter existing
assertions.

## Notes

- **Why exactly two codepoints:** Zl contains only U+2028 and Zp
  contains only U+2029 (verified against the full Unicode database), and
  every other forced-line-break codepoint (LF, CR, VT, FF, NEL) already
  falls in the `Character.isISOControl` range. So this pair is the
  complete remaining set; the narrow fix fully closes the line-break
  class with no need for a category sweep.
- **Why metadata-only:** the body is multi-line by design and the
  `stripBidiAndZeroWidth` contract must keep line separators. The
  M1-433 byte-identical guarantee for the Stage 1 body output depends on
  that method not changing — the new body-path-preservation test pins
  it.
- **Cheapest seam:** the change is a two-clause addition inside the
  existing ISO-control loop in `stripMetadataField`
  (`IngestTextNormalizer.java`), parallel to the existing
  `Character.isISOControl(c)` continue.
- **security_relevant: true** — touches the untrusted-content
  normalization boundary; invites a `/redteam` pass.
- Source advisory: `docs/plan/m1/redteam/M1-433-2026-06-23.md`
  (OUT-OF-MODEL block). Parent hardening: M1-433.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-435-strip-line-paragraph-separators-from-title.md
```
