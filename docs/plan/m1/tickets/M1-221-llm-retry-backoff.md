---
id: M1-221
title: "LLM retry-once backoff: sleep before the single retry (M1-192 redteam F2)"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: 'Decision recorded...before implementation' is a process gate embedded as an acceptance criterion; it cannot be run or checked against test output. Move to §Notes or §Implementation checklist, or replace with a behavioral assertion (e.g. commit message footer includes a 'Design choice: (a)|(b)' line)."
    - "ACCEPTANCE-RUNNABLE item 4: The 'or by citing the existing worker structure in the implementation notes' fallback is a by-inspection form. Drop the OR clause and require either a named test or a concrete diff-verifiable structural assertion (e.g. the sleep call site is outside any @Transactional-annotated method boundary)."
  blockers: []
blocked_by: []
remediates: M1-192
files_budget: 18
files_scope: []
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the retry COUNT (stays at exactly one retry per docs/spec/security.md §Failure handling — this ticket changes only the timing of that retry)
  - the stage-specific failure paths themselves (INFRA_FAILURE → READY-with-Stage-1 / quarantine posture) — unchanged
  - provider-side LLM consumers (SummaryProseGenerator, ChatAgent, LlmTranslationProvider) — they have no retry-once machinery today; adding retries there is a different ticket
  - the adapter's exception types and LlmHttpSupport IF the fixed-sleep outcome is chosen (only the header-honoring outcome may touch the adapter)
  - eval-package files whose loggers never receive a Throwable (EmbeddingMetadataStartupGuard, Stage1Worker, Stage2VerdictHandler, StartupReleaseOnStage2FailureWarn, TagVocabulary) — no SafeLog gap there; their jboss-logger style is untouched
  - raw-Throwable logging sites outside the collector eval package (fetchers, stream sources, provider modules) — outside both audits' GAP scope
acceptance:
  - "Decision recorded (in the ticket body before implementation, flagged in the commit message): the sleep is either (a) Retry-After-honoring — the adapter exceptions regain a CLAMPED retryAfterMs (ceiling ≤ 30s, hostile/overflow header values clamped, the M1-192 audit's unclamped-parse defect must not return) and the workers sleep on it when present, falling back to the fixed delay when absent — or (b) a fixed/jittered delay with no adapter changes"
  - "Stage2Worker's single retry no longer fires immediately: a named test asserts a measurable configurable delay (default on the order of ~2s, test-tuned smaller) between attempt 1 and attempt 2 on a rate-limited (429/503-shaped) failure"
  - "TaggerWorker and EntityExtractorWorker apply the same sleep-before-retry shape where their retry-once paths re-invoke the LLM, each pinned by a named test (their non-LLM retry shapes — schema-garbage / zero-valid — are out of band and unchanged)"
  - "The sleep does not hold a DB transaction or pinned platform thread open: the delay runs on the worker's virtual thread outside any transactional boundary, asserted by test or by citing the existing worker structure in the implementation notes"
  - "SafeLog adoption (redteam 2026-06-07, INFO-LEAK medium): every catch block in Stage2Worker, TaggerWorker, and EntityExtractorWorker that passes the caught Throwable to the logger — `LOG.warnf(e, ...)` at Stage2Worker.java:224, TaggerWorker.java:209 and :307, EntityExtractorWorker.java:198 and :251 (line numbers as of the refine) — logs via the existing `SafeLog` utility (infochat-core `app.zcat.infochat.core.log.SafeLog`) instead, upholding docs/spec/security.md §Secrets handling — User content in exceptions: 'The original `Throwable` is never passed to the underlying SLF4J logger.'"
  - "Mechanical check: `git grep -n 'warnf(e' -- infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2Worker.java infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java` returns zero matches"
  - "SafeLog sweep (redteam 2026-06-07 second audit on the committed diff, INFO-LEAK medium; user-directed in-branch fix): every remaining collector eval-package site that passes a caught Throwable to the logger — EmbeddingWorker.java:170 and :267, Stage1Pipeline.java:496, ReEvaluationJob.java:106, :113 and :476, ReadyPromoter.java:122 and :129, PerSourceUnknownTracker.java:55 and :110, AdminReviewTtlJob.java:63 and :70 (line numbers as of the audit) — logs via the existing `SafeLog` utility instead, upholding docs/spec/security.md §Secrets handling — User content in exceptions: 'The original `Throwable` is never passed to the underlying SLF4J logger.'"
  - "Mechanical check: `git grep -nE 'LOG[.](warnf|errorf|infof)[(](e|cause|ex),' -- infochat-collector/src/main/java/app/zcat/infochat/collector/eval/` and `git grep -nE 'LOG[.](warn|error|info)[(].*, (e|cause|ex)[)];' -- infochat-collector/src/main/java/app/zcat/infochat/collector/eval/` both return zero matches"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test (worker backoff tests)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 472
      removed: 30
  - round: 2
    date: 2026-06-07
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 673
      removed: 68
  - round: 2
    date: 2026-06-07
    verdict: OVERRIDE-APPROVE
    checks:
      # carried through from the overridden MANUAL verdict; the
      # scope_drift FAIL stands as the reviewer reported it — the
      # verdict alone carries the override.
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
overrides:
  - date: 2026-06-07
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — "Must-shrink (round N >= 2,
      engineering-rules-verbatim.md §8 Round-N must-shrink): the round-2
      diff grew along ALL THREE dimensions vs round 1 (files 12 > 11,
      lines added 673 > 472, lines removed 68 > 30). The rule's only
      exception requires the round-(N-1) REWORK to have explicitly
      authorized a growth-causing refactor ... round 1's verdict was
      APPROVE with zero REWORK items ... so the exception cannot apply.
      Mechanical FAIL."
    user_justification: |
      The growth is mandated by the user-confirmed redteam-finding
      refine on this same ticket (escalations 2026-06-07, revisions
      snapshot): the in-progress audit added the SafeLog acceptance
      items (5 and 6) plus the audit verdict file, so the round-2 diff
      necessarily exceeds round 1 along every dimension. Shrinking
      would mean dropping user-confirmed acceptance — the rules trade
      the reviewer itself refused to make. Every substantive check is
      PASS (test integrity, out-of-scope, acceptance all 7 items, spec
      conformance); the FAIL is purely the mechanical shrink
      arithmetic. Precedent: M1-131 (2026-06-02) — an in-branch redteam
      fix on an approved ticket always trips must-shrink; resolution is
      a recorded override, not artificial shrinking. Test integrity is
      NOT being overridden.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-07
    category: INFO-LEAK
    severity: medium
    promise: |
      "Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose (chat-mode message bodies, post bodies, saved-post annotations, command arguments). The application provides a `SafeLog` utility that drops the exception message body, retains only the exception class name, and truncates the cause chain to class names (depth-capped at 5). The original `Throwable` is never passed to the underlying SLF4J logger." (§Secrets handling — User content in exceptions)
    gap: |
      The reworked catch block in `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2Worker.java:223-226` (the hunk this diff rewrote to return `new Attempt(null, true)`) still passes the raw provider `RuntimeException` directly to the logger: `LOG.warnf(e, "Stage 2 LLM call attempt %d failed ...")`. The exception originates from `provider.generate(...)` whose request payload embeds the **original, pre-redaction post body** (`Stage2Worker.java:215-221` weaves `originalBody` into the prompt; per §Quarantine workflow that pre-redaction content is so sensitive the Provider DB role is denied `SELECT` on it). HTTP-client exceptions routinely echo request/response fragments and target URLs (which for some providers carry the API key as a query parameter), so a 4xx/5xx burst — exactly the condition this M1-221 backoff change is built around and now exercises on every infra-shaped failure — emits the unredacted `Throwable` message and full stack into stdout logs, bypassing the `SafeLog` mechanism the spec commits to. `SafeLog` exists (`infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java`) and is used by the Nostr stream and Provider paths, but not by this worker's failure arm that the diff modified.
    repro: |
      (1) Adversary publishes a feed post containing Stage-1-triggering content (e.g. a prompt-injection regex hit) plus a payload they want exfiltrated to operator logs. (2) Stage 2 fires with the original pre-redaction body in the prompt. (3) The LLM endpoint returns 429/503 (attacker can help induce this by flooding the feed with flagged posts, the same rate-limit scenario this ticket targets) or any client-side error whose exception message includes the request/response context. (4) `LOG.warnf(e, ...)` writes the raw exception — potentially carrying pre-redaction post content and provider-call context — to the console log at WARN, the production baseline level, so no operator misconfiguration is required. The spec promises this class of leak is structurally impossible via `SafeLog`.
    suggested_fix_class: input-sanitization
  - date: 2026-06-07
    category: INFO-LEAK
    severity: medium
    promise: |
      "Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose (chat-mode message bodies, post bodies, saved-post annotations, command arguments). ... The original `Throwable` is never passed to the underlying SLF4J logger." (§Secrets handling — User content in exceptions)
    gap: |
      The diff converts the raw-Throwable log sites in exactly three eval workers to `SafeLog` (Stage2Worker, TaggerWorker, EntityExtractorWorker) and its own comments state the exploit condition explicitly: "the provider exception can echo its request context, which embeds the post body woven into the prompt." That same condition holds, unfixed, at sibling eval-pipeline sites handling the same upstream-untrusted post bodies:
      - `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java:267` — `LOG.warnf(e, "EmbeddingWorker: embed call attempt %d threw", attempt)` passes the raw provider exception for an `embed(inputs)` call whose inputs are post bodies; identical failure shape to the three sites the diff fixed.
      - `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java:496` — `LOG.warnf(cause, "Stage 1 sanitizer exception ...")` logs the raw HTML-sanitizer Throwable; parser/sanitizer exceptions routinely quote the offending input, i.e. attacker-controlled feed bytes, and Stage 1 is by definition the pre-vetting path where the body is fully hostile.
      - `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:113` and `.../ready/ReadyPromoter.java:129` — raw `RuntimeException` from per-post processing logged with full message + stack.
      The partial conversion makes the residual gap worse to triage: the codebase now asserts (in comments shipped by this diff) that these exception classes leak post bodies, while leaving sibling call sites that emit exactly those exceptions raw. The promise "the original Throwable is never passed to the underlying SLF4J logger" is therefore not delivered across the eval pipeline this diff modifies.
    repro: |
      Adversary publishes a feed post whose body contains a planted secret-looking string plus malformed HTML (Stage 1 path) or content that triggers an embedding-provider error echoing the request (e.g. an oversized body provoking a 4xx whose client exception includes the request payload). The Collector logs the raw exception at WARN — the production baseline level — and the post body (which §Secrets handling says must never appear in non-audit logs at any level) lands in the operator log stream, bypassing both the SafeLog class-name-only rule and the API-key redactor that `SafeLog.formatSafe` applies.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: main (fe32971403c8c1f117909a208735ca53cd88fc02)
    head: working tree of m1/M1-221-llm-retry-once-backoff-sleep-b (uncommitted, --in-progress audit)
    verdict_file: docs/plan/m1/redteam/M1-221-2026-06-07.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One INFO-LEAK medium: Stage2Worker.java:224 passes the raw provider
      Throwable to LOG.warnf, bypassing the SafeLog commitment
      (security.md §Secrets handling) on the failure arm this ticket's
      backoff makes more frequently exercised. Verified PRE-EXISTING on
      main (the LOG.warnf line is unchanged context in the rewritten
      catch block; Stage2Worker last touched by M1-209) and the same
      raw-Throwable pattern appears at ~10 sites across the collector
      eval package, so the remediation shape is a SafeLog-adoption sweep
      ticket over the eval workers, not M1-221 rework. Two OUT-OF-MODEL
      advisories: backoffMs upper-bound overflow (trusted operator
      config) and pi-profile Stage 2 serialization under sustained rate
      limiting (inside the documented degraded mode).
  - date: 2026-06-07
    verdict: FINDINGS
    base: da653912feeb2bbdf71e449392a281143d417cc3^ (parent of the implementation commit)
    head: da653912feeb2bbdf71e449392a281143d417cc3 (M1-221 implementation commit, pre-merge)
    verdict_file: docs/plan/m1/redteam/M1-221-2026-06-07.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Second same-day audit, re-run on the committed diff after the
      in-branch SafeLog fix (acceptance items 5 and 6) landed in
      da65391. The threat-actor confirmed the three in-diff SafeLog
      conversions are correct (read SafeLog.java: drops Throwable
      bodies, redacts the caller message) and that the backoff
      mechanics are sound (retry count exactly one on every arm,
      interrupt does not swallow the spec-mandated retry, negative
      config fails startup, new log lines carry only UUIDs/counters).
      The one INFO-LEAK medium is the residual surface of the first
      audit's finding: sibling eval-pipeline sites this diff did NOT
      touch still pass raw Throwables to the logger
      (EmbeddingWorker.java:267, Stage1Pipeline.java:496,
      ReEvaluationJob.java:113, ReadyPromoter.java:129) — all
      pre-existing on main. Disposition: user directed an in-branch
      sweep over all 12 residual sites in 6 files (no stacked
      remediation ticket); see acceptance items 8–9 and the round-2
      refine revision. One
      OUT-OF-MODEL advisory: Stage 2 sleeps holding its semaphore
      permit (design-intended back-pressure); size the operator-tunable
      upper end with the per-source UNKNOWN auto-disable and
      NEEDS_REVIEW depth alert in mind.
escalations:
  - date: 2026-06-07
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL. SCOPE-DRIFT-CHECK: FAIL — must-shrink: round-2
      diff grew along ALL THREE dimensions vs round 1 (files 12 > 11,
      added 673 > 472, removed 68 > 30); round 1 was APPROVE with zero
      REWORK items so the refactor exception cannot apply. All other
      checks PASS. UNCERTAINTY: "shrinking the diff back below round 1
      ... would require dropping the SafeLog acceptance items (5 and 6)
      that the user-confirmed redteam refine added to this same ticket
      ... a rules trade the reviewer may not make. ... an override is a
      user-level action recorded via the escalation flow — the ticket's
      overrides: list is still empty, so the reviewer cannot treat the
      override as already granted."
  - date: 2026-06-07
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (1 medium INFO-LEAK; verdict file
      docs/plan/m1/redteam/M1-221-2026-06-07.md). GAP excerpt: "the
      hunk this diff rewrote ... still passes the raw provider
      `RuntimeException` directly to the logger: `LOG.warnf(e, "Stage 2
      LLM call attempt %d failed ...")`. The exception originates from
      `provider.generate(...)` whose request payload embeds the
      original, pre-redaction post body ... bypassing the `SafeLog`
      mechanism the spec commits to." Pre-existing on main; same
      pattern at ~10 sites across the collector eval package.
revisions:
  - date: 2026-06-07
    reason: |
      redteam-finding refine round 2 (second audit, run on the committed
      diff da65391): the residual INFO-LEAK medium — raw-Throwable log
      sites in eval-package files the ticket had explicitly deferred to
      a follow-up sweep ticket — is fixed IN-BRANCH by user direction
      ("we should not stack tickets"; this ticket already remediates the
      first audit's instance of the same gap). files_budget 10 → 18
      (six more production files); the deferred-sweep out_of_scope
      entry is replaced by the narrowed negative space (no-Throwable
      logger files + non-eval modules).
    snapshot:
      status: done
      files_budget: 10
      out_of_scope_entry_replaced: |
        the remaining raw-Throwable logging sites in collector eval
        files this ticket does not otherwise touch (EmbeddingWorker,
        ReEvaluationJob, ReadyPromoter, PerSourceUnknownTracker,
        AdminReviewTtlJob) — same SafeLog gap, separate follow-up sweep
        ticket; pulling 5 more files in here would breach files_budget
  - date: 2026-06-07
    reason: redteam-finding refine (SafeLog adoption in this ticket's three workers)
    snapshot:
      status: escalated
      files_budget: 10
      acceptance:
        - "Decision recorded (in the ticket body before implementation, flagged in the commit message): the sleep is either (a) Retry-After-honoring — the adapter exceptions regain a CLAMPED retryAfterMs (ceiling ≤ 30s, hostile/overflow header values clamped, the M1-192 audit's unclamped-parse defect must not return) and the workers sleep on it when present, falling back to the fixed delay when absent — or (b) a fixed/jittered delay with no adapter changes"
        - "Stage2Worker's single retry no longer fires immediately: a named test asserts a measurable configurable delay (default on the order of ~2s, test-tuned smaller) between attempt 1 and attempt 2 on a rate-limited (429/503-shaped) failure"
        - "TaggerWorker and EntityExtractorWorker apply the same sleep-before-retry shape where their retry-once paths re-invoke the LLM, each pinned by a named test (their non-LLM retry shapes — schema-garbage / zero-valid — are out of band and unchanged)"
        - "The sleep does not hold a DB transaction or pinned platform thread open: the delay runs on the worker's virtual thread outside any transactional boundary, asserted by test or by citing the existing worker structure in the implementation notes"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope:
        - the retry COUNT (stays at exactly one retry per docs/spec/security.md §Failure handling — this ticket changes only the timing of that retry)
        - the stage-specific failure paths themselves (INFRA_FAILURE → READY-with-Stage-1 / quarantine posture) — unchanged
        - provider-side LLM consumers (SummaryProseGenerator, ChatAgent, LlmTranslationProvider) — they have no retry-once machinery today; adding retries there is a different ticket
        - the adapter's exception types and LlmHttpSupport IF the fixed-sleep outcome is chosen (only the header-honoring outcome may touch the adapter)
---

# M1-221: LLM retry-once backoff: sleep before the single retry (M1-192 redteam F2)

## Context

M1-192's redteam audit (F2, DOS/low —
`docs/plan/m1/redteam/M1-192-2026-06-07.md`): the spec mandates "retry
once, then apply the stage-specific failure path"
(docs/spec/security.md §Failure handling), and the collector workers
implement that retry as an IMMEDIATE re-invocation
(`Stage2Worker.invokeWithRetryOnce`, plus the analogous retry-once
paths in TaggerWorker and EntityExtractorWorker). Against a
rate-limited endpoint (429/503, possibly advising `Retry-After: 2`),
the immediate second attempt is near-certain to fail milliseconds after
the first, converting every rate-limit burst into the degraded
failure path (Stage 2: INFRA_FAILURE → READY with Stage 1 redactions
only on the default profile) even though waiting ~2 s would have
produced a healthy verdict. An adversary who can correlate feed
publishing with provider rate-limit windows gets content judged by
Stage 1 alone more often than the retry-once design intends.

M1-192 deleted the adapter's dead, unclamped Retry-After machinery
(the authorized deletion branch; it had zero consumers). This ticket
adds the consumer side properly — designed as one piece, with the
worker files in scope.

## Design space (the acceptance item 1 decision)

- **(a) Header-honoring**: re-introduce `retryAfterMs` on
  `LlmCallFailedException` (clamped at parse time, ceiling ≤ 30s);
  workers sleep on it when present. Precise, but re-adds adapter
  plumbing across LlmHttpSupport + providers and their tests.
- **(b) Fixed/jittered delay**: workers sleep a configurable ~2 s
  (jittered) before the single retry, regardless of what the endpoint
  advised. Zero adapter changes; captures most of the value since
  rate-limit bursts clear in seconds.

Either outcome satisfies the spec (which mandates the retry count,
not its timing). Pick one and say why in the commit message.

## Decision (acceptance item 1)

**Outcome (b): fixed jittered delay, zero adapter changes.** Recorded
2026-06-07 before implementation; user-confirmed.

Why (b) over (a):

- Proportionality: the finding is DOS/low and its own scenario is a
  short burst ("possibly advising `Retry-After: 2`"); a ~2 s jittered
  sleep covers exactly that case.
- Every default profile points at local Ollama
  (`http://localhost:11434/v1`), which does not send Retry-After —
  (a)'s header path would be dead outside the remote-llm profile.
- The delay is configurable (`infochat.llm.retry-backoff-ms`), so a
  remote-llm operator can widen the survival band (e.g. 10 s) without
  header plumbing.
- (a) re-adds the hostile-header parse surface whose unclamped defect
  the M1-192 audit flagged, and its ground-truthed file count
  (LlmHttpSupport + LlmCallFailedException — nested in
  OpenAiCompatibleProvider, message+cause only today — + both
  providers' non-2xx throw sites + 3 workers + adapter clamp tests +
  worker tests) exceeds files_budget 10.
- Neither outcome survives sustained limiting with the one
  spec-mandated retry; the systemic mitigations are bounded
  concurrency (already present) and the M1-222 rate cap.

Shape: workers sleep base + uniform(0, base/2) jitter before the
single retry, ONLY on the infrastructure-shaped failure arm (the
provider call threw — the 429/503 case); schema-garbage / zero-valid
retry shapes stay immediate per the acceptance item 3 parenthetical.
Stage2Worker gains a private `Attempt(verdict, infraFailure)` record
so its retry path can distinguish exception from unparseable reply.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- files_budget 10 accounting (worst case, outcome (a)): 3 workers +
  2 exception types + LlmHttpSupport + 2 provider impls = 8 production
  files + worker tests; outcome (b) is ~3 production files + tests.
- security_relevant: true — the finding is a DOS-shaped degradation of
  the Stage 2 security boundary's effective coverage window.
- The M1-192 audit's out-of-model items (local-only guard TOCTOU vs
  per-call config reads; wildcard-bind test harness) are advisory and
  NOT part of this ticket.

## Redteam refinement (2026-06-07)

This ticket's own in-progress audit
(`docs/plan/m1/redteam/M1-221-2026-06-07.md`, INFO-LEAK medium) found
the workers' LLM-failure catch blocks pass the raw provider
`Throwable` to `LOG.warnf`, violating docs/spec/security.md §Secrets
handling — User content in exceptions (the provider exception can
embed the pre-redaction post body woven into the prompt). The leak is
PRE-EXISTING on main and spans ~10 sites across the eval package; the
refine pulls in ONLY the five sites in the three worker files this
ticket already touches (see the SafeLog acceptance items). The
remaining five files are an explicit out_of_scope entry feeding a
follow-up sweep ticket. Expected process consequence: the round-2
review diff grows vs round 1 (new audit file + SafeLog conversions),
which trips must-shrink; the resolution is a recorded round-cap
override, not artificial shrinking.

## Redteam refinement round 2 (2026-06-07, post-commit)

The second audit — re-run on the committed diff da65391 — confirmed
the three in-diff SafeLog conversions and flagged the residual
surface as its one finding (INFO-LEAK medium): the sibling
eval-package raw-Throwable sites the paragraph above had deferred.
User direction: fix in-branch, do not stack a remediation ticket on a
remediation ticket. The sweep converts every remaining
Throwable-passing log site across EmbeddingWorker, Stage1Pipeline,
ReEvaluationJob, ReadyPromoter, PerSourceUnknownTracker, and
AdminReviewTtlJob (12 sites; same jboss→slf4j + SafeLog shape as the
three workers). Files whose loggers never receive a Throwable keep
their jboss style — converting them would be scope drift. The fix
lands as a follow-up commit on the ticket branch; the squash-merge
absorbs it under the canonical `M1-221:` subject.
