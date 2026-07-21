---
id: M1-667
title: Route user-content exception catches through SafeLog
status: done
created: 2026-07-20
last_updated: 2026-07-21
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingUsersDao.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewPayloadHygieneTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
clarity_check:
  date: 2026-07-21
  verdict: WARN
  warnings:
    - "step-1b self-check: DigestWorker.java:145 was a false census row — line 145 is a `return` statement, and the file's catch at :149 is already routed through `SafeLog.error(LOG, ..., e)` at :156 (comment cites §Secrets handling). Removed the false row from §Census, the matching citation from acceptance #1, and DigestWorker.java from files_scope. Pure-mechanical premise fix; scope/intent unchanged (17 real bypass sites remain)."
  blockers: []
out_of_scope:
  - >-
    Catches whose exception message/cause chain never carries user-authored
    prose (the threat model's class — §Secrets handling "User content in
    exceptions" enumerates chat-mode message bodies, post bodies, saved-post
    annotations, command arguments). Concretely excluded: PartitionCreator
    and PartitionPruner (DDL SQLExceptions over table names — no user
    content), AssetSnapshotFetcher (HTTP/DB ops over market data and
    operator-authored asset_config — no user prose), AbstractPgListener
    (PostgreSQL LISTEN/NOTIFY connection exceptions — transport, no content),
    and SignalSubprocess (signal-cli subprocess restart — no user content).
    These keep their raw logger calls. If a site's disposition is wrong, the
    /redteam gate (security_relevant: true) is where it surfaces.
acceptance:
  - >-
    Every catch in §Census marked "fix" routes its Throwable through
    app.zcat.infochat.core.log.SafeLog (SafeLog.warn or SafeLog.error), NOT a
    raw logger call that passes the Throwable to the underlying logger —
    whether `LOG.warnf(e, ...)`/`LOG.errorf(e, ...)` (throwable-first,
    printf-style) or `LOG.error(msg, e)` (throwable-last, SLF4J-style). The
    sites: SummaryProseGenerator.java:106 and :137, TranslationPipeline.java:90,
    DigestScheduler.java:203, PendingCommandHandler.java:124 and :159,
    AuditCommandHandler.java:158, :241, and :279, QuarantineCommandHandler.java:204,
    :258, :394, :466, and :488, PendingUsersDao.java:91, NewPostListener.java:111
    and :117, and QuarantineReviewListener.java:209 and :218.
  - >-
    Each migrated file's logger field follows the project convention where the
    catch passes through SafeLog (org.slf4j.Logger if the file already uses
    SLF4J; the JBoss Logger interop is fine where SafeLog is invoked with the
    SLF4J-bound logger — match the established pattern in ChatAgent /
    CompressCommandHandler / SummaryCommandHandler / M1-642's
    CategoryRollupGenerator). NewPostListener and QuarantineReviewListener
    extend AbstractPgListener, whose `abstract Logger log()` returns the JBoss
    Logger and whose own `log().errorf(e, ...)` / `log().infof(...)` calls
    require it; these two subclasses therefore KEEP the JBoss Logger field
    (for the parent) and route their catch-site calls through SafeLog via a
    separate static SLF4J Logger (the acceptance's "JBoss Logger interop"
    clause). The seven non-listener files migrate fully to SLF4J (no parent
    coupling).
  - mvn verify is green
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §User content in exceptions
decision_refs: []
remediates: M1-642
reviews:
  - round: 1
    date: 2026-07-21
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: FAIL
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 32
      added: 1771
      removed: 65
    rework_items:
      - "SCOPE-DRIFT: add QuarantineReviewPayloadHygieneTest.java to files_scope (diffed but outside declared boundary)"
      - "TEST-INTEGRITY: add §8 test-modification authorization naming the test and the assertion change"
  - round: 2
    date: 2026-07-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 32
      added: 1819
      removed: 65
---

# M1-667: Route user-content exception catches through SafeLog

## Context

M1-642's redteam-multi audit (2026-07-20) found an INFO-LEAK —
`CategoryRollupGenerator.generateRollup` passed the raw `Throwable` to the
logger (`LOG.warnf(e, …)`), bypassing `SafeLog`, on a path whose prompt
interpolates post bodies. M1-642 fixed its own instance; the finding's note
flagged that the SAME pattern exists at other call sites. docs/spec/security.md
§Secrets handling commits that exception messages and stack traces emitted via
the application logger MUST NOT contain user-authored prose (chat-mode message
bodies, post bodies, saved-post annotations, command arguments), and that
`SafeLog` is the mechanism. This ticket remediates the remaining instances on
paths where the caught exception's message/cause chain can carry that prose.

## Census

Mechanical enumeration of the bypass shape — JBoss-Logger calls that pass the
Throwable to the underlying logger. Two signatures appear in main src:
`LOG.*f(e, ...)` (throwable-first, printf-style — the majority) and
`LOG.error(msg, e)` (throwable-last, SLF4J-style — exactly two sites, both
on user-content paths). The primary grep catches the `*f(e, ...)` form; a
supplementary grep catches the `(msg, e)` form:

```
# primary — throwable-first printf-style
grep -rnE "\.(warnf|errorf|debugf|infof)\(e[),]" --include="*.java" \
  infochat-collector/src/main infochat-provider/src/main \
  infochat-core/src/main infochat-llm-adapter/src/main \
  infochat-messaging-adapter/src/main infochat-ssrf/src/main

# supplementary — throwable-last SLF4J-style (string-literal msg)
grep -rnE '\.(warn|error|debug|info)\("[^"]*",\s*e\)' --include="*.java" \
  infochat-collector/src/main infochat-provider/src/main \
  infochat-core/src/main infochat-llm-adapter/src/main \
  infochat-messaging-adapter/src/main infochat-ssrf/src/main
```

Re-run both at `start` and confirm every returned site has a row before
implementing.

| Site | Disposition |
|---|---|
| `infochat-provider/.../summary/SummaryProseGenerator.java:106` | fix — SUMMARIZER provider-resolution catch on the post-prose path |
| `infochat-provider/.../summary/SummaryProseGenerator.java:137` | fix — SUMMARIZER LLM call catch; prompt interpolates cluster prose (the direct analog of M1-642's finding) |
| `infochat-provider/.../translation/TranslationPipeline.java:90` | fix — translator LLM call catch; the exception can carry the text being translated |
| `infochat-provider/.../digest/DigestScheduler.java:203` | fix — dispatch catch; propagates render-path exceptions |
| `infochat-provider/.../command/PendingCommandHandler.java:124` | fix — `/pending` write catch; command-argument path (constraint-violation messages can echo the value) |
| `infochat-provider/.../command/PendingCommandHandler.java:159` | fix — `/pending` query catch |
| `infochat-provider/.../command/AuditCommandHandler.java:158` | fix — `/audit` write catch |
| `infochat-provider/.../command/AuditCommandHandler.java:241` | fix — `/audit` query catch |
| `infochat-provider/.../command/AuditCommandHandler.java:279` | fix — `lookupActor` catch |
| `infochat-provider/.../command/QuarantineCommandHandler.java:204` | fix — `/quarantine list` write catch |
| `infochat-provider/.../command/QuarantineCommandHandler.java:258` | fix — `/quarantine list` query catch |
| `infochat-provider/.../command/QuarantineCommandHandler.java:394` | fix — `/quarantine reject` audit catch |
| `infochat-provider/.../command/QuarantineCommandHandler.java:466` | fix — `/quarantine` stored-procedure catch |
| `infochat-provider/.../command/QuarantineCommandHandler.java:488` | fix — `lookupActor` catch |
| `infochat-provider/.../command/PendingUsersDao.java:91` | fix — `lookupActor` catch on the command path |
| `infochat-provider/.../outbox/NewPostListener.java:111` | fix — payload-parse catch (`LOG.error(msg, e)` — SLF4J-style throwable-last); user-content boundary |
| `infochat-provider/.../outbox/NewPostListener.java:117` | fix — post handler catch; the handler processes post content |
| `infochat-provider/.../outbox/QuarantineReviewListener.java:209` | fix — payload-parse catch (`LOG.error(msg, e)` — SLF4J-style throwable-last); user-content boundary |
| `infochat-provider/.../outbox/QuarantineReviewListener.java:218` | fix — quarantine-review handler catch on the post path |
| `infochat-collector/.../partition/PartitionCreator.java:111` | out-of-scope — partition DDL SQLException over table names (no user prose) |
| `infochat-collector/.../partition/PartitionPruner.java:95` | out-of-scope — partition pruning SQLException (table names) |
| `infochat-collector/.../assets/AssetSnapshotFetcher.java:147,159,202,219,265,298` | out-of-scope — HTTP/DB ops over market data + operator asset_config (not user-authored prose) |
| `infochat-provider/.../outbox/AbstractPgListener.java:170,186` | out-of-scope — PostgreSQL LISTEN/NOTIFY connection exception (transport, no content) |
| `infochat-messaging-adapter/.../signal/SignalSubprocess.java:265` | out-of-scope — signal-cli subprocess restart (no user content) |

## Acceptance

See `acceptance`. Every "fix"-row site routes its Throwable through `SafeLog`
(the `LOG.warnf(e, …)` / `LOG.errorf(e, …)` call is replaced by the
`SafeLog.warn` / `SafeLog.error` equivalent). `mvn verify` stays green. The
`/redteam` gate (this ticket is `security_relevant: true`) independently
re-audits the INFO-LEAK surface and must return CLEAN on the remediated sites.

## Out-of-scope

See `out_of_scope`. The partition DDL, asset market-data, PG-connection, and
signal-subprocess catches keep their raw logger calls: none of those exception
paths carry user-authored prose, so the threat model's SafeLog mandate does not
reach them. If `/redteam` disagrees on any site at the step-4 gate, that site
moves into scope via `escalate → refine`.

## Notes

`SafeLog` drops the exception message body and keeps only the class name plus a
depth-capped cause chain of class names (and runs the caller msg through the
API-key redactor). The threat model accepts the operator-debugging tradeoff
("Operators debugging exceptions reproduce locally where the unredacted trace is
available") — so routing a DB SQLException through SafeLog loses the SQL error
detail in the log. That is the model's stated posture, not a regression.

Adjacent code (the established pattern to match): `ChatAgent`,
`CompressCommandHandler`, `SummaryCommandHandler`, `OutboundDelivery`, and
M1-642's `CategoryRollupGenerator` — all SLF4J `Logger` + `SafeLog.warn/error`
for content-path catches.

Alternatives considered:
  - fix only the LLM-call sites: rejected — the threat model's "command
    arguments" clause reaches the command-handler DB catches (a constraint-
    violation message can echo a user-supplied value), so they are in scope.
  - a repo-wide lint that flags `LOG.*f(e, …)`: not in this ticket; the §Census
    grep is the enumeration and the reviewer checks the disposed sites against
    the diff. A future `process:` change could add such a lint if the class
    recurs.

  - 2026-07-21 step-1b self-check (start): the original census listed
    `DigestWorker.java:145` as a fix row, but re-running the enumeration grep
    at start returned no match. Inspection showed line 145 is a `return`
    statement and the file's only content-path catch (line 149) is already
    routed through `SafeLog.error(LOG, ..., e)` at line 156 — the file is
    already compliant, no bypass exists. The false row was removed from §Census,
    the matching citation from acceptance #1, and `DigestWorker.java` from
    `files_scope`. Mechanical premise correction; the ticket's scope and intent
    are unchanged.

  - 2026-07-21 implementation refine (user-directed at step 3): the census
    prose's claim that "SLF4J-style `log.warn(msg, e)` is already absent from
    main src" was false — a supplementary grep for the `(msg, e)` signature
    found two more bypass sites at `NewPostListener.java:111` and
    `QuarantineReviewListener.java:209` (both `LOG.error("...", e)` on
    user-content payload-parse boundaries). Both are in files already in
    `files_scope` and already being migrated, so the scope effect is two extra
    catch-site edits, not two extra files. Added both to §Census and acceptance
    #1, and added the supplementary grep to the §Census enumeration. No
    follow-up ticket: the user explicitly directed folding these in now.

  - Test-modification authorization (engineering-rules §8):
    `QuarantineReviewPayloadHygieneTest.dispatchUnparseablePayloadErrorLogOmitsPayload`
    is modified because the catch at QuarantineReviewListener:209 now routes
    through SafeLog, which by design does NOT attach the raw Throwable to the
    LogRecord (it emits only the class name in the formatted message per
    §Secrets handling). The old `assertNotNull(record.getThrown())` and
    `bound.getMessage()` assertions are replaced by
    `assertTrue(text.contains("exception=java.lang.IllegalArgumentException"))`,
    verifying the exception class name appears in the formatted log line. The
    shared `assertFalse(text.contains(PAYLOAD_MARKER))` payload-hygiene
    assertion is retained. This is NOT a semantic weakening — the new
    assertion matches SafeLog's contract exactly.

## Round 1 rework

Reviewer returned REWORK with 2 items — both ticket-paperwork fixes, no code
changes:

1. **SCOPE-DRIFT-CHECK FAIL**: `QuarantineReviewPayloadHygieneTest.java` was
   diffed but not in `files_scope`. **Fixed**: added the test path to
   `files_scope` (files_budget 12 still accommodates — 10 actual files).
2. **TEST-INTEGRITY-CHECK FAIL**: the test modification lacked explicit §8
   authorization. **Fixed**: added the "Test-modification authorization" note
   above in §Notes, naming the test method and explaining the assertion change.

All substantive checks (ACCEPTANCE, SPEC-CONFORMANCE, OUT-OF-SCOPE,
NEGATIVE-SPACE) passed. No `*.java`/`pom.xml`/`resources` changed in this
rework round — only the ticket file — so `mvn verify` is N/A (inert diff);
the round-1 green log covers the current testable surface.
