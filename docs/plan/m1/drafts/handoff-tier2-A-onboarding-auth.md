# Session handoff — Tier 2 Group A: onboarding / auth (invite-code system + slow-start + ban + admin grants)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-A ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets and all six Tier 1 groups are done and merged
  on main. The full history is reproducible from
  `git log --grep "^M1-"`. STATUS.md shows pending=1 (M1-043,
  freshly drafted post-M1-040 redteam), done=47, deferred=7.
- Tier 1 cleanup tail that runs before T2-A:
  - M1-038 (done) — InboundRouter hardening: fenced-code carve-out
    + body-size cap + ContactIds.redact helper at three logging
    sites. Sets the template for T2-A intake-side log redaction.
  - M1-039 (done) — /add-source handler hardening: in-handler
    ban-check ordering + contact-ID redaction in exceptions.
    Defense-in-depth that REMAINS LOAD-BEARING even after T2-A
    lands the intake-side ban check (per M1-039's out_of_scope).
  - M1-040 (done) — /summary prompt-injection wrapper +
    adapter-scoped users lookup across CommandHandlers via a
    @RequestScoped InboundContext bean. T2-A's command handlers
    consume InboundContext.adapterName() unchanged.
  - M1-043 (pending) — /summary refusal-marker interception
    (M1-040 OUT-OF-MODEL #1 remediation). Small, independent of
    T2-A — may land before or after T2-A. Does NOT block this
    handoff.
- Deferred Tier-1 tickets relevant to T2-A:
  - M1-019 (deferred, deferred_on: M1-033) — stdout API-key
    redaction. Post-MVP hardening.
  - M1-020 (deferred, deferred_on: M1-038) — broad messaging-adapter
    exception-message sanitization via SafeLog utility. Post-MVP.
    T2-A handlers MUST follow the M1-038/M1-039/M1-040 contact-ID
    redaction pattern locally; the broader SafeLog rewrite waits.
  - M1-021 (deferred, deferred_reason: end-of-tier-1-redteam) —
    identity/audit redteam remediation. Not in T2-A's path.
  - M1-031, M1-041, M1-042 (deferred) — also not in T2-A's path.
- Branch is main, otherwise clean.

## What's NOT yet on disk that T2-A creates

The intake pipeline today has these steps wired in InboundRouter:

  - body-size cap (M1-038)
  - Unicode normalization w/ fenced-code carve-out (M1-038)
  - adapter-name capture into @RequestScoped InboundContext
    (M1-040)
  - auto-register-on-first-DM via AutoRegisterService (M1-035c +
    M1-035d) — THIS IS THE TIER-1 LEGACY PATH T2-A REPLACES with
    the invite-gated registration path (decision D44)
  - per-command dispatch to CommandHandler.handle(ScopeRef, body)

T2-A adds, to the SAME router, all the application-level checks
spec'd in `docs/spec/security.md` §Authorization model — and the
admin/ban/invite/vouch command handlers that mutate the state the
checks read.

What does NOT yet exist (T2-A creates / extends):

  - The `users.registration_state` enum is on disk (V5 migration
    landed in M1-008a) with values `{group_only, invited,
    vouched}`. T2-A's invite-code-consume path writes `'invited'`;
    the auto-register path that today writes nothing-or-default
    must instead write `'group_only'` on group auto-register; the
    pre-ban deletion path must delete rows where
    `registration_state = 'preban'`. Verify the enum values by
    `grep -rn "registration_state\|preban\|group_only\|invited\|vouched" infochat-core/src/main/resources/db/migration/`
    BEFORE assuming.
  - `invite_code` table — partial coverage in V5 migration; the
    `(status, expires_at)` partial unique index supporting the
    PENDING-cap query may NOT yet exist. Verify and, if absent,
    add as a V12 migration in the invite-system subticket.
  - Brute-force counter table — design notes name it
    `invite_code_attempt`; verify whether it lives in V5 or needs
    a fresh migration.
  - The `probation_until` column on `users` — verify by
    `grep -n "probation_until\|slow.start\|D45" infochat-core/src/main/resources/db/migration/`.
    If absent, add as part of the slow-start subticket's
    migration. If present (M1-008a may have added it
    speculatively), the slow-start subticket only consumes.
  - Per-`(adapter, contact_id)` transport-level rate cap — fresh
    in T2-A. The bucket lives in memory (Quarkus
    `@ApplicationScoped` token-bucket bean keyed by
    `(adapter, contact_id)` with profile-driven cap value). NO
    new table.
  - The invite-attempt counter — fresh in T2-A. Whether it lives
    in DB or in-memory is a sub-decision; design-notes-side, see
    `docs/design/04-security.md` §Authorization-step 2 for the
    locked choice.
  - The intake-step ordering in InboundRouter — currently steps 1
    (resolve identity), 1.7-partial (normalize already lands the
    body), and the auto-register branch are wired. T2-A must
    splice in: step 1.5 (rate cap), step 2 (DM invite gate),
    step 3 (rewrite to write `registration_state = 'group_only'`),
    step 4 (ban check at intake — replaces the M1-039 in-handler
    defense-in-depth), step 5 (probation check), step 7
    permission step's DM-gate carve-out.
  - All command handlers: /ban, /unban, /grant-admin,
    /revoke-admin, /invite create/list/revoke, /vouch.

## What you do this session

Author the T2-A ticket files in `docs/plan/m1/tickets/`. The
**default split** (from session-grouping-plan.md §Tier 2 →
T2-A) is THREE standalone tickets:

  T2-A.1 — invite-code system + intake-step splice for steps 1.5,
           2, 4, 7-DM-gate-carve-out + /ban + /unban + /invite
           create/list/revoke
  T2-A.2 — slow-start probation (intake step 5) + restricted
           command set enforcement + /vouch
  T2-A.3 — /grant-admin + /revoke-admin + last-admin protection
           trigger + global-across-adapters last-admin counter

**Umbrella+subs option.** If during authoring T2-A.1 ends up
exceeding `files_budget: 12` (the M1-035 / M1-008 umbrella
threshold), restructure into the umbrella+subs pattern of M1-035 /
M1-008: an umbrella ticket carrying ONE cross-cutting integration
test (a full invite→consume→probation→/vouch→full-access roundtrip
through InMemoryAdapter) plus three subtickets. The umbrella's IT
is the natural artefact to share across all three subtickets'
`out_of_scope` listings. Either shape is acceptable; pick the one
whose acceptance criteria fit cleanly into the round-cap budget.

The session-grouping-plan estimate (3 tickets) reflects the
**spec-sentence count**, not the implementation-files count.
Verify the implementation-files count by reading the relevant
spec sections (anchored below) and the M1-038/M1-039/M1-040 intake
code BEFORE committing to a split. Three tickets each touching a
small intake-step splice + one fresh handler family is the target;
if any subticket spans more than ~8 implementation files, the
umbrella+subs pattern is the right escape hatch.

## Where you are in the milestone

Tier 1 (MVP vertical slice) is complete. Tier 2 (v1 invariants)
begins with this session.

  T2-A onboarding / auth  (THIS SESSION — 3 tickets, optionally
                           umbrella + 3 subs if oversized)
  T2-B DM commands on entities  (/save, source mgmt, tag prefs)
  T2-C translation              (TranslationProvider, /lang)
  T2-D chat-mode                (chat agent + memory + /compress)
  T2-E privacy                  (/forget, /export)
  T2-F groups                   (group support + digests)
  T2-G quarantine               (/quarantine list/approve/reject)
  T2-H assets                   (/zcash, /monero, bootstrap-assets)

T2-A REPLACES the Tier-1 auto-register-on-first-DM path
(M1-035c/M1-035d) with the v1 invite-gated registration flow per
D44. The replacement is in-place: the AutoRegisterService class
either gets a new role (write registration_state='group_only' on
group auto-register; refuse to fire on DM unknown contacts) or is
displaced by a new InviteCodeService + AutoRegisterService becomes
group-only. Decide between rename-and-narrow vs split-into-two
during T2-A.1 authoring.

After T2-A, the next session authors T2-B's detailed handoff JIT.
See `docs/plan/m1/drafts/session-grouping-plan.md` for the full
plan.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T2-A gets fresh IDs at
the tail. The next free integer at this session's start is
**M1-044** (M1-043 was just allocated to the /summary
refusal-marker remediation; M1-041 and M1-042 are deferred but
their IDs are consumed). T2-A's three tickets are M1-044, M1-045,
M1-046. If the umbrella+subs escape hatch is taken in T2-A.1,
the umbrella is M1-044 and subs are M1-044a/b/c (matching the
M1-007 / M1-008 / M1-035 lowercase-suffix convention on the same
digit slot).

Per-ticket title shapes (use these verbatim, modulo final
imperative-summary tightening):

  T2-A.1 → "Invite-code system + intake auth-step splice
            (steps 1.5, 2, 4, 7-DM-gate) + /ban + /unban + /invite
            create/list/revoke"
  T2-A.2 → "Slow-start probation tier + restricted command set
            (intake step 5) + /vouch"
  T2-A.3 → "/grant-admin + /revoke-admin + last-admin protection
            (per-adapter scope, global counter)"

## Per-ticket framing

### T2-A.1 (M1-044) — Invite-code system + intake auth-step splice + /ban + /unban + /invite

**Spec anchors** (cite verbatim in `spec_refs:`):

  - `docs/spec/security.md` §Authorization model — the full
    canonical step-ordering text (lines 300-417 on main HEAD;
    `grep -n '^## Authorization model$' docs/spec/security.md`
    to verify). All seven steps + 1.5 (rate cap) + 1.7
    (normalize, already on disk).
  - `docs/spec/security.md` §User ban — ban-check semantics,
    pre-ban / pre-ban-unban deletion carve-out, banned-admin
    lockout escape hatch, group-admin restoration disclosure
    on /unban.
  - `docs/spec/security.md` §Invite-code registration — the full
    invite system: /invite create --contact|--open, single-use,
    TTL, cross-adapter isolation, brute-force rate limit,
    per-adapter open-cap, global contact-cap, group_only DM-gate.
  - `docs/spec/commands.md` §Admin — command surfaces for /ban,
    /unban, /invite create|list|revoke, the unknown-contact rule,
    /unban side-effect disclosure.
  - `docs/spec/commands.md` §Onboarding — welcome-message
    branches (DM-fresh, DM-returning, group-first-mention), the
    no-proactive-unban-notify rule.
  - `docs/spec/schema.md` §Identity and access — invite_code
    table shape, registration_state enum, audit verbs.

**Design references** (read but cite only if locking a behavior):

  - `docs/design/04-security.md` §Authorization-step 2 + §Rate
    limiting (profile-driven cap values, brute-force window).
  - `docs/design/03-commands.md` §3.4 §Admin (handler
    organization, bundle-key naming).
  - `docs/design/07-deployment.md` §Configuration surface
    (rate-cap and brute-force settings live here per profile).

**Locked decisions for this ticket**:

  - **Intake-step splice happens in `InboundRouter.onMessage`,
    NOT in a new bean.** M1-038/M1-039/M1-040 already wired
    onMessage to be the single intake gate; T2-A extends the
    method, does NOT replace it. The auth steps each delegate to
    a small @ApplicationScoped service (`RateCapBucket`,
    `InviteCodeConsumer`, `BanCheck`) so the router stays a thin
    sequencer.
  - **The pre-M1-040 in-handler ban check (M1-039) stays.** T2-A
    moves the AUTHORITATIVE ban check to intake; the in-handler
    check at AddSourceCommandHandler:114-126 becomes
    defense-in-depth, NOT a no-op. Same applies to any other
    handler that already has a ban guard. Do NOT remove existing
    in-handler ban checks in T2-A.
  - **`AutoRegisterService` is renamed-and-narrowed**, NOT
    deleted. The auto-register-on-first-DM behavior from
    M1-035c is removed (DM unknown contacts now route to step 2);
    the auto-register-on-first-group-mention behavior is kept
    but extended to write `registration_state = 'group_only'`.
    The class stays under
    `infochat-provider/src/main/java/.../messaging/` with the
    same name; method signatures may change. Existing tests
    (M1-035c's auto-register IT) get migrated, not deleted —
    they assert the group path; the DM path's assertion shifts
    to "DM unknown → fixed invite-required reply, NO row".
  - **Rate-cap bucket is in-memory** (no DB). The
    `RateCapBucket` bean is `@ApplicationScoped` and holds a
    `Map<(adapter, contact_id), Bucket>` plus a Quarkus scheduler
    that evicts idle buckets. Profile-driven cap values come
    from `application.properties` keys `infochat.rate-cap.*`.
  - **The brute-force counter lives in DB**, not in-memory.
    Reason: the threshold breach is an `audit_log` row, and
    the spec says "an audit row records the threshold breach";
    DB-side accumulation is the natural place. Either reuse
    the existing `invite_code_attempt` shape if it exists (V5
    grep) or add a fresh V12 migration in this ticket. The
    migration_touch flag MUST be set to true.
  - **Welcome-message wording lives in design notes** (D23 cited
    by §Onboarding). T2-A.1 reads the exact strings from
    `docs/design/03-commands.md` §3.4 / §Onboarding and ships
    them in bundle keys; T2-A.1 does NOT amend the spec or the
    design notes' wording.

**Out-of-scope (template for the ticket's frontmatter)**:

  - any change to the spec — §Authorization model + §User ban +
    §Invite-code registration are already complete and committed
  - any chat-mode behavior — T2-D territory; T2-A's permission
    step rejects chat-mode for probation users via the
    restricted-command-set check in T2-A.2 (the rejection lands
    in T2-A.2; T2-A.1 wires only the steps that fire before the
    permission check)
  - any /vouch handler — T2-A.2 territory (vouch lifts BOTH
    probation and DM gate, and the probation lift is the primary
    effect; co-locate with slow-start)
  - any /grant-admin / /revoke-admin handler — T2-A.3 territory
  - any /promote / /demote handler — T2-F territory (groups)
  - any /quarantine handler — T2-G territory
  - any audit_log writer changes beyond inserting the
    audit verbs this ticket needs — M1-041 owns the
    consolidation
  - any T2-H asset-command interaction
  - any TranslationProvider interaction — T2-C territory; the
    welcome-message bundle keys ship in English only in this
    ticket
  - any change to the M1-038 ContactIds.redact helper or the
    M1-040 InboundContext bean — both are consumed unchanged
  - any change to the existing AutoRegisterService method
    signatures beyond what the rename-and-narrow requires

**Acceptance shape**:

  - 5-7 acceptance items covering: intake-step splice order
    (assert the order via a unit test that drives onMessage with
    a sequence of inbound messages and asserts each step fires);
    invite-code create/list/revoke happy-path; invite-consume
    happy-path including registration_state='invited' write;
    invite-consume failure paths (wrong adapter, expired, USED,
    REVOKED); pre-ban and pre-ban-unban deletion; brute-force
    counter increments and threshold-breach audit row;
    per-adapter open-cap and global contact-cap enforcement;
    /unban side-effect disclosure (reinstated group-admin rows
    listed in reply + audit).
  - One @QuarkusTest IT exercising the full flow end-to-end
    via InMemoryAdapter.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 8-12. The intake splice + 3 services + 2
command handlers + 1 migration + 4-5 tests + 1 IT.

**security_relevant: true** (every acceptance item is a security
commitment).

### T2-A.2 (M1-045) — Slow-start probation tier + restricted command set + /vouch

**Spec anchors**:

  - `docs/spec/security.md` §Slow-start tier — the full
    probation behavior (allowed/blocked command lists, lazy
    promotion at `NOW() > probation_until`, /vouch graduation).
  - `docs/spec/security.md` §Authorization model step 5
    (probation check ordering — intake step, NOT per-handler).
  - `docs/spec/security.md` §Invite-code registration —
    `/vouch <contact>` lifts the DM gate for group_only users
    in addition to clearing probation; both effects in one
    transaction.
  - `docs/spec/commands.md` §Admin §/vouch — surface, audit
    requirements, the "already past probation + group_only =
    still a valid /vouch target" carve-out.
  - `docs/spec/commands.md` §Operator note: group-admin race —
    the "fresh group of unregistered users" paragraph, which
    pins the interaction between probation and group-admin
    auto-promote.

**Design references**:

  - `docs/design/04-security.md` §Slow-start tier — profile-
    driven probation window values.

**Locked decisions**:

  - **The restricted command set lives in
    `CommandPermissions.java`** (new class under
    `infochat-provider/src/main/java/.../command/`), NOT
    enumerated in every handler. The class exposes
    `boolean allowedDuringProbation(String slashCommand)`.
    Handlers do not change; the intake permission check
    (step 7) gates dispatch.
  - **`/stop` is NOT blocked during probation** — spec §Slow-start
    tier explicitly carves it out. The carve-out is the only
    asymmetry in the table; pin it with a unit test on
    CommandPermissions that asserts `/stop` returns true even
    when probation is active.
  - **Asset commands are an allowlist family, not an
    enumeration.** Spec §Slow-start tier: "every top-level
    asset command registered via `bootstrap-assets.json`".
    The allowed-during-probation check consults
    `AssetCommandRegistry` (which lands in T2-H) — but T2-H is
    after T2-A. Resolve by making `CommandPermissions` delegate
    to a CDI bean `AssetCommandFamilyOracle` that T2-H replaces
    with the bootstrap-fed registry. In T2-A.2 the oracle's
    bean ships with a hard-coded empty set (no asset commands
    exist yet); T2-H displaces the impl without changing the
    interface. Document this seam in T2-A.2's locked decisions
    and in its out_of_scope listing.
  - **Lazy promotion via `probation_until IS NULL OR
    probation_until < NOW()`** — no background sweep, no
    scheduler. The lazy-clear ("clear the column on the next
    request from a promoted user") is an opportunistic
    UPDATE in the permission step; failure to clear is not a
    bug (the column is informational once the user is past).

**Out-of-scope**:

  - any chat-mode plumbing — T2-D territory (T2-A.2 only blocks
    `/chat` and probation; it does NOT implement chat-mode)
  - any asset-command registry plumbing — T2-H territory (T2-A.2
    ships the seam, NOT the registry)
  - any /grant-admin / /revoke-admin handler — T2-A.3 territory
  - any TranslationProvider — T2-C territory
  - any audit-log writer changes beyond the /vouch audit row
  - any change to InboundRouter intake-step splice from T2-A.1
    beyond inserting step 5 (probation check) and step 7
    (permission check)
  - any /promote / /demote handler — T2-F territory

**Acceptance shape**:

  - 4-6 acceptance items: CommandPermissions.allowedDuringProbation
    matrix matches the spec's allow/block lists verbatim;
    /stop carve-out pinned; intake step 5 fires after ban
    check and before permission check; /vouch single-transaction
    semantics (probation_until → NULL AND registration_state
    transition from group_only → vouched, both or neither);
    /vouch no-op friendly reply for already-past-probation +
    non-group_only rows; lazy promotion via opportunistic
    UPDATE.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 6-9.

**security_relevant: true**.

### T2-A.3 (M1-046) — /grant-admin + /revoke-admin + last-admin protection

**Spec anchors**:

  - `docs/spec/security.md` §Authorization model — the full
    last-admin protection rule (cannot revoke only admin, cannot
    ban only admin, cannot ban self), the **per-adapter scope**
    of /grant-admin (the command is inbound-adapter-scoped) +
    the **global counter** for last-admin enforcement.
  - `docs/spec/security.md` §Per-adapter admin threat profile
    — the SimpleX-vs-Signal threat surface that motivates the
    per-adapter scoping.
  - `docs/spec/commands.md` §Admin §/grant-admin + §/revoke-admin
    — command surfaces, cross-adapter restriction, the unknown-
    contact rule.

**Locked decisions**:

  - **Last-admin protection enforced at the TRIGGER layer**
    (spec: "Enforced at the trigger layer, not just the command
    layer, so a buggy command cannot bypass it"). The trigger
    lives in a Flyway V<N> migration in this ticket. Verify
    whether M1-008a already defines a `users_last_admin_guard`
    trigger; if yes, T2-A.3 only extends it; if no, T2-A.3
    creates it. **migration_touch: true** if a migration is
    added.
  - **The last-admin counter is global**:
    `SELECT COUNT(*) FROM users WHERE is_admin = true`, not
    per-adapter. Pin with a unit test that seeds admins on two
    adapters and asserts /revoke-admin succeeds on one as long
    as the other remains.
  - **`/grant-admin <contact>` resolves against the inbound
    adapter** — the (adapter, contact_id) lookup uses the
    M1-040 @RequestScoped InboundContext.adapterName() value.
    The handler does NOT take an `--adapter` flag (unlike
    `/invite create`).
  - **Per-adapter scoping is the ONLY blast-radius bound**
    available in v1 — the threat model assumes a compromised
    admin on adapter A can name any contact on adapter A but
    cannot reach adapter B without compromising adapter B
    independently. Pin this with an IT that drives /grant-admin
    on adapter A targeting a contact id present on adapters A
    and B and asserts only the adapter-A row is mutated.

**Out-of-scope**:

  - any /ban / /unban handler — T2-A.1 territory (the last-admin
    counter does block bans on the last admin, but the ban
    handler ITSELF is T2-A.1's; T2-A.3 just ships the trigger
    that the ban handler delegates to)
  - any /promote / /demote / /vouch handler
  - any audit-log writer changes beyond the grant/revoke audit
    rows
  - any change to AutoRegisterService or the InboundRouter
    intake-step splice
  - any /invite / /quarantine handler

**Acceptance shape**:

  - 4-5 acceptance items: trigger fires on UPDATE users SET
    is_admin = false when count(*) where is_admin=true would
    drop to zero; per-adapter scoping (assert the lookup uses
    InboundContext.adapterName()); cannot revoke self (the
    actor's user.id can be in the candidate set, but the
    /revoke-admin handler must reject the self-revoke case
    with a friendly error BEFORE the trigger fires — the
    trigger is the last-line defense, the handler is the
    first-line UX); audit row carries actor + target +
    adapter; unknown-contact rule (unregistered (adapter,
    contact_id) returns friendly error, no row written).
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 5-7.

**security_relevant: true**.

## Locked decisions (cross-cutting across T2-A)

- **Intake-step order in InboundRouter.onMessage is the spec's
  numbered order**: step 1 → 1.5 → 1.7 (already on disk) → 2 →
  3 → 4 → 5 → 6 → 7. Each step's failure is the SAME shape:
  a friendly fixed reply via the per-step bundle key, return,
  no further processing. The ordering is the security-critical
  invariant; deviation requires escalate / spec-amend.
- **All three subtickets reuse M1-040's `InboundContext`**. No
  new request-scoped beans. Handlers @Inject InboundContext and
  read `.adapterName()` for the (adapter, contact_id) lookup
  pattern M1-040 / AutoRegisterService established.
- **Audit-log row writes during T2-A use the same shape as
  M1-036 (/add-source AUDIT verb)** — direct INSERT into
  `audit_log` from each handler / service. The M1-041 (deferred)
  AuditLogWriter consolidation comes later; T2-A does NOT block
  on it.
- **Bundle keys**: every new user-visible reply ships through a
  bundle key under
  `infochat-provider/src/main/resources/messages/Messages.properties`
  per the M1-035c precedent. NO inline string literals in
  handler code. The keys' exact names are not spec-fixed;
  follow the M1-035c convention (`reply.invite.*`,
  `error.ban.*`, etc.).
- **No `--no-verify`, no test disables.** Standard engineering
  rules apply.
- **Spec edits are forbidden in T2-A.** Every acceptance item
  must trace to spec text already on main HEAD. If a sentence
  the handler depends on is missing or ambiguous, escalate to
  `spec-amend` BEFORE implementing.

## After authoring all tickets

1. Verify each ticket's `spec_refs:` anchors actually exist with
   `grep -nE '^## |^### ' docs/spec/<file>` (clarity-check
   pre-flight blocks otherwise).
2. Verify each ticket's `files_scope:` paths exist or are
   plausibly new (relative-path under one of the modules).
3. Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and confirm:
   - pending count goes from 1 (M1-043) to 4 (M1-043 + M1-044 +
     M1-045 + M1-046), OR 1+1+3 if umbrella+subs taken.
   - M1-044 shows as `Runnable` (no blocked_by).
   - M1-045 shows blocked_by: M1-044.
   - M1-046 shows blocked_by: M1-044.
4. Leave the four new ticket files UNTRACKED on main. Do NOT
   commit them. The workflow rule: drafts ride untracked
   through `/m1-tick start`.
5. Update `docs/plan/m1/drafts/session-grouping-plan.md` Tier 2
   row for T2-A to record the actual IDs (M1-044/045/046 or
   umbrella+subs). Commit that single edit as
   `process: Record T2-A ID allocation (M1-044/045/046)`.
6. Print a one-screen summary in chat listing the three (or
   four) ticket IDs and titles, and the recommended start order
   (M1-044 first because both M1-045 and M1-046 depend on its
   intake-step splice / InviteCodeConsumer / RateCapBucket).

## What you do NOT do in this session

- Do NOT author any T2-B/C/D/E/F/G/H tickets. Those are later
  sessions.
- Do NOT implement any T2-A code. No `src/` edits anywhere.
- Do NOT amend any spec or design file. T2-A's spec is already
  complete on main HEAD per the §Authorization model / §User
  ban / §Invite-code registration / §Slow-start tier sections.
- Do NOT touch M1-035c's AutoRegisterService impl (the
  authoring session writes the acceptance criteria that
  T2-A.1's implementer will follow when narrowing it; the
  implementer's session executes the rename, NOT this one).
- Do NOT run `mvn verify`. Ticket authoring does not touch Java
  code.
- Do NOT commit the new ticket files; they ride untracked into
  `/m1-tick start`.

## Engineering rules in force

The full rules live in `CLAUDE.md` §Engineering rules and
`docs/process/engineering-rules-verbatim.md`. The ones that bite
for this session:

- **Surgical changes.** Each commit touches only the files its
  task needs. The session-grouping-plan edit in step 5 is one
  separate `process:` commit.
- **No defensive code for impossible scenarios.** Validation
  belongs at system boundaries; adapter inbound is a boundary
  (already handled by M1-038), but internal calls between
  T2-A's services are trusted.
- **No workarounds, no shortcuts.** If a constraint blocks
  ticket authoring, escalate via the workflow — never reach for
  destructive shortcuts or guess at a spec the brief did not
  resolve.
- **Push back when simpler exists.** If the brief's 3-ticket
  default split has a materially simpler alternative (e.g. all
  T2-A fits cleanly in two tickets, or the umbrella+subs
  pattern is obviously the right call), surface it in chat
  BEFORE committing the files.
- **Read spec files only when something is unclear.** The brief
  cites the spec anchors with section names; the authoring
  session reads those sections directly rather than re-deriving
  state from the codebase.

## Outputs

By the end of this session:

- Three (or four, if umbrella+subs) new ticket files exist
  UNTRACKED under `docs/plan/m1/tickets/`. They appear in
  STATUS.md as `pending`, with M1-044 runnable and M1-045 /
  M1-046 blocked_by M1-044.
- One `process:` commit on main updating
  `docs/plan/m1/drafts/session-grouping-plan.md` Tier 2 row
  for T2-A.
- Working tree contains the new ticket files (untracked) and
  STATUS.md (committed via the process: commit if your
  session-grouping-plan edit triggered regen, otherwise still
  committed in the same process commit). No code changes.

The natural next step is `/m1-tick start M1-044` (or the
umbrella ID if that split is taken), which fires the clarity
pre-flight subagent on the authored ticket. If the pre-flight
returns blockers, the authoring session's brief was insufficient
— rework via `/m1-tick escalate <id> clarity-fail`.
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Three (or four) new ticket files appear UNTRACKED under
      `docs/plan/m1/tickets/`. Status: pending.
- [ ] STATUS.md regenerates: M1-044 runnable, M1-045 + M1-046
      blocked_by M1-044.
- [ ] One `process:` commit on main updates the session-grouping
      plan's T2-A row.
- [ ] No `src/` edits anywhere.
- [ ] No spec or design edits.

If the session deviates (touches code, amends the spec, or
authors T2-B/C/D... tickets), it has misread the brief — abort
and start over with the same prompt.
