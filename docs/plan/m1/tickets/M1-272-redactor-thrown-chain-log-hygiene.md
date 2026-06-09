---
id: M1-272
title: "Redactor thrown-chain coverage + log hygiene sweep"
status: done
created: 2026-06-09
last_updated: 2026-06-10
revisions:
  - date: 2026-06-09
    reason: clarity-fail refine (files_scope gap + unauthorized test modify + unnamed tests on items 6-7)
    snapshot: |
      Pre-refine files_scope listed only NostrRelayConnection.java for the
      nostr production tree (NostrMessage.java absent). Pre-refine acceptance
      items 4, 6, 7 verbatim:
        4: "NostrRelayConnection never lets raw relay bytes reach WARN logs:
            malformed-frame failures log a fixed reason code or a
            control-char-stripped summary (MalformedFrameException no longer
            embeds unstripped frame bytes in its message); named test with a
            control-char/ANSI frame."
        6: "MembershipEventHandler redacts adapterGroupId at the
            UserLeft/BotRemoved warn sites (:72, :138), matching its other
            sites."
        7: "The heartbeat host_id is sanitized before log interpolation."
      All other frontmatter fields unchanged by the refine.
escalations:
  - date: 2026-06-09
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      BLOCKERS:
        1. FILES-BUDGET-PLAUSIBLE / files_scope gap: Acceptance item 4 requires
           changing MalformedFrameException so it no longer embeds raw frame
           bytes in its message. MalformedFrameException is a nested class of
           NostrMessage.java (infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrMessage.java),
           which is not in files_scope. Add it. (If the fix instead routes
           through NostrRelayConnection.java alone — e.g., catching and
           re-throwing with a sanitized message at the call site — the
           acceptance item should be reworded to describe that approach so the
           scope exclusion is coherent.)
        2. TEST-CHANGES-AUTHORIZED: test_plan.modifies lists
           infochat-provider/src/test/java/app/zcat/infochat/provider/group
           (contains MembershipEventHandlerTest.java) but no acceptance item
           names a test in that directory, and no body section describes what
           the pre-existing test is being changed to assert. Add an explicit
           acceptance item (or a Notes subsection) naming
           MembershipEventHandlerTest and describing the new expected behavior
           it will pin.
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrMessage.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/MembershipEventHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Redactor in-band sentinel exact-equality quirk and the params-array in-place mutation — wont-fix lean per the unified report; record, don't fix.
  - The redaction catalogue contents (what counts as a secret) — unchanged; only WHERE the catalogue is applied widens.
  - NewPostListener — already hardened; it is the parity model, not a target.
  - LLM error-body previews (downgraded in the report; bounded and scanned already).
acceptance:
  - "Redactor covers the thrown chain: messages of record.getThrown() and its cause chain pass through the same catalogue scan as the message and params before reaching the console; a named test asserts an API-key-shaped string inside a nested cause's message is redacted in console output."
  - "ThrottledAdminNotifier no longer binds the raw throwable around the redactor, and AbstractInstanceLockGuard no longer interpolates raw e.getMessage() into its fatal line — both routes are covered by the redaction/sanitization path; named tests."
  - "The core sanitize() helper strips DEL and C1 controls (including 0x9B CSI), matching its 'leaves no gaps' comment; named test with C1 bytes."
  - "NostrRelayConnection never lets raw relay bytes reach WARN logs: malformed-frame failures log a fixed reason code or a control-char-stripped summary. The throw sites in NostrMessage.java that embed summarize(frame) / summarize(filterSpec) into MalformedFrameException messages (:51, :54, :79, :99, :102) strip controls (C0, DEL, C1) from the summarized bytes; named test with a control-char/ANSI frame."
  - "QuarantineReviewListener reaches NewPostListener parity: no raw NOTIFY payload echoed in ERROR logs or exception messages at the three cited sites (:255-256, :285, :295); named test."
  - "MembershipEventHandler redacts adapterGroupId at the UserLeft/BotRemoved warn sites (:72, :138), matching its other sites; the pre-existing MembershipEventHandlerTest gains named tests asserting the UserLeft and BotRemoved unknown-group warn lines do not contain the raw adapterGroupId. Existing test methods are not modified."
  - "AbstractInstanceLockGuard sanitizes the heartbeat host_id before interpolating it into the lock-holder fatal line (:250-251); the pre-existing InstanceLockLivenessTest gains a named test asserting a control-char-bearing host_id is rendered stripped. Existing test methods are not modified."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
    - infochat-core/src/test/java/app/zcat/infochat/core
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 890
      removed: 45
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-10
    category: INFO-LEAK
    severity: medium
    promise: |
      security.md §Secrets handling — "Stdout console logs pass through
      the closed API-key catalogue redactor, fail-closed on regex
      timeout (whole message replaced with a fixed sentinel). The
      audit_log writer consumes the same Redactor utility so the two
      cannot drift." The promise is that every API-key-shaped value
      reaching a console log line is scanned and redacted by the
      Redactor.
    gap: |
      The new thrown-chain redaction in Redactor.redactThrownChain
      (infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:180-220)
      walks only the getCause() chain — it never inspects
      getSuppressed(). The console formatter (SimpleFormatter / JBoss
      formatter, via Throwable.printStackTrace) DOES render suppressed
      throwables ("Suppressed: ..."). Two sub-paths: (1) When no
      catalogue match is found in the main cause chain,
      redactThrownChain returns the ORIGINAL throwable unchanged
      (Redactor.java:202-204), so its suppressed exceptions ride
      through to the formatter completely unscanned. (2) The rebuilt
      path constructs RedactedThrown with super(message, cause, false,
      true) (Redactor.java:231) which disables suppression — so
      suppressed nodes are silently DROPPED rather than scanned-and-
      preserved. The net effect: a suppressed throwable carrying an
      API-key-shaped string is never run through the catalogue, and on
      the unchanged path it reaches the console raw. The
      RedactorThrownChainTest added in this diff exercises only
      getCause() nesting; no test covers getSuppressed().
    repro: |
      A try-with-resources block over a JDBC/HTTP resource throws a
      primary exception whose message is clean, but the resource's
      close() throws a secondary exception whose message echoes a
      connection string or driver error containing a value matching the
      generic "...password=<32+ chars>" / "bearer <token>" catalogue
      shape (or an `sk-...` / `AKIA...` literal). Java records the
      close() exception as a SUPPRESSED throwable on the primary. The
      primary's own message and cause chain contain no catalogue match,
      so redactThrownChain takes the unchanged arm and returns the
      original Throwable. The console formatter renders the
      "Suppressed:" frame verbatim, putting the secret on stdout. The
      spec commits that console logs are redacted of the catalogue
      shapes; this surface is not.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-10
    verdict: FINDINGS
    base: f3bc0e7b7e11a6b6351f49f1eb840a0a18cd3662
    head: f27ee7918b9ce3ce0e2931fcb28b5505176dd45d
    verdict_file: docs/plan/m1/redteam/M1-272-2026-06-10.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One medium INFO-LEAK: redactThrownChain never inspects
      getSuppressed(), so a suppressed throwable whose message carries
      a catalogue-shaped secret reaches the console unscanned on the
      no-match arm (and is dropped, not scanned-and-preserved, on the
      rebuilt arm). Verified against the committed Redactor.java before
      transcription (gap/repro line numbers corrected from
      diff-relative to file-relative). M1-272 is done, so the fix is a
      new remediation ticket (remediates: M1-272) extending the chain
      walk to suppressed throwables plus a getSuppressed() test in
      RedactorThrownChainTest. Out-of-model item (prose in suppressed
      throwables) is advisory — same structural fix covers it for
      catalogue shapes; prose hygiene stays a SafeLog call-site duty.
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-272: Redactor thrown-chain coverage + log hygiene sweep

## Context

Deep-review v4 verified medium **M-C1**, medium **M-K6**, medium **M-P10**,
and the **T-LOG** low sweep (`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3;
sources `deep-code-review/v4/fable5/02-module-infochat-core.md#F1/#F3`,
`deep-code-review/v4/fable5/06-module-infochat-collector.md#F1`,
`deep-code-review/v4/opus-47/06-module-infochat-collector.md#F1`,
`deep-code-review/v4/opus-47/07-module-infochat-provider.md#F3`,
`deep-code-review/v4/gpt-55/report.md` M-04, `deep-code-review/v4/mimo/report.md`
CORE-002, MED-003):

- **M-C1:** `Redactor.isLoggable` redacts message+params but never walks
  `record.getThrown()`; `ThrottledAdminNotifier` sanitizes the message arg but
  binds the raw throwable (`LOG.warnf(e, …)`); `AbstractInstanceLockGuard`
  interpolates raw `e.getMessage()` into a fatal line. Residual exposure:
  non-key secrets, control chars, and the thrown-stack rendering.
- **M-K6** (3 independent runs): `NostrRelayConnection.handleFrame` logs
  `e.getMessage()` of `MalformedFrameException`, which embeds
  `summarize(frame)` — ≤120 chars of raw relay bytes, control chars not
  stripped. The console Redactor scans for API-key shapes only, so
  newline/ANSI log forging is open from an untrusted relay.
- **T-LOG:** `sanitize()` strips C0 only, leaving DEL + C1 (incl. 0x9B CSI)
  despite its comment; `QuarantineReviewListener` echoes the raw NOTIFY
  payload where `NewPostListener` is hardened (downgraded from HIGH — payload
  fields are collector-built, attacker influence requires DB compromise;
  hygiene parity); `MembershipEventHandler` logs `adapterGroupId` unredacted
  at two warn sites; heartbeat `host_id` interpolated unsanitized.

## Acceptance

See frontmatter. The structural fix is the Redactor walking the thrown chain
(fable5's Option B); the per-site items are the sweep that makes the
guarantee uniform.

## Out-of-scope

See frontmatter — two verified-but-wont-fix Redactor quirks stay as recorded
facts.

## Notes

- Order the work structural-first: extend Redactor, then re-check which
  per-site fixes are still needed (some sites may become safe once the
  thrown chain is covered; keep their explicit fixes only where the site
  bypasses the console handler, e.g. exception messages flowing into admin
  notifications).
- The Nostr fix should route through a fixed reason code or the shared
  control-char-stripping helper — the same helper the sanitize() leg
  strengthens, so do that leg first.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-272-*.md
```
