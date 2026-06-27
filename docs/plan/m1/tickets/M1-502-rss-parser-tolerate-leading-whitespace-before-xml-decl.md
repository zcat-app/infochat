---
id: M1-502
title: "RssFeedParser: tolerate leading whitespace before the XML declaration"
status: done
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParserTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # The fix is scoped to leading XML whitespace (#x20 #x9 #xD #xA) before the
  # declaration — the diagnosed root cause. A leading byte-order mark is NOT
  # stripped: the JDK StAX reader already auto-detects a BOM, and removing a
  # UTF-16 BOM would defeat that auto-detection. Do not add BOM stripping.
  - "Stripping a leading byte-order mark (BOM) before the declaration"
  # No transport-layer change. SingleGetFetch / NitterFetcher / RssFetcher /
  # OdyseeFetcher / YouTubeFetcher are unchanged — the fix lives at the one XML
  # boundary they all route through (RssFeedParser.parse).
  - "Changes to SingleGetFetch or any kind-specific Fetcher class"
  # Whitespace/junk ANYWHERE other than the leading bytes (e.g. between the
  # declaration and the root element, or trailing) is already legal XML and is
  # not touched.
  - "Tolerating non-leading malformed content"
  # Re-enabling the 24 failed nitter sources is an operator action (/source-enable
  # or DB UPDATE) taken AFTER this fix is deployed — not part of this diff.
  - "Re-enabling failed nitter sources in the production DB"
acceptance:
  - >-
    RssFeedParser.parse tolerates insignificant leading whitespace before the
    XML declaration: a body whose bytes are `0x20 0x20 <?xml ... ?> <rss>...`
    (two spaces, the exact production payload from rss.xcancel.com) parses
    successfully and returns one NormalizedPost per <item>, instead of raising
    RssFeedParseException "XML stream error: ParseError at [row,col]:[1,8]".
  - >-
    Only the XML whitespace characters (space #x20, tab #x9, CR #xD, LF #xA) are
    skipped, and only at the very start of the body. The first non-whitespace
    byte must be the start of the XML content (the `<` of `<?xml` or the root
    element); the parser does not skip a BOM or any non-whitespace prefix.
  - >-
    A well-formed body with NO leading whitespace parses byte-for-byte identically
    to before (the existing fixture-based and inline tests stay green — the skip
    is a no-op when the first byte is already `<`).
  - >-
    A body that is entirely whitespace (or empty) still raises
    RssFeedParseException as before (no root element) — the tolerance does not
    mask a genuinely contentless feed.
  - >-
    New unit test(s) in RssFeedParserTest cover at minimum: (a) the two-leading-
    spaces RSS case parses and yields the expected item count + upstreamIdentifier;
    (b) leading tab/CR/LF combinations are tolerated; (c) an all-whitespace body
    still raises. Tests are plain JUnit 5 (no @QuarkusTest), matching the file's
    existing inline-payload style.
  - >-
    Full pre-existing suite (`mvn verify` from repo root) is green — the parse
    path is shared by the RSS, Nitter, Odysee, and YouTube fetchers, so the
    collector fetcher IT/smoke tests must still pass.
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings:
    - "spec_ref format: corrected `docs/spec/architecture.md` bare path to `docs/spec/architecture.md §Ingest SPIs` per the §<section-title> convention."
  blockers: []
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 262
      removed: 7
---

## Problem

Every Nitter (`kind='nitter'`) fetch fails at parse time:

```
WARN [...FetchScheduler] tick failed for source uuid=... :
  RssFeedParseException: XML stream error: ParseError at [row,col]:[1,8]
  Message: The processing instruction target matching "[xX][mM][lL]" is not allowed.
```

Root cause (confirmed at the byte level): `rss.xcancel.com` serves RSS with
**two leading spaces before the `<?xml` declaration**:

```
$ curl -sS -A "infochat/1.0.0-SNAPSHOT" "https://rss.xcancel.com/OpenAI/rss" | head -c 64 | od -An -tx1
 20 20 3c 3f 78 6d 6c 20 76 65 72 73 69 6f 6e 3d   ("  <?xml version=")
 ...
```

The XML 1.0 grammar requires the declaration at byte 0 (no content — not even
whitespace — may precede `<?xml`). `RssFeedParser.parse` wraps the body in a
`ByteArrayInputStream` starting at byte 0 (`RssFeedParser.java:78`), so the
strict StAX reader sees the two spaces and rejects the document at `[1,8]` (the
`xml` of the declaration is read as an illegal processing-instruction target).

HTTP is healthy: 200, `content-type: application/rss+xml`, not a 403, not gzip.
The only defect is the two-byte whitespace prefix. The same `parse` path is
shared by the RSS, Nitter, Odysee, and YouTube fetchers via
`SingleGetFetch.fetchAndParse`, so the fix is made once at that boundary.

Impact: 24 nitter sources fail on every 10-minute tick; their posts never reach
the outbox.

## Fix

In `RssFeedParser.parse`, before constructing the `XMLStreamReader`, advance an
offset past any leading XML whitespace bytes (`0x20`, `0x09`, `0x0D`, `0x0A`) and
build the `ByteArrayInputStream` from that offset (`new ByteArrayInputStream(body,
offset, body.length - offset)`). Valid XML — which never has leading whitespace
after this skip — is unaffected because the skip is a no-op when the first byte is
already `<`. Per the XML spec the only thing legally permitted to lead the
declaration is nothing, so skipping insignificant whitespace cannot change the
meaning of any well-formed feed; it only rescues the lenient-server case.

A leading BOM is deliberately **not** stripped (see `out_of_scope`): the JDK StAX
reader auto-detects a BOM for encoding selection, and removing it would break that
detection for any UTF-16 feed. The diagnosed payload is UTF-8 with an ASCII
whitespace prefix, which the whitespace skip handles exactly.

## After the fix lands and is deployed

Re-enable the failed nitter sources (`/source-enable`, or a DB `UPDATE source SET
status='active', consecutive_failures=0 WHERE kind='nitter' AND status='failed'`)
and confirm `last_success_at` advances and posts appear. This operator step is
out of scope for the diff.
</content>
</invoke>
