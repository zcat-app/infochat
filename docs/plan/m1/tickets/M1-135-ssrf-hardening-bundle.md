---
id: M1-135
title: "SSRF hardening bundle"
status: done
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
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 403
      removed: 106
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-02
    category: INFO-LEAK
    severity: medium
    promise: |
      "DNS-resolved IPs are checked against a blocklist of private,
      loopback, link-local, multicast, CGNAT, and cloud-metadata ranges
      (notably `169.254.169.254` and IPv6 equivalents) plus the host's
      own non-loopback interfaces." (docs/spec/security.md §SSRF and
      outbound connections, lines 137-140)
    gap: |
      The new IPv6-transition-form decode in IpBlocklist routes an
      embedded IPv4 through isBlockedV4 only, which checks the static
      range table but NOT the host's-own-interface set. The
      host-interface check lives solely at the top of isBlocked
      (IpBlocklist.java:101, hostInterfacesProvider.get().contains(addr))
      and compares the as-supplied InetAddress. For 6to4 / Teredo /
      NAT64 / IPv4-compatible inputs the supplied address is a genuine
      16-byte Inet6Address (IpBlocklist.java:225-255), so it never
      equals the v4 interface entry in the set; the code then falls
      through to embeddedV4(...) -> isBlockedV4(embedded)
      (IpBlocklist.java:117-118), which has no host-interface
      consultation (IpBlocklist.java:121-164). An embedded IPv4 equal to
      one of the host's own public non-loopback interface IPs (VPN
      tunnel, container bridge, freshly-attached cloud EIP) is therefore
      not blocked. The IPv4-mapped form escapes this because the JDK
      normalizes ::ffff:a.b.c.d to an Inet4Address; the four new
      transition forms do not normalize and so expose the gap.
    repro: |
      Host has a non-RFC1918 interface IP, e.g. a cloud EIP 203.0.113.5,
      with a sensitive service bound to it. Attacker submits a URL whose
      host resolves to (or is the literal) 2002:cb00:7105:: (6to4 of
      203.0.113.5) — or the equivalent Teredo / 64:ff9b::cb00:7105 NAT64
      / ::203.0.113.5 IPv4-compatible spelling. isBlocked returns false
      (host-interface contains misses the Inet6Address; isBlockedV6
      misses; embeddedV4 decodes to 203.0.113.5; isBlockedV4 returns
      false because the public IP is in no static range and host
      interfaces are not re-checked), so the dial proceeds against an
      address the spec commits to blocking. The IPv4-mapped spelling of
      the same IP would be blocked, demonstrating the inconsistency.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-02
    verdict: FINDINGS
    base: 254ad1a22cc6a3b8f45178280bf78b98eb38ced0^
    head: 254ad1a22cc6a3b8f45178280bf78b98eb38ced0
    verdict_file: docs/plan/m1/redteam/M1-135-2026-06-02.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One medium INFO-LEAK finding: the IPv6-transition-form decode
      checks the embedded IPv4 against the static range table but not the
      host's-own-non-loopback-interface set, so a 6to4/Teredo/NAT64/
      IPv4-compatible spelling of a host public interface IP bypasses the
      host-interface clause (the IPv4-mapped form does not, because the
      JDK normalizes it to Inet4Address). Defense-in-depth gap against an
      explicit spec clause; medium because end-to-end exploitation also
      needs a non-RFC1918 host interface plus working transition-gateway
      return-routing (the latter out-of-model). FIXED IN-BRANCH before
      merge (per user direction): isBlocked now routes every decoded
      embeddedV4 through a host-interface check (isHostInterfaceV4); the
      original commit 254ad1a was not amended — the fix is a follow-up
      commit on the branch that squashes into the single M1-135 commit
      at merge. Five IpBlocklistTest cases added. Two OUT-OF-MODEL
      advisories recorded in the verdict file; neither converted to a
      ticket.
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
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
