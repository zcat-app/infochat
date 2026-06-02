---
id: M1-151
title: "Typed SSRF / error signals (UrlProbe + last-admin SQLSTATE)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by:
  - M1-144
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the broader SSRF bundle (M1-135) — this is only the typed-signal vs string-match angle
  - the V5 last-admin trigger definition (only the handler-side detection)
acceptance:
  - "UrlProbe maps SSRF failure modes by typed SsrfPolicyException reason (subclass or enum), not by message.startsWith(...) string prefixes"
  - "BanCommandHandler / RevokeAdminCommandHandler detect the last-admin trigger by SQLSTATE (RAISE … USING ERRCODE + getSQLState()), not by SQLException message substring"
  - "An IT confirms the last-admin branch fires against real PostgreSQL"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-151: Typed SSRF / error signals

## Context

Two fragile string-sniffing patterns: `UrlProbe.java:95-96` branches on
`message.startsWith("body read timeout"/"body read deadline")` from
`SsrfPolicyException`; `BanCommandHandler`/`RevokeAdminCommandHandler` detect the
V5 last-admin trigger by `e.getMessage().contains("last_admin_protection")`.
A reword of either message silently breaks the mapping.

## Acceptance

See frontmatter. Match on type (typed `SsrfPolicyException` reason) and on
SQLSTATE, not on text.

## Out-of-scope

See frontmatter. `blocked_by: M1-144` — the last-admin handlers are heavily
edited by the UserRepository sweep; rebase onto it.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-URLPROBE-MSG, §C-LASTADMIN-MSG;
  `opus-47-full-handout.md` §F-MAINT-82. Note: opus-47-full dropped C-URLPROBE-MSG;
  the master handout recovered it (`UrlProbe.java` lives under provider/source).
