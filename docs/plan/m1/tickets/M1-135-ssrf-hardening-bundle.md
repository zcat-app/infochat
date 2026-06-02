---
id: M1-135
title: "SSRF hardening bundle"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 6
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the JVM-global PinnedDnsResolver de-globalization (WS-LOCK — WATCH/own milestone, not here)
  - the LLM-provider body cap (covered by M1-141 — different module)
  - typed SsrfPolicyException subclasses for UrlProbe/last-admin message-matching (covered by M1-151)
acceptance:
  - "IpBlocklist decodes all four embedded-IPv4 IPv6 forms (6to4 2002::/16, Teredo 2001::/32, NAT64 64:ff9b::/96, IPv4-compatible ::a.b.c.d) and routes them through isBlockedV4; the IpBlocklistTest matrix covers each"
  - "The M1-025 IpBlocklist(Set<InetAddress>) backwards-compat constructor is deleted; the two IpBlocklistTest call sites move to the Supplier form (§7 — no shims in greenfield M1)"
  - "IPv6 URL-literal hosts pass canonicalizeHost (brackets stripped before IDN.toASCII, re-added for the dial); ALLOW_UNASSIGNED is dropped from the security-critical path"
  - "The redirect loop builds/reuses one HttpClient and closes it; readBounded uses a virtual-thread factory rather than a per-call platform-thread ExecutorService; each readFuture.get is clamped to min(readTimeout, remaining-deadline)"
  - "Cross-origin redirects strip Authorization/Cookie/Proxy-Authorization; 3xx follow is narrowed to 301/302/303/307/308; UrlRedactor brackets IPv6; constructor timeout-validation messages name which knob"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
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

# M1-135: SSRF hardening bundle

## Context

Every SSRF-module finding is bundled into one ticket so they don't collide on
`SsrfGuardedHttpClient.java` / `IpBlocklist.java`:

- **A16** IPv6 transition ranges (6to4/Teredo/NAT64/IPv4-compatible) bypass the
  blocklist (only IPv4-mapped is decoded).
- **A26** the M1-025 backwards-compat constructor is a §7 shim violation.
- **C-IPV6-CANON** IPv6 URL-literals can't pass `canonicalizeHost` (IDN rejects brackets).
- **B-HTTP-CLIENT** per-call+per-redirect `HttpClient` never closed.
- **B-READBOUNDED-EXECUTOR** per-call platform-thread executor (project targets virtual threads).
- **B-DEADLINE-TOCTOU** body-read deadline overshoot by up to one read-timeout.
- **C-EXTRAHEADERS-REDIRECT** credential headers re-applied across cross-origin redirects.
- **C-SSRF-304** 304/305/306 treated as redirects.
- **C-IDN-UNASSIGNED / C-URLREDACTOR-IPV6 / C-SSRF-ERRMSG** small hardening + clarity.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The JVM-global resolver de-globalization (WS-LOCK) is a WATCH /
own-milestone item, not here. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A16, §A26, §B-HTTP-CLIENT,
  §B-READBOUNDED-EXECUTOR, §B-DEADLINE-TOCTOU, §C-IPV6-CANON, §C-SSRF-304,
  §C-EXTRAHEADERS-REDIRECT, §C-IDN-UNASSIGNED, §C-URLREDACTOR-IPV6, §C-SSRF-ERRMSG;
  `opus-47-full-handout.md` §F-SEC-06/09, F-PERF-05/06/10, F-MAINT-14/33/34/73/74.
