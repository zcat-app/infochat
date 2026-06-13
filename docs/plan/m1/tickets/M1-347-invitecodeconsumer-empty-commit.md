---
id: M1-347
title: "InviteCodeConsumer: drop the empty-transaction commit on the already-breached path"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The breach-audit semantics, the drop counter, and the stale-entry sweep — unchanged; the put(key, now) observation-time refresh and recordInviteDrop() stay unconditional.
  - The commit-ordering invariant on the FIRST-breach path (audit INSERT then commit then in-memory mark) — preserved; only the already-breached no-write path stops issuing an empty commit.
acceptance:
  - "In the over-threshold branch, conn.commit() is issued only when the audit INSERT actually ran (the first-breach case, !breachAudited.containsKey(key)). On the already-breached path (key present), no DB statement executes and no commit() is issued, so the durability-ordering comment above it (the in-memory mark runs after commit precisely so it fires only once the DB row is durable) reads true for every branch — there is no longer an empty-transaction commit beneath a comment about making a row durable when no row exists."
  - "breachAudited.put(key, Instant.now()) and recordInviteDrop() remain unconditional (preserving the documented observation-time refresh and the 'drop counter increments regardless of rate-limit state' contract). The BruteForceThresholdBreached return is unchanged. No externally-observable behavior changes (an empty commit() and a skipped commit() are equivalent for an autoCommit=false connection that issued no statements)."
  - "If any test asserts a specific commit count on this path via a connection spy, it is updated to follow the code; otherwise the change is invisible to tests. Existing InviteCodeConsumer tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-347: InviteCodeConsumer — drop the empty-transaction commit

## Context

Deep-review v5.5 (opus-48, `07-module-infochat-provider.md` F2) found that the
over-threshold branch issues `conn.commit()` unconditionally even when the
audit-INSERT `if` body did not run. **Verified at source 2026-06-14:**
InviteCodeConsumer.java:209-216 — `if (!breachAudited.containsKey(key)) {
insertAudit(...); }` is followed by an unconditional `conn.commit();`.

On the already-breached path (the common case for a sustained attack after the
first breach audit) no DB statement executes, yet `conn.commit()` runs on an empty
transaction directly beneath a long comment explaining that `put(key, now)` runs
*after* commit so "the in-memory mark only fires once the DB row is durable." For
that path there is no DB row to make durable, so the unconditional commit makes a
reader reconcile a documented durability ordering against a path where the
durability subject does not exist. Harmless to the database; a readability seam the
§Coding-style comment policy targets.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Gate the commit on the same `wrote` condition as the INSERT; keep `put` and
  `recordInviteDrop` unconditional. Purely readability + comment-accuracy; no
  behavior change.
