---
id: M1-373
title: "core+provider: reconcile internal null-handling with the null-marked contract (InfochatProfile, RateCapBucket.Key)"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/config/InfochatProfile.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - A repo-wide sweep for internal-to-internal null checks — deferred; this ticket fixes only the two sites the deep review named, to stay surgical.
  - InfochatProfile.resolveOrThrow's empty-list check and the IllegalStateException messages — unchanged; the empty-list case is a genuine boundary condition, only the null-parameter contract is reconciled.
acceptance:
  - "InfochatProfile.fromConfigName(String) and resolveOrThrow(List<String>) carry @Nullable (org.jspecify.annotations) on the parameters whose existing null-handling they keep, so the signature contract matches the code and the existing null-input unit tests (collector + provider InfochatProfileTest) stay valid. Their lenient behavior (Optional.empty() / IllegalStateException) is unchanged."
  - "RateCapBucket's private Key record drops the two Objects.requireNonNull guards (adapter, contactId): an internal-to-internal private record under the null-marked package needs no runtime null check (engineering rules §7/§7a). Its callers are unchanged."
  - "mvn -B clean verify from the repo root (NullAway:ERROR active) exits 0, proving the reconciled contracts compile cleanly."
test_plan:
  preserves:
    - all tests currently green on main (including the InfochatProfile null-input unit tests, which keep passing because the parameters become @Nullable rather than the null arm being removed)
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-373: reconcile internal null-handling with the contract

## Context

Deep-review v7 (opus-48) cross-cutting theme **CT1** (core F1 + provider F1).
Verified at source 2026-06-14. Two sites disagree with the null-marked
`app.zcat.infochat` package default in opposite directions:

- `InfochatProfile.fromConfigName` / `resolveOrThrow`
  (`infochat-core/.../config/InfochatProfile.java:56-67, 81-100`) have
  `name == null` / `profileChain == null` arms whose only callers are the
  collector + provider `InfochatProfileTest` null-input cases. Contract says
  non-null; code handles null. **Resolution: mark the parameters `@Nullable`**
  so the lenient behavior is the declared contract (this keeps the test-pinned
  null path legal).
- `RateCapBucket.Key` (`infochat-provider/.../messaging/RateCapBucket.java:472-477`)
  is a `private record` with `Objects.requireNonNull` on both components —
  exactly the internal-to-internal defensive check §7 narrows out and §7a says
  NullAway already enforces. **Resolution: drop the `requireNonNull` guards.**

Both are low-severity engineering-rule drift; bundled because they are the same
"reconcile null-handling with the contract" theme and each is a one-line change.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The two sites resolve in opposite directions on purpose: `@Nullable` where the
  lenient null behavior is genuinely wanted (config-boundary-adjacent reader with
  a test contract), removal where the null check is pure paranoia between trusted
  internal callers.
