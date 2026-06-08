---
id: M1-240
title: "infochat-collector: span-offset doc, unused asset-refresh fields"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The span-offset VALUES stored in quarantine.span_start/span_end — they are internally consistent char offsets (record→redact→store all use char units); do NOT change the computation, only the documentation that mislabels them.
  - The @Scheduled(every="{infochat.assets.refresh.<host>}") expressions — they are the real, sufficient binding to the refresh-interval keys and must remain (they already fail boot on a missing/malformed value).
  - The AssetSnapshotFetcher fetch logic and the asset sources — unchanged.
acceptance:
  - "Co-F3: the QuarantineDao class javadoc and the QuarantineRow record javadoc for span_start/span_end are corrected to state Java char (UTF-16) offsets, not 'byte offsets' (the values are Matcher.start()/end() char indices); no behavior change."
  - "Co-F4: the three unused @ConfigProperty Duration fields (coingeckoRefresh, krakenRefresh, bitfinexRefresh) plus their @SuppressWarnings and the justifying comment are removed, UNLESS a still-binding prior acceptance contract requires them — see Notes; if they are kept, the comment is corrected to cite that binding contract truthfully rather than 'a future runtime-tuning ticket'."
  - "If the fields are removed: the @Scheduled refresh-interval keys remain bound and boot still fails on a missing/malformed value (the sole, sufficient binding)."
  - "Existing collector tests stay green; mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/commands.md §Asset commands
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-240: infochat-collector — span-offset doc, unused asset-refresh fields

## Context

Two low-severity collector cleanups, grouped (same module, both
hygiene/no-runtime-behavior):

- `deep-code-review/v2.5/opus-48/06-module-infochat-collector.md#F3` (DRIFT):
  `QuarantineDao` javadoc and the `QuarantineRow` record document
  `span_start`/`span_end` as "byte offsets," but the stored values are
  `Matcher.start()`/`end()` — Java char (UTF-16) offsets
  (`Stage1Pipeline.java:335-341`). The column is internally consistent
  (record→redact→store all use char units), but the documented contract is
  false and would mis-address multi-byte content the moment someone slices
  bytes by it. Doc-only fix.
- `#F4` (SIMPLIFICATION): `AssetSnapshotFetcher` injects three
  `@ConfigProperty Duration` fields (`coingeckoRefresh`/`krakenRefresh`/
  `bitfinexRefresh`), each `@SuppressWarnings("unused")` and never read; the
  `@Scheduled(every="{...}")` expressions bind those keys independently, so
  the fields are dead speculative state.

## Acceptance

See frontmatter. In prose: correct the span-offset javadoc to "Java char
(UTF-16) offsets"; remove the three unused refresh fields (or, if a binding
contract requires them, correct the comment to cite it truthfully); keep the
`@Scheduled` key bindings; tests stay green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The offset computation, the `@Scheduled` expressions, and
the fetch logic are untouched.

## Notes

- **Co-F4 is a judgment call the implementer must resolve, not assume.** The
  fields' own comment says they exist "as the explicit binding the
  acceptance contract requires." Before deleting, check whether the
  originating ticket's acceptance still mandates them
  (`git log --oneline -- infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java`
  and read that ticket). If it does, removal would contradict a prior
  ticket — keep the fields and fix only the comment; if it does not (the
  more likely case — the `@Scheduled` expression already provides the
  fail-boot-on-missing-key property the comment claims the fields provide),
  remove them. Either resolution is acceptable; the status quo (dead fields
  + a comment pointing at a future ticket that will never read them in that
  shape) is not.
- Exact recommended edits are in the source findings.
