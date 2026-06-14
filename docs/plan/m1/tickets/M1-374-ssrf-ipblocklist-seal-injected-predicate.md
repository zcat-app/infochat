---
id: M1-374
title: "ssrf: seal IpBlocklist and drive the test loopback carve-out through an injected predicate instead of subclassing"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 12
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The blocklist ranges, IPv6 transition-form decodes, and DNS-pinning behavior — unchanged; this ticket changes the extensibility surface, not the policy.
  - The package-private Supplier<Set<InetAddress>> host-interface overload (M1-026) — unchanged; only the isBlocked-override carve-out is replaced.
acceptance:
  - "IpBlocklist becomes final (cannot be subclassed). The test loopback carve-out is supplied through an injected mechanism (e.g. an optional loopback-permitting predicate / boolean) on the existing package-private constructor, NOT by overriding isBlocked. No production construction path enables the carve-out."
  - "Every test double that today extends IpBlocklist (LoopbackPermittingBlocklist in ssrf/collector/provider plus the inline subclasses in the collector fetcher + nostr tests and the provider source/command tests) is converted to the injected carve-out; no `extends IpBlocklist` remains in the tree."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (convert LoopbackPermittingBlocklist)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher (convert inline subclasses + LoopbackPermittingBlocklist)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr (convert inline subclasses)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source (convert inline subclasses)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command (convert inline subclasses)
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
redteam_audits: []
---

# M1-374: seal IpBlocklist, inject the test carve-out

## Context

Deep-review v7 (opus-48) ssrf finding **F1**. Verified at source 2026-06-14:

`IpBlocklist` (`infochat-ssrf/.../IpBlocklist.java:93`) is a `public`,
non-`final` class with an overridable `isBlocked`, opened solely so cross-module
test doubles can carve out the loopback range. No production override exists, but
the type system does not prevent a future production subclass from silently
re-opening a blocked range on a security-critical class.

**Effort vs value (honest — read before scheduling):** the carve-out is consumed
by **~18 test files across all three modules** (`grep "extends IpBlocklist"`:
`LoopbackPermittingBlocklist` in ssrf/collector/provider plus inline subclasses
in the collector fetcher/nostr tests and the provider source/command tests).
Sealing the class means converting every one of them to the injected predicate.
This is a medium-effort, cross-module test refactor for a **defense-in-depth**
gain with **no live vulnerability** (no production subclass). It is the lowest-value
item in the v7 set and a reasonable **won't-fix / much-later** candidate — kept on
the board for completeness, not recommended before beta.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- `files_budget` is set to 12 to reflect the cross-module test fan-out; if the
  real touched-file count exceeds it, escalate→refine to raise the budget rather
  than narrowing scope (all sites must convert or `extends IpBlocklist` lingers).
