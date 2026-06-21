---
id: M1-424
title: "provider: honor profile-driven chat-memory retention (pi=30d)"
status: done
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/scheduler/ChatMemoryPruner.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler/ChatMemoryPrunerTest.java
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The pruner's SQL, the make_interval(secs=>?) binding, the sub-day-truncation guard, and the @Scheduled cadence key (infochat.chat.pruner.interval) — all correct, unchanged.
  - The canonical retention table in docs/design/02-schema.md §2.6.2 / §2.10 (90/90/30/90) — it is the source of truth this ticket aligns to, not edited.
  - infochat.chat.body-cap and any other infochat.chat.* key already declared in application.properties.
  - Making the TTL user-tunable — it is deliberately fixed (operator-only) per Invariant 9 / D40; this ticket only makes it correctly profile-driven, not user-configurable.
acceptance:
  - "infochat-provider application.properties declares infochat.chat.retention=PT2160H (90 days) at the base/laptop/vps/remote-llm level and %pi.infochat.chat.retention=PT720H (30 days), matching the docs/design/02-schema.md §2.10 retention table (laptop/vps/remote-llm 90d, pi 30d)."
  - "ChatMemoryPruner's @ConfigProperty for infochat.chat.retention drops its inline defaultValue, per the profile-driven-key convention (FetchScheduler.java:95-100 / AssetSnapshotFetcher.java §Profile-driven cadence: profile-driven keys live in application.properties without inline defaults)."
  - "A test asserts the pi profile resolves the retention horizon to 30 days (PT720H) and a non-pi profile resolves to 90 days (PT2160H) — i.e. the pruner no longer silently applies 90 days to every profile."
  - "The class javadoc and the inline comment in ChatMemoryPruner are corrected to describe the actual profile-driven horizons (90d laptop/vps/remote-llm, 30d pi) rather than the prior comment claiming a 720h Pi override that was never wired."
  - "docs/design/07-deployment.md line ~94 chat_memory TTL row is corrected from 30/30/14/30 to 90/90/30/90 to match the canonical 02-schema.md §2.10 table (the existing row duplicates the post-partition-retention row below it and is wrong)."
  - "ChatMemoryPrunerTest remains green; mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler/ChatMemoryPrunerTest.java (profile-resolved retention assertions)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
decision_refs:
  - D40
  - D37
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 72
      removed: 12
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-21
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: ChatMemoryPrunerTest.java is a pre-existing file being modified, but test_plan lists it under adds rather than modifies. Authorized by acceptance item #3; frontmatter classification inconsistency only."
  blockers: []
---

# M1-424: honor profile-driven chat-memory retention (pi=30d)

## Context

Deep-review full (2026-06-21) provider finding **F1**, reframed after
verify-at-source 2026-06-21.

The deep-review flagged the `ChatMemoryPruner` comment ("90 days default
(laptop/vps/remote-llm); Pi overrides to 720h (30 days)") as comment-rot describing
a non-existent config key. Verifying against the design shows the **opposite**: the
comment describes the *intended* behavior, but the **code never implements it**.

- `docs/design/02-schema.md` §2.6.2 / §2.10 mandates a **profile-driven** retention
  horizon: laptop/vps/remote-llm = 90 days, **pi = 30 days** (D40, Invariant 9).
- `ChatMemoryPruner` declares `@ConfigProperty(name="infochat.chat.retention",
  defaultValue="PT2160H")` (90 days) and `infochat.chat.retention` is declared in
  **no** `application.properties` file, with **no** `%pi` override. So every
  profile — including pi — silently uses the 90-day inline default. Pi retains chat
  memory 3× longer than the design specifies (a privacy-adjacent defect).

A secondary design inconsistency surfaced: `docs/design/07-deployment.md` line ~94
lists chat_memory TTL as 30/30/14/30 — identical to the `post` partition-retention
row directly below it (line ~95), i.e. a copy-paste error. The canonical chat
retention table is `02-schema.md` §2.10 (90/90/30/90); this ticket corrects the
07-deployment row to match.

## Acceptance

See frontmatter. The shape: declare the profile-driven values in
`application.properties` (base 90d + `%pi` 30d), drop the inline `defaultValue` per
the codebase's profile-driven-key convention, fix the now-misleading comment/javadoc
to match, correct the 07-deployment table row, and pin both resolved values with a
test.

## Out-of-scope

See frontmatter. No change to the pruner's SQL or scheduling, and no change to the
canonical 02-schema retention table.

## Notes

- Adjacent convention: `AssetSnapshotFetcher.java` §"Profile-driven cadence" and
  `FetchScheduler.java:95-100` — profile-driven keys carry NO inline `defaultValue`
  and resolve from `application.properties` per-profile blocks. Match that here.
- Testing profile-resolved config: the existing `ChatMemoryPrunerTest` is the home;
  a `@TestProfile`-style override (or asserting the resolved `Duration` under the
  `pi` profile) pins the 30d vs 90d split. Implementer picks the mechanism; the
  acceptance is that pi=PT720H and non-pi=PT2160H are both proven.
- This is the heaviest of the four 2026-06-21 deep-review tickets because it is a
  behavior change (Pi retention 90d→30d) plus a design-doc correction, hence
  risk: medium.
