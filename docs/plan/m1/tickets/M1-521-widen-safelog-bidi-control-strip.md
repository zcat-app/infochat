---
id: M1-521
title: "Widen SafeLog.stripControls to the full bidi-control set (close log-line spoof gap)"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
remediates: M1-491
blocked_by: []
files_budget: 2
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log/SafeLogBidiTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Zero-width codepoints (U+200B ZWSP, U+200C ZWNJ, U+200D ZWJ, U+FEFF BOM)
    are NOT added to SafeLog. They neither visually reorder nor split a
    single log line, so they are not part of the log-line spoof / line-break
    threat SafeLog defends — IngestTextNormalizer strips them for ingest and
    matching reasons that do not apply to a one-line operator log value. This
    ticket is scoped to the bidi-reordering gap the M1-491 redteam flagged.
  - >-
    IngestTextNormalizer and the ingest / inbound paths are NOT modified. The
    log-line sanitizer (SafeLog) is the only thing that changes; Stage 1 body
    output, title/url normalization, and inbound normalization stay
    byte-identical.
  - >-
    No change to log format or log levels, and no non-sanitization behavior
    change. SafeLog must keep REPLACING each stripped codepoint with a single
    space (its existing log-line semantics, shared by the M1-491 additions) —
    it must NOT switch to deletion the way IngestTextNormalizer does.
  - >-
    Existing SafeLogBidiTest and SafeLogStripControlsTest assertions are NOT
    altered or weakened. This ticket only ADDS assertions; the M1-491 pins for
    U+202E/U+2028/U+2029 and the C0/C1/DEL pins must stay green. No Flyway
    migration.
acceptance:
  - >-
    SafeLog.stripControls additionally neutralizes (maps to a single space,
    exactly like its existing control and U+202E handling) the bidi-control
    codepoints it currently lets through: U+061C (ALM), U+200E (LRM), U+200F
    (RLM), U+202A (LRE), U+202B (RLE), U+202C (PDF), U+202D (LRO), and
    U+2066..U+2069 (LRI, RLI, FSI, PDI). The M1-491 codepoints U+202E, U+2028,
    U+2029 remain covered. This is the same bidi-control set that
    IngestTextNormalizer.stripBidiAndZeroWidth already strips on the ingest
    path (infochat-core/.../ingest/IngestTextNormalizer.java) — after the fix
    the log-line path no longer lags the ingest path on bidi-reordering
    codepoints. Printable text and the non-control Unicode that
    SafeLogStripControlsTest pins (e.g. NBSP U+00A0) stay untouched.
  - >-
    SafeLogBidiTest gains assertions that stripControls neutralizes each
    newly-covered bidi-control codepoint to a space while leaving ordinary
    printable text byte-identical; the existing U+202E/U+2028/U+2029
    assertions stay unchanged and green.
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - "infochat-core/src/test/java/app/zcat/infochat/core/log/SafeLogBidiTest.java — ADD assertions for the newly-covered bidi-control codepoints; no existing assertion altered."
  preserves:
    - all tests currently green on main
    - the M1-491 SafeLogBidiTest U+202E/U+2028/U+2029 pins and SafeLogStripControlsTest C0/C1/DEL pins
spec_refs:
  - docs/spec/security.md §User content in exceptions
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-521: Widen SafeLog.stripControls to the full bidi-control set (close log-line spoof gap)

## Context

Follow-up to the M1-491 redteam audit (CLEAN, one OUT-OF-MODEL observation;
audit record `docs/plan/m1/redteam/M1-491-2026-06-29.md`). M1-491 added U+202E,
U+2028 and U+2029 to `SafeLog.stripControls`, but among the Unicode
bidirectional-formatting codepoints it covered **only** U+202E. The remaining
bidi reorderers — U+061C (ALM), U+200E/U+200F (LRM/RLM), U+202A..U+202D
(LRE, RLE, PDF, LRO) and the directional isolates U+2066..U+2069
(LRI, RLI, FSI, PDI) — still pass through `stripControls` and can achieve the
**same** visual line-reordering forgery the M1-491 javadoc cites as the reason
to strip U+202E (the Trojan-Source class, CVE-2021-42574).

The finding survived falsification:

- `SafeLog` does not route through any fuller strip — the Nostr relay NOTICE
  (`NostrRelayConnection.handleFrame`) and `SafeLog.formatSafe`'s caller `msg`
  reach `stripControls` directly, with nothing else on the log path.
- The project **already** treats the full bidi-control set as a spoofing
  threat: `IngestTextNormalizer.stripBidiAndZeroWidth` strips U+061C,
  U+200E/U+200F, U+202A..U+202E and U+2066..U+2069 on the ingest/body path, and
  `InboundRouter` applies the same strip on provider inbound. `SafeLog` is the
  lone untrusted-text sanitizer that stops at U+202E — an internal
  inconsistency, not a deliberate boundary.

This is `security_relevant` (log-injection / operator visual-spoof) but was
correctly flagged OUT-OF-MODEL by the redteam because `docs/spec/security.md`
makes no explicit promise about bidi sanitization of log lines. Closing the
gap aligns the log-line path with the project's own established bidi policy.

## Acceptance

See frontmatter. Extend `SafeLog.stripControls` to neutralize the bidi-control
codepoints it currently misses (the set `IngestTextNormalizer` already strips),
keeping SafeLog's replace-with-space semantics, and pin them in
`SafeLogBidiTest`.

## Out-of-scope

See frontmatter. Zero-width codepoints, the ingest path, and log format/levels
are untouched.

## Notes

- Source: M1-491 `/redteam --in-progress` OUT-OF-MODEL item (2026-06-29).
- **Sharing the codepoint set is optional, not required.** The implementer MAY
  factor the bidi-control codepoint set into a single shared declaration with
  `IngestTextNormalizer` to prevent future drift, but only if SafeLog keeps
  replacing-with-space (IngestTextNormalizer *deletes*) and
  IngestTextNormalizer's behavior is left byte-identical. Extending SafeLog's
  existing inline predicate is equally acceptable and stays within the 2-file
  budget; choose the simpler diff.
- Authoring caution (from M1-491): keep `\u` out of Java `//` comments (still
  parsed → "illegal unicode escape"), and write these codepoints as `\uXXXX`
  escapes, never literal chars, in both source and tests.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-521-*.md
```
