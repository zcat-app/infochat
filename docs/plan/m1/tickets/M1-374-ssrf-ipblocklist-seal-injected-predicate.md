---
id: M1-374
title: "ssrf: seal IpBlocklist and drive the test loopback carve-out through an injected predicate instead of subclassing"
status: done
created: 2026-06-14
last_updated: 2026-06-19
blocked_by: []
files_budget: 30
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/pom.xml
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-collector/pom.xml
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-provider/pom.xml
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
  - "IpBlocklist becomes final (cannot be subclassed). The test loopback carve-out is enabled ONLY via a package-private constructor parameter (a boolean / loopback-permitting predicate) on IpBlocklist, NOT by overriding isBlocked. isBlockedAgainst is demoted from protected to private — it is no longer an extension seam."
  - "No main (production) source set in ANY module can construct a carve-out-enabled IpBlocklist: the carve-out is compiler-barred from production, not merely convention-barred. Concretely — the no-arg public constructor and the M1-026 Supplier overload both build the STRICT blocklist (carve-out disabled); the carve-out-enabling construction exists only in test sources. A grep of every `src/main` tree for carve-out-enabled construction returns empty by visibility, not by reviewer diligence."
  - "Cross-module test fixtures obtain a loopback-permitting IpBlocklist from a single shared test-only factory that lives in ssrf TEST sources (package app.zcat.infochat.ssrf, so it can reach the package-private carve-out constructor), exported to infochat-collector and infochat-provider via a Maven test-jar (maven-jar-plugin test-jar goal on infochat-ssrf; <classifier>test-jar</classifier> scope=test deps on the two consumers). The factory is the ONE definition of the test carve-out; the ~18 hand-rolled subclasses collapse to calls of it."
  - "Every test double that today extends IpBlocklist (LoopbackPermittingBlocklist in ssrf + collector/reddit plus the inline subclasses in the collector fetcher/nostr tests and the provider source/command tests) is converted to a call of the shared factory; no `extends IpBlocklist` remains anywhere in the tree."
  - "mvn -B clean verify from the repo root exits 0 — including the infochat-collector and infochat-provider IT suites, which now resolve the shared test fixture through the test-jar (gates the split-package / Quarkus-test-indexing risk noted in §Notes)."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (new shared test-only factory that builds the loopback-permitting IpBlocklist via the package-private carve-out constructor; exported via the ssrf test-jar)
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (delete LoopbackPermittingBlocklist; repoint SsrfGuardedHttpClientTest + SsrfGuardedHttpClientConcurrencyTest construction sites to the shared factory)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher (convert inline subclasses + delete reddit LoopbackPermittingBlocklist; repoint to the shared factory)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr (convert inline subclasses to the shared factory)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source (convert inline subclasses to the shared factory)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command (convert inline subclasses to the shared factory)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 27
      added: 313
      removed: 410
escalations:
  - date: 2026-06-19
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-review budget/scope breach. During implementation design it
      became clear the carve-out cannot be both (a) sealed from production by
      the type system and (b) reachable by cross-module test fixtures without
      a Maven test-jar, which touches 3 poms outside files_scope; and the real
      touched-file count (~26) exceeds files_budget 20. Resolved by refine
      (option B, user-decided 2026-06-19): widen files_scope with the 3 poms,
      raise files_budget 20 -> 30, and sharpen the acceptance to the
      type-system-enforced design.
clarity_check:
  date: 2026-06-19
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: files_budget 12 was knowingly lower than the ticket's own ~18-test-file estimate (~19 with IpBlocklist.java); clarity recommended bumping to 20 before start. Resolved by the budget refine below."
  blockers: []
revisions:
  - date: 2026-06-19
    reason: "pre-start budget refine — user-authorized at the clarity-WARN gate. files_budget 12 was knowingly under-set vs the ~19 files this cross-module seal+inject refactor provably touches (18 `extends IpBlocklist` sites + IpBlocklist.java); the clarity-reviewer explicitly recommended 20. Bumped 12 -> 20 to avoid a guaranteed mid-flight budget-breach escalation. No scope change — the touched set is unchanged; only the budget cap reflects it."
    prior_values: |
      files_budget: 12
  - date: 2026-06-19
    reason: "budget-breach refine (escalate -> refine, option B, user-decided). Design analysis showed the original acceptance is internally unsatisfiable in scope: AC1 asked for a package-private carve-out constructor + 'no production construction path enables the carve-out', but cross-module test fixtures (collector/provider, different packages) cannot reach a package-private ssrf constructor without either (i) a PUBLIC carve-out seam on the SSRF-guard class in main — which re-creates the convention-protected hole the ticket exists to remove — or (ii) a Maven test-jar sharing a test-sources factory, which touches 3 poms not previously in files_scope. The user chose (ii) the type-system-enforced design. Widened files_scope with infochat-ssrf/pom.xml, infochat-collector/pom.xml, infochat-provider/pom.xml; raised files_budget 20 -> 30 (real touched count ~26, headroom for cascade); rewrote acceptance items 1-3 to specify the package-private carve-out constructor + test-jar-shared test factory + compiler-enforced no-production-path invariant; added test_plan.adds for the new shared factory."
    prior_values: |
      files_budget: 20
      files_scope (poms not yet present):
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
        - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
        - infochat-provider/src/test/java/app/zcat/infochat/provider/source
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
      acceptance (3 items): final + injected mechanism on the existing
        package-private constructor; convert every extends IpBlocklist;
        mvn verify exits 0.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-19
    verdict: CLEAN
    base: 589ad59edb4a6ebd0d8261887734bdffb1c1d937
    head: "working tree (uncommitted impl on m1/M1-374-ssrf-ipblocklist-seal-injected-predicate)"
    verdict_file: docs/plan/m1/redteam/M1-374-2026-06-19.md
    out_of_model_count: 0
    note: |
      In-progress audit run between /m1-tick review APPROVE (round 1) and
      /m1-tick commit, opted in via --in-progress. Adversary saw the
      working-tree-vs-fork-point diff (the actual uncommitted implementation),
      not main...HEAD which holds only the ticket-spec refine. CLEAN: 0
      findings, 0 out-of-model. No remediation; ticket clear to commit.
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

- `files_budget` started at 12, was bumped to 20 at the clarity-WARN gate, and
  to 30 at the budget-breach refine below (real touched count ~26). The
  cross-module test fan-out plus the 3 poms is the driver; all `extends
  IpBlocklist` sites must convert or one lingers.

### Design decision (2026-06-19, option B — type-system-enforced seal)

The original AC1 ("injected mechanism on the existing package-private
constructor" + "no production construction path enables the carve-out") is
internally unsatisfiable in the original scope. `SsrfGuardedHttpClient` takes a
*concrete* `IpBlocklist`, so once the class is `final` there is no subclass or
wrapper — tests must pass a real, carve-out-enabled `IpBlocklist`. But the
collector/provider test doubles live in different packages and cannot reach a
package-private ssrf constructor. The two ways to bridge that:

- **(A) a public carve-out seam in `main`** (public constructor / `static
  forTestingPermittingLoopback()`): production never calls it today, but it is a
  PUBLIC carve-out on the security-critical SSRF guard. The type system does not
  stop a future production caller — this re-creates the exact convention-guarded
  hole the ticket exists to remove (just "public factory call" instead of
  "silent subclass"). Rejected.
- **(B) carve-out construction lives ONLY in test sources** (chosen): a
  package-private carve-out constructor in `main` (reachable only from the ssrf
  package), plus a single public test factory in ssrf **test** sources (same
  package, so it can call that constructor) exported to the two consumers via a
  Maven **test-jar**. Production in every module is *compiler-barred* from
  enabling the carve-out — the permissive factory is on no `main` classpath. The
  `final` keyword alone delivers "no silent subclass"; (B) additionally delivers
  "no production carve-out path at all," enforced by visibility. It also
  collapses the ~18 hand-rolled subclasses into one shared definition.

**Known risk gated by AC5.** The shared factory must sit in package
`app.zcat.infochat.ssrf` to reach the package-private constructor, and the ssrf
*main* jar already owns that package — so on the collector/provider test
classpath the package is *split* across the main jar and the test-jar. This is
legal on a non-modular classpath and a standard Maven idiom, but Quarkus test
indexing can be fussy; the collector + provider IT suites passing under `mvn
verify` is the gating proof. If that proves irreparable, fall back to (A).
