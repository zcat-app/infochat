---
id: M1-045
title: Slow-start probation tier + restricted command set (step 5) + /vouch
status: pending
created: 2026-05-20
last_updated: 2026-05-20
blocked_by:
  - M1-044
files_budget: 12
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
complexity: high
risk: medium
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
  - "CommandPermissions.allowedDuringProbation does NOT permit any of the spec-blocked commands: `add-source`, `save`, `unsave`, `follow-tag`, `unfollow-tag`, `clear`, `compress`, `group-timezone`, `retry`, `ban`, `unban`, `invite`, `vouch`, `grant-admin`, `revoke-admin`, `promote`, `demote`, `quarantine`, `audit`. Verify by reading the method: each of these returns false (NOT in the allowed set, NOT in the asset family). The unknown-command case (a non-existent slash) returns false (fail-closed)"
  - "CommandPermissionsTest pins the spec matrix command-by-command: a `@ParameterizedTest` with one row per command in the spec's closed list, asserting `allowedDuringProbation(<name>) == <expected>` for every command. The test file MUST include separate assertions for each of the 11 explicitly-allowed commands AND each of the 19 explicitly-blocked commands listed in spec §Slow-start tier. Verify: `grep -E '\"stop\"' CommandPermissionsTest.java` returns ≥1 match (the /stop carve-out pin) AND `grep -E '\"add-source\"' CommandPermissionsTest.java` returns ≥1 match (the blocked-during-probation pin) AND `grep -E '\"forget\"' CommandPermissionsTest.java` returns ≥1 match (the privacy-lever pin) AND `grep -E '\"lang\"' CommandPermissionsTest.java` returns ≥1 match (the locale-lever pin). `grep -E '@Test|@ParameterizedTest' CommandPermissionsTest.java` returns ≥3 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java exists, is `@ApplicationScoped`, and exposes `boolean isAssetCommand(String slashCommand)` returning `false` for ALL inputs in this ticket's commit (the asset registry lands in T2-H per docs/spec/commands.md §Asset commands + docs/design/10-asset-commands.md). The class docstring documents this seam explicitly: T2-H will displace the impl by injecting the bootstrap-fed registry via CDI without changing the interface. Verify: `grep -E '@ApplicationScoped' AssetCommandFamilyOracle.java` returns ≥1 match AND `grep -E 'return\\s+false' AssetCommandFamilyOracle.java` returns ≥1 match AND `grep -E 'T2-H' AssetCommandFamilyOracle.java` returns ≥1 match (the seam documentation)"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java exists, is `@ApplicationScoped`, and exposes `boolean inProbation(UUID userId)` AND `void clearIfPromoted(UUID userId)`. The `inProbation` method reads `SELECT probation_until FROM users WHERE id = ?` and returns `probation_until IS NOT NULL AND probation_until > NOW()` per spec §Slow-start tier (`The mechanism is lazy: the permission check is probation_until IS NULL OR probation_until < NOW()`). The `clearIfPromoted` method runs `UPDATE users SET probation_until = NULL WHERE id = ? AND probation_until IS NOT NULL AND probation_until <= NOW()` — the opportunistic clear that nulls the column on the next request from a promoted user. Verify: `grep -E '@ApplicationScoped' ProbationCheck.java` returns ≥1 match AND `grep -E 'probation_until\\s+IS\\s+NOT\\s+NULL\\s+AND\\s+probation_until\\s*>\\s*NOW' ProbationCheck.java` returns ≥1 match AND `grep -E 'UPDATE\\s+users\\s+SET\\s+probation_until\\s*=\\s*NULL' ProbationCheck.java` returns ≥1 match"
  - "ProbationCheckTest covers: (a) `inProbation` returns true for a user with `probation_until = NOW() + 1h`; (b) `inProbation` returns false for a user with `probation_until = NOW() - 1h` (past); (c) `inProbation` returns false for a user with `probation_until = NULL` (already promoted); (d) `clearIfPromoted` nulls the column for a user with `probation_until = NOW() - 1h` (past); (e) `clearIfPromoted` does NOT modify a user with `probation_until = NOW() + 1h` (still in probation); (f) `clearIfPromoted` is a no-op for a user with `probation_until = NULL`. `grep -E '@Test' ProbationCheckTest.java` returns ≥6 matches"
  - "InboundRouter is modified to splice step 5 (probation check) AFTER step 4 (ban check) AND BEFORE step 6 (parse). The implementation: after the ban-check branch returns false (caller is not banned), parse the body to extract the slash command name; resolve `inProbation(actor.id)` via the new ProbationCheck bean; if true AND the command is NOT in `CommandPermissions.allowedDuringProbation`, send the `error.probation.blocked` bundle reply and STOP dispatch. If probation is in effect AND the command IS allowed, OR if probation is not in effect, run `clearIfPromoted(actor.id)` opportunistically (the lazy clear) and proceed to step 6 / dispatch. Verify: `grep -E 'probationCheck\\.inProbation' InboundRouter.java` returns ≥1 match AND `grep -E 'allowedDuringProbation' InboundRouter.java` returns ≥1 match AND `grep -E 'probationCheck\\.clearIfPromoted' InboundRouter.java` returns ≥1 match"
  - "InboundRouter is also modified to splice step 7 — the `group_only` DM-gate carve-out from M1-044b is REPLACED with a CommandPermissions-aware version: a `group_only` user's slash command is rejected with `error.invite.required` regardless of which command they invoked (the spec's DM-gate has no per-command carve-out). This ticket may either preserve the M1-044b DM-gate logic unchanged OR fold it into the new CommandPermissions check; either shape is acceptable as long as the spec rule holds. (The DM-gate fires for `group_only` users; probation fires for ANY user whose `probation_until > NOW()`; both checks are evaluated independently per spec.)"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java implements `CommandHandler` with `name() == \"vouch\"`. The handler: (1) requires `users.is_admin = true` on the caller — non-admin returns `error.admin_only`; (2) parses one positional `<contact>` argument; (3) returns `error.contact_not_registered` if no `users` row exists for the inbound-adapter-scoped (M1-040 InboundContext) `(adapter, target_contact_id)`; (4) on the happy path performs the spec's TWO transitions in ONE transaction: `UPDATE users SET probation_until = NULL, registration_state = CASE WHEN registration_state = 'group_only' THEN 'vouched' ELSE registration_state END WHERE id = ?` — the registration_state advance only fires when the prior state was 'group_only' per spec §Slow-start tier `/vouch <contact>`; (5) writes the `VOUCH` audit row audit-before-effect with `details_json` carrying both transitions (`{\"probation_cleared\": true, \"registration_state_from\": \"<prior>\", \"registration_state_to\": \"<new>\"}`); (6) returns the no-op friendly reply `reply.vouch.noop` when the target is already past probation AND not `group_only` (spec: `For a row already in 'invited' or 'vouched' state, registration_state is left unchanged. No-op cases. Already past probation and the row is not group_only → friendly no-op reply.`); (7) returns the happy-path reply `reply.vouch.success` otherwise. Verify: `grep -E 'public\\s+String\\s+name' VouchCommandHandler.java` returns a match returning `\"vouch\"` AND `grep -E 'UPDATE\\s+users\\s+SET\\s+probation_until\\s*=\\s*NULL' VouchCommandHandler.java` returns ≥1 match AND `grep -E '''group_only''' VouchCommandHandler.java` returns ≥1 match AND `grep -E '''vouched''' VouchCommandHandler.java` returns ≥1 match"
  - "VouchCommandHandlerTest covers: (a) non-admin caller receives `error.admin_only`, no DB write; (b) unknown contact receives `error.contact_not_registered`, no DB write; (c) `group_only` user in probation → row updates to `probation_until=NULL` AND `registration_state='vouched'`, reply `reply.vouch.success`, audit row VOUCH with `details_json` carrying both transitions; (d) `group_only` user already past probation but still DM-gated → row updates `registration_state='vouched'` (probation_until is already NULL or in the past — leaves it NULL), reply `reply.vouch.success` (valid /vouch target per spec); (e) `invited` user in probation → row updates `probation_until=NULL`, `registration_state` UNCHANGED, reply `reply.vouch.success`, audit row VOUCH; (f) `invited` user already past probation → no-op reply `reply.vouch.noop`, no audit row (the no-op case writes NO audit row — there is no spec-mandated audit verb for an in-effect no-op; alternatively, a NOOP_VOUCH audit row is acceptable but not required); (g) `vouched` user past probation → same no-op as (f); (h) the UPDATE runs in ONE transaction — assert via setting up a Connection wrapper that records BEGIN/COMMIT calls. `grep -E '@Test' VouchCommandHandlerTest.java` returns ≥7 matches"
  - "InboundRouterProbationOrderingTest pins step 5's position relative to steps 4 and 6: (a) a registered, non-banned user in probation sending `/add-source` → rateCap → users-lookup → banCheck (false) → probationCheck (true) → allowedDuringProbation (false) → outbound matches `error.probation.blocked`, handleSlash NOT called; (b) same user sending `/help` → probationCheck (true) → allowedDuringProbation (true) → handleSlash called → /help reply returned; (c) a registered, non-banned user past probation sending `/add-source` → probationCheck (false) → clearIfPromoted called → handleSlash called → /add-source dispatch; (d) a banned user in probation sending `/help` → banCheck (true) → outbound matches `error.ban.fixed`, probation NOT consulted (step 4 fires before step 5). `grep -E '@Test' InboundRouterProbationOrderingTest.java` returns ≥4 matches"
  - "BundleKeys.java adds: `ERROR_PROBATION_BLOCKED = \"error.probation.blocked\"`, `REPLY_VOUCH_SUCCESS = \"reply.vouch.success\"`, `REPLY_VOUCH_NOOP = \"reply.vouch.noop\"`. Bundles/en.properties adds the entries. The error.probation.blocked entry's value contains a probation-aware reply (per `docs/design/03-commands.md` §3.3 `Blocked operations return a friendly localized reply stating when full access unlocks`) and uses MessageFormat `{0}` for the time-until-probation-ends. Verify: `grep -E '^error\\.probation\\.blocked\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^reply\\.vouch\\.success\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^reply\\.vouch\\.noop\\s*=' bundles/en.properties` returns 1 match"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-043 tests, M1-044a per-service tests, M1-044b InboundRouterIntakeOrderingTest / InboundRouterTest, M1-044c handler tests, M1-044 umbrella IT (the umbrella's IT does NOT exercise probation graduation since the IT runs before this ticket commits; once M1-045 lands, M1-044's IT continues to pass because the probation-gated request in M1-044's IT step (g) is from a non-probation `invited` user — the new probation step short-circuits to handleSlash for /help since /help is allowed during probation anyway)"
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
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
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

`complexity: high` and `risk: medium` because the step-5
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
- **`ProbationCheck` shape.** Two methods, each one short
  prepared statement. `inProbation` is read-only;
  `clearIfPromoted` runs the opportunistic UPDATE in its own
  short transaction (autoCommit=true is fine — the UPDATE is
  idempotent + idempotent-conditioned).
- **`InboundRouter` step-5 splice.** After M1-044b's step-4
  ban-check returns false and BEFORE the chat-mode-vs-slash
  branch, parse the command name from the first body token:
  ```java
  String name = parseCommandName(normalized);  // "help" from "/help" etc.
  if (probationCheck.inProbation(actor.id())) {
      if (!commandPermissions.allowedDuringProbation(name)) {
          sendReply(scope, bundleLoader.get(ERROR_PROBATION_BLOCKED, formatTimeUntilUnlock(actor)));
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

- (none — this ticket adds four new test files and one new
  test method to a pre-existing test class, modifying no
  prior test method.)

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
