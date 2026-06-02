---
id: M1-158
title: "Documentation / stale-comment sweep (CT3)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 10
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-core/src/main/resources/db/migration
  - infochat-core/src/main/java/app/zcat/infochat/core
  - docs/design
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any code change beyond comment/javadoc edits (this is a documentation sweep)
  - editing applied migration SQL beyond comment blocks
acceptance:
  - "SsrfGuardedHttpClient class javadoc no longer claims ws/wss are rejected (the class supports them); HostInterfaceSet javadoc reflects the per-call Supplier (not the abandoned construction-time snapshot)"
  - "docs/design/09-reference.md DAG table sets (none) for the three sibling modules that do not depend on infochat-core"
  - "V7 grant-block comment drops the never-created infochat_listen role; V16 grant-block comment reflects the ThrottledAdminNotifier relocation to infochat-core"
  - "LangCommandHandler/FollowTagCommandHandler javadoc no longer describes a removed group-scope short-circuit; FetchScheduler dispatchKey + NormalizedPost.sourceId javadoc describe the actual per-tick opaque token (do not key state on it); the MicroProfileConfigReader 'null'-sentinel is documented or removed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §What lives in design notes
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-158: Documentation / stale-comment sweep (CT3)

## Context

Class javadoc, V*.sql comments, and design notes that described code-as-it-was
at an earlier ticket and now actively mislead: `SsrfGuardedHttpClient` ws/wss
rejection bullet, `HostInterfaceSet` snapshot semantics, the
`docs/design/09-reference.md` DAG, the V7 `infochat_listen` phantom role, the V16
notifier-relocation comment, the `LangCommandHandler`/`FollowTagCommandHandler`
group-scope short-circuit, the `FetchScheduler.dispatchKey` per-startup-vs-per-tick
javadoc, and `NormalizedPost.sourceId`. Pure comment/javadoc edits.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The MicroProfileConfigReader `"null"` sentinel may instead be
removed in M1-141 if that ticket lands first — coordinate; here it is the doc fallback.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-SSRF-JAVADOC, §C-DAG-DOC,
  §C-MIGRATION-COMMENTS, §C-LANG-JAVADOC, §A22 (NORMALIZEDPOST-JAVADOC), §C-MICROPROFILE-NULL;
  `opus-47-full-handout.md` §F-MAINT-31/32/33/34/35/36/11/12/43, CT3; `opus-47-only-handout.md` §M23-26/29/31.
- This is a pure-doc bundle; per CLAUDE.md it would normally be a `spec:`/`process:`
  commit, but it edits source-file comments/javadoc, so it stays a ticket.
