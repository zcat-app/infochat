---
id: M1-044d
title: Contact-id redaction + breach-audit ordering (M1-044a fixes)
status: done
created: 2026-05-21
last_updated: 2026-05-22
blocked_by: []
clarity_check:
  date: 2026-05-22
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 97
      removed: 23
redteam_findings: []
redteam_audits:
  - date: 2026-05-22
    verdict: CLEAN
    base: main
    head: 12cbd1f
    verdict_file: docs/plan/m1/redteam/M1-044d-2026-05-22.md
    out_of_model_count: 3
    note: |
      Post-commit pre-merge audit (running between /m1-tick commit
      and /m1-tick merge per the security_relevant: true convention).
      CLEAN — both M1-044a remediations land cleanly: INFO-LEAK fix
      restores §Secrets handling redaction at all six call sites;
      AUDIT-EVASION fix restructures the breach branch so the in-
      memory mark only fires after the DB commit succeeds, restoring
      §Invite-code registration's "an audit row records the threshold
      breach" promise. Three OUT-OF-MODEL advisory observations
      (concurrent over-audit race; crash-between-commit-and-add widens
      duplicate-audit window; trade-off comment relies on JDBC commit/
      rollback semantics) are not threat-model failures and are
      candidates for future hardening if scope permits.
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-044a
out_of_scope:
  - any change to the spec — §Secrets handling + §Invite-code registration are the source of truth; this ticket lands code already required by those sections
  - any change to ContactIds.redact in infochat-core — M1-038's helper is consumed unchanged
  - any change to RateCapBucket.java — the DOS finding from M1-044a's red-team is folded into M1-044b's pending splice ticket, not this remediation
  - any change to the InviteCodeConsumer.consume defense-in-depth precondition (the AUTH-BYPASS finding from M1-044a's red-team) — that gating is enforced at the caller per M1-044b's existing acceptance items 2 and 5, not by hardening the M1-044a service surface
  - any change to InboundRouter.java — M1-044b's splice ticket owns it
  - any new admin command handler — M1-044c territory
  - any change to the V12 migration or any V<N> migration — this ticket modifies code only
  - any change to bundle keys or en.properties — M1-044b authors the fixed-reply bundle keys
  - any change to application.properties — M1-044b ships the per-profile rate-cap / probation config
  - any test outside the three test files in files_scope — every M1-035c / M1-036 / M1-037 / M1-038 / M1-039 / M1-040 / M1-043 / M1-044a test stays green unchanged
acceptance:
  - "InviteCodeConsumer.java imports app.zcat.infochat.core.log.ContactIds. Verify: `grep -E 'import\\s+app\\.zcat\\.infochat\\.core\\.log\\.ContactIds' InviteCodeConsumer.java` returns ≥1 match"
  - "InviteCodeConsumer.java contains ZERO raw-`contactId` concatenations into IllegalStateException messages. Verify: `grep -nE 'contact_id=\"\\s*\\+\\s*contactId' InviteCodeConsumer.java` returns ZERO matches"
  - "InviteCodeConsumer.java's three IllegalStateException-throwing sites (the catch on the inner consume() try, the catch on the outer dataSource try, and the users-row-missing post-insert check) all wrap contactId via ContactIds.redact. Verify: `grep -nE 'ContactIds\\.redact\\(contactId\\)' InviteCodeConsumer.java` returns ≥3 matches"
  - "BanCheck.java imports app.zcat.infochat.core.log.ContactIds. Verify: `grep -E 'import\\s+app\\.zcat\\.infochat\\.core\\.log\\.ContactIds' BanCheck.java` returns ≥1 match"
  - "BanCheck.java's single IllegalStateException-throwing site wraps contactId via ContactIds.redact. Verify: `grep -nE 'contact_id=\"\\s*\\+\\s*contactId' BanCheck.java` returns ZERO matches AND `grep -nE 'ContactIds\\.redact\\(contactId\\)' BanCheck.java` returns ≥1 match"
  - "AutoRegisterService.java imports app.zcat.infochat.core.log.ContactIds. Verify: `grep -E 'import\\s+app\\.zcat\\.infochat\\.core\\.log\\.ContactIds' AutoRegisterService.java` returns ≥1 match"
  - "AutoRegisterService.java's IllegalStateException-throwing sites wrap contactId via ContactIds.redact. Verify: `grep -nE 'contact_id=\"\\s*\\+\\s*contactId' AutoRegisterService.java` returns ZERO matches AND `grep -nE 'ContactIds\\.redact\\(contactId\\)' AutoRegisterService.java` returns ≥2 matches"
  - "InviteCodeConsumer.consume's breach-audit ordering is rollback-safe: `breachAudited.add(key)` MUST appear in source order AFTER the corresponding `insertAudit(...)` call AND AFTER `conn.commit()` in the breach branch. The fix shape is: contains-check before insertAudit (skip if already audited), insertAudit, conn.commit(), THEN breachAudited.add(key). Verify: `grep -nE 'insertAudit\\(' InviteCodeConsumer.java` returns ≥2 matches (one for INVITE_CONSUME, one for INVITE_BRUTE_FORCE_BREACH) AND the line number of the FIRST `grep -nE 'breachAudited\\.add\\(key\\)' InviteCodeConsumer.java` match is GREATER than the line number of the FIRST `grep -nE 'insertAudit\\(conn,\\s*contactId,\\s*adapter,\\s*INVITE_BRUTE_FORCE_BREACH' InviteCodeConsumer.java` match"
  - "InviteCodeConsumerTest has a @Test method whose name contains `bruteForceBreachAuditFailureRetries` (case-insensitive) that asserts the rollback-safety property: when the INSERT INTO audit_log statement for INVITE_BRUTE_FORCE_BREACH fails (driven by an in-test DataSource decorator that throws SQLException on audit_log inserts OR by transiently DROP'ing/RENAMing the audit_log table within the test and then restoring it), the first consume call propagates IllegalStateException AND the in-memory `breachAudited` set does NOT permanently contain the breaching key — a subsequent consume call from the same `(adapter, contact_id)` MUST attempt the audit insert again rather than silently skipping it. Verify: `grep -iE 'void\\s+\\w*bruteForceBreachAuditFailureRetries\\w*\\s*\\(' InviteCodeConsumerTest.java` returns ≥1 match"
  - "BanCheckTest's existing tests (bannedRow, unbannedRow, unknownContact from M1-044a) continue to pass without modification. Verify: `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java` shows ZERO assertion changes — the only delta MAY be an import line if test scaffolding requires it"
  - "AutoRegisterServiceTest's existing tests (groupFreshInsert, groupIdempotent from M1-044a) continue to pass without modification. Verify: `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java` shows ZERO assertion changes — the only delta MAY be an import line if test scaffolding requires it"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-035c's HelpCommandHandlerTest, BundleLoaderTest, M1-035b's InboundRouterTest / InboundRouterNormalizeTest / StartupGatesTest, M1-035d's wiring tests, M1-036's AddSourceCommandHandler tests, M1-037's /summary tests, M1-038/M1-039/M1-040 hardening tests, M1-043's refusal-marker test, M1-044a's per-service tests (RateCapBucketTest, InviteCodeConsumerTest's pre-existing seven scenarios, BanCheckTest, AutoRegisterServiceTest, AdapterRouterIT)"
test_plan:
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  preserves:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java (M1-044a — assertions unchanged)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java (M1-044a — assertions unchanged)
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §Invite-code registration
decision_refs:
  - D44
---

# M1-044d: Contact-id redaction + breach-audit ordering (M1-044a fixes)

## Context

`/redteam M1-044a` (2026-05-21, verdict file `docs/plan/m1/redteam/M1-044a-2026-05-21.md`)
returned FINDINGS with two medium-severity defects against the M1-044a
implementation commit (15cbcdd):

- **INFO-LEAK (medium)** — raw `contactId` concatenated into
  `IllegalStateException` messages at six sites across
  `InviteCodeConsumer`, `BanCheck`, and `AutoRegisterService`. This
  regresses the §Secrets handling spec commitment "Contact IDs are
  logged in redacted form (prefix + ellipsis + suffix) outside the
  audit log" and the M1-038 / M1-039 precedent that applied
  `ContactIds.redact` to handler exception messages
  (see `AddSourceCommandHandler.java:242` for the canonical shape).
- **AUDIT-EVASION (medium)** — in `InviteCodeConsumer.consume`, the
  in-memory `breachAudited.add(key)` call happens BEFORE the
  `insertAudit(...)` write. An SQL fault on the audit INSERT rolls
  back the DB transaction but does NOT roll back the in-memory Set
  mutation. Subsequent calls find the key already in `breachAudited`
  and silently skip the audit insert, defeating the §Invite-code
  registration spec commitment "an audit row records the threshold
  breach."

M1-044a is `done` and its commit (15cbcdd) is immutable per the
workflow ("Never amend a passed commit"). This ticket lands the
fixes as a separate commit whose `remediates: M1-044a` field anchors
the lineage.

The two LOW-severity findings from the same red-team audit
(DOS on RateCapBucket eviction; AUTH-BYPASS defense-in-depth on
`consume` not self-verifying the no-existing-users-row precondition)
are folded into M1-044b's pending splice ticket — DOS by widening
the eviction predicate when M1-044b's per-profile rate-cap config
lands, AUTH-BYPASS via M1-044b's existing acceptance items 2 / 4 / 5
which already pin the gate ordering (users-row-empty check before
consume; ban check after consume's user resolution). Neither low
finding is this ticket's scope.

## Definition of Done

- `InviteCodeConsumer.java` imports `app.zcat.infochat.core.log.ContactIds`
  and replaces every raw-`contactId` concatenation in exception
  messages with `ContactIds.redact(contactId)`. Three call sites
  affected (the inner-consume catch, the outer-connection catch, the
  users-row-missing post-insert check).
- `BanCheck.java` imports `ContactIds` and replaces its single
  raw-`contactId` exception-message concatenation with
  `ContactIds.redact(contactId)`.
- `AutoRegisterService.java` imports `ContactIds` and replaces its
  two raw-`contactId` exception-message concatenations with
  `ContactIds.redact(contactId)`.
- `InviteCodeConsumer.consume`'s breach branch is restructured so
  `breachAudited.add(key)` runs AFTER `insertAudit(...)` AND AFTER
  `conn.commit()`. The contains-check (`breachAudited.contains(key)`)
  replaces the conditional `breachAudited.add(key)` short-circuit
  at the top of the breach branch; the `add(key)` call moves to
  AFTER the audit insert commits.
- A new `InviteCodeConsumerTest` method exercises the rollback-safety
  property: when the audit_log INSERT fails, the in-memory Set is
  NOT permanently marked, so the next call attempts the audit insert
  again.
- Every M1-044a test continues to pass without assertion changes.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **`ContactIds.redact` shape** — the helper at
  `infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java`
  is `public static String redact(String contactId)`. The canonical
  consumer pattern is `AddSourceCommandHandler.java:240-242`:
  ```java
  throw new IllegalStateException(
          "AddSourceCommandHandler.lookupActor failed for contact_id="
                  + ContactIds.redact(contactId), e);
  ```
  Mirror this shape verbatim in the six new sites.
- **The six INFO-LEAK call sites**:
  - `InviteCodeConsumer.java:171-173` — inner consume() try's catch:
    "InviteCodeConsumer.consume failed for adapter=... contact_id=" + contactId
  - `InviteCodeConsumer.java:176-178` — outer dataSource try's catch:
    "InviteCodeConsumer.consume connection failed for adapter=... contact_id=" + contactId
  - `InviteCodeConsumer.java:231-233` — `insertOrSelectUser` post-insert
    users-row-missing check: "users row missing after invite-consume
    INSERT for adapter=... contact_id=" + contactId
  - `BanCheck.java:57-59` — catch on isBanned: "BanCheck.isBanned
    failed for adapter=... contact_id=" + contactId
  - `AutoRegisterService.java:79-82` — post-upsert users-row-missing
    check: "users row missing after upsert..." + contactId
  - `AutoRegisterService.java:88-90` — top-level catch on
    resolveOrRegisterGroup: "AutoRegisterService.resolveOrRegisterGroup
    failed..." + contactId
  (Line numbers are from the M1-044a HEAD commit 15cbcdd; the post-
  fix line numbers will shift by import-line and call-site length
  deltas — verify by re-running grep against the patched file.)
- **The AUDIT-EVASION fix.** Restructure `consume`'s breach branch as:
  ```java
  if (attempts >= bruteForceThreshold) {
      if (!breachAudited.contains(key)) {
          insertAudit(conn, contactId, adapter,
                  INVITE_BRUTE_FORCE_BREACH, contactId, contactId);
          conn.commit();
          breachAudited.add(key);     // mark only AFTER commit succeeded
      } else {
          conn.commit();              // no audit needed; commit is a no-op
      }
      return new BruteForceThresholdBreached();
  }
  ```
  The contains-check + post-commit add is the smallest viable shape
  that preserves the spec's "audit row written EXACTLY ONCE per
  breach event" while ensuring the in-memory mark is consistent
  with DB state. If the audit INSERT or the COMMIT throws, the
  catch block at the outer try rolls back the DB AND leaves
  `breachAudited` untouched for that key — the next call retries
  the audit.
- **AUDIT-EVASION test seam.** The cleanest way to force the audit
  INSERT to fail in InviteCodeConsumerTest is a small `DataSource`
  decorator that wraps the Quarkus-injected production DataSource
  and routes `prepareStatement` calls whose SQL matches
  `^INSERT INTO audit_log` through a `PreparedStatement` proxy that
  throws SQLException on `executeUpdate`. The decorator can live
  as a static inner class of `InviteCodeConsumerTest` (no separate
  test-helper file — keeps the files_scope at 6). The test method
  injects the decorator via `@Inject` field swap (Mockito's
  `@InjectMock`) for the duration of the test only.
  - If `@InjectMock` is unavailable in the Quarkus 3.33 test setup,
    fall back to: a non-Quarkus JUnit test that constructs
    InviteCodeConsumer with a manually-built decorator DataSource
    pointed at the same Postgres test container. This is the same
    shape RateCapBucketTest uses (plain JUnit, no @QuarkusTest).
- **Idempotency.** The breach-branch contains-check makes the path
  idempotent under retry: if call N audit-inserts succeeded but
  commit failed (rare but possible), call N+1 will skip the audit
  insert because `breachAudited.contains(key)` is now true. Wait —
  this is wrong: the .add(key) only fires AFTER commit succeeds.
  So if commit fails, .add(key) is NOT called, and call N+1 will
  re-attempt the audit insert, which writes a SECOND audit row
  before commit. The spec says "exactly once per breach event."
  - Mitigation: the existing M1-044a invariant "exactly once per
    breach event" tolerates a second audit row on commit-retry
    (the spec language is "an audit row records the threshold
    breach," singular by intent but the over-once case is a
    rollback-driven artifact, not an attacker-controlled signal).
    The fix value is correctness under audit-insert failure (the
    common failure mode), not commit failure (which is rare and
    leaves no DB row at all — the second attempt's audit row IS
    the only audit row that survived).
  - Document this trade-off in the breach-branch's javadoc.

## Big-picture notes

- **The fix lands on the per-ticket branch `m1/M1-044d-redteam-fixes`
  and squash-merges separately from M1-044a.** The umbrella
  M1-044's roundtrip IT exercises happy paths; neither finding's
  scenario (exception-message leak; audit-insert failure under load)
  is exercised by the umbrella, so the umbrella's `blocked_by` does
  not need updating — M1-044 can still land after M1-044a/b/c
  without waiting for this remediation. (If the user prefers to
  serialize the umbrella behind the remediation, that's a separate
  call to add M1-044d to M1-044's blocked_by; this ticket does not
  make that call unilaterally.)
- **The low-severity DOS finding (RateCapBucket eviction predicate)
  is folded into M1-044b's pending ticket** as a new acceptance
  item bundled with M1-044b's existing per-profile rate-cap config
  work — see M1-044b's revisions log for the fold-in note.
- **The low-severity AUTH-BYPASS finding (InviteCodeConsumer.consume
  defense-in-depth)** is acknowledged in M1-044b's Big-picture
  notes pointing at M1-044b's existing acceptance items 2 and 4 as
  the caller-side gating that prevents the scenario. No code change
  is needed for that finding.
- **Why the M1-038 / M1-039 redaction precedent didn't catch this
  at M1-044a authoring time:** the M1-044a code reviewer's
  SCOPE-DRIFT-CHECK looks at acceptance items, not at spec promises
  the diff might unintentionally violate. The §Secrets handling
  spec commitment is implicit in the M1-038 / M1-039 precedent
  but wasn't transcribed into M1-044a's acceptance. Going forward,
  any new ticket touching code that constructs exception messages
  containing `contactId` should include a `ContactIds.redact` grep
  check in its acceptance — captured as a candidate
  [[feedback_acceptance_transcribe_spec_promises]] follow-up.

## Out-of-scope expansion

- **RateCapBucket.java.** The DOS finding's fix lives in M1-044b's
  expanded scope (eviction predicate widening), not here.
- **InviteCodeConsumer's defense-in-depth precondition (no users
  row exists).** M1-044b's existing acceptance items 2 / 4 / 5
  already enforce the caller-side ordering; no M1-044a-side change
  needed.
- **InboundRouter intake splice.** M1-044b territory.
- **Admin command handlers.** M1-044c territory.
- **Bundle keys + application.properties.** M1-044b territory.
- **Any V<N> migration.** No migrations in this ticket.
- **Any test outside the three test files in files_scope.** Every
  M1-035 / M1-036 / M1-037 / M1-038 / M1-039 / M1-040 / M1-043 /
  M1-044a test stays green unchanged. BanCheckTest.java and
  AutoRegisterServiceTest.java appear in files_scope only so the
  reviewer's NEGATIVE-SPACE-CHECK can confirm they are NOT
  inadvertently modified (assertion-level changes would be a
  test-integrity violation against the M1-044a baseline).

## Authorized test changes

- `InviteCodeConsumerTest.java` — M1-044a's existing seven test
  methods (acceptedContactBound, acceptedOpenAdapter,
  rejectedAlreadyUsed, rejectedExpired, rejectedCrossContact,
  rejectedCrossAdapter, bruteForceBreach) stay unchanged. The
  ticket ADDS one new test method
  (bruteForceBreachAuditFailureRetries) exercising the rollback-
  safety property. The new method MAY introduce one static inner
  class (a DataSource decorator) — this is a test-scaffolding
  addition, not a modification of existing assertions.
- (no other test is modified)

## Alternatives considered

- **Add `ContactIds.redact` as a per-package wrapper helper to
  avoid repeating the import.** Rejected — M1-038's precedent
  imports ContactIds directly into each consuming class; mirroring
  that shape keeps the diff symmetric with M1-038 / M1-039 / M1-036
  and avoids introducing a new abstraction layer. Three identical
  imports across the package is acceptable per CLAUDE.md
  §"Don't add features, refactor, or introduce abstractions
  beyond what the task requires."
- **Use a try/finally pattern in the breach branch where
  breachAudited.remove(key) fires on SQLException.** Considered —
  it would catch BOTH the audit-insert failure AND the commit
  failure cases. Rejected for v1 because (a) the contains-check +
  post-commit add is simpler and (b) the commit-failure-during-
  breach-audit case is rare AND the second audit row it produces
  is a known, documented trade-off per Implementation notes.
- **Land the AUDIT-EVASION fix as a stored-procedure-style
  invariant on the audit_log INSERT trigger.** Rejected — same
  reasoning as M1-044a's stored-procedure alternative: the v1
  audit-write path is application-side, the GRANT matrix
  constrains writes, and a trigger would couple this ticket to
  a Flyway migration without clear win.
- **Fold the INFO-LEAK fix into M1-044b's splice ticket instead of
  filing a separate remediation ticket.** Rejected — the M1-044a
  commit is `done` per the workflow's "Never amend a passed
  commit" rule. The remediation must be a new commit; folding
  into M1-044b would conflate splice work with M1-044a-correctness
  work, harming review focus on both.
