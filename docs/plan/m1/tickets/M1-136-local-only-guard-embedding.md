---
id: M1-136
title: "local-only startup guard covers embedding endpoint + remote-embedding log"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  - docs/spec/llm.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the LlmRouter instanceof decoupling (covered by M1-141)
  - moving the guard to a Provider-side hook beyond a spec-reconciliation note
acceptance:
  - "When infochat.llm.local-only=true the guard scans infochat.embeddings.base-url and provider-name override keys (not only per-task base-urls), and fails startup if any points off-host"
  - "A test with local-only=true and a remote embedding base-url asserts startup fails"
  - "Switching the embedding provider to a remote service emits the spec-promised explicit confirmation log line on startup"
  - "The Collector-vs-Provider placement is reconciled with docs/spec/llm.md (a comment/spec note clarifying embedding generation runs in the collector ingest pipeline)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Embedding pipeline
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 331
      removed: 36
  - round: 2
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 331
      removed: 36
escalations:
  - date: 2026-06-02
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — docs/spec/llm.md does NOT match any files_scope
      entry (which lists only the two routing directories) and is not a
      lifecycle-exempt path, so the membership rule forces a FAIL. Every other
      check PASSED (test-integrity, out-of-scope, negative-space, acceptance
      items 1-5, spec-conformance, parameter-contract). The reviewer flags this
      as a ticket-internal conflict, not developer overreach: acceptance item 4
      names docs/spec/llm.md, out_of_scope item 2 carves out "a spec-
      reconciliation note", and Context says "the spec wording needs the note" —
      but files_scope omits docs/spec/. Resolution options: (1) refine the ticket
      to add docs/spec/llm.md to files_scope → clean APPROVE; (2) drop the
      docs/spec/llm.md hunk and satisfy item 4 via the code comment alone,
      leaving the stale "fails Provider startup" wording for a later spec: commit.
revisions:
  - date: 2026-06-02
    reason: "manual-verdict (round 1) rework — reviewer SCOPE-DRIFT-CHECK FAIL was a ticket-internal conflict, not developer overreach: acceptance item 4 names docs/spec/llm.md and out_of_scope item 2 carves out 'a spec-reconciliation note', but files_scope omitted docs/spec/. Refine adds docs/spec/llm.md to files_scope (the scope the acceptance/out_of_scope text already intended). No code change, no acceptance-semantics change, no files_budget change (still 4) — only the files_scope membership widens so the existing faithful spec note is in-bounds. Every other reviewer check (test-integrity, out-of-scope, negative-space, acceptance 1-5, spec-conformance, parameter-contract) already PASSED."
    prior_values: |
      files_scope (pre-refine, 2 entries):
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing
        - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
      status (pre-refine, transient): escalated (manual-verdict)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-02
    verdict: CLEAN
    base: main (merge-base d518ff7)
    head: 2d922bc
    verdict_file: docs/plan/m1/redteam/M1-136-2026-06-02.md
    out_of_model_count: 1
    note: |
      Adversarial audit of the startup-time local-only guard broadening
      (embedding base-url + provider-override key coverage) and the
      remote-embedding confirmation log. CLEAN: the diff operates only on
      trusted operator config (inside the security.md trust boundary), touches
      no untrusted-input surface, and faithfully delivers both docs/spec/llm.md
      promises. One OUT-OF-MODEL advisory (startup-only loopback / DNS-rebind /
      multi-A-record window) is explicitly accepted by the spec's "checked once
      at startup" wording and requires attacker-controlled DNS or malicious
      operator config (out of scope per TLS/MITM + trusted-config assumptions).
      No remediation ticket needed; a possible v2 hardening (per-call SSRF
      re-resolution for the LLM/embedding outbound path) is noted for the user.
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "Acceptance item 4 is a by-inspection criterion (adding a comment or spec note is verifiable only by reading the source); the reviewer should confirm the note is present in the diff."
  blockers: []
---

# M1-136: local-only startup guard covers embedding endpoint + remote-embedding log

## Context

`LlmRouterStartupGuard` scans per-task base-urls when
`infochat.llm.local-only=true`, but not `infochat.embeddings.base-url` or the
provider-name override keys. A local-only deployment with a remote embedding
endpoint silently ships post title+summary off-host with no startup failure —
the local-only commitment is the privacy invariant the spec sells operators. The
promised "explicit confirmation log line on startup" for a remote-embedding
switch is also missing. deepseek adds: the guard runs on Collector startup while
the spec text says Provider — reconcile (embedding runs in the collector ingest
pipeline, so placement matches code; the spec wording needs the note).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A12 (LOCAL-ONLY-GUARD, High);
  `opus-47-full-handout.md` §F-SEC-07; `opus-47-only-handout.md` §obs.1.
- Loci: `LlmRouterStartupGuard.java:96-103,183-204`; spec `docs/spec/llm.md:132-134`.
- Default profiles point embedding at loopback Ollama, so the gap is dormant
  until an operator sets a remote base-url.
