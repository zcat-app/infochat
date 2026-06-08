---
id: M1-215
title: "Core hygiene: sanitized key in getState WARN, full-C0 sanitize, single AuditLogWriter constructor"
status: done
created: 2026-06-07
last_updated: 2026-06-08
escalations:
  - date: 2026-06-08
    reason: loop
    resolution: |
      Resolved by rebasing the branch onto current main, NOT by any
      change in this ticket. The failing UrlProbeRelayTest is a
      pre-existing main-side flake (outside this ticket's diff): a
      vanilla Vertx.vertx() FakeRelayServer created after a Quarkus
      app booted in the same JVM misroutes WS upgrades to a null
      requestHandler, so the handshake never answers and the dial
      times out at the 10s relay-connect cap. M1-184 (merged to main
      after this branch forked from d913efb) already diagnosed the
      same root cause and rewrote FakeRelayServer as a framework-free
      ServerSocket handshake responder. This branch was 9 commits
      behind main and missed that fix; rebasing picks it up. The
      independently-written FakeRelayServer rewrite on this branch was
      dropped during the rebase in favour of M1-184's reviewed,
      merged version (surgical-changes: the fixture is not in this
      ticket's files_scope).
    reviewer_verdict_excerpt: |
      N/A — two consecutive full-suite mvn verify failures with the
      same root cause, both AFTER the refine fix compiled clean:
      UrlProbeRelayTest.relayProbeReportsSuccessForReachablePolicyAllowedRelay
      (infochat-provider, M1-203's relay probe; outside this ticket's
      diff) times out at ~10.1s under full-suite load in both runs
      ("expected: <true> but was: <false>") yet passes in isolation
      in 1.087s. 720/721 provider tests green; this ticket's own new
      tests pass. The test's 2s connect/2s handshake budgets against
      the local FakeRelayServer appear too tight under full-suite
      load in this environment.
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-review build failure. Removing AuditLogWriter's no-arg
      constructor breaks two test SUBCLASSES (implicit super() calls):
      infochat-provider/src/test/java/app/zcat/infochat/provider/group/FailingAuditLogWriter.java:18
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/TargetedFailingAuditLogWriter.java:24
      Both are outside files_scope (all-infochat-core). The draft-time
      sweep grepped `new AuditLogWriter(` only, so `extends` sites were
      missed (M1-175/M1-160 call-site-sweep class of error).
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: the 'named test or the WARN provably uses the same sanitized form' phrasing allows an inspection-only resolution with no test. Consider tightening to require a named test method (e.g., 'ThrottledAdminNotifierTest.getState_warnLogsSanitizedKey passes') to make the criterion unambiguously runnable."
    - "SECURITY-FLAG-CONSISTENT: the C0/log-injection hardening in acceptance item 2 (preventing ANSI escape forgery on terminal scrapes) is typically classified as security-relevant. The deliberate declaration is noted and documented in the ticket, so this is informational only."
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/FailingAuditLogWriter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/TargetedFailingAuditLogWriter.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SafeLog — audit finding D12 ("error drops stack trace") dissolved at re-grounding: the class javadoc documents the throwable drop as the deliberate D37 posture ("The original Throwable is never passed to the underlying SLF4J logger — no stack trace, no message body") and it already emits the class-name-only, depth-capped, suppressed-walking cause chain the suggested fix asked for; untouched
  - the V5 verb-catalogue comment block — immutable applied migration; the living catalogue (design 02-schema §2.1.8) is M1-210's leg
  - AuditAction javadoc cleanup — the file is in M1-195's files_scope; not touched here
  - notifyOnce's throttle-window semantics and the admin_notification_state schema — only the two named logging/sanitize sites and the constructor shape change
  - RedactionHook and redaction behavior — M1-210 carries its javadoc contract note; AuditLogWriter's redact-before-INSERT flow is unchanged
acceptance:
  - "ThrottledAdminNotifier.getState's SQLException WARN logs the sanitized key: a caller-supplied key containing CR/LF cannot place a raw line break in the WARN line — named test or the WARN provably uses the same sanitized form the SQL already uses (today the catch logs the raw key while safeKey is computed six lines above for the query)"
  - "sanitize neutralizes the full C0 control range, not only CR/LF/NUL: a key or message carrying ESC (0x1B) or another C0 control reaches the log/DB sinks with the control character replaced — named test (today only \\r, \\n, \\0 are replaced, so ESC passes through and an ANSI escape sequence could forge terminal output on an operator scrape)"
  - "AuditLogWriter has exactly one constructor (the injected form): the no-arg CDI/field-injection path is gone, CDI bean discovery still resolves the bean (constructor injection), the two direct construction sites keep compiling unchanged (AuditLogWriterIT, RetryDigestCommandTest's field assignment — both already use the injected form), and the two test subclasses missed by the draft-time sweep (FailingAuditLogWriter, TargetedFailingAuditLogWriter — both fully override write(), so the hook is never exercised) chain to the injected constructor with an identity hook; re-grounded sweep covers both `new AuditLogWriter(` and `extends AuditLogWriter`, four sites total"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/FailingAuditLogWriter.java — chain to the injected constructor with an identity hook (constructor-shape follow-through only; the throwing write() override is untouched)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/TargetedFailingAuditLogWriter.java — same identity-hook super() chain; the now-stale javadoc sentence about the no-arg super constructor is corrected
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D37
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 204
      removed: 37
revisions:
  - date: 2026-06-08
    reason: budget-breach refine — draft-time constructor sweep grepped `new AuditLogWriter(` only and missed two `extends AuditLogWriter` test doubles in infochat-provider whose implicit super() calls break when the no-arg constructor is removed; widen files_scope with those two files, correct acceptance item 3's sweep claim, and authorize the two test-double modifications in test_plan
    prior_values: |
      files_scope:
        - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
        - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
        - infochat-core/src/test/java/app/zcat/infochat/core/notifier
        - infochat-core/src/test/java/app/zcat/infochat/core/audit
      acceptance[2]: "AuditLogWriter has exactly one constructor (the
        injected form): the no-arg CDI/field-injection path is gone, CDI
        bean discovery still resolves the bean (constructor injection),
        and the existing non-CDI construction sites keep compiling
        unchanged — draft-time sweep found exactly two, both already
        using the injected form (AuditLogWriterIT, RetryDigestCommandTest's
        field assignment)"
      test_plan had no `modifies:` key.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-215: Core hygiene — getState WARN key, full-C0 sanitize, single AuditLogWriter constructor

## Context

Three of the four core lows from the audit's misc bucket (unified D9,
D10, D11 — `deep-code-review/v2/UNIFIED.md` §2), re-grounded
2026-06-07:

1. **D9 (low).** getState's SQLException WARN interpolates the RAW
   caller-supplied key even though the method computes safeKey for the
   query six lines earlier — the one unsanitized sink in a class whose
   whole point is the sanitized ADMIN-NOTIFY scrape contract.
   (opus-47 rated this high; the audit's calibrated severity LOW is
   binding — exploitation needs an SQLException AND attacker-keyed
   input, and today's keys are mostly internal constants.)
2. **D10 (low).** sanitize replaces only CR/LF/NUL; ESC and the rest
   of C0 pass through, leaving ANSI-escape forgery open on terminal
   scrapes of the log line.
3. **D11 (low).** Two constructors (no-arg CDI field-injection + an
   injected form documented "for non-CDI consumers") — two
   initialization paths for one dependency; a non-CDI caller using the
   no-arg form gets a null redaction hook. Collapse to constructor
   injection.

The fourth member (D12, SafeLog) **dissolved at re-grounding** — see
out_of_scope; the drop is recorded with evidence in the batch summary.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T33 under `deep-code-review/v2/` (core
  members; opus-48 core F4, kimi-folder core F3, opus-47 core F3).
- security_relevant is false deliberately: no spec sentence governs
  these sites — the legs anchor to ThrottledAdminNotifier's own
  documented scrape contract and D37's logging posture.
- Constructor-change call-site sweep (M1-175 precedent) done at draft
  time grepped `new AuditLogWriter(` only: two direct sites, both
  already passing a hook. The 2026-06-08 budget-breach refine
  re-grounded it with `extends AuditLogWriter` included: two test
  subclasses (FailingAuditLogWriter, TargetedFailingAuditLogWriter)
  rely on the implicit no-arg super() and must chain an identity hook
  — four sites total.
