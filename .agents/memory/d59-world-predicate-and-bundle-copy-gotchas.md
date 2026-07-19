---
name: d59-world-predicate-and-bundle-copy-gotchas
description: "D59 subscription model: the world predicate has ~9 sites (the periodic digest is DigestPostCollector, NOT EligiblePostQuery); bootstrap-origin fixtures leak across scopes; change reply copy by editing a key's VALUE, never by adding a concatenated key."
metadata:
  type: project
---

Durable gotchas from shipping the D59 subscription model (every `source` row is
`source_origin ∈ {bootstrap, user}`; a scope's world is "live non-excluded
bootstrap sources OR the scope's subscriptions").

- **The periodic group digest is `DigestPostCollector`, NOT `EligiblePostQuery`.**
  `EligiblePostQuery` serves on-demand `/summary`. A ticket that enumerates
  "the query classes" from memory will miss sites: the real count was **9** —
  EligiblePostQuery (×3), DigestPostCollector (×2 SQL), the chat tools,
  GetPostTool, SaveCommandHandler (DM-world and group-world legs),
  UnfollowTagCommandHandler seed, SummaryCommandHandler steer. A partial flip
  yields "search-visible but unfetchable" posts. The chat tools share
  `SearchPostsTool.worldPredicateSql(alias)` so the privacy predicate cannot
  drift per site — reuse it rather than hand-writing the predicate again.
- **Cross-class test-isolation hazard:** a `bootstrap`-origin fixture source is
  visible to EVERY scope under the world predicate, so a leftover pollutes
  other classes' scope-isolated assertions. Any test class seeding a bootstrap
  source needs `@AfterEach` cleanup, not just `@BeforeEach` (and delete
  `saved_post` before the source — it holds the FK).
- **Backfills default fail-CLOSED.** The migration backfilled existing rows to
  `'user'`, not `'bootstrap'` — a redteam fix; the other direction would have
  permanently publicized every pre-upgrade privately-added source. The loader's
  ON CONFLICT promote runs in the same boot (Flyway @Priority 100 before the
  @Startup loader @Priority 200), so there is no dark window.
- **Grants shape the query.** The provider's grant on `source_exclusion` is
  SELECT/INSERT/DELETE with **no UPDATE**, so an existence probe must NOT use
  `FOR UPDATE` (that needs UPDATE privilege). Races are absorbed by
  `ON CONFLICT DO NOTHING`.

**Bundle-copy rule (applies to every copy-change ticket):** to change a reply's
wording, edit the EXISTING key's VALUE in place — do NOT add a new key
concatenated onto the composed reply. Command-handler tests assert
`assertEquals(bundleLoader.get(KEY)[+MessageFormat], reply.text())`, so a value
edit keeps them green (both sides re-read the same key) while a new
concatenated key breaks every exact-equality assertion. MessageFormat keys
(`{0}`) need `''` doubled; raw `bundleLoader.get` keys use a single `'`.
See [[bundle-key-needs-cs-twin]] for the mandatory cs twin.
