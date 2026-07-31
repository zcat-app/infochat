---
id: M1-732
title: "groups.digest_mode and the hybrid category body: count + roll-up + headlines"
status: pending
created: 2026-07-30
last_updated: 2026-07-31
blocked_by:
  - M1-731
files_budget: 12
files_scope:
  - infochat-core/src/main/resources/db/migration/V67__group_digest_mode.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/StubGroupDataSource.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - docs/design/03-commands.md
complexity: high
risk: low
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - the /digest brief|normal|full command and its audit verb (M1-733)
  - delivery batching (M1-734)
  - CategoryRollupGenerator's prompt (M1-728)
  - the section cap (M1-721) and lead section (M1-725)
  - prominence ordering (M1-724)
  - /summary's render forms
  - the D17 degraded fallback and the zero-posts fixed reply (already single-message and mode-independent)
acceptance:
  - "V67__group_digest_mode.sql adds groups.digest_mode TEXT NOT NULL DEFAULT 'normal' CHECK (digest_mode IN ('brief','normal','full')) — additive NOT NULL with a default, metadata-only on PostgreSQL 11+ (same argument V44__group_digest_enabled.sql:15 makes for digest_enabled), so no table rewrite"
  - "A normal category section renders, in order: UPPERCASE header with the section's TRUE cluster count; the CategoryRollupGenerator synthesis; up to infochat.digest.category-headline-count (default 5) bare headlines, each a DisplayHeadline title plus its URL and NO prose; and the existing reply.summary.short.category_footer affordance"
  - "brief drops the headlines (header + roll-up + footer only); full keeps today's per-cluster prose with categoryItemCap lifted to Integer.MAX_VALUE"
  - "The header count is the section's FULL cluster count, not the number of headlines shown — a test pins a 13-cluster section rendering '13' while showing 5"
  - "Per-mode LLM call counts against one 8-category/40-cluster fixture: brief and normal issue exactly one CategoryRollupGenerator call per surviving section and ZERO SummaryProseGenerator calls; full issues one prose call per rendered cluster"
  - "DigestWorker.readGroupMetadata is a SQL-deserialization boundary: a digest_mode that is NULL or unrecognized resolves to normal, logged once at WARN — a test pins the fallback"
  - "StubGroupDataSource gains a digest_mode column, keeping its existing 3-arg constructor defaulting to normal so DigestWorkerClockTest needs no edit"
  - "DigestRenderer.render(List,String) is DELETED (no production caller — verified 2026-07-31: DigestWorker:213 uses renderSections; the only callers are tests); the 7 test call sites (DigestRendererTest x6, DigestRendererSectionsTest x1) are retargeted to the mode-aware renderSections"
  - "reply.digest.category.more and reply.digest.category.more_other are DELETED from en.properties AND cs.properties (D43 bilateral keyset — BundleLoaderTest) together with their BundleKeys constants REPLY_DIGEST_CATEGORY_MORE / _MORE_OTHER; their sole producer (DigestRenderer.java:153-165) goes with the lifted full cap"
  - "DigestRendererTest.renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay runs at mode full, so the sanitize-before-persist pin stays non-vacuous under the mode that still renders per-cluster prose"
  - "The ONE new config key infochat.digest.category-headline-count (default 5) is documented in docs/design/03-commands.md in the SAME diff as the @ConfigProperty (DocumentedConfigKeyParityTest, M1-708)"
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java — mode-parameterized section shape (normal = header + roll-up + headlines + footer; brief = no headlines; full = per-cluster prose, no overflow line)"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java — header true-count pin — 13-cluster section renders '13' while showing 5 headlines"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java — per-mode LLM call-count fixture, 8 categories / 40 clusters (brief/normal = 1 roll-up per section, zero SummaryProseGenerator calls; full = 1 prose call per cluster)"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java — readGroupMetadata digest_mode fallback — NULL and unrecognized values resolve to normal, WARN logged once"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-722
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-732: the hybrid digest body

> **Authored 2026-07-31** (was a skeleton from the M1-722 decompose,
> 2026-07-30). Two dispositions the decompose punted on were decided with the
> user on 2026-07-31: `DigestRenderer.render(List,String)` is **deleted**
> (verified dead in production — `DigestWorker:213` uses `renderSections`, the
> only callers are tests), and the orphaned
> `reply.digest.category.more` / `_more_other` keys are **deleted** (en+cs,
> D43 pair). Both are acceptance items.
>
> Sizing set at authoring: `migration_touch: true` (adds `V67`),
> `complexity: high` / `round_cap: 3` — the largest child and the one M1-722's
> three plan passes kept failing on — and `security_relevant: true`: the
> render path carries the M1-697 sanitize control (see §Notes), so the
> redteam gate runs before review. Because `migration_touch: true` forbids
> the `--parallel`/worktree start, this ticket runs **sequentially in the
> primary checkout**, and only once no other ticket is in flight (deferred
> 2026-07-31 until M1-736/M1-737 land).

## Context

Today every cluster a digest renders costs a prose paragraph, and a group's
only volume control is `/digest on|off` — the alternatives the spec names,
`/unfollow-tag` and `/unfollow-source`, narrow what the group can *retrieve*,
not merely what it is *sent*, because D59 scopes `/summary` and chat search to
the same world. So a group that finds the digest too long can lose topics
everywhere, switch it off, or endure it.

This ticket adds the `groups.digest_mode` column and makes the category body
mode-dependent. The user-facing command that sets the mode is M1-733; the
delivery batching is M1-734.

## The shape

```
SECURITY — 13 stories
  Three supply-chain attacks, an OpenSSL DoS, and a WordPress RCE.
  · CVE-2026-1234 — OpenSSL heap overflow  <url>
  · npm chalk/debug compromise  <url>
  · [3 more headlines]
  /summary security to expand
```

Roughly five lines and one LLM call per category, whether the category holds
three stories or three hundred. `brief` drops the headlines; `full` keeps
today's per-cluster prose. Measured against the same 8-category fixture:

| | Lines | LLM calls |
|---|---|---|
| today (`full`, cap 12) | ~380 | ~96 |
| `normal` | ~64 | 8 |
| `brief` | ~32 | 8 |

## Census: the dispatch seams

Keyed on **the method signatures this ticket changes**, because an identifier
grep cannot see a subclass overriding a method it never names in text — the
blind spot that cost M1-722 two of its three gates.

```bash
grep -rn "class .* extends DigestRenderer" --include=*.java infochat-provider/src
grep -rn "renderSections(\|renderer.render(" --include=*.java infochat-provider/src
```

| Changed signature | Subclass / caller | Disposition |
|---|---|---|
| `DigestRenderer.renderSections(List,String)` → 3-arg | `RecordingDigestRenderer.java:46-47` (`@Override`) | **fix** — take the mode param |
| ″ | `DigestWorker.java:213` | **fix** — pass the group's mode |
| ″ | `DigestRenderer.java:73` (`render`) | **fix** — thin join; the mode it delegates with must be STATED (see below) |
| ″ | `DigestRendererTest.java:206,285` | **fix** — call sites, mode arg added |
| ″ | `DigestRendererSectionsTest.java:75,95,127,160,176,197,211` | **fix** — call sites, mode arg added |
| `DigestRenderer.render(List,String)` | `DigestRendererTest.java:60,89,112,135,151,175,176` | **DECIDE** — see the blocker below |

**The signature is REPLACED, never overloaded.** An overload leaves every
`@Override` silently bound to a method nothing calls, and the resulting
failures surface as unrelated-looking degrades (`DigestWorkerClockTest:86`),
not as compile errors.

## The blocker that failed M1-722's third plan pass

`DigestRendererTest` has **six** `renderer.render(posts, "en")` call sites,
none of which M1-722 enumerated, and their assertions are exactly what the
mode change inverts:

- `:102-120` `overflowLineNowSteersToSummaryFull` — sets
  `renderer.categoryItemCap = 2`, asserts
  `result.contains("+2 more — /summary ai --full to see them")` and
  `assertEquals(2, proseGenerator.callCount())`.
- `:122-141` `overflowForOtherBucketSteersToBareSummaryFull` — same shape.
- `:52-68` `render_producesLocalizedProse` — asserts
  `result.contains("LLM digest summary for cluster")` and
  `callCount() > 0`.

`normal`/`brief` render zero per-cluster prose; `full` (cap lifted to
`Integer.MAX_VALUE`) emits no `+N more` line. **There is no mode assignment for
`render()` under which all three stay green**, and `render(List,String)` has no
production caller at all — its only callers are these tests plus
`DigestRendererSectionsTest:130` (verified again 2026-07-31:
`DigestWorker:213` calls `renderSections` directly, and `DigestWorker:217`
carries a "NEVER call render() after renderSections()" comment).

**DECIDED 2026-07-31 (user): `render(List,String)` is DELETED.** The seven
test call sites are retargeted to the mode-aware `renderSections` — the
three inverted assertions above are rewritten in their M1-722-item-17 shape
against `full` (prose uncapped, no overflow line) or dropped where `normal`
coverage supersedes them. Pinned in `acceptance`.

## Also orphaned by this change

With `full`'s cap lifted and `normal`/`brief` rendering no items,
`DigestRenderer.java:153-165` is the sole producer of
`reply.digest.category.more` / `reply.digest.category.more_other`
(`en.properties:836,838`, `cs.properties:634,636`,
`BundleKeys.REPLY_DIGEST_CATEGORY_MORE` / `_MORE_OTHER`). Both keys lose their
last caller in every mode. **DECIDED 2026-07-31 (user): DELETE both keys and
their `BundleKeys` constants in this diff.** D43 bilateral keyset: the
`en.properties` deletion needs its `cs.properties` twin or `BundleLoaderTest`
fails.

## Acceptance

Authored 2026-07-31 into the frontmatter; the decompose's carried-forward
commitments behind them:

- `V67__group_digest_mode.sql` adds `groups.digest_mode TEXT NOT NULL DEFAULT
  'normal' CHECK (digest_mode IN ('brief','normal','full'))`. Additive NOT NULL
  with a default is metadata-only on PostgreSQL 11+ (the argument
  `V44__group_digest_enabled.sql:15` makes for `digest_enabled`), so no table
  rewrite. Renumbered `V66` → `V67` on 2026-07-31: M1-736 merged `V66__post_tagger_sweep.sql` first; `V67` is free as of 2026-07-31.
- A `normal` category section renders, in order: UPPERCASE header with the
  section's TRUE cluster count; the `CategoryRollupGenerator` synthesis; up to
  `infochat.digest.category-headline-count` (default 5) bare headlines, each a
  `DisplayHeadline` title plus its URL and NO prose; and the existing
  `reply.summary.short.category_footer` affordance.
- The header count is the section's FULL cluster count, not the number of
  headlines shown — a test pins a 13-cluster section rendering "13" while
  showing 5.
- Per-mode LLM call counts against one 8-category/40-cluster fixture: `brief`
  and `normal` issue exactly one call per surviving section (the roll-up) and
  ZERO `SummaryProseGenerator` calls; `full` issues one per rendered cluster.
- `DigestWorker.readGroupMetadata` is a SQL-deserialization boundary: a
  `digest_mode` that is NULL or unrecognized resolves to `normal`, logged once
  at WARN. A test pins the fallback.
- `StubGroupDataSource` gains a `digest_mode` column, keeping its existing
  3-arg constructor defaulting to `normal` so `DigestWorkerClockTest` needs no
  edit.
- The ONE new config key `infochat.digest.category-headline-count` (default 5)
  is documented. `DocumentedConfigKeyParityTest` (M1-708) requires the doc edit
  and the `@ConfigProperty` in the SAME diff.

## Out-of-scope

Declared in the frontmatter: the `/digest brief|normal|full` command and its audit
verb (M1-733); delivery batching (M1-734); `CategoryRollupGenerator`'s prompt
(M1-728); the section cap (M1-721) and lead section (M1-725); prominence
ordering (M1-724); `/summary`'s render forms; the D17 degraded fallback and the
zero-posts fixed reply, both already single-message and mode-independent.

## Notes

**Where the mode type lives.** `files_budget` should account for a `DigestMode`
enum; the in-repo precedent is nesting it in an in-scope class
(`DigestWorker.SlotOutcome` at `DigestWorker.java:127`,
`DigestRenderer.RenderedSection` at `DigestRenderer.java:477`).

**The sanitize control travels with the headline (verified).**
`DisplayHeadline.of(Post, LlmOutputSanitizer)` is `public static` and reachable
from `provider.digest`; it runs flatten → sanitize → truncate with ONE author's
field per call, so reusing it carries the M1-697 redaction onto the new
headline path. `DegradedDigestRenderer.java:65-68` is the empty-headline idiom
(the helper returns `""` when a post has no renderable text, and callers MUST
omit the separator too). Note M1-729 (merged 2026-07-30) changed
`DisplayHeadline.java` — author against current `main`, not against M1-722's
citations.

**A control that degrades silently if unwatched.** Under `normal`, the
sanitize-before-persist pin
`DigestRendererTest.renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay`
(`:183-216`) passes **vacuously** — the injected `/grant-admin` prose never
enters a section, so the test `test_plan.preserves` calls "the byte-identity
proof" keeps its name and loses its teeth. Engineering-rules §10 ("Tests are
controls too") applies — **resolved at authoring (2026-07-31): the pin runs at
mode `full`** (an acceptance item), the only mode that still renders
per-cluster prose.
