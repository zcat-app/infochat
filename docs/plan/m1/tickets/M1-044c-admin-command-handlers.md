---
id: M1-044c
title: Admin command handlers — /ban, /unban, /invite create/list/revoke
status: pending
escalations:
  - date: 2026-05-22
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL — 1 blocker, 0 warnings.
      Blockers:
        1. ACCEPTANCE-VS-DOD-CONSISTENT (acceptance item 16): the
           InviteCommandHandler dispatch acceptance item asserts
           `grep -E 'create|list|revoke' InviteCommandHandler.java
           returns >=3 matches across the three subcommand
           dispatch branches`. The DoD explicitly enumerates three
           named subcommand branches (create, list, revoke) each
           with a visibly different behavioral shape (INSERT + cap
           enforcement; paginated SELECT; PENDING→REVOKED
           transition). This is HETEROGENEOUS-AGGREGATE-NAMED with
           N=3 named elements — the N>=3 FAIL threshold. The
           alternation regex `create|list|revoke` is maskable:
           three occurrences of "create" (in variable names,
           String literals, comments, or import paths) would
           satisfy >=3 while leaving the `list` and `revoke`
           branches entirely absent. An implementer can ship
           InviteCommandHandler with only the create subcommand
           and pass this grep.
           Fix: Replace the aggregate with three separate
           acceptance greps:
             (a) `grep -E '"create"' InviteCommandHandler.java
                 returns >=1 match`
             (b) `grep -E '"list"' InviteCommandHandler.java
                 returns >=1 match`
             (c) `grep -E '"revoke"' InviteCommandHandler.java
                 returns >=1 match`
           Or adapt the regex to match only the dispatch case
           literal (e.g. in a switch or if-else chain) so each
           grep pins its own named branch independently. Item 16
           can remain one acceptance item with three verify lines,
           or can be split into 16a/b/c.
  - date: 2026-05-22
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL — 1 blocker, 0 warnings.
      Blockers:
        1. BODY-CLAIM-COVERAGE: §Implementation notes §UnbanCommandHandler
           preban-path request_id propagation commits via MUST that the
           handler issues `SET LOCAL infochat.request_id = ?` before
           `CALL delete_preban_user(...)` so the procedure-written
           UNBAN_PREBAN_DELETE audit row carries the same `request_id`
           as the dispatch. No acceptance item verifies this specific
           behavior: item 12 checks only that the audit row EXISTS
           (not its request_id value), and item 34's
           `grep -E 'request_id' UnbanCommandHandler.java` is satisfied
           by any occurrence of the string (including the UUID
           generation line) without requiring `SET LOCAL`. An
           implementer can omit SET LOCAL entirely and pass all 36
           acceptance items, leaving the UNBAN_PREBAN_DELETE audit row
           with a NULL request_id.
           Fix options:
             (a) extend item 12 to also assert the audit row's
                 request_id is non-null and matches the dispatch
                 (SELECT request_id FROM audit_log WHERE
                 action='UNBAN_PREBAN_DELETE' returns a non-null
                 value equal to the dispatch UUID), OR
             (b) add a separate acceptance item with
                 `grep -E 'SET LOCAL.*infochat[._]request_id'
                 UnbanCommandHandler.java` returns >=1 match, OR
             (c) strengthen item 34 for UnbanCommandHandler
                 specifically to pin SET LOCAL invocation.
  - date: 2026-05-22
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The ticket triggers the shared-dispatch-surface gate
      (files_scope contains three `*Command*.java` files under
      `provider/src/main/java/`). Per the gate, every test exercising
      the changed dispatch surface (CDI `Instance<CommandHandler>`
      selection in `InboundRouter`) must either be enumerated in
      `verified_stays_green:` or appear in §"Authorized test changes".
      Grepping `provider/src/test/` for tests that drive `InboundRouter`
      dispatch yields 15 tests. Only 10 appear in `verified_stays_green:`.
      The 5 stays-green tests not enumerated are:

        - app.zcat.infochat.provider.messaging.AdapterRouterIT — sends
          /help and /unknown-command via adapter.deliverDm(...); routes
          through the real InboundRouter dispatch path.
        - app.zcat.infochat.provider.messaging.InboundRouterTest —
          drives the router via @Inject InMemoryAdapter + deliverDm(...)
          for /help, /xyz, /boom, plus the rate-cap-overflow path.
        - app.zcat.infochat.provider.messaging.InboundRouterIntakeOrderingTest
          — drives router.onMessage(dmInbound(..., "/help"), ADAPTER)
          for the M1-044b intake-step splice ordering.
        - app.zcat.infochat.provider.messaging.InboundRouterNormalizeTest
          — drives the router through the normalization pipeline.
        - app.zcat.infochat.provider.messaging.InboundRouterContactIdRedactionTest
          — @Injects InboundRouter and exercises the dispatch path
          for contact-id redaction in logs.

      Each is stays-green by the same rationale already used for the
      four IT entries: the new handler beans bind to
      name() == "ban" | "unban" | "invite"; first-token dispatch in
      InboundRouter routes by name; the unenumerated tests send /help,
      /add-source, /summary, /xyz, /boom, or /unknown-command, none of
      which collide. The omission is enumeration completeness, not
      classification correctness. (AddSourceContactIdRedactionTest
      calls handler.handle() directly and never reaches the registry,
      so it is NOT exercising the changed dispatch surface and need
      not be enumerated; the other five do reach the registry.)

      The ticket's §Out-of-scope expansion bullet "M1-044b InboundRouter
      test surface (FROZEN at its review round per out_of_scope)" is
      the intended blanket-freeze, but the gate's text is explicit:
      "If a test classified stays-green is not listed in
      verified_stays_green, FAIL with reason 'stays-green test not
      enumerated'." The blanket freeze is not a structural substitute
      for per-test enumeration.

      SUGGESTED ESCALATION: refine
      The refine is small: add the five missing test classes to
      verified_stays_green: with rationale matching the existing
      four-IT pattern ("drives the full InboundRouter dispatch path
      but sends /help/xyz/boom/unknown-command only; first-token
      dispatch routes to a handler name that the three new handlers
      cannot intercept"). No body changes; no acceptance changes;
      no files_scope changes; no files_budget change. The
      clarity_check block should be re-cleared so the next
      /m1-tick start runs a fresh clarity pass.

      Additional flagged risk (not OUTLINE-failing, but worth a
      Implementation-notes bullet during refine): the V5
      delete_preban_user procedure reads request_id from
      current_setting('infochat.request_id', TRUE). The
      UnbanCommandHandler must SET LOCAL infochat.request_id = ?
      BEFORE the CALL so the procedure-written
      UNBAN_PREBAN_DELETE audit row carries the same request_id
      as the dispatch.
  - date: 2026-05-22
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL — 3 blockers, 3 warnings.
      Blockers:
        1. ACCEPTANCE-RUNNABLE (items 1, 2, 8): /ban confirm flow is claimed
           in Big-picture notes but acceptance item 1 has no confirm step and
           item 2's scenarios cover no confirm-prompt path.
        2. ACCEPTANCE-RUNNABLE (item 8): /invite revoke requires confirm but
           InviteCommandHandlerTest scenario (k) covers only the post-confirm
           execution path; no scenario covers the first-invocation confirm
           prompt.
        3. ACCEPTANCE-VS-DOD-CONSISTENT: items 2, 4, 9 use
           HETEROGENEOUS-AGGREGATE-NAMED @Test counts over 7 / 5 / 12 named
           scenarios; replace each with one per-scenario method-name
           assertion (M1-044b precedent).
      Warnings:
        - ACCEPTANCE-ORDERING-CONSISTENT: item 1 step 8 (audit) appears after
          mutation steps 5-7 but DoD says audit-before-effect.
        - SELF-CONTAINED-CHECK: confirm-flow protocol (stateless --confirm)
          conflicts with commands.md §Surface conventions timeout model.
        - ACCEPTANCE-RUNNABLE item 7: same confirm-flow conflict.
  - date: 2026-05-22
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL — 1 blocker, 1 warning.
      Blockers:
        1. BODY-CLAIM-COVERAGE: §Big-picture notes paragraph 2 + §Implementation
           notes §Common scaffolding both assert that the three admin handlers
           refuse to run in group scope and return `error.group_admin_not_in_v1`
           — a specific runtime behavior committed in present-tense indicative
           mood with a named reply string. None of the 36 acceptance items
           verifies this behavior (no grep for `group_admin_not_in_v1` in any
           handler file, no named test scenario for group-scope invocation, no
           behavioral assertion of the group-scope rejection reply). An
           implementer can omit the group-scope check from all three handlers
           and pass every acceptance grep.
      Warnings:
        - VERIFIED-STAYS-GREEN-PLAUSIBLE: AutoRegisterServiceTest FQN is wrong —
          actual package is `messaging`, not `intake`
          (infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/
          AutoRegisterServiceTest.java).
revisions:
  - date: 2026-05-22
    reason: clarity-fail refine round 4 (1 blocker ACCEPTANCE-VS-DOD-CONSISTENT — item 16 InviteCommandHandler dispatch aggregate grep `create|list|revoke` returns ≥3 is HETEROGENEOUS-AGGREGATE-NAMED with N=3 named elements, maskable) — split the aggregate into three per-subcommand SINGLE-ELEMENT greps
    summary: |
      Pre-refine snapshot. The round-4 clarity FAIL surfaced one
      ACCEPTANCE-VS-DOD-CONSISTENT blocker against acceptance item
      16 (InviteCommandHandler dispatch). The item's second verify
      line `grep -E 'create|list|revoke' InviteCommandHandler.java
      returns ≥3 matches across the three subcommand dispatch
      branches` is HETEROGENEOUS-AGGREGATE-NAMED with N=3 named
      elements (create / list / revoke); each branch has a visibly
      different behavioral shape (INSERT + cap enforcement;
      paginated SELECT; PENDING→REVOKED transition). The
      alternation regex is maskable: three occurrences of `create`
      alone (variable names, String literals, comments) satisfy
      ≥3 while leaving the `list` and `revoke` branches entirely
      absent. An implementer can ship InviteCommandHandler with
      only the create subcommand and pass this grep.

      Provenance. The aggregate grep predates the round-1
      clarity-fail refine. That round-1 refine replaced three
      OTHER aggregate greps in items 2, 4, 9 with per-scenario
      named greps but missed item 16's three-branch aggregate.
      Each subsequent clarity round caught one isolated blocker
      (BODY-CLAIM-COVERAGE on `error.group_admin_not_in_v1`;
      OUTLINE-FAILED on verified_stays_green completeness;
      BODY-CLAIM-COVERAGE on SET LOCAL request_id propagation)
      and item 16's aggregate slipped through three pre-flights
      because earlier rounds had higher-impact blockers that
      consumed reviewer attention. This refine is the catch-up
      on the latent item-16 issue.

      Refine action (no body / DoD / files_scope / files_budget
      change):

        1. Replace item 16's second verify line:
             `grep -E 'create|list|revoke' InviteCommandHandler.java
              returns ≥3 matches across the three subcommand
              dispatch branches`
           with three SINGLE-ELEMENT greps, one per named
           subcommand:
             (a) `grep -E '"create"' InviteCommandHandler.java`
                 returns ≥1 match
             (b) `grep -E '"list"' InviteCommandHandler.java`
                 returns ≥1 match
             (c) `grep -E '"revoke"' InviteCommandHandler.java`
                 returns ≥1 match
           Each regex matches the literal 8/6/8-char Java string-
           literal occurrence (`"create"` / `"list"` / `"revoke"`)
           — the only realistic places a quoted lowercase
           subcommand string appears in this handler are the
           dispatch case labels (switch expression per
           `CLAUDE.md` §Coding style "Prefer switch expressions"
           or string-equality branches like
           `"create".equals(token)`). Comment masking is not a
           concern: `"create"` requires literal double-quote
           chars around the word, which only appear in source
           string literals, not in line comments.
           Defense in depth: items 17–20 already pin each branch
           to its branch-specific SQL/keywords (CONTACT_BOUND /
           OPEN_ADAPTER / INSERT INTO invite_code /
           gen_random_uuid for create; `created_at DESC` and
           OPEN marker for list; UPDATE invite_code SET status =
           'REVOKED' for revoke), so even a deliberately
           subverted dispatch grep can't hide a missing branch.

        2. The first verify line (`grep -E 'public\s+String\s+name'
           InviteCommandHandler.java returns ≥1 match returning
           "invite"`) is unchanged.

        3. Item 16's prose is reworded from "dispatches on the
           first subcommand token (`create`, `list`, `revoke`);
           any other subcommand returns
           `error.invite.unknown_subcommand`" to "dispatches on
           the first subcommand token to a `create` / `list` /
           `revoke` branch; any other subcommand returns
           `error.invite.unknown_subcommand`" — semantically
           identical, just the / / / form to match the new
           verify lines visually.

        4. `clarity_check:` cleared so the next /m1-tick start
           runs a fresh clarity pass against the refined item 16.

      Acceptance count: 37 → 37 (item 16 stays one item with a
      four-grep verify line). files_budget held at 11;
      files_scope unchanged; complexity / risk / round_cap
      unchanged (high / high / 3). No body changes. No DoD
      changes.

      Full-ticket safety audit (paranoid pass to prevent another
      one-at-a-time clarity round):
        - Aggregate-count predicates: only item 16's
          `'create|list|revoke' ≥3` is HETEROGENEOUS-AGGREGATE-
          NAMED. All other count-bearing predicates are ≥1 (per
          grep) or `returns 1 match` (anchored bundle key
          greps). Confirmed by grep for `≥` / `>=` / `returns
          [0-9]` across the file.
        - HETEROGENEOUS-AGGREGATE-NAMED elsewhere: items with
          multiple greps linked by AND (items 1, 17, 18, 19, 34,
          35, 36) each pin distinct named elements with one
          grep per element — SINGLE-ELEMENT per grep, the safe
          pattern.
        - BODY-CLAIM-COVERAGE: §Big-picture notes claims (ship
          without confirm; handlers write directly to audit_log)
          covered by items 26/31/2-8 and item 35. §Implementation
          notes claims (audit-FIRST inside transaction;
          CallableStatement CALL; SET LOCAL request_id;
          group-admin restoration list in details_json) covered
          by items 1/8, 9/13, 10, 15. The "out-of-range page
          returns empty list" pagination note is design
          convention without a commitment verb — passes per
          calibration.
        - ACCEPTANCE-ORDERING-CONSISTENT: item 1 step 1.5
          (audit pre-write inside transaction before steps
          4-7 mutations) agrees with DoD ("audit INSERT runs
          FIRST inside the same transaction") and §Implementation
          notes §BanCommandHandler transaction shape. Item 9
          step 5 ordering (open transaction → pre-write audit →
          UPDATE → COMMIT) agrees with DoD audit-before-effect.
        - VERIFIED-STAYS-GREEN-PLAUSIBLE: all 15 entries
          previously verified by the clarity reviewer; no new
          entries added in this refine.
        - SPEC-REFS-VALID: 5/5 resolved previously; no changes.
        - FORWARD-REFERENCE-CHECK: all M1-* ticket references
          resolve previously; no changes.
        - GREP-EMBEDDED-QUOTE: new item-16 greps use single-
          quoted bash outer (`'"create"'`) and contain no
          literal apostrophe, so single-quote outer is correct.
        - lint-ticket.py: must pass post-refine (re-run before
          commit).
  - date: 2026-05-22
    reason: clarity-fail refine round 3 (1 blocker BODY-CLAIM-COVERAGE — SET LOCAL infochat.request_id propagation in UnbanCommandHandler had no acceptance hook) — option B (add single acceptance item with the SET LOCAL grep)
    summary: |
      Pre-refine snapshot. The round-3 clarity FAIL surfaced one
      BODY-CLAIM-COVERAGE blocker against §Implementation notes
      §UnbanCommandHandler preban-path request_id propagation, which
      commits via MUST that the handler issues
      `SET LOCAL infochat.request_id = ?` on the same Connection
      BEFORE the CALL delete_preban_user(...) so the procedure-
      written UNBAN_PREBAN_DELETE audit row carries the same
      request_id as the dispatch. The body claim was uncovered by
      acceptance — item 12 verified only that the audit row exists
      (not its request_id value), and item 34's
      `grep -E 'request_id' UnbanCommandHandler.java` was satisfied
      by any string occurrence (including the UUID generation line)
      without requiring SET LOCAL. An implementer could omit SET
      LOCAL entirely and pass all 36 acceptance items, leaving the
      procedure-written audit row's request_id NULL.

      Provenance. The offending §Implementation notes bullet was
      added by the round-1 outline-fail refine (revision entry
      directly below, action #2 — "Fold the V5 delete_preban_user
      request_id propagation risk into §Implementation notes as a
      bullet on the UnbanCommandHandler preban path"). That refine
      paired a body claim with no acceptance hook; BODY-CLAIM-
      COVERAGE (clarity-prompt §13, added 2026-05-22) catches the
      pattern but only on the NEXT /m1-tick start. The cost was one
      extra refine cycle that a refine-time coverage check could
      have avoided — a meta-pattern worth a memory entry.

      Refine action (option B — single new acceptance item):

        1. New acceptance item added immediately after item 9
           (UnbanCommandHandler dispatch sequence): "UnbanCommandHandler
           propagates `request_id` to the V5 `delete_preban_user`
           stored procedure via `SET LOCAL infochat.request_id = ?`
           on the same JDBC Connection BEFORE the `CALL
           delete_preban_user(...)`. The V5 procedure reads
           `request_id` from `current_setting('infochat.request_id',
           TRUE)` and writes it into the `UNBAN_PREBAN_DELETE` audit
           row ... Verify: `grep -E 'SET LOCAL.*infochat[._]request_id'
           UnbanCommandHandler.java` returns >=1 match."
        2. `clarity_check:` cleared so the next /m1-tick start runs
           a fresh clarity pass against the augmented acceptance.

      Acceptance count: 36 → 37. files_budget held at 11;
      files_scope unchanged (the new item is a source-grep against
      an in-scope file); complexity / risk / round_cap unchanged
      (high / high / 3). No body changes. DoD unchanged.
  - date: 2026-05-22
    reason: outline-fail refine (1 blocker test-scaffolding completeness — 5 InboundRouter dispatch-surface tests missing from verified_stays_green:)
    summary: |
      Pre-refine snapshot. The Plan subagent returned OUTLINE
      FAILED on the test-scaffolding completeness audit: the
      shared-dispatch-surface gate triggered (files_scope adds
      three *CommandHandler.java files), and grepping
      provider/src/test/ for tests that drive InboundRouter
      dispatch yielded 15 tests against the 10 currently
      enumerated under verified_stays_green:. The 5 missing
      classes are all in
      app.zcat.infochat.provider.messaging:
      AdapterRouterIT, InboundRouterTest,
      InboundRouterIntakeOrderingTest,
      InboundRouterNormalizeTest,
      InboundRouterContactIdRedactionTest. Each is stays-green
      by the same first-token-dispatch rationale already used
      for the four IT entries (SummaryIT / SummaryAdapterScopeIT
      / AddSourceIT / AddSourceAdapterScopeIT): the new
      handlers' name() values are "ban" / "unban" / "invite";
      the unenumerated tests dispatch /help, /add-source,
      /summary, /xyz, /boom, /unknown-command — none collide.
      The omission is enumeration completeness, not classifier
      error. The §Out-of-scope-expansion bullet
      "M1-044b InboundRouter test surface (FROZEN at its review
      round per out_of_scope)" is the intended blanket-freeze
      but is not a structural substitute for per-test
      enumeration per the gate text.

      Refine actions (small, no acceptance/body/files_scope
      change):
        1. Add five entries under verified_stays_green: with
           rationale matching the existing four-IT pattern.
        2. Fold the V5 delete_preban_user request_id
           propagation risk into §Implementation notes as a
           bullet on the UnbanCommandHandler preban path
           (handler must SET LOCAL infochat.request_id = ?
           BEFORE the CALL so the procedure-written
           UNBAN_PREBAN_DELETE audit row carries the same
           request_id as the dispatch).
        3. Clear clarity_check: so the next /m1-tick start
           runs a fresh clarity pass against the refined
           verified_stays_green: list.

      files_budget held at 11; files_scope unchanged;
      complexity / risk / round_cap unchanged (high / high / 3);
      acceptance item count unchanged at 30. No body claim
      changes.
  - date: 2026-05-22
    reason: clarity-fail refine round 2 (1 blocker BODY-CLAIM-COVERAGE + 1 warning VERIFIED-STAYS-GREEN-PLAUSIBLE) — option A (strip fabricated claim)
    summary: |
      Pre-refine snapshot. The round-2 clarity FAIL surfaced one
      blocker (BODY-CLAIM-COVERAGE: §Big-picture notes paragraph 2
      and §Implementation notes §Common scaffolding both committed
      handlers to "refuse to run in group scope" with a fabricated
      `error.group_admin_not_in_v1` reply, but no acceptance item
      verified it) and one warning
      (VERIFIED-STAYS-GREEN-PLAUSIBLE: AutoRegisterServiceTest FQN
      named the `intake` package; actual is `messaging`).

      Root-cause review with the user surfaced that the failing
      behavioral claim was itself fabricated context. The
      constraint "Group-scope admin commands are DM-only in v1"
      was never discussed and has no spec basis:

        - docs/spec/commands.md §Admin lines 810-963 list /ban,
          /unban, /invite create/list/revoke as bot-admin-only
          with NO mention of DM-only or scope restriction.
        - docs/spec/commands.md §Permission model lines 965-1011
          commits to "Bot-wide destructive operations require
          bot admin" — scope is not mentioned.
        - The bundle key `error.group_admin_not_in_v1` exists
          nowhere in spec, design, or the codebase. The only
          existing `*group_admin*` key is
          `error.add_source.group_admin_only` (M1-036) with a
          DIFFERENT semantic ("only group admins can do this,"
          not "this doesn't work in groups").
        - M1-039 defers actor-seam SPI widening to T2-F but does
          NOT introduce any "bot-admin DM-only" rule.

      Refine actions (option A — strip the fabricated claim):

        1. §Big-picture notes paragraph 2 ("Group-scope admin
           commands are DM-only in v1...") stripped entirely.
           The SPI-shape constraint and the T2-F deferral remain
           noted in §Common scaffolding and §Out-of-scope
           expansion; no behavioral commitment about group-scope
           rejection is made.
        2. §Implementation notes §Common scaffolding rewritten
           to describe ONLY how the DM-scope caller is identified
           (via `((ScopeRef.Dm) scope).contactId()`) and to note
           the SPI does not yet thread `Identity sender` in
           group scope (M1-039 / T2-F) — without committing to
           any group-scope user-visible behavior.
        3. §Out-of-scope expansion "Group-scope dispatch" bullet
           rewritten to drop the "handlers refuse with a
           friendly reply" claim; only the T2-F SPI-widening
           deferral remains.
        4. Fabricated bundle key `error.group_admin_not_in_v1`
           removed from prose. No acceptance item or
           BundleKeys constant enumeration referenced it (the
           round-1 refine had not added it; only the body
           prose contained it).
        5. `verified_stays_green:` AutoRegisterServiceTest FQN
           corrected:
           `app.zcat.infochat.provider.intake.AutoRegisterServiceTest`
           → `app.zcat.infochat.provider.messaging.AutoRegisterServiceTest`.
        6. `clarity_check:` block cleared so the next
           `/m1-tick start` runs a fresh clarity pass against
           the rewritten ticket.

      Group-scope behavior in v1 (verified, NOT skipped): /ban,
      /unban, /invite are bot-admin-only commands. Group-admin
      tier (a separate concept) is alive in v1 via the V5
      `group_membership` schema with `is_group_admin`, the
      `one_admin_per_group` partial unique index, the
      clear-admin trigger, and the M1-036 /add-source group
      gate; /promote and /demote land in T2-F. This refine
      does not skip any group-admin tier work — M1-044c
      never owned that tier in the first place.

      files_budget held at 11; files_scope unchanged;
      complexity / risk / round_cap unchanged (high / high / 3);
      acceptance item count unchanged at 36.
  - date: 2026-05-22
    reason: clarity-fail refine (3 blockers + 3 warnings + 4 lint-detected GREP-EMBEDDED-QUOTE + 1 OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE)
    summary: |
      Pre-refine snapshot. The round-1 clarity FAIL surfaced three
      structural issues plus three warnings, and a parallel
      `scripts/lint-ticket.py` run added five more BLOCKERs the
      reviewer did not surface (four GREP-EMBEDDED-QUOTE,
      one OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE).

      Refine actions:

        1. Confirm-flow DEFERRED to a follow-up ticket. The original
           ticket claimed `/ban` and `/invite revoke` ship with a
           stateless `--confirm` flag pattern (Big-picture notes
           paragraphs 1-3), but the spec at `docs/spec/commands.md`
           §Surface conventions lines 47-68 mandates an in-memory
           timeout-based pending-confirm model scoped to (user,
           scope) with "any other input cancels it" semantics. The
           ticket-as-drafted was a spec violation. Implementing the
           spec-compliant model would have grown M1-044c by
           ~3-4 files (ConfirmStateService + timeout config +
           cancellation wiring), pushing files_budget from 11 toward
           15 and reusing scaffolding across /ban + /invite revoke +
           future /grant-admin /revoke-admin. The chosen resolution:
           strip the confirm-flow commitment from M1-044c, add an
           out_of_scope entry for the follow-up ticket that will
           implement the spec-compliant ConfirmStateService as the
           canonical pattern for ALL destructive admin commands. v1
           ships /ban + /invite revoke WITHOUT confirm until that
           follow-up lands (a temporary spec deviation the follow-up
           remediates).

        2. Three aggregate `grep -E '@Test' returns ≥N` predicates
           replaced by per-method-name greps. The previous items 2,
           4, 9 each collapsed N structurally-distinct scenarios
           into one count assertion; an implementer could have
           deleted any single scenario and duplicated another
           without tripping the aggregate. The new shape: each
           scenario is its own acceptance item naming the test
           method by camelCase fragment (M1-044b precedent).

        3. Item 1 step ordering: the BAN handler's audit-write is
           promoted to step 1.5 (immediately after admin gate +
           parse) rather than step 8 (after every mutation). The
           DoD says audit-before-effect; the original step 8
           placement contradicted that ordering even though the
           prose claim was consistent.

        4. Four GREP-EMBEDDED-QUOTE sites (`''REVOKED''`,
           `''OPEN_ADAPTER''`, `''CONTACT_BOUND''`, second
           `''REVOKED''`) rewritten with double-quoted bash outer
           so the literal apostrophes around the SQL string
           literals survive shell parsing.

        5. `verified_stays_green:` populated. The lint heuristic
           matches all three handler files (`*Command*.java` under
           `provider/src/main/java/`). Adding new CommandHandler
           beans does not change the dispatch path for existing
           commands — the registry routes by name — so every
           existing handler test is trivially insulated. Each
           rationale cites the test's dispatch posture (calls
           handler directly per M1-049, uses RecordingInboundRouter,
           routes a different command name).

        6. Invite-revoke-on-ban SQL pinned to the spec-correct
           shape: `WHERE adapter = ? AND invite_type =
           'CONTACT_BOUND' AND expected_contact_id = ? AND status =
           'PENDING'`. The original item 1 grep accepted a broader
           pattern matching OPEN_ADAPTER pending invites too,
           contradicting the Implementation notes' spec
           interpretation (open invites are not bound to any
           contact, so the "open-but-bound-on-consume targeting
           that contact" phrase from §Invite-code registration
           cannot apply to a still-PENDING open invite).

      Original acceptance had 14 items; refined acceptance has 30
      items (most of the growth is the 7+5+12=24 per-method-name
      greps replacing 3 aggregate count predicates). files_budget
      held at 11; files_scope unchanged; complexity / risk /
      round_cap unchanged (high / high / 3). The clarity_check
      block is cleared so the next /m1-tick start runs a fresh
      clarity pass against the refined acceptance.
created: 2026-05-20
last_updated: 2026-05-22
blocked_by:
  - M1-044a
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
verified_stays_green:
  - test_class: app.zcat.infochat.provider.messaging.HelpCommandHandlerTest
    rationale: "M1-049 refactored to call handler.handle() directly with mocked collaborators; routes a different command name (\"help\") so a new BanCommandHandler/UnbanCommandHandler/InviteCommandHandler bean cannot intercept this test's dispatch"
  - test_class: app.zcat.infochat.provider.command.AddSourceCommandHandlerTest
    rationale: "M1-049 refactored to call handler.handle() directly with mocked collaborators; routes a different command name (\"add-source\") so the three new handler beans are unobservable from this test"
  - test_class: app.zcat.infochat.provider.command.AddSourceBanCheckOrderingTest
    rationale: "M1-049 refactored to call handler.handle() directly with mocked collaborators; routes a different command name and asserts only on the AddSourceCommandHandler's collaborator interactions"
  - test_class: app.zcat.infochat.provider.command.SummaryCommandHandlerTest
    rationale: "M1-049 refactored to call handler.handle() directly with mocked collaborators; routes a different command name (\"summary\") so the three new handler beans cannot intercept"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRegistryTest
    rationale: "uses a RecordingInboundRouter @Alternative that intercepts onMessage(); the real CommandHandler dispatch is never exercised, so adding new CommandHandler beans is unobservable from this test"
  - test_class: app.zcat.infochat.provider.messaging.AutoRegisterServiceTest
    rationale: "exercises AutoRegisterService directly without consulting the CommandHandler registry; the new handler beans are registered as separate CDI beans and do not interact with this service"
  - test_class: app.zcat.infochat.provider.command.SummaryIT
    rationale: "drives the full InboundRouter dispatch path but sends /summary inbounds only; first-token dispatch routes to SummaryCommandHandler.name()==\"summary\", which the three new handlers' name()s (\"ban\", \"unban\", \"invite\") cannot intercept"
  - test_class: app.zcat.infochat.provider.command.AddSourceIT
    rationale: "drives the full InboundRouter dispatch path but sends /add-source inbounds only; first-token dispatch routes to AddSourceCommandHandler.name()==\"add-source\" which the three new handlers cannot intercept"
  - test_class: app.zcat.infochat.provider.command.SummaryAdapterScopeIT
    rationale: "drives the full InboundRouter dispatch path but sends /summary inbounds only; same first-token routing argument as SummaryIT"
  - test_class: app.zcat.infochat.provider.command.AddSourceAdapterScopeIT
    rationale: "drives the full InboundRouter dispatch path but sends /add-source inbounds only; same first-token routing argument as AddSourceIT"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRouterIT
    rationale: "drives the full InboundRouter dispatch path but sends /help and /unknown-command inbounds only; first-token dispatch routes to HelpCommandHandler.name()==\"help\" or returns the unknown-command reply, neither of which the three new handlers' name()s (\"ban\", \"unban\", \"invite\") can intercept"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterTest
    rationale: "drives the full InboundRouter dispatch path but sends /help, /xyz, /boom only; first-token dispatch routes to HelpCommandHandler or the unknown-command reply, neither of which the three new handlers can intercept"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterIntakeOrderingTest
    rationale: "drives router.onMessage(dmInbound(..., \"/help\"), ADAPTER) only; first-token dispatch routes to HelpCommandHandler.name()==\"help\" which the three new handlers cannot intercept"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterNormalizeTest
    rationale: "drives the router through the normalization pipeline with non-ban/non-unban/non-invite inbounds; first-token dispatch never reaches the three new handlers"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterContactIdRedactionTest
    rationale: "@Injects InboundRouter and exercises the dispatch path for contact-id redaction in logs without sending /ban, /unban, or /invite; first-token dispatch routes elsewhere"
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §User ban and §Invite-code registration are the source of truth
  - any change to the M1-044a services (RateCapBucket, InviteCodeConsumer, BanCheck, AutoRegisterService, V12 migration) — consumed unchanged
  - any change to InboundRouter — M1-044b's commit, FROZEN at its review round
  - any change to the M1-044b bundle keys added for fixed replies (`error.invite.required`, `error.ban.fixed`, `reply.welcome.dm_fresh`, `reply.welcome.group_first_mention`) — this ticket adds its own handler-output keys but does NOT modify the intake-splice keys
  - any change to the V5 `delete_preban_user` stored procedure — consumed unchanged via CALL
  - any change to the V5 `invite_code` table or its `idx_invite_code_pending` index — consumed via SELECT/UPDATE
  - any /vouch handler — M1-045 territory (probation graduation + group_only → vouched advance)
  - any /grant-admin / /revoke-admin handler — M1-046 territory
  - any /promote / /demote handler — T2-F territory
  - the umbrella IT — M1-044 territory
  - any new `--all` flag on `/invite list` (the spec/design fixes `[--page N]` only; --all is for /quarantine list, NOT /invite list)
  - any audit-log-redaction-hook change — M1-041 territory; handlers write directly to `audit_log` with the same row shape M1-036's /add-source uses
  - any TranslationProvider exercise — T2-C territory; new bundle entries are English only
  - any test outside the eight files in files_scope — M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040 tests stay green unchanged (enumerated under `verified_stays_green:` above)
  - any confirm-flow implementation — `/ban` and `/invite revoke` require confirm per spec §Surface conventions, but the spec's in-memory timeout-based pending-confirm model (with "any other input cancels it" semantics) lands in a follow-up ticket that builds the canonical ConfirmStateService for ALL destructive admin commands (/ban, /invite revoke, /grant-admin, /revoke-admin, /quarantine, etc.). M1-044c ships /ban and /invite revoke WITHOUT confirm; the follow-up ticket retrofits the gate as a pre-dispatch ConfirmStateService.requireConfirm(scope, command) call. This is a deliberate temporary spec deviation
  - any `--confirm` flag parse path — would conflict with the spec's `<command> confirm` follow-up-message model the follow-up ticket implements
acceptance:
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java implements `CommandHandler` with `name() == \"ban\"`. The handler dispatch sequence is: (1) admin gate — require `users.is_admin = true` on the caller (consults the M1-040 InboundContext.adapterName() + `(adapter, contact_id)` SELECT); non-admin returns the `error.admin_only` bundle reply and writes no row; (2) parse one positional `<contact>` argument plus the optional `--reason \"...\"` flag; (3) self-ban rejection — if `actor.id == target.id` return `error.ban.cannot_ban_self` and write no row; (1.5) open one application-side transaction (Connection, `autoCommit=false`) and PRE-WRITE the BAN audit row INSIDE this transaction BEFORE any mutation per audit-before-effect; if any contact-bound pending invites would be revoked by step 7 below, also pre-write the corresponding INVITE_REVOKE audit row(s) at this step with the same `request_id`; (4) last-admin guard — the V5 `trg_last_admin_protection_update` trigger raises on `UPDATE users SET is_banned = TRUE` against the only `is_admin=TRUE AND is_banned=FALSE` row; the handler catches the trigger's exception (literal `last_admin_protection` in message), ROLLS BACK the transaction (audit row goes with it), and surfaces a friendly `error.ban.last_admin` reply; (5) for an unknown contact: MINT a `preban` row via `INSERT INTO users (adapter, contact_id, is_banned, registration_state, banned_at, banned_by, ban_reason) VALUES (?, ?, TRUE, 'preban', NOW(), ?, ?)`; (6) for a known contact: `UPDATE users SET is_banned = TRUE, banned_at = NOW(), banned_by = ?, ban_reason = ? WHERE id = ?`; (7) in the SAME transaction, transition every CONTACT_BOUND pending invite for `(adapter, expected_contact_id)` to `REVOKED` via `UPDATE invite_code SET status = 'REVOKED' WHERE adapter = ? AND invite_type = 'CONTACT_BOUND' AND expected_contact_id = ? AND status = 'PENDING'` (open invites are NOT revoked on the ban per spec — see Implementation notes for the spec interpretation); COMMIT. Verify: `grep -E 'public\\s+String\\s+name' BanCommandHandler.java` returns ≥1 match returning `\"ban\"`; `grep -E 'cannot_ban_self|self.ban|actor.*==.*target' BanCommandHandler.java` returns ≥1 match for self-ban guard; `grep -E \"UPDATE\\s+invite_code\\s+SET\\s+status\\s*=\\s*'REVOKED'\" BanCommandHandler.java` returns ≥1 match for the contact-bound pending-invite revoke; `grep -E \"invite_type\\s*=\\s*'CONTACT_BOUND'\" BanCommandHandler.java` returns ≥1 match for the spec-correct CONTACT_BOUND filter; `grep -E 'INSERT\\s+INTO\\s+audit_log' BanCommandHandler.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: a non-admin caller's /ban invocation returns `error.admin_only` and writes no row to `users` or `audit_log`. The test seeds a non-admin user, invokes the handler, asserts the reply equals the bundle value for `error.admin_only`, and asserts SELECT count(*) is unchanged on both tables. Verify: `grep -iE 'void\\s+\\w*banByNonAdminReturnsAdminOnly\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban targeting themselves (actor.id == target.id) returns `error.ban.cannot_ban_self` and writes no row. Verify: `grep -iE 'void\\s+\\w*banSelfReturnsCannotBanSelf\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban against an unknown contact MINTS a preban row with `is_banned=true`, `registration_state='preban'`, `banned_by=<actor.id>`, `ban_reason=<flag-value>`. The test asserts the row's full shape via SELECT after the handler returns. Verify: `grep -iE 'void\\s+\\w*banUnknownContactMintsPreban\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban against a known invited user UPDATEs the existing row with `is_banned=true`, `banned_at` set to a non-null timestamp, `banned_by=<actor.id>`, `ban_reason=<flag-value>`. The test seeds the row with `is_banned=false` then asserts the updated state via SELECT. Verify: `grep -iE 'void\\s+\\w*banKnownUserSetsIsBannedTrue\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban against a target with one PENDING `invite_type='CONTACT_BOUND'` invite transitions that invite to `REVOKED` in the SAME transaction. The test seeds (a) the target users row, (b) one PENDING CONTACT_BOUND invite for that contact, runs /ban, and asserts both the users row and the invite row reflect the new state via a single SELECT after commit. Verify: `grep -iE 'void\\s+\\w*banWithPendingContactBoundInviteRevokesItInSameTransaction\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban writes `BAN` + `INVITE_REVOKE` audit rows that share the same `request_id`. The test seeds a pending CONTACT_BOUND invite, runs /ban, then SELECTs both rows from `audit_log` and asserts their `request_id` values are equal. Verify: `grep -iE 'void\\s+\\w*banAndInviteRevokeAuditRowsShareRequestId\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's /ban against the only `is_admin=TRUE AND is_banned=FALSE` row (a deployment with one admin) raises the V5 `trg_last_admin_protection_update` trigger; the handler catches the exception, rolls back the transaction, and replies with `error.ban.last_admin`. The test asserts no row was modified (the audit row pre-written at step 1.5 also rolls back with the transaction). Verify: `grep -iE 'void\\s+\\w*banOfOnlyAdminSurfacesLastAdminError\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java implements `CommandHandler` with `name() == \"unban\"`. The handler dispatch sequence is: (1) admin gate — non-admin returns `error.admin_only`; (2) parse one positional `<contact>` argument; (3) resolve the target `users` row by `(inbound_adapter, target_contact_id)`; no row → `error.contact_not_registered` (the spec's Unknown-contact rule) and no DB write; (4) when the target row's `registration_state = 'preban'`, CALL the V5 `delete_preban_user(target.id, actor.id)` stored procedure — the procedure writes the `UNBAN_PREBAN_DELETE` audit row AND deletes the row in the same transaction; the handler's reply is `reply.unban.preban_deleted` (the bundle value contains the literals `pre-ban-only row removed` AND `fresh invite required`); (5) when the target row is non-preban, open an application-side transaction, pre-write the `UNBAN` audit row audit-before-effect, then `UPDATE users SET is_banned = FALSE, banned_at = NULL, banned_by = NULL, ban_reason = NULL WHERE id = ?`, then COMMIT; (6) the reply on the non-preban path enumerates restored group-admin rows when any exist via `SELECT g.id, g.display_name FROM group_membership gm JOIN groups g ON g.id = gm.group_id WHERE gm.user_id = ? AND gm.is_group_admin = TRUE` — the reply uses the `reply.unban.group_admins_restored` template that interpolates the list AND includes a `/demote <contact>` hint per spec §User ban; (7) when no group-admin rows exist on the non-preban path, the reply is the plain `reply.unban.plain` value. Verify: `grep -E 'CALL\\s+delete_preban_user' UnbanCommandHandler.java` returns ≥1 match; `grep -E 'pre-ban-only row removed|preban_deleted' UnbanCommandHandler.java` returns ≥1 match (the bundle key referenced for the preban-delete reply); `grep -E 'is_group_admin' UnbanCommandHandler.java` returns ≥1 match"
  - "UnbanCommandHandler propagates `request_id` to the V5 `delete_preban_user` stored procedure via `SET LOCAL infochat.request_id = ?` on the same JDBC Connection BEFORE the `CALL delete_preban_user(...)`. The V5 procedure reads `request_id` from `current_setting('infochat.request_id', TRUE)` and writes it into the `UNBAN_PREBAN_DELETE` audit row; without SET LOCAL the procedure-written audit row's `request_id` is NULL and the dispatch's audit trail loses correlation with the handler's other rows. The SET LOCAL value MUST equal the same `UUID.randomUUID().toString()` request_id the handler uses for the dispatch (so the procedure-written `UNBAN_PREBAN_DELETE` row and any handler-written rows for the same dispatch share one request_id). Verify: `grep -E 'SET LOCAL.*infochat[._]request_id' UnbanCommandHandler.java` returns ≥1 match"
  - "UnbanCommandHandlerTest scenario: a non-admin caller's /unban invocation returns `error.admin_only` and writes no row. Verify: `grep -iE 'void\\s+\\w*unbanByNonAdminReturnsAdminOnly\\w*\\s*\\(' UnbanCommandHandlerTest.java` returns ≥1 match"
  - "UnbanCommandHandlerTest scenario: an admin's /unban against an unknown contact (no `users` row at all) returns `error.contact_not_registered` and writes no row. Verify: `grep -iE 'void\\s+\\w*unbanUnknownContactReturnsContactNotRegistered\\w*\\s*\\(' UnbanCommandHandlerTest.java` returns ≥1 match"
  - "UnbanCommandHandlerTest scenario: an admin's /unban against a `registration_state='preban'` row CALLs the V5 `delete_preban_user(target.id, actor.id)` procedure; the test asserts (a) the users row is gone post-call, (b) one `UNBAN_PREBAN_DELETE` row exists in `audit_log` referencing the deleted user, (c) the reply matches `reply.unban.preban_deleted` AND contains the literals `pre-ban-only` AND `fresh invite`. Verify: `grep -iE 'void\\s+\\w*unbanOfPrebanRowCallsDeletePrebanUserProcedure\\w*\\s*\\(' UnbanCommandHandlerTest.java` returns ≥1 match"
  - "UnbanCommandHandlerTest scenario: an admin's /unban against a non-preban row when the target has ZERO `is_group_admin=true` rows flips `is_banned=false` via UPDATE, writes the `UNBAN` audit row, and replies with `reply.unban.plain`. Verify: `grep -iE 'void\\s+\\w*unbanOfNonPrebanWithoutGroupAdminsReturnsPlainReply\\w*\\s*\\(' UnbanCommandHandlerTest.java` returns ≥1 match"
  - "UnbanCommandHandlerTest scenario: an admin's /unban against a non-preban row when the target has ONE `is_group_admin=true` row flips `is_banned=false` via UPDATE, writes the `UNBAN` audit row whose `details_json.restored_group_admin` list contains the same group, AND replies with `reply.unban.group_admins_restored` (containing the group's display name AND the literal `/demote`). Verify: `grep -iE 'void\\s+\\w*unbanOfNonPrebanWithGroupAdminsReturnsRestoredReply\\w*\\s*\\(' UnbanCommandHandlerTest.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java implements `CommandHandler` with `name() == \"invite\"`. The handler dispatches on the first subcommand token to a `create` / `list` / `revoke` branch; any other subcommand returns `error.invite.unknown_subcommand`. Verify: `grep -E 'public\\s+String\\s+name' InviteCommandHandler.java` returns ≥1 match returning `\"invite\"`; `grep -E '\"create\"' InviteCommandHandler.java` returns ≥1 match; `grep -E '\"list\"' InviteCommandHandler.java` returns ≥1 match; `grep -E '\"revoke\"' InviteCommandHandler.java` returns ≥1 match"
  - "`/invite create` flag parsing: requires `--adapter <name>`; requires EXACTLY ONE of `--contact <id>` or `--open`; neither → `error.invite.missing_flag` (lists both options per spec); both → `error.invite.mutually_exclusive`. The `--adapter <name>` value is validated against the set of currently-enabled adapters at parse time — naming an unknown adapter returns `error.invite.unknown_adapter` per spec §Admin `/invite create`. Pre-banned-contact rejection: `/invite create --contact <id>` where the (adapter, contact_id) row exists with `is_banned=true` returns `error.invite.banned_target` pointing the admin at `/unban`; NO invite is created. Pre-flight cap check: `/invite create --open` enforces the per-adapter open cap from `infochat.invite.open-cap-per-adapter` — the count query filters `invite_type = 'OPEN_ADAPTER' AND status = 'PENDING' AND (expires_at IS NULL OR expires_at > NOW())` per spec §Invite-code registration (`Codes that are USED, REVOKED, or whose expires_at has passed do not count toward either cap`); over-cap returns `error.invite.open_cap_met` with the current open-code list and a `/invite revoke` hint. `/invite create --contact <id>` enforces the global contact cap from `infochat.invite.contact-cap-global` via the same shape (filter on `invite_type='CONTACT_BOUND'`, no adapter scope); over-cap returns `error.invite.contact_cap_met`. Verify: `grep -E \"invite_type\\s*=\\s*'OPEN_ADAPTER'\" InviteCommandHandler.java` returns ≥1 match AND `grep -E \"invite_type\\s*=\\s*'CONTACT_BOUND'\" InviteCommandHandler.java` returns ≥1 match AND `grep -E 'is_banned' InviteCommandHandler.java` returns ≥1 match"
  - "`/invite create` happy-path (both --contact and --open): writes the row via `INSERT INTO invite_code (code, invite_type, adapter, expected_contact_id, status, created_by, created_at, expires_at) VALUES (gen_random_uuid(), ?, ?, ?, 'PENDING', ?, NOW(), NOW() + <ttl>)` — the V5 schema's iff-CHECK enforces that `expected_contact_id` is non-null iff `invite_type = 'CONTACT_BOUND'`. The reply (`reply.invite.created`) carries the new code's UUID literal once. The `INVITE_CREATE` audit row is pre-written audit-before-effect with `target_kind='invite'`, `target_id=<code-uuid::text>`, `target_contact_id=<expected_contact_id or NULL>`, `details_json={\"invite_type\": \"...\", \"adapter\": \"...\"}`. Both `--contact` and `--open` paths execute on first invocation in this ticket — confirm flow is deferred per the Big-picture notes / out_of_scope entry; the follow-up ticket will retrofit the spec's in-memory pending-confirm gate as a pre-dispatch service call. Verify: `grep -E 'INSERT\\s+INTO\\s+invite_code' InviteCommandHandler.java` returns ≥1 match; `grep -E 'gen_random_uuid' InviteCommandHandler.java` returns ≥1 match"
  - "`/invite list` lists `PENDING` rows from `invite_code` where `(expires_at IS NULL OR expires_at > NOW())` — implements the spec's active-pending filter. Sort by `created_at DESC`. Paginated; page size 20 (`docs/design/03-commands.md` §3.10). Output format from `reply.invite.list_entry` template: `<code prefix> · adapter=<adapter> · target=<contact_id or 'OPEN'> · expires=<ISO timestamp>` (exact field shape implementer's choice as long as the bundle template matches). Open-vs-contact-bound distinguishability is mandatory: every `invite_type='OPEN_ADAPTER'` row carries the literal `OPEN` marker per spec §Invite-code registration (`The list output must visually distinguish --open codes from --contact codes`). Verify: `grep -E 'OPEN' bundles/en.properties` returns ≥1 match in the `reply.invite.list_entry` template or its OPEN-variant key; `grep -E 'created_at\\s+DESC' InviteCommandHandler.java` returns ≥1 match"
  - "`/invite revoke <code>`: open an application-side transaction, pre-write the `INVITE_REVOKE` audit row inside the transaction audit-before-effect, then `UPDATE invite_code SET status = 'REVOKED' WHERE code = ? AND status = 'PENDING' RETURNING id`. Zero rows returned (code already USED/REVOKED/absent) → ROLL BACK and reply `error.invite.revoke_not_pending`; one row returned → COMMIT and reply `reply.invite.revoked`. Confirm-gate is deferred per the Big-picture notes / out_of_scope entry; the handler executes on first invocation in this ticket. Verify: `grep -E \"UPDATE\\s+invite_code\\s+SET\\s+status\\s*=\\s*'REVOKED'\" InviteCommandHandler.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite` with no subcommand → `error.invite.unknown_subcommand` and no DB write. Verify: `grep -iE 'void\\s+\\w*inviteWithoutSubcommandReturnsUnknownSubcommand\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create` with neither --contact nor --open → `error.invite.missing_flag` and no DB write. Verify: `grep -iE 'void\\s+\\w*inviteCreateWithoutContactOrOpenReturnsMissingFlag\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --contact x --open` (both flags) → `error.invite.mutually_exclusive` and no DB write. Verify: `grep -iE 'void\\s+\\w*inviteCreateWithBothFlagsReturnsMutuallyExclusive\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter unknown --contact x` → `error.invite.unknown_adapter` and no DB write. Verify: `grep -iE 'void\\s+\\w*inviteCreateWithUnknownAdapterReturnsUnknownAdapter\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter inmemory --contact x` where x is pre-banned (is_banned=true) → `error.invite.banned_target` and no `invite_code` row is created. Verify: `grep -iE 'void\\s+\\w*inviteCreateAgainstBannedContactReturnsBannedTarget\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter inmemory --contact x` happy-path → one PENDING `invite_type='CONTACT_BOUND'` row exists post-call with `expected_contact_id=x`; reply contains the new code's UUID; one `INVITE_CREATE` audit row exists with the matching `request_id`. Verify: `grep -iE 'void\\s+\\w*inviteCreateContactBoundHappyPath\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter inmemory --open` happy-path → one PENDING `invite_type='OPEN_ADAPTER'` row exists post-call with `expected_contact_id=NULL`; reply contains the new code's UUID; one `INVITE_CREATE` audit row exists. Confirm flow is deferred to the follow-up ticket; this test invokes the handler ONCE and asserts the row is created on first invocation. Verify: `grep -iE 'void\\s+\\w*inviteCreateOpenHappyPath\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter inmemory --contact y` when the global contact-bound cap is at its limit (seed N existing PENDING CONTACT_BOUND invites where N equals the cap) → `error.invite.contact_cap_met` and no new row. Verify: `grep -iE 'void\\s+\\w*inviteCreateWhenContactCapMetReturnsContactCapMet\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite create --adapter inmemory --open` when the per-adapter open cap is at its limit → `error.invite.open_cap_met` and no new row. Verify: `grep -iE 'void\\s+\\w*inviteCreateWhenOpenCapMetReturnsOpenCapMet\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite list` with N PENDING rows of mixed `invite_type` returns all N entries sorted by `created_at DESC`; every `OPEN_ADAPTER` row carries the literal `OPEN` marker in the rendered output; expired rows (where `expires_at <= NOW()`) are filtered out; the first page contains the first 20 entries. Verify: `grep -iE 'void\\s+\\w*inviteListReturnsActivePendingRowsSortedByCreatedAtDesc\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite revoke <code>` against a PENDING row → row transitions to REVOKED, reply matches `reply.invite.revoked`, one `INVITE_REVOKE` audit row exists. Confirm flow is deferred to the follow-up ticket; this test invokes the handler ONCE and asserts the transition on first invocation. Verify: `grep -iE 'void\\s+\\w*inviteRevokeHappyPathTransitionsRowToRevoked\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: `/invite revoke <code>` against an already-REVOKED row → `error.invite.revoke_not_pending` and no audit row written. Verify: `grep -iE 'void\\s+\\w*inviteRevokeOnAlreadyRevokedReturnsNotPending\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "BundleKeys.java adds new public constants for every key the three handlers reference. At minimum: ERROR_ADMIN_ONLY, ERROR_CONTACT_NOT_REGISTERED, ERROR_BAN_CANNOT_BAN_SELF, ERROR_BAN_LAST_ADMIN, REPLY_UNBAN_PREBAN_DELETED, REPLY_UNBAN_GROUP_ADMINS_RESTORED, REPLY_UNBAN_PLAIN, ERROR_INVITE_UNKNOWN_SUBCOMMAND, ERROR_INVITE_MISSING_FLAG, ERROR_INVITE_MUTUALLY_EXCLUSIVE, ERROR_INVITE_UNKNOWN_ADAPTER, ERROR_INVITE_BANNED_TARGET, ERROR_INVITE_OPEN_CAP_MET, ERROR_INVITE_CONTACT_CAP_MET, ERROR_INVITE_REVOKE_NOT_PENDING, REPLY_INVITE_CREATED, REPLY_INVITE_LIST_HEADER, REPLY_INVITE_LIST_ENTRY, REPLY_INVITE_REVOKED, REPLY_BAN_SUCCESS. (The previous draft listed REPLY_INVITE_CONFIRM_OPEN; that key is removed because the confirm flow is deferred to a follow-up ticket — see the confirm-deferral entry in out_of_scope.) The exact constant names are implementer's choice as long as the bundle key strings match `error.*` / `reply.*` shape from the spec/design. Verify: every NEW constant on BundleKeys.java has a matching key in bundles/en.properties — this is the M1-035c reflective bundle-completeness assertion the existing BundleLoaderTest enforces"
  - "bundles/en.properties adds the corresponding entries. The unban-preban-deleted entry's value contains the literals `pre-ban-only row removed` AND `fresh invite required` per spec §User ban. The unban-group-admins-restored entry uses MessageFormat `{0}` for the comma-joined group list AND includes the literal `/demote` (the hint) per spec. The ban-success entry interpolates the redacted target contact id (the actor sees their own command output; the contact id is the redacted form per §Secrets handling). Verify: `grep -E '^reply\\.unban\\.preban_deleted\\s*=' bundles/en.properties` returns 1 match AND the value contains both `pre-ban-only` AND `fresh invite`; `grep -E '^reply\\.unban\\.group_admins_restored\\s*=' bundles/en.properties` returns 1 match AND the value contains `/demote`"
  - "Every audit-log write across the three handlers goes through `INSERT INTO audit_log (...)` directly (the M1-036 / M1-039 pattern). The M1-041 AuditLogWriter consolidation is deferred. Each handler's audit-write site uses `request_id = UUID.randomUUID().toString()` at the start of the dispatch and uses the same request_id for every audit row in the same dispatch (the BAN + INVITE_REVOKE pair on /ban is the canonical correlated-rows shape per spec — both must carry the same request_id). Verify: `grep -E 'request_id' BanCommandHandler.java` returns ≥1 match; `grep -E 'request_id' UnbanCommandHandler.java` returns ≥1 match; `grep -E 'request_id' InviteCommandHandler.java` returns ≥1 match"
  - "Every contact-id-bearing exception message in the three handlers (IllegalStateException construction paths around the SQL execute blocks) interpolates the contact id via ContactIds.redact from M1-038. Verify: `grep -E 'ContactIds\\.redact' BanCommandHandler.java` returns ≥1 match; `grep -E 'ContactIds\\.redact' UnbanCommandHandler.java` returns ≥1 match; `grep -E 'ContactIds\\.redact' InviteCommandHandler.java` returns ≥1 match (M1-039 precedent — the contact id appears only in the redacted form in non-audit logs)"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass per the `verified_stays_green:` enumeration above: M1-035c HelpCommandHandlerTest / AutoRegisterServiceTest / BundleLoaderTest, M1-036 AddSourceCommandHandler tests, M1-037 SummaryCommandHandler tests, M1-038 InboundRouter*Tests, M1-039 AddSourceBanCheckOrderingTest / AddSourceContactIdRedactionTest, M1-040 SummaryProseInjectionTest / AddSourceAdapterScopeIT / SummaryAdapterScopeIT, M1-043 (when it lands), M1-044a RateCapBucketTest / InviteCodeConsumerTest / BanCheckTest / AutoRegisterServiceTest, plus the M1-044b InboundRouter test surface (FROZEN at its review round per out_of_scope)"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
  preserves:
    - all tests currently green on main
    - M1-044a's per-service tests
spec_refs:
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D9
  - D11
  - D44
  - D46
---

# M1-044c: Admin command handlers — /ban, /unban, /invite create/list/revoke

## Context

T2-A.1 subticket 3 of 3 (parallel to M1-044b after M1-044a's
services land). Ships the five admin command handlers that
mutate the state the M1-044b intake-step splice reads:

- `/ban <contact> [--reason "..."]` — mints a `preban` row for
  unknown contacts (the spec's pre-ban carve-out); flips
  `is_banned = TRUE` for known contacts; revokes any pending
  CONTACT_BOUND invites for the same `(adapter, contact_id)` in
  the same transaction; audit-before-effect.
- `/unban <contact>` — deletes the row via the V5
  `delete_preban_user` stored procedure for preban rows;
  flips `is_banned = FALSE` for non-preban rows; enumerates
  restored group-admin rows in the reply AND in the audit
  row's `details_json`.
- `/invite create --adapter <name> {--contact <id> | --open}`
  — mints a single-use PENDING `invite_code` row; enforces
  the per-adapter open cap and global contact cap; rejects
  banned targets; audit-before-effect; cross-adapter creation
  is permitted (the one admin command that takes `--adapter`).
- `/invite list [--page N]` — paginated read of PENDING +
  not-expired codes; distinguishes OPEN_ADAPTER rows with a
  prominent marker.
- `/invite revoke <code>` — transition PENDING → REVOKED;
  audit-before-effect. **Confirm-gate deferred to a follow-up
  ticket** (see Out-of-scope expansion); v1 ships without the
  confirm gate.

The handlers consume:

- The M1-040 `@Inject InboundContext` bean for the
  inbound-adapter scope (every handler except `/invite create`
  which takes an explicit `--adapter` flag).
- The V5 `delete_preban_user` stored procedure for the unban
  preban path (the procedure writes its own
  `UNBAN_PREBAN_DELETE` audit row internally — the handler
  does not duplicate).
- The V5 `trg_last_admin_protection_update` and
  `trg_last_admin_protection_delete` triggers as the
  last-line defense for the last-admin invariant; the
  handlers surface trigger exceptions as friendly errors so
  the user sees a UX-friendly reply rather than a stack
  trace.
- The V5 `invite_code` table for create / list / revoke; the
  M1-044a `idx_invite_code_pending` index supports the cap
  count queries.
- The M1-038 `ContactIds.redact` helper for exception
  message interpolation.

`complexity: high` and `risk: high` because the handlers
implement multiple security-critical invariants in one
ticket: pre-ban + invite-revoke-on-ban + last-admin
protection + per-cap enforcement + audit-before-effect across
five command surfaces. The `round_cap: 3` accommodates
likely-to-fail-first acceptance items.

`security_relevant: true`.

`migration_touch: false` — V5 + V12 are the only migrations
T2-A.1 touches; M1-044a's V12 covers it. This ticket consumes
the schema as-is.

## Definition of Done

- Three handler classes (`BanCommandHandler`,
  `UnbanCommandHandler`, `InviteCommandHandler`) each
  implement `CommandHandler` with the appropriate `name()`.
  `InviteCommandHandler` dispatches on the first subcommand
  token to `create` / `list` / `revoke`.
- Every spec rule from §User ban and §Invite-code
  registration that maps to a command surface lands here,
  EXCEPT the confirm-gate for `/ban` and `/invite revoke`
  which is deferred to a follow-up ticket: preban +
  invite-revoke-on-ban (CONTACT_BOUND only per spec
  interpretation), last-admin protection, unban side-effect
  disclosure, cross-adapter invite creation, per-adapter open
  cap, global contact cap, brute-force rejection of banned
  targets, single-use atomicity, TTL + inclusive-expiry
  boundary handling.
- Each handler writes its audit row(s) audit-before-effect
  per Invariant 7 — the audit INSERT runs FIRST inside the
  same transaction as the state mutation; a transaction
  roll-back leaves no audit-vs-state divergence.
- New bundle keys + entries land in `BundleKeys.java` +
  `bundles/en.properties`; the M1-035c reflective
  bundle-completeness assertion in `BundleLoaderTest` covers
  the new keys automatically.
- Per-handler tests against a Testcontainers Postgres exercise
  every acceptance scenario (one `@Test` method per scenario,
  24 total across the three test classes).
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Common scaffolding.** Each handler `@Inject`s
  `DataSource`, `BundleLoader`, `InboundContext`. Each
  handler resolves the caller via the M1-040 adapter-scoped
  SELECT `SELECT id, is_admin, is_banned, registration_state
  FROM users WHERE adapter = ? AND contact_id = ?` against
  the (adapter, contact_id) UNIQUE constraint. The
  `contact_id` for the caller comes from
  `((ScopeRef.Dm) scope).contactId()` in DM scope. The
  CommandHandler SPI does not yet thread the sender's
  `Identity` through to the handler in group scope (M1-039 /
  T2-F); this ticket does not widen the SPI and adds no
  special group-scope branch.
- **`BanCommandHandler` transaction shape.** Wrap the audit
  pre-write + ban + invite revoke writes in one
  application-side transaction (open one Connection, set
  autoCommit=false, COMMIT at the end). The audit row INSERT
  runs FIRST inside the transaction (step 1.5 in acceptance
  item 1); the user-row mutation and the invite-revoke UPDATE
  run AFTER. The trigger `trg_last_admin_protection_update`
  raises `SQLException` with the literal `last_admin_protection`
  in its message — match on that to surface the friendly
  reply rather than a stack trace; the rollback discards the
  pre-written audit row along with the failed mutation.
- **Pre-ban INSERT shape.** Mirror the AutoRegisterService
  M1-044a INSERT but with `is_banned=TRUE` and
  `registration_state='preban'` and the additional
  `banned_at`/`banned_by`/`ban_reason` columns.
- **Invite revoke during ban.** The spec at §Invite-code
  registration says:
  > "If `/ban <contact>` runs while one or more `PENDING`
  > invites exist for the same `(adapter, contact_id)`
  > (either pre-bound via `--contact` or
  > open-but-bound-on-consume targeting that contact), every
  > such invite is transitioned to `REVOKED` in the same
  > transaction as the ban."

  The "open-but-bound-on-consume targeting that contact"
  phrase is the trip wire: an `--open` invite is NOT bound at
  creation time, so it cannot be "open-but-bound targeting
  that contact" until it's consumed (at which point it's no
  longer PENDING). The spec's intent is therefore:
  - `--contact` invites where `expected_contact_id = <target>`
    are REVOKED on the ban.
  - `--open` invites are NOT revoked on the ban (the open
    invite remains available to any other unknown contact).

  The implementation SQL is `UPDATE invite_code SET status =
  'REVOKED' WHERE adapter = ? AND invite_type =
  'CONTACT_BOUND' AND expected_contact_id = ? AND status =
  'PENDING'`. Acceptance item 1 pins both the OPEN-exclusion
  filter AND the CONTACT_BOUND filter explicitly.
- **`UnbanCommandHandler` preban path.** The V5 stored
  procedure `delete_preban_user(p_user_id UUID, p_actor_id
  UUID)` runs with SECURITY DEFINER and writes the audit row
  before deleting. The handler must CALL this procedure via
  JDBC `CallableStatement` rather than running its own DELETE
  (the Provider role has NO direct DELETE on `users` — the
  V5 GRANT block confirms this). The handler validates that
  `p_actor_id` is a real `is_admin=true` user before issuing
  the CALL (the procedure has no caller-side validation per
  the M1-008a red-team finding; the application layer is the
  trust boundary here). Pre-check: `actor.is_admin = true`
  already enforces this for the `/unban` command path.
- **`UnbanCommandHandler` preban-path `request_id`
  propagation.** The V5 `delete_preban_user` procedure reads
  `request_id` from
  `current_setting('infochat.request_id', TRUE)` and writes
  it into the `UNBAN_PREBAN_DELETE` audit row. The handler
  MUST `SET LOCAL infochat.request_id = ?` on the same
  Connection BEFORE the `CALL delete_preban_user(...)` so the
  procedure-written audit row carries the same `request_id`
  as the dispatch. Without this, the audit row's `request_id`
  is NULL and the dispatch's audit trail loses correlation.
- **`UnbanCommandHandler` group-admin restoration disclosure.**
  When the row is non-preban, after the UPDATE, SELECT the
  user's `is_group_admin=true` rows; if any, the reply uses
  the `reply.unban.group_admins_restored` template. The
  audit row's `details_json` carries the same list under the
  `restored_group_admin` key per spec.
- **Cap query.** The PENDING cap query uses the V5
  `idx_invite_code_pending` partial index automatically since
  the WHERE clause filters `status = 'PENDING'`. The
  `expires_at` filter adds a row-level evaluation per index
  hit; Postgres handles this cleanly for the small cap sizes.
- **`/invite list` pagination.** Page size 20 from
  `docs/design/03-commands.md` §3.10. The 1-indexed `--page
  N` flag. Out-of-range page returns the empty list (no
  error).
- **Self-ban guard placement.** The check runs INSIDE the
  handler (`actor.id == target.id`), NOT at the trigger
  layer — the trigger has no signal of which connection
  issued the UPDATE per the M1-008a red-team finding. The
  in-handler check is the only line of defense; pin it
  carefully.
- **Pre-banned contact rejection on `/invite create
  --contact`.** Check `is_banned=true` on the
  `(adapter, expected_contact_id)` row at parse time;
  reject with the friendly `error.invite.banned_target` reply
  AND no INSERT.
- **Cross-adapter `/invite create` — the one exception.**
  Unlike `/ban`, `/unban`, `/vouch`, etc., `/invite create`
  reads the adapter from the `--adapter <name>` flag, NOT
  from `InboundContext.adapterName()`. The
  `InboundContext.adapterName()` is the **inbound** adapter
  (where the bot admin ran the command); the target adapter
  is the flag. Both may differ. The pre-banned-contact check
  and the cap check use the FLAG adapter. The audit row's
  `actor_adapter` field carries the **inbound** adapter; the
  audit row's `details_json` carries the target adapter.
  This is the spec's intentional "high-assurance admin onboards
  a contact on the lower-assurance adapter" pattern.
- **Audit-row shape.** Every BAN / UNBAN / INVITE_CREATE /
  INVITE_REVOKE row carries: `actor_user_id=actor.id`,
  `actor_contact_id=actor.contactId`,
  `actor_adapter=inboundContext.adapterName()`,
  `action=<verb>`, `target_kind` per the row's natural target
  (`'user'` for BAN/UNBAN/INVITE_REVOKE-against-contact-bound,
  `'invite'` for INVITE_CREATE/INVITE_REVOKE), `target_id`
  per the natural key, `target_contact_id` per the
  redaction-time denormalization, `scope_id=NULL` (DM scope),
  `request_id=UUID.randomUUID().toString()`, `details_json`
  per-verb shape.

## Big-picture notes

- **Confirm-gate deferred to a follow-up ticket.** The spec
  at `docs/spec/commands.md` §Surface conventions lines 47-68
  mandates that destructive commands (including `/ban` and
  `/invite revoke`) require a follow-up `<command> confirm`
  within a fixed profile-tunable timeout, with in-memory
  pending state scoped to (user, scope) and "any other input
  cancels it" semantics. A spec-compliant implementation
  requires a ConfirmStateService bean + timeout config +
  cancellation wiring; that scaffolding is shared across
  `/ban`, `/invite revoke`, `/grant-admin` (M1-046),
  `/revoke-admin` (M1-046), and future destructive commands.
  Implementing it here would have grown M1-044c past its
  files_budget (the original draft tried to shortcut with a
  stateless `--confirm` flag, which violated the spec's
  timeout model). The chosen path: ship M1-044c WITHOUT
  confirm, file a follow-up ticket that lands the canonical
  ConfirmStateService and retrofits the gate as a
  pre-dispatch check across all destructive admin commands.
  This is a deliberate temporary spec deviation; the
  follow-up ticket closes it.
- **The audit-write helper consolidation is deferred to
  M1-041.** Handlers write directly to `audit_log` here; a
  future ticket replaces the per-handler INSERT with a shared
  AuditLogWriter facade. The verbs used here
  (`BAN`, `UNBAN`, `INVITE_CREATE`, `INVITE_REVOKE`) are all
  in the V5 closed catalogue at lines 281-287.

## Out-of-scope expansion

- **M1-044a services.** Consumed unchanged.
- **InboundRouter intake splice.** M1-044b.
- **The umbrella's roundtrip IT.** M1-044.
- **/vouch handler.** M1-045 (also lifts the DM gate from
  `group_only`).
- **/grant-admin, /revoke-admin.** M1-046.
- **/promote, /demote.** T2-F (group context only).
- **/quarantine commands.** T2-G.
- **AuditLogWriter consolidation.** M1-041.
- **Confirm-gate / ConfirmStateService.** Deferred to a
  follow-up ticket per the Big-picture notes; v1 ships /ban
  and /invite revoke without confirm. The follow-up ticket
  builds the spec-compliant in-memory pending-confirm service
  and retrofits the gate as a pre-dispatch check across
  /ban, /invite revoke, /grant-admin, /revoke-admin, and
  /quarantine commands.
- **Group-scope dispatch.** T2-F lands the CommandHandler
  SPI widening that threads `Identity sender` through to
  handlers; this ticket does not widen the SPI.
- **TranslationProvider exercise.** T2-C; new entries are
  English only.

## Authorized test changes

- (none — this ticket adds three new test files and modifies
  no pre-existing test.)

## Alternatives considered

- **Split into three separate handler tickets (one per
  command surface).** Rejected — the three commands share
  spec section, share the M1-040 InboundContext consumption
  pattern, and share bundle-key authoring. The cost of a
  unified ticket (30 acceptance items, 8 files) is lower
  than three tickets of 4-5 items each because the shared
  scaffolding amortizes. The reviewer's must-shrink on
  REWORK still applies; round_cap: 3 buys headroom for the
  per-handler acceptance refinements.
- **Implement /invite create / list / revoke as three
  separate `CommandHandler` classes with `name()` returning
  `"invite-create"` / `"invite-list"` / `"invite-revoke"`.**
  Rejected — the spec/design (`docs/spec/commands.md` §Admin)
  uses the subcommand shape `/invite <subcommand>`, so the
  dispatcher routes on the first token after `/invite`. A
  single handler with subcommand dispatch is the spec-correct
  shape. If the M1-035b handleSlash dispatcher cannot do
  subcommand matching, the InviteCommandHandler does the
  inner dispatch.
- **Defer the invite-revoke-on-ban behavior to a follow-up
  ticket.** Rejected — the spec ties them together: pre-ban
  revokes pending invites for the same contact, and the
  audit trail's correlation by `request_id` is part of the
  spec contract. Splitting them would defer a security
  invariant.
- **Implement the spec-compliant ConfirmStateService inside
  M1-044c.** Rejected for this ticket — would push the file
  count past files_budget (ConfirmStateService.java + its
  test + config keys + cancellation wiring = ~3-4 files on
  top of the existing 8); the service is shared across at
  least five destructive commands (/ban, /invite revoke,
  /grant-admin, /revoke-admin, future /quarantine), so a
  separate ticket that builds the canonical service and
  retrofits all five gates at once is the correct shape. The
  cost is a temporary spec deviation (v1 ships /ban and
  /invite revoke without confirm) until the follow-up lands.
- **Keep the stateless `--confirm` flag pattern in M1-044c
  and amend the spec.** Rejected — the spec's in-memory
  timeout model is a deliberate design choice for "any other
  input cancels it" UX; amending it would be a design
  regression (no timeout means abandoned confirms accumulate
  in user mental state; no cancel-on-other-input means a
  user typing a different command unaware of the pending
  confirm gets surprising behavior). The follow-up ticket
  builds the spec-compliant service rather than amending
  the spec.
