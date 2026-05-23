---
id: M1-045
title: Slow-start probation tier + restricted command set (step 5) + /vouch
status: done
created: 2026-05-20
last_updated: 2026-05-24
clarity_check:
  date: 2026-05-23
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-045.md
reviews:
  - round: 1
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 2169
      removed: 18
  - round: 2
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 23
      added: 2805
      removed: 92
  - round: 3
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 23
      added: 2843
      removed: 92
redteam_findings:
  - date: 2026-05-23
    category: AUTH-BYPASS
    severity: medium
    promise: |
      docs/spec/security.md §Slow-start tier: "Every newly registered user
      enters a probation period (decision D45). ... Blocked operations
      return a friendly reply stating when full access unlocks; the reply
      never reaches the LLM or any write path." And §Authorization model
      step 7: "Probation restrictions (D45) are part of the permission
      matrix: blocked commands return a friendly 'probation period' reply
      and never reach execution."
    gap: |
      InboundRouter.java lines 307, 341-343, 366 — `snapshot` is captured
      via `lookupUser` BEFORE step 3's autoRegisterService insert; the
      step-5 probation gate then guards on `if (snapshot.isPresent())`.
      For a group `@mention` from an unknown contact, `snapshot` is empty
      at this point, so the probation check is skipped for the very
      message that triggered auto-registration, even though
      AutoRegisterService inserted the row with probation_until in the
      future.
    repro: |
      First-message group @mention from an unregistered contact bypasses
      step 5 probation enforcement; dispatch reaches step 6 with no
      probation gate. Today every group-scope-callable handler short-
      circuits independently, so no exploitable write follows, but the
      security boundary the spec commits to is the probation gate itself.
      Future T2-F group-admin handlers inherit the bypass silently.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-23
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Invite-code registration: "Group-registered
      users do not get free DM access. ... the permission step (step 7)
      adds a DM-only gate that rejects any DM from a `group_only` user
      with the same fixed `Access requires an invitation.` reply as
      step 2's invalid path."
    gap: |
      InboundRouter.java lines 366-384 (step 5 probation gate) run BEFORE
      lines 426-431 (step 7 DM-gate). A group_only user DMing in
      probation receives `error.probation.blocked` (with allowed-set
      enumeration) instead of the spec-mandated `error.invite.required`
      fixed reply. The reply-text divergence between unknown DM contacts
      (`error.invite.required`) and registered group_only contacts
      (`error.probation.blocked`) lets a contact probe whether the bot
      has any registered presence for them.
    repro: |
      Attacker auto-registers in a group as group_only via @mention.
      Within the probation window they open a DM and send /help; the
      bot replies with the probation-blocked text including the
      allowed-set enumeration. A second attacker DMs from a never-seen
      contact and gets the literal invite-required reply. The text
      divergence is a confidentiality boundary the spec promised to
      keep flat.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-23
    verdict: FINDINGS
    base: main
    head: m1/M1-045-slow-start-probation-and-vouch
    verdict_file: docs/plan/m1/redteam/M1-045-2026-05-23.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Pre-commit audit on the in-review branch tip (working-tree-vs-main
      diff, same lifecycle position as `/m1-tick review`). Two findings
      surfaced — both spec-conformance gaps that the ticket's acceptance
      text either explicitly carves out (medium AUTH-BYPASS's "guarded
      on snapshot.isPresent" intent) or implicitly permits via the
      step-5-vs-step-7 ordering choice (low INFO-LEAK's reply-text
      divergence). User to decide whether to fold a fix into this commit
      (refine), open a remediation ticket, or amend the spec to
      authorize the current behavior. Two OUT-OF-MODEL observations
      noted (vouch-of-banned-user operator surprise, vouch-actor TOCTOU
      vs concurrent /revoke-admin).
  - date: 2026-05-23
    verdict: CLEAN
    base: main
    head: m1/M1-045-slow-start-probation-and-vouch (round-3 working tree)
    verdict_file: docs/plan/m1/redteam/M1-045-2026-05-23b.md
    findings_count: 0
    out_of_model_count: 3
    note: |
      Pre-commit re-audit after round 3 closed the round-2 AUTH-BYPASS +
      INFO-LEAK findings AND addressed both round-2 OUT-OF-MODEL items
      (banned-target rejection, /revoke-admin TOCTOU via SELECT FOR
      UPDATE). Round 3 also closed a NEW INFO-LEAK that the round-2 fix
      accidentally introduced (non-admin probing target state via 4-way
      reply discrimination in VouchCommandHandler) by reordering the
      admin gate to be the FIRST read inside the transaction, BEFORE
      target lookup. Three new OUT-OF-MODEL observations are advisory
      only — each follows an established project convention (M1-036
      no-op-no-audit pattern; engineering rule §No defensive code;
      spec scopes audit to the success path) and warrants no further
      code change.
blocked_by:
  - M1-044
files_budget: 20
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandPermissionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProbationCheckTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopProbationCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopCommandPermissions.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmFlowIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Slow-start tier + §Invite-code registration are the source of truth; this ticket implements them
  - any change to the V5 schema or any new migration — V5 already lands `probation_until` and the registration_state enum; M1-044a's V12 is the only new T2-A migration
  - any change to the M1-044 services (RateCapBucket, InviteCodeConsumer, BanCheck, AutoRegisterService) — consumed unchanged
  - any change to M1-044c admin handlers (BanCommandHandler, UnbanCommandHandler, InviteCommandHandler) — consumed unchanged
  - any change to M1-044's umbrella IT — M1-044 is FROZEN
  - any /grant-admin / /revoke-admin handler — M1-046 territory
  - any /promote / /demote handler — T2-F territory
  - any asset-command registry implementation — T2-H territory; this ticket ships the `AssetCommandFamilyOracle` seam with an EMPTY allowlist that T2-H displaces
  - any TranslationProvider exercise — T2-C territory; new bundle entries are English only
  - any audit-log writer consolidation — M1-041 territory; the /vouch handler writes directly to audit_log
  - any background sweep / scheduler to clear `probation_until` — spec says explicitly "no background job is required; lazy promotion via the next request"
  - any test outside the eleven files in files_scope — every M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-044*/M1-046 test stays green unchanged
acceptance:
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java exposes a public method `boolean allowedDuringProbation(String slashCommand)` that returns the closed allowed-set per spec §Slow-start tier verbatim. The implementation lists, as a `Set<String>` constant or a switch expression: `help`, `status`, `get-tags`, `get-sources`, `list-sources`, `summary`, `saved`, `export`, `forget`, `lang`, `stop` (the /stop carve-out). Plus delegation to `AssetCommandFamilyOracle.isAssetCommand(slashCommand)` for the asset-command family. Verify: `grep -E '\"help\"|\"status\"|\"get-tags\"|\"get-sources\"|\"list-sources\"|\"summary\"|\"saved\"|\"export\"|\"forget\"|\"lang\"|\"stop\"' CommandPermissions.java` returns ≥11 distinct matches AND `grep -E 'AssetCommandFamilyOracle' CommandPermissions.java` returns ≥1 match"
  - "CommandPermissions.allowedDuringProbation does NOT permit any of the spec-blocked commands: `add-source`, `save`, `unsave`, `follow-tag`, `unfollow-tag`, `clear`, `compress`, `group-timezone`, `retry`, `ban`, `unban`, `invite`, `vouch`, `grant-admin`, `revoke-admin`, `promote`, `demote`, `quarantine`, `audit`. Each of these returns false (NOT in the allowed set, NOT in the asset family). The unknown-command case (a non-existent slash) returns false (fail-closed). Verified runnably by the parameterized test in acceptance item [3]."
  - "CommandPermissionsTest pins the spec matrix command-by-command: a `@ParameterizedTest` with one row per command in the spec's closed list, asserting `allowedDuringProbation(<name>) == <expected>` for every command. The test file MUST include separate assertions for each of the 11 explicitly-allowed commands AND each of the 19 explicitly-blocked commands listed in spec §Slow-start tier. Verify: `grep -E '\"stop\"' CommandPermissionsTest.java` returns ≥1 match (the /stop carve-out pin) AND `grep -E '\"add-source\"' CommandPermissionsTest.java` returns ≥1 match (the blocked-during-probation pin) AND `grep -E '\"forget\"' CommandPermissionsTest.java` returns ≥1 match (the privacy-lever pin) AND `grep -E '\"lang\"' CommandPermissionsTest.java` returns ≥1 match (the locale-lever pin). `grep -E '@Test|@ParameterizedTest' CommandPermissionsTest.java` returns ≥3 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java exists, is `@ApplicationScoped`, and exposes `boolean isAssetCommand(String slashCommand)` returning `false` for ALL inputs in this ticket's commit (the asset registry lands in T2-H per docs/spec/commands.md §Asset commands + docs/design/10-asset-commands.md). The class docstring documents this seam explicitly: T2-H will displace the impl by injecting the bootstrap-fed registry via CDI without changing the interface. Verify: `grep -E '@ApplicationScoped' AssetCommandFamilyOracle.java` returns ≥1 match AND `grep -E 'return\\s+false' AssetCommandFamilyOracle.java` returns ≥1 match AND `grep -E 'T2-H' AssetCommandFamilyOracle.java` returns ≥1 match (the seam documentation)"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java exists, is `@ApplicationScoped`, and exposes `boolean inProbation(UUID userId)` AND `void clearIfPromoted(UUID userId)` AND `Instant probationExpiry(UUID userId)`. The `inProbation` method reads `SELECT probation_until FROM users WHERE id = ?` and returns `probation_until IS NOT NULL AND probation_until > NOW()` per spec §Slow-start tier (`The mechanism is lazy: the permission check is probation_until IS NULL OR probation_until < NOW()`). The `clearIfPromoted` method runs `UPDATE users SET probation_until = NULL WHERE id = ? AND probation_until IS NOT NULL AND probation_until <= NOW()` — the opportunistic clear that nulls the column on the next request from a promoted user. The `probationExpiry` method reads the same column and returns the `Instant` value (null when the column is NULL or the row is missing); it is called by `InboundRouter` only on the blocked-during-probation path to populate the `{0}` time-until-unlock token in `error.probation.blocked`, so it does NOT add per-dispatch overhead on the happy path. Verify: `grep -E '@ApplicationScoped' ProbationCheck.java` returns ≥1 match AND `grep -E 'probation_until\\s+IS\\s+NOT\\s+NULL\\s+AND\\s+probation_until\\s*>\\s*NOW' ProbationCheck.java` returns ≥1 match AND `grep -E 'UPDATE\\s+users\\s+SET\\s+probation_until\\s*=\\s*NULL' ProbationCheck.java` returns ≥1 match AND `grep -E 'Instant\\s+probationExpiry' ProbationCheck.java` returns ≥1 match"
  - "ProbationCheckTest covers: (a) `inProbation` returns true for a user with `probation_until = NOW() + 1h`; (b) `inProbation` returns false for a user with `probation_until = NOW() - 1h` (past); (c) `inProbation` returns false for a user with `probation_until = NULL` (already promoted); (d) `clearIfPromoted` nulls the column for a user with `probation_until = NOW() - 1h` (past); (e) `clearIfPromoted` does NOT modify a user with `probation_until = NOW() + 1h` (still in probation); (f) `clearIfPromoted` is a no-op for a user with `probation_until = NULL`; (g) `probationExpiry` returns the `Instant` for a user with `probation_until = NOW() + 1h`; (h) `probationExpiry` returns null for a user with `probation_until = NULL`. `grep -E '@Test' ProbationCheckTest.java` returns ≥8 matches"
  - "InboundRouter is modified to splice step 5 (probation check) AFTER step 4 (ban check) AND BEFORE step 6 (parse). The implementation: after the ban-check branch returns false (caller is not banned), parse the body to extract the slash command name; resolve `inProbation(actor.id)` via the new ProbationCheck bean; if true AND the command is NOT in `CommandPermissions.allowedDuringProbation`, send the `error.probation.blocked` bundle reply and STOP dispatch. If probation is in effect AND the command IS allowed, OR if probation is not in effect, run `clearIfPromoted(actor.id)` opportunistically (the lazy clear) and proceed to step 6 / dispatch. Verify: `grep -E 'probationCheck\\.inProbation' InboundRouter.java` returns ≥1 match AND `grep -E 'allowedDuringProbation' InboundRouter.java` returns ≥1 match AND `grep -E 'probationCheck\\.clearIfPromoted' InboundRouter.java` returns ≥1 match"
  - "InboundRouter is also modified to splice step 7 — the `group_only` DM-gate carve-out from M1-044b is REPLACED with a CommandPermissions-aware version: a `group_only` user's slash command is rejected with `error.invite.required` regardless of which command they invoked (the spec's DM-gate has no per-command carve-out). This ticket may either preserve the M1-044b DM-gate logic unchanged OR fold it into the new CommandPermissions check; either shape is acceptable as long as the spec rule holds. (The DM-gate fires for `group_only` users; probation fires for ANY user whose `probation_until > NOW()`; both checks are evaluated independently per spec.)"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java implements `CommandHandler` with `name() == \"vouch\"`. The handler: (1) requires `users.is_admin = true` on the caller — non-admin returns `error.admin_only`; (2) parses one positional `<contact>` argument; (3) returns `error.contact_not_registered` if no `users` row exists for the inbound-adapter-scoped (M1-040 InboundContext) `(adapter, target_contact_id)`; (4) on the happy path performs the spec's TWO transitions in ONE transaction: `UPDATE users SET probation_until = NULL, registration_state = CASE WHEN registration_state = 'group_only' THEN 'vouched' ELSE registration_state END WHERE id = ?` — the registration_state advance only fires when the prior state was 'group_only' per spec §Slow-start tier `/vouch <contact>`; (5) writes the `VOUCH` audit row audit-before-effect with `details_json` carrying both transitions (`{\"probation_cleared\": true, \"registration_state_from\": \"<prior>\", \"registration_state_to\": \"<new>\"}`); (6) returns the no-op friendly reply `reply.vouch.noop` when the target is already past probation AND not `group_only` (spec: `For a row already in 'invited' or 'vouched' state, registration_state is left unchanged. No-op cases. Already past probation and the row is not group_only → friendly no-op reply.`); (7) returns the happy-path reply `reply.vouch.success` otherwise. Verify: `grep -E 'public\\s+String\\s+name' VouchCommandHandler.java` returns a match returning `\"vouch\"` AND `grep -E 'UPDATE\\s+users\\s+SET\\s+probation_until\\s*=\\s*NULL' VouchCommandHandler.java` returns ≥1 match AND `grep -E '''group_only''' VouchCommandHandler.java` returns ≥1 match AND `grep -E '''vouched''' VouchCommandHandler.java` returns ≥1 match"
  - "VouchCommandHandlerTest covers: (a) non-admin caller receives `error.admin_only`, no DB write; (b) unknown contact receives `error.contact_not_registered`, no DB write; (c) `group_only` user in probation → row updates to `probation_until=NULL` AND `registration_state='vouched'`, reply `reply.vouch.success`, audit row VOUCH with `details_json` carrying both transitions; (d) `group_only` user already past probation but still DM-gated → row updates `registration_state='vouched'` (probation_until is already NULL or in the past — leaves it NULL), reply `reply.vouch.success` (valid /vouch target per spec); (e) `invited` user in probation → row updates `probation_until=NULL`, `registration_state` UNCHANGED, reply `reply.vouch.success`, audit row VOUCH; (f) `invited` user already past probation → no-op reply `reply.vouch.noop`, no audit row (the no-op case writes NO audit row — there is no spec-mandated audit verb for an in-effect no-op; alternatively, a NOOP_VOUCH audit row is acceptable but not required); (g) `vouched` user past probation → same no-op as (f); (h) the UPDATE runs in ONE transaction — assert via setting up a Connection wrapper that records BEGIN/COMMIT calls. `grep -E '@Test' VouchCommandHandlerTest.java` returns ≥7 matches"
  - "InboundRouterProbationOrderingTest pins step 5's position relative to steps 4 and 6: (a) a registered, non-banned user in probation sending `/add-source` → rateCap → users-lookup → banCheck (false) → probationCheck (true) → allowedDuringProbation (false) → outbound matches `error.probation.blocked`, handleSlash NOT called; (b) same user sending `/help` → probationCheck (true) → allowedDuringProbation (true) → handleSlash called → /help reply returned; (c) a registered, non-banned user past probation sending `/add-source` → probationCheck (false) → clearIfPromoted called → handleSlash called → /add-source dispatch; (d) a banned user in probation sending `/help` → banCheck (true) → outbound matches `error.ban.fixed`, probation NOT consulted (step 4 fires before step 5). `grep -E '@Test' InboundRouterProbationOrderingTest.java` returns ≥4 matches"
  - "BundleKeys.java adds: `ERROR_PROBATION_BLOCKED = \"error.probation.blocked\"`, `REPLY_VOUCH_SUCCESS = \"reply.vouch.success\"`, `REPLY_VOUCH_NOOP = \"reply.vouch.noop\"`. Bundles/en.properties adds the entries. The error.probation.blocked entry's value contains a probation-aware reply (per `docs/design/03-commands.md` §3.3 `Blocked operations return a friendly localized reply stating when full access unlocks`) and uses MessageFormat `{0}` for the time-until-probation-ends. Verify: `grep -E '^error\\.probation\\.blocked\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^reply\\.vouch\\.success\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^reply\\.vouch\\.noop\\s*=' bundles/en.properties` returns 1 match"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-043 tests, M1-044a per-service tests, M1-044b InboundRouterIntakeOrderingTest / InboundRouterTest, M1-044c handler tests, M1-044 umbrella IT (the umbrella's IT does NOT exercise probation graduation since the IT runs before this ticket commits; once M1-045 lands, M1-044's IT continues to pass because the probation-gated request in M1-044's IT step (g) is from a non-probation `invited` user — the new probation step short-circuits to handleSlash for /help since /help is allowed during probation anyway)"
  - "InboundRouter step 5 probation gate enforces against group-auto-registered users on their FIRST message: after step 3's autoRegisterService.resolveOrRegisterGroup insert, the snapshot is re-fetched so step 5 has a present snapshot for the just-inserted row. The `snapshot.isPresent()` guard at step 5 is REMOVED (defensive-code rule: snapshot is always present by step 4 by construction — DM-empty short-circuited at step 2; Group-empty was just auto-registered and re-fetched). Verify: `grep -cE 'snapshot = lookupUser\\(adapter, contactId\\)' InboundRouter.java` ≥ 1 (the post-step-3 re-fetch — the initial step-1 lookup uses `Optional<UserSnapshot> snapshot = lookupUser(...)`) AND `grep -E 'if \\(snapshot.isPresent\\(\\)\\) \\{' InboundRouter.java` returns 0 matches at the step-5 splice."
  - "InboundRouter DM-gate fires BEFORE probation gate: the M1-044b post-dispatch DM-gate carve-out (group_only + DM → error.invite.required) is RE-PLACED at the intake layer as step 4.7, between step 4 (ban check) and step 5 (probation check). A group_only user DMing in probation receives `error.invite.required` (the fixed spec reply), not `error.probation.blocked`. Verify: in InboundRouter.java, the DM-gate branch appears textually BEFORE the `probationCheck.inProbation` call AND AFTER the `banCheck.isBanned` call. The post-step-5 DM-gate at the prior location (around lines 426-431) is removed."
  - "VouchCommandHandler rejects banned targets with error.vouch.banned_target: the target user lookup additionally reads is_banned. If true, the handler returns ERROR_VOUCH_BANNED_TARGET before opening the transaction (no audit row, no UPDATE). Verify: `grep -E 'is_banned' VouchCommandHandler.java` returns ≥1 match in the target-lookup SELECT AND `grep -E 'ERROR_VOUCH_BANNED_TARGET' VouchCommandHandler.java` returns ≥1 match."
  - "VouchCommandHandler actor admin-check runs INSIDE the transaction with SELECT FOR UPDATE AND is the FIRST read inside the tx (BEFORE target lookup): a `lookupActorForUpdate` helper opens its SELECT inside the same transaction as the audit INSERT + UPDATE, using `FOR UPDATE` to serialize against concurrent /revoke-admin UPDATEs. The admin-first ordering closes the round-2 INFO-LEAK where the prior 'target-first' ordering let a non-admin caller distinguish target states (unknown / banned / past-probation / present) via the 4-way reply discrimination. If actor.is_admin is false at tx time, the handler ROLLBACKs and returns error.admin_only — non-admin callers cannot trigger any target lookup (no info leak about target existence, ban status, or registration state). Verify: `grep -E 'FOR UPDATE' VouchCommandHandler.java` returns ≥1 match AND `grep -E 'lookupActorForUpdate' VouchCommandHandler.java` returns ≥1 match AND in VouchCommandHandler.java the `lookupActorForUpdate(conn, ...)` call appears BEFORE the `lookupTargetInTx(conn, ...)` call textually inside the same `try { ... }` block following `conn.setAutoCommit(false)`."
  - "BundleKeys.java adds ERROR_VOUCH_BANNED_TARGET = \"error.vouch.banned_target\". bundles/en.properties carries the entry. Verify: `grep -E '^error\\.vouch\\.banned_target\\s*=' bundles/en.properties` returns 1 match."
  - "VouchCommandHandlerTest adds a `vouchBannedTargetReturnsBannedTarget` scenario seeding is_banned=true on the target, asserting the error.vouch.banned_target reply and zero DB writes (registration_state + probation_until unchanged). InboundRouterProbationOrderingTest adds (e) `groupAutoRegisterFirstMessageBlocksProbationCommand` pinning the AUTH-BYPASS fix and (f) `groupOnlyDmInProbationReceivesInviteRequiredNotProbationBlocked` pinning the INFO-LEAK fix. `grep -E '@Test' InboundRouterProbationOrderingTest.java` returns ≥6 matches (the prior 4 + the 2 new ones)."
  - "VouchCommandHandlerTest adds a `vouchByNonAdminAgainstUnknownContactReturnsAdminOnlyNotContactNotRegistered` scenario pinning the round-2 redteam INFO-LEAK closure: a non-admin caller invoking /vouch on an UNKNOWN target receives `error.admin_only` (NOT `error.contact_not_registered`). The reply text must match the bundle entry for ERROR_ADMIN_ONLY exactly; the absent target must not have been synthesized into the users table. `grep -E '@Test' VouchCommandHandlerTest.java` returns ≥10 matches (the prior 8 + the banned-target test + the non-admin-unknown-contact test)."
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandPermissionsTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProbationCheckTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopProbationCheck.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopCommandPermissions.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmFlowIT.java
  preserves:
    - all tests currently green on main
    - M1-044a/b/c tests + M1-044 umbrella IT
spec_refs:
  - docs/spec/security.md §Slow-start tier
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Invite-code registration
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/commands.md §Operator note: group-admin race
decision_refs:
  - D45
  - D44
---

# M1-045: Slow-start probation tier + restricted command set (step 5) + /vouch

## Context

T2-A.2 — the second of three Tier-2.A onboarding/auth tickets.
Lands the spec's slow-start probation tier (D45) end-to-end:

1. **CommandPermissions** — closed allowed-during-probation
   set per spec §Slow-start tier. The set is enumerated from
   the spec verbatim with one carve-out (`/stop` is explicitly
   not blocked) and one delegated allowlist (every
   operator-configured asset command, enumerated via the
   `AssetCommandFamilyOracle` seam that T2-H replaces).
2. **AssetCommandFamilyOracle** — a CDI seam exposing
   `isAssetCommand(name)`. In this ticket's commit the
   implementation returns false unconditionally (no asset
   commands exist in v1 until T2-H ships
   `bootstrap-assets.json`). T2-H lands a real impl that
   reads the registry; the interface stays the same.
3. **ProbationCheck** — small `@ApplicationScoped` bean that
   reads `users.probation_until` and exposes `inProbation`
   plus an opportunistic `clearIfPromoted` (the lazy-clear
   the spec mandates).
4. **InboundRouter splice step 5** — between step 4 (ban
   check) and step 6 (parse), the router resolves the command
   name from the body's first token and consults
   `ProbationCheck` + `CommandPermissions`; blocked commands
   short-circuit with `error.probation.blocked` reply.
   Allowed commands proceed; on the way, the router runs
   `clearIfPromoted` once per dispatch.
5. **/vouch handler** — admin command that runs the spec's
   two transitions (`probation_until=NULL` and conditional
   `registration_state='group_only' → 'vouched'`) in a single
   transaction with one VOUCH audit row.

The ticket depends on M1-044 (the umbrella) — the M1-044b
intake splice is the seam this ticket extends with step 5.
After M1-045 lands, the InboundRouter's intake order is:
identity → 1.5 rate-cap → 1.7 normalize → 2 invite consume →
3 group auto-register → 4 ban check → 5 probation check
(new) → 6 parse → 7 dispatch + DM-gate carve-out.

`complexity: high` and `risk: high` because the step-5
splice is a new authorization gate that must agree with the
spec's closed allowed/blocked enumeration verbatim; getting
the matrix wrong is a privilege defect.

`security_relevant: true`.

`migration_touch: false` — V5 already lands `probation_until`
and the registration_state enum; M1-044a's V12 is the only
new T2-A migration; this ticket consumes the schema as-is.

## Definition of Done

- `CommandPermissions.allowedDuringProbation(name)` returns the
  spec-correct boolean for every value in the spec's command
  catalogue. Tests enumerate every command in the catalogue
  with the expected verdict.
- `AssetCommandFamilyOracle.isAssetCommand(name)` returns
  `false` for every input in this ticket's commit; the
  class documents T2-H's replacement plan.
- `ProbationCheck.inProbation(userId)` and `clearIfPromoted(userId)`
  exist, are exercised by per-method tests, and use the
  spec-quoted SQL shapes.
- `InboundRouter.onMessage` splices step 5 between step 4 and
  step 6; the splice fires `clearIfPromoted` opportunistically;
  blocked-during-probation commands short-circuit with the
  probation reply.
- `VouchCommandHandler` ships the two-transitions-in-one-
  transaction shape; the no-op case writes no audit row;
  the audit row's `details_json` carries both transitions.
- Per-class tests cover the acceptance items.
- `mvn -B clean verify` exits 0; M1-044's umbrella IT remains
  green.

## Implementation notes

- **`CommandPermissions` shape.** A single
  `@ApplicationScoped` class with a `Set<String> ALLOWED =
  Set.of(...)` constant + a method that consults the set and
  the asset oracle:
  ```java
  public boolean allowedDuringProbation(String name) {
      return ALLOWED.contains(name) || assetCommandFamilyOracle.isAssetCommand(name);
  }
  ```
  The ALLOWED set's 11 entries are: `help`, `status`,
  `get-tags`, `get-sources`, `list-sources`, `summary`,
  `saved`, `export`, `forget`, `lang`, `stop`. The `/stop`
  carve-out is the only non-read-only-or-locale-or-privacy
  entry; pin it with its own test.
- **`AssetCommandFamilyOracle` shape.** Empty implementation
  in v1:
  ```java
  @ApplicationScoped
  public class AssetCommandFamilyOracle {
      // T2-H replaces with the bootstrap-fed registry.
      public boolean isAssetCommand(String name) { return false; }
  }
  ```
  The class docstring documents the T2-H replacement contract
  so a future reader understands why the impl is empty.
- **`ProbationCheck` shape.** Three methods, each one short
  prepared statement. `inProbation` is read-only;
  `clearIfPromoted` runs the opportunistic UPDATE in its own
  short transaction (autoCommit=true is fine — the UPDATE is
  idempotent + idempotent-conditioned); `probationExpiry`
  reads the same column and returns the `Instant` value (null
  when the row is missing or `probation_until` is NULL). The
  third method exists so `InboundRouter`'s step 5 can populate
  the `{0}` time-until-unlock token in `error.probation.blocked`
  without widening the snapshot record's column set (the
  snapshot's `(id, isBanned, registrationState)` shape is
  preserved; the extra SELECT only fires on the rare
  blocked-during-probation reply path, NOT on every dispatch).
- **`InboundRouter` step-5 splice.** After M1-044b's step-4
  ban-check returns false and BEFORE the chat-mode-vs-slash
  branch, parse the command name from the first body token:
  ```java
  String name = parseCommandName(normalized);  // "help" from "/help" etc.
  if (probationCheck.inProbation(actor.id())) {
      if (!commandPermissions.allowedDuringProbation(name)) {
          Instant expiry = probationCheck.probationExpiry(actor.id());
          sendReply(scope, bundleLoader.get(ERROR_PROBATION_BLOCKED, formatTimeUntilUnlock(expiry)));
          return;
      }
  } else {
      probationCheck.clearIfPromoted(actor.id());
  }
  // proceed to handleSlash or chat-mode
  ```
  - Chat-mode (non-slash) input is one of the blocked
    operations per spec §Slow-start tier (`Blocked: chat
    mode`). When the body does not start with `/`, the
    "command name" passed to `CommandPermissions` is the
    sentinel `chat-mode` (or any string that is NOT in the
    allowed set and NOT in the asset family) so the gate
    rejects non-slash input during probation. Pin in
    CommandPermissionsTest: `allowedDuringProbation("chat-mode")
    == false`.
- **Lazy-clear placement.** `clearIfPromoted` runs ONCE per
  dispatch when the user is NOT in probation. Putting the
  call inside the else-branch (the "not in probation" arm)
  means the UPDATE fires on every post-probation user's
  inbound until the column nulls — that's fine, the UPDATE
  is conditioned on `probation_until <= NOW()` AND
  `probation_until IS NOT NULL`, so it's a true no-op after
  the first call. The total cost is a single round trip per
  inbound from a graduated-but-not-yet-cleared user.
- **`VouchCommandHandler` SQL.** The two-transition UPDATE
  in one statement (the CASE expression on
  `registration_state`):
  ```sql
  UPDATE users
     SET probation_until = NULL,
         registration_state = CASE
             WHEN registration_state = 'group_only' THEN 'vouched'
             ELSE registration_state
         END
   WHERE id = ?
  ```
  The audit row is INSERTed BEFORE the UPDATE inside the
  same transaction (audit-before-effect per Invariant 7).
  The handler reads the prior registration_state from the
  caller's `users` lookup, so the audit row's `details_json`
  can carry both `registration_state_from` and `registration_state_to`.
- **/vouch no-op shape.** When the prior row state is
  already-past-probation AND not `group_only`, the UPDATE
  would be a no-op (the CASE leaves registration_state
  unchanged AND probation_until is already NULL). The
  handler short-circuits BEFORE running the SQL and returns
  the friendly `reply.vouch.noop` reply with no audit row.
  This is consistent with the spec's "no-op friendly reply"
  + matches M1-036's pattern of not writing audit rows for
  no-op admin operations.
- **DM-only and group-scope.** Per the M1-044c precedent, the
  /vouch handler is DM-only in v1 (ScopeRef.Group does not
  carry the actor's contact id; T2-F lands the SPI widening).
  Group-scope invocation returns `error.group_admin_not_in_v1`.
- **`error.probation.blocked` bundle value shape.** The
  bundle value uses MessageFormat `{0}` for the
  time-until-unlock (e.g. `"~12h"` or
  `"<duration> remaining"`). The handler formats the duration
  from `probation_until - NOW()`. The exact wording is
  drawn from `docs/design/03-commands.md` §3.3 (`Blocked
  operations return a friendly localized reply ... stating
  when full access unlocks`).
- **InboundRouter step-7 DM-gate update.** This ticket may
  preserve M1-044b's DM-gate implementation unchanged OR
  fold the DM-gate into the new CommandPermissions check.
  The spec's DM-gate rule applies to EVERY slash command from
  a `group_only` user (no per-command carve-out unlike the
  probation gate's `/stop` carve-out). Implementer's choice;
  the two acceptable shapes are: (a) M1-044b's
  post-dispatch override stays; M1-045 only adds step 5; or
  (b) the DM-gate check is moved into `CommandPermissions` as
  a separate method (e.g. `boolean allowedFromGroupOnly(String name)`
  returning always false) and the gate fires before dispatch.
  Either shape meets acceptance.

## Big-picture notes

- **M1-046 lands /grant-admin and /revoke-admin** — those
  handlers must reject probation users (a probation user is
  by definition not a bot admin per spec §Slow-start tier
  "All admin commands ... blocked"). The intake-side
  probation gate this ticket lands enforces that rejection
  before the admin handler dispatches; the in-handler
  admin-tier check is the second line of defense.
- **T2-D lands chat-mode** — the chat-mode dispatcher this
  ticket gates with the probation check. T2-D inherits the
  step-5 gate unchanged.
- **T2-H lands asset commands** — replaces
  AssetCommandFamilyOracle's empty impl with the
  bootstrap-fed registry. The CommandPermissions delegation
  doesn't change.
- **The lazy-clear mechanism is best-effort.** A user can
  graduate mid-dispatch (between the inProbation read and
  the dispatch logic), but the spec is explicit: the user is
  promoted at the instant `NOW() > probation_until`,
  regardless of whether the column has been nulled. The
  permission step uses `inProbation` for the decision; the
  separate `clearIfPromoted` is a hygiene UPDATE that lets
  /audit / /status / future commands see a clean NULL.

## Out-of-scope expansion

- **M1-044a/b/c.** Consumed unchanged.
- **/grant-admin, /revoke-admin.** M1-046.
- **/promote, /demote.** T2-F.
- **Asset command registry implementation.** T2-H. The
  AssetCommandFamilyOracle's empty impl is the seam.
- **AuditLogWriter consolidation.** M1-041.
- **Background sweep to clear `probation_until`.** Spec
  explicitly forbids it: "no background job is required."
- **/help filtering for probation users.** Spec says `/help`
  for a probation user is filtered to the allowed set. This
  is HelpCommandHandler's responsibility (M1-035c); it can
  consume `CommandPermissions` via @Inject to filter. This
  ticket leaves HelpCommandHandler unchanged — the filter is
  a quality-of-life follow-up, NOT a security commitment
  (probation users invoking blocked commands ALREADY get the
  probation reply regardless of whether /help advertises
  them). A separate ticket may add the filter.
- **Translation of new bundle entries.** T2-C.

## Authorized test changes

- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java` —
  the existing `newRouterWithLog` helper directly assigns every
  collaborator field on a subclassed `InboundRouter`. The two
  new `@Inject` fields this ticket adds (`commandPermissions`,
  `probationCheck`) would be null at the new step-5 splice
  site, NPEing every pre-existing scenario that reaches step
  5. Authorized edit: wire the extracted top-level
  `NoopCommandPermissions` and `NoopProbationCheck` package-mate
  test doubles (defined in `NoopCommandPermissions.java` and
  `NoopProbationCheck.java`, mirroring the `NoopConfirmStateService`
  precedent — both Noops must NOT record into the `CallLog`
  because the pre-existing per-step call-order assertions would
  otherwise gain spurious entries) into `newRouterWithLog`. No
  pre-existing test method body is modified; no pre-existing
  assertion changes; no new `@Test` methods added. The Noops'
  chosen behavior: `NoopProbationCheck.inProbation` returns
  `false` and `clearIfPromoted` is a no-op, so every
  pre-existing scenario follows the not-in-probation path and
  the existing call-order assertions remain valid. (Round-1
  prior shape used inline private inner classes; round 2
  REMOVES those inner classes and uses the extracted top-level
  versions per the project rule against test inner classes.)
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java` —
  each `new InboundRouter() {...}` instantiation in this file
  (3 sites) leaves `commandPermissions` and `probationCheck`
  null, NPEing at the new step-5 splice. Authorized edit:
  after the existing `router.confirmStateService = new
  NoopConfirmStateService();` line, add two lines
  `router.commandPermissions = new NoopCommandPermissions();`
  and `router.probationCheck = new NoopProbationCheck();`,
  bracketed by a short comment block pointing at the Noop
  classes' javadoc for the log-silent rationale. No
  pre-existing test method body is modified; no pre-existing
  assertion changes; no new `@Test` methods added.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java` —
  same as ConfirmCancelTest above: each `new InboundRouter() {...}`
  instantiation needs the two Noop field assignments to avoid
  NPEing at step 5. Authorized edit: same two-line addition
  bracketed by the same comment block. No pre-existing test
  method body is modified; no pre-existing assertion changes;
  no new `@Test` methods added.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java` —
  same shape as the prior two; this file has TWO `new
  InboundRouter() {...}` sites (around lines 160 and 381),
  both of which need the two Noop field assignments. Only the
  `bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns` scenario
  actually reaches step 5 today (the other tests short-circuit
  earlier), but both sites are wired uniformly for hygiene and
  to prevent future test additions in this file from
  re-introducing the NPE. Authorized edit: same two-line
  addition at each site, bracketed by the same comment block.
  No pre-existing test method body is modified; no pre-existing
  assertion changes; no new `@Test` methods added.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java` —
  the `@BeforeEach` pre-seeds `mvp-user-2` with
  `probation_until = NOW() + 24h` (pre-M1-045 default to satisfy
  `firstDmAutoRegistersUserAndRepliesWithHelp`'s ±30s window
  assertion on the auto-registered probation column). After
  step 5 lands, the `unknownCommandProducesBundleKeyedFriendlyReply`
  scenario short-circuits at step 5 (unknown commands fail
  closed) and the test asserts the unknown-command bundle reply
  — which never fires. Authorized edit: add a one-line `UPDATE
  users SET probation_until = NULL WHERE contact_id =
  'mvp-user-2'` (via the test's existing `dataSource` field) at
  the top of `unknownCommandProducesBundleKeyedFriendlyReply`
  ONLY. The other tests in the file (`firstDm*`, `secondHelp*`)
  send `/help` which IS allowed during probation, so they
  remain untouched and continue to assert the unchanged
  probation-bearing auto-register defaults. No pre-existing test
  body is otherwise modified; no assertion changes; no new
  `@Test` methods added.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java` —
  the `@BeforeEach` pre-seeds `m1-036-mvp-user-1` with
  `probation_until = NOW() + 24h` for the same pre-M1-045
  reason. After step 5 lands,
  `mvpExitCriterionFourEndToEndAddSourceProducesRowsTagsSubscriptionAndReply`
  short-circuits at step 5 (`/add-source` is NOT in the
  allowed-during-probation set) and never reaches the
  AddSourceCommandHandler. Authorized edit: add a one-line
  `UPDATE users SET probation_until = NULL WHERE contact_id =
  'm1-036-mvp-user-1'` at the top of the single MVP test
  body. No other test in this file is affected; no other
  pre-existing assertion changes; no new `@Test` methods added.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java` —
  (round 2.2 redteam-fix addendum) the M1-045 Fix 1 (AUTH-BYPASS) adds
  a second `lookupUser` call right after `autoRegisterService
  .resolveOrRegisterGroup`. Scenario (h)
  `groupMentionAutoRegistersAndDispatchesNormally` previously expected
  the call-order list to contain a SINGLE `lookupUser` entry; with the
  re-fetch in place it now must contain TWO. Authorized edit: update
  the expected `List.of(...)` to insert one extra `"lookupUser"` after
  `"autoRegisterService.resolveOrRegisterGroup"`. The override is also
  updated to a stateful form (a counter or AtomicInteger): the first
  call returns the seeded `Optional<UserSnapshot>` (Optional.empty()
  in scenario (h)); subsequent calls synthesize an `Optional.of` from
  V5 auto-register defaults (`is_banned=false`,
  `registration_state='group_only'`). Without the stateful override
  the second lookupUser would still return empty and step 5's now-
  guard-less `snapshot.get().id()` would NPE. No pre-existing test
  body is otherwise modified beyond the override + the scenario (h)
  call-list update; no assertion semantics change; no new `@Test`
  methods added to this file.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmFlowIT.java` —
  the file's `seedUser` helper unconditionally writes
  `probation_until = NOW() + 24h` regardless of `isAdmin`.
  The three failing scenarios (`banPromptThenConfirmExecutesBanEndToEnd`,
  `inviteCreateOpenPromptThenConfirmExecutesCreateEndToEnd`,
  `nonMatchingInputAfterBanPromptCancelsPendingAndDispatchesNewCommand`)
  all use an admin actor whose `/ban` or `/invite create --open`
  invocation is now blocked at step 5. Authorized edit: change
  the `seedUser` helper to write `probation_until = NULL` when
  `isAdmin = true`, mirroring the real-world invariant that
  bootstrap admins skip the slow-start probation window
  (bootstrap creates admins directly, bypassing the
  AutoRegisterService path that seeds the column). Non-admin
  seed shape is unchanged — non-admin targets in these tests
  remain in their pre-existing `probation_until = NOW() + 24h`
  state (they are recipients of `/ban` and `/invite`, never
  actors). No pre-existing test method body is otherwise
  modified; no assertion changes; no new `@Test` methods added.

## Alternatives considered

- **Implement the probation matrix inside the existing
  CommandHandler classes (each handler checks its own
  probation status).** Rejected — the spec puts probation
  enforcement at step 7 of the authorization order, which is
  the intake layer's responsibility. Distributing the check
  across handlers would (a) duplicate the matrix in every
  handler and (b) miss any future handler that forgot to
  add the check. The single intake-layer check is
  spec-aligned and DRY.
- **Use a Quarkus @Scheduled bean to sweep `probation_until`
  every minute.** Rejected — spec explicitly says "no
  background job is required" and lazy clear is the
  prescribed mechanism. A sweep would also lose the
  per-dispatch invariant that the user is promoted at the
  exact instant `NOW() > probation_until`.
- **Defer the AssetCommandFamilyOracle seam to T2-H entirely
  (omit it here, hard-code asset commands as unallowed
  during probation).** Rejected — T2-H would have to modify
  CommandPermissions AND CommandPermissionsTest at the time
  it lands, expanding T2-H's scope. The seam costs ~10
  lines here and lets T2-H replace one method body cleanly.
- **Make `/vouch` write two separate audit rows (one for
  probation clear, one for registration_state advance).**
  Rejected — spec says one transaction with both effects;
  one VOUCH audit row with both transitions in `details_json`
  is the natural shape. The audit log retains the full state
  transition without needing a second action verb.
