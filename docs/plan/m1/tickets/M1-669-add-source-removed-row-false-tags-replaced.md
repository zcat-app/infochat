---
id: M1-669
title: "/add-source on a removed source must not claim tags replaced"
status: done
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Changing what `/add-source` DOES to a soft-deleted row. The
    UPSERT_SOURCE_SQL guard `WHEN ? AND source.deleted_at IS NULL` is
    deliberate — reviving a removed source is `/source-enable`'s job, and it
    asks for confirmation first ("About to revive removed source `X`. No
    subscriptions will be restored."). This ticket makes the REPLY and the
    AUDIT match the SQL's existing behaviour; it must NOT make `/add-source`
    clear `deleted_at`, revive the row, or replace tags on a removed row.
    Auto-revive would bypass that confirmation gate and is a separate
    decision, not a bug fix.
  - >-
    The three existing outcomes on a LIVE (non-deleted) row —
    FRESH_INSERT, SUBSCRIBED_EXISTING, ADMIN_TAGS_REPLACED — and their
    bundle strings. Their behaviour is correct and live-verified; the
    existing assertions covering them stay byte-for-byte unchanged.
  - >-
    The `source_subscription` insert (UPSERT_SUBSCRIPTION_SQL) and the
    `source_origin` DO-UPDATE exclusion. Subscribing a caller to a removed
    source is inert (a removed source yields no posts and is hidden from
    /list-sources); changing subscription semantics is out of scope.
  - >-
    AuditAction enum membership and the RedactionHook/audit-row shape.
    This ticket only changes WHETHER the ADD_SOURCE row is written in the
    no-op case, never the enum or the row's columns.
acceptance:
  - >-
    The NON-admin caller against a soft-deleted row is reported distinctly
    too, resolving to its own outcome (e.g. SUBSCRIBED_EXISTING_REMOVED)
    rather than SUBSCRIBED_EXISTING. Its reply must not imply a working
    feed and must name the remedy for THAT tier: /source-enable is
    bot-admin-only (SourceEnableCommandHandler gates on `!isAdmin` ->
    ERROR_ADMIN_ONLY), so telling a non-admin to run it would repeat the
    defect this ticket fixes. Like the admin removed-case, it writes no
    ADD_SOURCE audit row. SourceUpsertServiceIT and
    AddSourceCommandHandlerTest each cover it.
  - >-
    The two documented outcome enumerations are brought back in step with
    the code: `docs/spec/commands.md` §Source management (the "reply
    distinguishes outcomes" list plus its "in all three cases" sentence)
    and the `docs/design/03-commands.md` "Reply distinguishes outcomes"
    table both cover the soft-deleted outcomes and no longer state a stale
    count. These are orphans this diff creates — the enumerations are
    exhaustive as written and a new outcome makes them an undercount.
  - >-
    SourceUpsertService reports the removed case distinctly: UPSERT_SOURCE_SQL's
    RETURNING clause additionally yields whether the conflicting row is
    soft-deleted (e.g. `source.deleted_at IS NOT NULL AS was_removed` — the
    DO UPDATE branch never writes deleted_at, so the returned value is the
    pre-existing one), and the outcome selector at the
    `else if (actorIsBotAdmin)` branch resolves to a NEW
    `Outcome.ADMIN_EXISTING_REMOVED` (name at implementer's discretion)
    instead of ADMIN_TAGS_REPLACED when that flag is true.
  - >-
    SourceUpsertServiceIT.adminReAddOfRemovedSourceDoesNotClaimTagsReplaced
    passes — seeds a source row with `deleted_at` set and non-empty
    `bootstrap_tags`, calls upsert() as a bot admin with DIFFERENT tags, and
    asserts (a) the returned outcome is the new removed-case outcome, NOT
    ADMIN_TAGS_REPLACED, and (b) `bootstrap_tags` in the DB is unchanged —
    pinning reply and SQL to the same truth.
  - >-
    SourceUpsertServiceIT.adminReAddOfRemovedSourceWritesNoTagReplacementAudit
    passes — same fixture, asserts no new `audit_log` row with action
    ADD_SOURCE is written for the no-op (count before == count after). The
    audit currently records a privileged tag replacement that did not occur.
  - >-
    AddSourceCommandHandlerTest covers the new outcome's reply: it names the
    source and points at the remedy — `/source-enable <id>` — so an admin
    is not left believing the feed is now active. New bundle keys are added
    to BOTH `en.properties` and `cs.properties` (D43 bilateral keyset;
    BundleLoaderTest fails on a missing twin), and the existing
    `reply.add_source.admin_tags_replaced` string is left unchanged in both.
  - >-
    REGRESSION PIN — the live-row paths are untouched: an admin re-adding a
    NON-deleted existing source still gets ADMIN_TAGS_REPLACED with tags
    actually replaced and the ADD_SOURCE audit row still written; a
    non-admin still gets SUBSCRIBED_EXISTING; a brand-new URL still gets
    FRESH_INSERT plus the URL-visibility disclosure line.
  - "mvn -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - "infochat-provider/.../source/SourceUpsertServiceIT.java (2 new cases)"
    - "infochat-provider/.../command/AddSourceCommandHandlerTest.java (new outcome reply case)"
  preserves:
    - "infochat-provider/.../source/SourceUpsertServiceIT.java (existing outcome cases)"
    - "infochat-provider/.../command/AddSourceCommandHandlerTest.java"
    - "infochat-provider/.../command/AddSourceIT.java"
    - "infochat-provider/.../i18n/BundleLoaderTest.java"
spec_refs:
  - "docs/spec/commands.md §Source management"
  - "docs/spec/schema.md §Entities"
decision_refs:
  - "D38"
  - "D43"
reviews:
  - round: 1
    date: 2026-07-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 373
      removed: 36
escalations: []
revisions:
  - date: 2026-07-22
    reason: >-
      Pre-flight self-check refine, user-authorized at /m1-tick run start. The
      new bundle key acceptance item 4 mandates needs a declaration site:
      AddSourceCommandHandler reaches all 17 of its keys through BundleKeys, so
      BundleKeys.java is added to files_scope (budget 6 -> 7). The alternative
      considered and rejected was a local `private static final` key constant in
      the handler (the QuarantineCommandHandler:97 / PendingCommandHandler:50
      precedent), which keeps the 6-file scope but writes a ticket-scope
      reference into a permanent source comment.
    prior_values: |
      files_budget: 6
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/main/resources/bundles/cs.properties
        - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - date: 2026-07-22
    reason: >-
      Second refine, user-authorized mid-implementation after both findings
      survived falsification. (1) The non-admin-against-a-removed-row reply
      is the same defect in the other tier — "Subscribed." for a feed that
      is hidden from /list-sources and delivers nothing — and the tiers need
      different remedies because /source-enable is bot-admin-only
      (SourceEnableCommandHandler:156). (2) The spec and design outcome
      enumerations are exhaustive as written ("in all three cases"), so the
      new outcomes orphan them; both paths added to files_scope
      (budget 7 -> 9). Acceptance items added for both.
    prior_values: |
      files_budget: 7
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/main/resources/bundles/cs.properties
        - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-22
  verdict: WARN
  warnings:
    - >-
      lint-ticket.py PASS (0 blockers, 0 warnings). Self-check confirmed the
      ticket's code claims against source: UPSERT_SOURCE_SQL's
      `WHEN ? AND source.deleted_at IS NULL` guard is at
      SourceUpsertService.java:108, the unconditional
      `else if (actorIsBotAdmin)` outcome selector at :165-167, and the
      `(xmax = 0) AS was_inserted` RETURNING precedent at :111.
    - >-
      files_scope omitted BundleKeys.java, the declaration site the new bundle
      key requires; refined with user authorization (see revisions).
  blockers: []
---

# M1-669: /add-source on a removed source must not claim tags replaced

## Context

Found in the 2026-07-22 post-cutover live regression sweep on the isolated
test instance (SimpleX, admin contact 4). An admin ran:

```
/add-source https://hnrss.org/frontpage --tags ai --type rss --name LiveSweepFeed
```

against a source that had been **soft-deleted** in the 2026-07-15 Phase-D run
(`deleted_at` set, `bootstrap_tags={test}`). The bot replied:

```
Source already existed; bootstrap tags replaced.
```

but the DB still showed `bootstrap_tags={test}` afterwards — the replacement
never happened, and the source is still removed.

**Root cause (verified in source, not inferred).** Two sites disagree:

- `SourceUpsertService.UPSERT_SOURCE_SQL` replaces tags only
  `WHEN ? AND source.deleted_at IS NULL` — a deliberate guard so a removed
  row is not silently mutated.
- The outcome selector immediately after has no such condition:
  `if (row.wasInserted()) … else if (actorIsBotAdmin) { outcome =
  Outcome.ADMIN_TAGS_REPLACED; insertAuditRow(…); }`.

So for a bot admin against a soft-deleted row the service reports — and
audits — a tag replacement that the SQL intentionally skipped.

**Why it matters.** Three concrete consequences, in severity order:

1. **The admin is misled.** The reply reads as success, so the operator
   believes the feed is registered. It is not: a removed source is hidden
   from `/list-sources` and yields no posts. Nothing in the reply mentions
   `/source-enable`, the actual remedy.
2. **The audit log records a privileged action that did not occur** — an
   `ADD_SOURCE` row attributing a tag replacement to that admin. The audit
   log is the accountability record; a false entry is worse than none.
3. **It is inconsistent with every sibling command**, all of which handle the
   removed state correctly and were verified in the same sweep:
   `/source-disable` → "That source is not currently active. No action
   taken."; `/source-enable` → "About to revive removed source `hnrss.org`.
   No subscriptions will be restored."; `/remove-source` → "That source is
   already removed. No action taken." `/add-source` is the only one that
   pretends.

## Acceptance

See the YAML `acceptance:` list. The shape of the fix is: make the SQL's
existing truth visible to the outcome selector (RETURNING the removed flag),
add one outcome + one bilateral bundle string that names the remedy, and
suppress the audit row for the no-op. Behaviour on live rows is unchanged.

## Out-of-scope

See the YAML `out_of_scope:` list. Most importantly: do NOT make
`/add-source` revive the row. Reviving is `/source-enable`'s confirmed
action, and auto-reviving here would bypass its confirmation gate.

## Notes

- **Reproduction fixture:** any `source` row with `deleted_at IS NOT NULL`;
  the sweep hit it because `/remove-source` is a soft delete, so a source
  removed in an earlier session stays conflict-eligible for
  `ON CONFLICT (kind, identifier)` forever.
- **Live evidence** (test instance, 2026-07-22 15:11): reply
  "Source already existed; bootstrap tags replaced." vs
  `select bootstrap_tags from source where id='e7ba51ec-…'` → `{test}`
  after `--tags ai`.
- The `(xmax = 0) AS was_inserted` idiom already in the RETURNING clause is
  the precedent for returning upsert-branch facts to the service layer.
