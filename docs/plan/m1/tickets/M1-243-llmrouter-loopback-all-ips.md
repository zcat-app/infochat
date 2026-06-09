---
id: M1-243
title: "LlmRouterStartupGuard: require every resolved IP to be loopback"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardLoopbackTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The boot-fail policy when local-only=true (the guard already fails boot on a non-loopback base-url) — unchanged; only the loopback decision primitive is hardened.
  - The mimo "drop DNS for a static loopback-literal set" direction — explicitly rejected (it trades away /etc/hosts alias detection and does not close the multi-record gap); see Notes.
  - The per-call HttpClient and the base-url parsing/normalization — unchanged.
acceptance:
  - "LlmRouterStartupGuard.isLoopback resolves the host via InetAddress.getAllByName(host) and returns true only when the result is non-empty AND every resolved address isLoopbackAddress(); a host with any non-loopback resolved address returns false (and thus fails boot under local-only). LlmRouterStartupGuardLoopbackTest asserts: a single loopback address passes; an empty/unresolvable result fails; and a multi-address result mixing loopback + non-loopback fails (closing the first-IP-only gap where a sibling public address would otherwise slip through)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardLoopbackTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-243: LlmRouterStartupGuard — require every resolved IP to be loopback

## Context

Source: `deep-code-review/v3/` UNIFIED-REPORT.md T3 (opus `04#F1` SECURITY,
RECONCILED against mimo `04#F1`).

`isLoopback` uses `InetAddress.getByName(host)`, which returns only the **first**
resolved address. The guard exists to fail boot when `local-only=true` and a
base-url is non-loopback — `isLoopback==true` is the "safe/on-host" verdict. A
multi-A-record host whose first record sorts loopback passes the guard while the
per-call `HttpClient` may connect to a sibling **public** address: the exact
silent post-body leak the guard is meant to prevent.

Two reviewers conflicted: opus says check *all* resolved addresses; mimo says
drop DNS for a static loopback-literal set. The report took opus's direction —
`getAllByName` + require every address loopback — because it closes the leak and
keeps `/etc/hosts` alias detection. See Notes.

## Acceptance

See frontmatter. In prose: switch to `getAllByName` and treat a host as loopback
only when the resolution is non-empty and *every* address is a loopback address;
a named test pins the single-loopback, empty, and mixed-result cases; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. The boot-fail policy, base-url parsing, and the per-call client
are unchanged. mimo's literal-set simplification is rejected.

## Notes

- mimo's startup-blocking concern (one DNS resolution blocks the boot thread on a
  slow resolver) is real but minor — one resolution, once, at boot — and is the
  acceptable cost of correctness. Note it; don't let it drive the design.
- `security_relevant: true` — a `/redteam` pass is appropriate; this guard is an
  SSRF/data-egress control.
</content>
</invoke>
