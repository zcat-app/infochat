---
id: M1-433
title: "Strip bidi/zero-width/control characters from post title and url at the ingest convergence point"
status: pending
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest
  - infochat-core/src/test/java/app/zcat/infochat/core/ingest
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Option B (normalize in each of the six parsers) is rejected: it creates the per-fetcher drift the URL-redaction consolidation already exists to avoid. Normalization happens once at the convergence point (PostPersister)."
  - "Option C (rely on the Provider rendering layer to strip non-body fields) is rejected as the owner: ingest is the deterministic single owner of stored-post normalization; splitting it across two modules leaves no single owner. Whatever the Provider does on output is not relied upon and is not changed by this ticket."
  - "Source post BODY normalization is unchanged in behavior: Stage1Pipeline's body output stays byte-identical (pinned by the existing Stage1 tests). This ticket only adds title/url coverage and de-duplicates the codepoint loop; it does not alter what the body path emits."
  - "NFKC normalization is NOT added to title/url — only the bidi-control + zero-width + control-character strip is applied to those fields. NFKC stays scoped to the body path where it already lives."
  - "No Flyway migration: title and url columns are unchanged in shape; only the values written are normalized."
  - "Asset/price snapshot paths are not posts and are untouched."
acceptance:
  - "A new shared helper under infochat-core/src/main/java/app/zcat/infochat/core/ingest exposes a pure method that applies the bidi-control strip (U+061C, U+200E/U+200F, U+202A..U+202E, U+2066..U+2069), the zero-width strip (U+200B/U+200C/U+200D/U+FEFF), and control-character stripping — the same codepoint set Stage1Pipeline.unicodeNormalize currently strips. The method is the single declaration of that codepoint loop."
  - "Stage1Pipeline.unicodeNormalize is refactored to call the new shared helper for the strip portion (NFKC remains in Stage1Pipeline); the body output is byte-identical to before, proven by the existing Stage1 tests staying green."
  - "PostPersister applies the shared strip to normalized.title() (still coerced to \"\" when null, per the V7 NOT NULL invariant) and to normalized.url() before binding (PostPersister.java:161 / :165). A url that no longer parses as a valid http/https URI after stripping is bound as NULL rather than stored mangled."
  - "A unit test under infochat-core/src/test/java/app/zcat/infochat/core/ingest asserts the helper removes a U+202E bidi override, a zero-width character, and an embedded control character, and leaves ordinary text unchanged."
  - "A test under infochat-collector/src/test/java/app/zcat/infochat/collector/outbox asserts that persisting a NormalizedPost whose title carries a bidi-override codepoint and whose url carries an embedded control character results in a stored title with the codepoint stripped and a url that is either the stripped-and-still-valid URL or NULL (when stripping leaves an invalid URI)."
  - "All tests currently green on main remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/ingest (shared-strip-helper unit test)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox (PostPersister title/url normalization test)
  preserves:
    - all tests currently green on main
    - all existing Stage1 tests (body output byte-identical)
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

# M1-433: Strip bidi/zero-width/control characters from post title and url at ingest

## Context

The 2026-06-23 `/deep-code-review full` run
(`.reviews/deep-review/full-2026-06-23-0957/`) surfaced the run's only
**SECURITY** finding (`06-module-infochat-collector.md#F1`, severity low).

Stage 1 (`Stage1Pipeline.unicodeNormalize`) runs NFKC + a bidi-control strip +
a zero-width strip on `post.body` only. The `title` and `url` columns are
written verbatim by `PostPersister` (`PostPersister.java:165` coerces a null
title to `""`; `:161` binds the url raw) and never pass through any
normalization. Both fields are upstream-controlled untrusted content (RSS/Atom
`<title>`, the Bluesky `author.handle` that builds the web URL, the Reddit
`permalink`/`name`), and both reach user-facing surfaces: `title` appears in the
`searchPosts`/`getPost` tool output and in summaries/digests; the `url` is
emitted **bare** per the plain-text formatting rule.

A title or handle carrying a `U+202E` bidi override or zero-width characters can
render a misleading line in a bot reply; a url carrying a control character can
inject an apparent extra line into a bare-URL reply — the exact obfuscation
class Stage 1 strips out of the body. This is **not** spec-drift —
`docs/spec/security.md` §Ingest pipeline (security side) scopes Stage 1 to "the
body" by its literal wording — but the same untrusted-content reasoning that
justifies normalizing the body applies to the title and the URL, and nothing in
the collector closes the gap today.

Re-verified at source on 2026-06-23: the body-only strip in
`Stage1Pipeline.unicodeNormalize` (`Stage1Pipeline.java:296`); the verbatim
title/url binds in `PostPersister` (`:161`, `:165`); the parallel parsers
(`RssFeedParser`, `RedditResponseParser`, `BlueskyResponseParser`) that all
converge on `PostPersister`.

## Acceptance

See frontmatter. The codepoint-strip loop is lifted to a single shared
`infochat-core` ingest helper (one declaration), Stage 1 is refactored to reuse
it with its body output byte-identical, and `PostPersister` — the one point
where every fetcher and StreamSource path converges — applies the strip to
`title` and `url`, dropping a url that no longer parses after the strip. Two
tests: a core unit test for the helper, and a collector test proving title/url
are normalized on persist.

## Out-of-scope

See frontmatter. Per-parser normalization (Option B) and Provider-side output
stripping (Option C) are both explicitly rejected in favor of the single ingest
convergence owner. NFKC is not extended to title/url; the body path's behavior
is unchanged; no migration.

## Notes

- **Why `PostPersister`:** it is the convergence point for all six fetchers and
  both StreamSource paths, matching the "one copy of the security-relevant
  logic" pattern the module already uses for URL redaction (`SingleGetFetch`).
  Normalizing here writes the fix once instead of six times.
- **Helper placement:** `infochat-core/.../core/ingest` already exists as the
  cross-module ingest package; the strip helper belongs there so both the
  collector Stage 1 path and `PostPersister` consume one declaration.
- **URL handling:** stripping a control character from a url can leave an
  invalid URI; the acceptance requires binding NULL in that case rather than
  storing a mangled string (a bare-emitted broken URL is worse than no URL).
- **security_relevant: true** — touches a documented untrusted-content trust
  boundary; invites a `/redteam` pass.
- **Alternatives considered:** Options A/B/C from
  `06-module-infochat-collector.md#F1`. Option A (this ticket) chosen; B and C
  recorded in out_of_scope with the reason each was rejected.
- Full reports: `.reviews/deep-review/full-2026-06-23-0957/` (`00-summary.md`
  first).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-433-normalize-post-title-url-at-ingest.md
```
