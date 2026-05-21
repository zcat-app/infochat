# M1-044b → A+D+E process-fix handoff

**Status**: TRANSIENT handoff. Delete this file once the A+D+E tickets land and M1-044b reaches `done`. Lives under `docs/plan/m1/` (not `docs/process/`) because it is conversation-bridge state, not durable process documentation.

**Audience**: a fresh-context conversation picking up after M1-044b's premise-fail #2. The previous conversation analyzed the recurring premise-fail pattern, agreed on a three-pronged structural fix (A+D+E), and stopped here to start the fix in a fresh session.

---

## TL;DR

M1-044b has burned 4 rounds of refinement (clarity-warn → clarity-fail → premise-fail → premise-fail #2). The previous conversation traced root causes, evaluated four process-fix options, and the user picked **A + D + E** (full set) and asked for a fresh context to execute them.

- **A** = procedural backstop: `verified_stays_green` frontmatter section + Plan-prompt instruction + lint warning
- **D** = test pyramid refactor: handler tests call `handler.handle()` directly instead of going through the full router
- **E** = parameter contract annotations: `@NotNull`/`@Nullable` on public APIs + lint enforcement + retroactive annotation pass

M1-044b itself is parked while A+D+E land. The parking path is one of three options — TBD with the user in the fresh context (see [Open decisions](#open-decisions)).

---

## What we decided in the previous conversation

1. **The recurring premise-fail class is a structural problem, not a check-coverage problem.** Adding "another lint rule" only moves detection earlier — the defect class still exists. The right fix is to make the defect class unrepresentable.

2. **Option A alone is too weak.** It requires authors to enumerate every out-of-scope test that drives the changed dispatch surface. The audit notes can still be wrong, and the trigger (the hand-maintained "shared dispatch surface" list) drifts. Useful as a backstop, but not the structural fix.

3. **Option D (test pyramid) is the structural fix.** The 7 failing M1-044b tests are *handler* tests that exercise the *full router*. When the router changes, they break — even though the handler is unchanged. The fix: handler tests call `handler.handle(scope, body)` directly with mocks for DB/probe/etc. Router-level concerns (ban check, invite gate, intake ordering) consolidate to router tests. The full-chain integration stays in the ITs.

4. **Option E (parameter contracts) addresses the user's secondary concern.** The current "No defensive code for impossible scenarios" rule prohibits paranoia but doesn't require the explicit contract that makes paranoia unnecessary. Without `@NotNull` / `@Nullable` annotations or `@param` javadoc, "what's legal" is implicit and unverifiable. The complement is: every reference-type parameter on a public method MUST declare nullability.

5. **Sequencing**: A first (~1d, immediate backstop), then D + E in parallel (~3d + 2d). M1-044b unparks after D lands because the "M1-036/M1-039 tests stay green" claim dissolves under D.

6. **Memory note already captured**: `feedback_out_of_scope_stays_green_verifiable.md` (in `~/.claude/projects/.../memory/`) records the M1-044b-specific lesson. Indexed in `MEMORY.md`.

---

## State at handoff

### Git state

- **Current branch**: `m1/M1-044b-inbound-router-intake-splice`
- **Main**: at `ae1c02c` (the M1-044b premise-fail-#1 refine)
- **Branch commits beyond main**: 0 (all M1-044b work is uncommitted on the branch)
- **Working tree**: 12 modified files + 1 untracked (the full M1-044b round-1 implementation + the 3 helper edits applied during the current attempt + frontmatter edits + STATUS.md regen). Specifically:
  - Production code (M1-044b round-1 implementation, on the branch when it was created from main):
    - `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java`
    - `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`
    - `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java`
    - `infochat-provider/src/main/resources/application.properties`
    - `infochat-provider/src/main/resources/bundles/en.properties`
  - Tests (some round-1, some helper-edits applied this session):
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java`
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java`
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java` (helper edit this session — @BeforeEach alice pre-seed)
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java` (helper edit this session — newRouter() factory + 4 new test doubles)
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java` (helper edit this session — newAtCapRouter() helper + 4 new test doubles + resolveOrRegisterGroup override)
    - `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java` (untracked — created in round-1)
  - Ticket / status:
    - `docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md` (clarity_check appended, outline_file pointer set, status flipped to escalated, premise-fail-#2 escalation entry appended)
    - `docs/plan/m1/STATUS.md` (regenerated)

### Ticket state

- **M1-044b**: `status: escalated`, latest `escalations[]` entry is `premise-fail` (#2). Five-way menu was printed but no choice was made — the conversation pivoted to "evaluate the whole process first."
- **STATUS.md counts**: pending=5, in-progress=0, in-review=0, escalated=1 (M1-044b), done=49, deferred=7
- **Runnable**: M1-044c, M1-044d

### Sidecar artifacts

- `target/m1-tick-test-M1-044b-r1.log` — round-1 mvn verify log; 7 failures in AddSource* tests
- `target/m1-tick-outline-M1-044b.md` — Plan subagent's outline from this session
- `target/m1-tick-clarity-M1-044b.txt` — clarity verdict (WARN, 0 blockers, 2 warnings)
- `target/m1-tick-prompt-clarity-M1-044b.txt`, `target/m1-tick-prompt-plan-M1-044b.txt` — rendered prompts

### Memory state

- New: `feedback_out_of_scope_stays_green_verifiable.md` (the M1-044b-class lesson)
- Linked from: `MEMORY.md` (one-line index entry added)

---

## The defect class, restated for fresh context

The 7 mvn-verify failures (round-1, post-helper-edits) split into two modes, both flowing from M1-044b's splice behaving exactly as spec §Authorization model + §User ban require:

| Mode | Failing tests | Failure cause | Why the splice is correct |
|---|---|---|---|
| **A: unknown DM contact** | `AddSourceCommandHandlerTest.inboundRouterDispatchesAddSourceToHandlerExactlyOnce`, `.dmNonBannedNonAdminProceedsAndProducesFreshInsertReply`, `.ambiguousUrlWithHtmlContentTypeSurfacesAmbiguousFriendlyError`, `.branchBSubscribedExistingReplyOmitsUrlVisibilityDisclosure`, `.rssPathUrlContradictedByHtmlContentTypeSurfacesAmbiguous` (5 tests) | Tests call `adapter.deliverDm("m1-036h-...", "/add-source ...")` with contact_ids that have NO `users` row. Pre-M1-044b, `AutoRegisterService` UPSERTed mid-flight; post-splice, step 2 (DM unknown → invite gate → Rejected) fires and the handler never runs. Reply: `Access requires an invitation.` | The spec mandates an invite gate at step 2. Auto-registering DM contacts on first contact was a pre-spec behavior; M1-044b correctly removes it. |
| **B: banned DM contact** | `AddSourceCommandHandlerTest.dmBannedUserRejectsBeforeProbe`, `AddSourceBanCheckOrderingTest.bannedDmUserReceivesFixedBanReply` (2 tests) | Tests pre-seed `is_banned=true` and expect each handler's own `error.add_source.banned` literal ("You are not permitted to add sources."). Post-splice, step 4 fires first with the M1-044b `ERROR_BAN_FIXED` literal ("Your access has been revoked."). | Spec §User ban: "Banned user receives one fixed reply per inbound message, regardless of input." The handler-level ban-message is spec-illegal post-M1-044b. |

The ticket's Out-of-scope expansion explicitly asserted "M1-035c/M1-036/M1-037/M1-039/M1-040 tests stay green unchanged — those existing tests seed registered users (or stub the relevant collaborators) so the new gates pass through them." That claim is verifiably false for these 7 tests, and the current pipeline (lint + clarity + Plan + reviewer) does not verify it because none of those layers read out-of-scope test sources.

---

## Why the four-layer pipeline misses this defect class

| Layer | Reads ticket | Reads cited spec | Reads out-of-scope test source |
|---|---|---|---|
| Lint (`scripts/lint-ticket.py`) | ✓ | – | – |
| Clarity preflight (`code-reviewer` subagent at `start`) | ✓ | ✓ | – |
| Plan subagent (`Plan` subagent at `start`, complexity:high only) | ✓ | ✓ | partial — only files_scope APIs |
| Reviewer (`code-reviewer` subagent at `review`) | ✓ | – | only at review time, on the diff |

The FIRST layer that exercises out-of-scope code is `mvn verify`. So defects of this class have an irreducibly late detection point under the current architecture. Catching them earlier requires either (a) expanding Plan's mandate to audit dependent tests (Option A), or (b) making the architecture such that handler-test outcomes don't depend on router behavior (Option D), or (c) making contracts explicit so downstream impact is visible at API-design time (Option E).

---

## The plan: A + D + E

### Option A — procedural backstop (1 day)

**Goal**: catch premise-fail-class defects at Plan time, not mvn-verify time.

**Deliverables**:

1. **New frontmatter section** in `docs/process/ticket-template.md`:
   ```yaml
   verified_stays_green:
     - test_class: <fully-qualified test class name>
       rationale: <one-line audit note: pre-seeds users with state X / mocks Y / doesn't exercise the changed surface>
     - ...
   ```
   Required when `files_scope` contains a "shared dispatch surface" file (see heuristic list below). Optional otherwise.

2. **Lint check** in `scripts/lint-ticket.py`: when `files_scope` contains any of the heuristic files (initial list: `InboundRouter.java`, `RateCapBucket.java`, `InviteCodeConsumer.java`, `BanCheck.java`, `AutoRegisterService.java`, and any path matching `*Command*.java` under `provider/src/main/`), fail with `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` if `verified_stays_green:` is empty. The heuristic list lives in a constant the lint script reads — extendable as new dispatch surfaces emerge.

3. **Plan-prompt instruction** in `docs/process/plan-prompt.md`: when `files_scope` contains a shared-dispatch-surface file, enumerate every test that exercises the path via `grep -rE "adapter\.deliverDm\(|router\.onMessage\(|<handler>\.handle\(" provider/src/test/`. For each hit, classify: (a) stays-green (pre-seeds + immune to changed behavior), (b) needs-edit (helper-only wiring → must appear in `files_scope`), (c) depends-on-superseded-behavior (must surface to the user — Plan FAIL). Cross-check against the ticket's `verified_stays_green:` list and FAIL if any test is misclassified.

4. **Clarity check** (light): clarity-reviewer reads `verified_stays_green:` and confirms each rationale is plausibly grounded (e.g., "pre-seeds users with admin row" is plausible only if the test source actually contains a matching INSERT).

5. **Memory pointer**: update `feedback_out_of_scope_stays_green_verifiable.md` once codified to point at the lint rule + clarity check + Plan instruction. Replace the "future codification target" section with the actual landed paths.

### Option D — test pyramid refactor (2 days)

**Goal**: handler tests cannot break when the router changes. Structural decoupling.

**Convention** (new doc: `docs/process/test-pyramid.md`):

- **Handler unit tests** (e.g. `AddSourceCommandHandlerTest`, `SummaryCommandHandlerTest`, `HelpCommandHandlerTest`): call `handler.handle(scope, body)` directly. Mock or stub: `UrlProbe`, `DataSource`, `BundleLoader`, `LlmClient`, any other collaborator the handler @Injects. Do NOT touch `InboundRouter`, `AdapterRegistry`, `InMemoryAdapter`, or any test helper that drives `deliverDm(...)`. Stay plain JUnit + Mockito where possible; use `@QuarkusTest` only if the handler genuinely needs ARC for some reason (rare).
- **Router unit tests** (e.g. `InboundRouterTest`, `InboundRouterIntakeOrderingTest`, `InboundRouterNormalizeTest`, `InboundRouterContactIdRedactionTest`): exercise `router.onMessage(...)` with mocked handlers and mocked intake-step services. Cover ban check, invite gate, intake ordering, rate cap, normalize, DM-gate.
- **Integration tests** (`*IT.java`, e.g. `AddSourceIT`, `SummaryIT`, `AdapterRouterIT`): full chain. Few per major flow (3-5). These DO use `adapter.deliverDm(...)`.

**Refactor list** (estimate based on current grep):

| Test class | Current shape | New shape | Notes |
|---|---|---|---|
| `AddSourceCommandHandlerTest` | `@QuarkusTest` + `adapter.deliverDm(...)` | plain JUnit + `handler.handle(scope, body)` direct + mocks | 6 @Test methods to refactor |
| `AddSourceBanCheckOrderingTest` | `@QuarkusTest` + `adapter.deliverDm(...)` | DELETE — ban is a router concern; the ban-before-probe ordering moves to InboundRouterIntakeOrderingTest scenario (f) which already exists | 1 @Test method |
| `SummaryCommandHandlerTest` | `@QuarkusTest` + `adapter.deliverDm(...)` | plain JUnit + `handler.handle(...)` direct + mocks for `SummaryService`, `JoinService`, etc. | ~7 @Test methods |
| `HelpCommandHandlerTest` (M1-035c) | TBD — confirm current shape | per convention | ~3-5 @Test methods |
| `AdapterRegistryTest` | wiring test; appropriate | unchanged structurally; tighten assertion to "wiring + dispatch reached" not "wiring + specific reply" | 2 @Test methods |
| `AddSourceIT`, `SummaryIT`, `AddSourceAdapterScopeIT`, `SummaryAdapterScopeIT`, `AdapterRouterIT` | full-chain IT | unchanged — these are correctly at the IT layer | ~5 IT classes |

**Cost**: ~2 days. The refactor is mechanical once the pattern is established — first handler test sets the pattern, the rest follow.

**Coverage check**: after each refactor, run `mvn verify` to confirm no regression. The total assertion count should INCREASE (handler tests + router tests + IT tests cover more total surface than the old "everything through deliverDm" pattern).

### Option E — parameter contracts (3 days)

**Goal**: every public method's reference-type parameters declare nullability explicitly. The "No defensive code" rule gets its positive complement.

**Convention update** (in `CLAUDE.md` §Engineering rules → §"No workarounds, no shortcuts" sibling section, AND in `docs/process/engineering-rules-verbatim.md`):

> "Method parameter contracts MUST be explicit. Every reference-type parameter on a public method declares nullability — either via annotation (`@NotNull`/`@Nullable` from `org.jetbrains.annotations`) or via javadoc `@param x must not be null` / `@param x may be null`. Methods without an explicit declaration default to non-null-assumed for all reference parameters; passing null is a caller bug, and NPE-on-call is the diagnostic. Internal/package-private methods MAY inherit the default without explicit annotation; public/protected methods MUST annotate. Validation at system boundaries (per the existing 'No defensive code' rule) still uses explicit null-checks; internal-trusted code does not."

**Tooling decision** (open — see [Open decisions](#open-decisions)): JetBrains `@NotNull`/`@Nullable` (de facto Java standard, mature IDE integration), JSpecify `@NonNull`/`@Nullable` (newer, type-use semantics, less tooling), or javadoc-only convention.

**Deliverables**:

1. **Dependency add** (if JetBrains/JSpecify): `org.jetbrains.annotations:annotations:24.x` in the parent POM with `provided` scope (compile-time only; no runtime dependency).
2. **Lint check** (new) in `scripts/lint-ticket.py` OR a separate `scripts/lint-contracts.py`: walks every `*.java` under `*/src/main/java/`, identifies public methods, checks each reference-type parameter has either an annotation or a `@param ... null` line in javadoc. Fail otherwise. Initial run will flag a lot — set a baseline and grandfather, then enforce on NEW code.
3. **Retroactive annotation pass**: walk the existing codebase and annotate every public method's reference params. Start with the boundary classes (`InboundRouter.onMessage`, `MessagingAdapter.send`, `CommandHandler.handle`, `BundleLoader.get`, `*Service` methods, etc.). Estimate ~50-100 public methods across infochat-provider, ~30 across infochat-collector, ~20 across SPI modules.
4. **Reviewer prompt update**: add a check that every new public method has annotated reference params. REWORK if missing.

**Cost**: ~3 days total. Day 1: dependency + lint. Day 2: retroactive annotation pass on the most-impacted modules. Day 3: reviewer prompt + memory note + propagate.

---

## Open decisions

The previous conversation asked but did not get answers on:

1. **Test pyramid aggression for D**:
   - (a) Full decoupling (~10 test refactors; ~2d) — RECOMMENDED in the prior analysis
   - (b) Additive (keep existing tests, add new direct-handler tests alongside)
   - (c) Selective (refactor only the 7 currently-failing tests)

2. **Contract annotation tooling for E**:
   - (a) JetBrains `@NotNull`/`@Nullable` — RECOMMENDED in the prior analysis (mature, IDE-friendly)
   - (b) JSpecify `@NonNull`/`@Nullable` (newer, less mature)
   - (c) Javadoc-only (no dependency, less precise)

3. **M1-044b parking path**:
   - (a) Defer M1-044b on a new umbrella ticket (e.g. `M1-PROCESS-FIX`) — RECOMMENDED in the prior analysis
   - (b) Decompose M1-044b into core-splice + dependent-test-fix subtickets
   - (c) Refine M1-044b now against current architecture, refactor after

All three need to be confirmed with the user in the fresh context BEFORE starting work.

---

## Recommended fresh-context boot sequence

```
1. Read this handoff (docs/plan/m1/process-fix-handoff.md).
2. Read docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md
   (specifically the latest escalations[] entry — the premise-fail #2 trigger context).
3. Read MEMORY.md and feedback_out_of_scope_stays_green_verifiable.md.
4. Confirm the three open decisions with the user (AskUserQuestion or
   plain chat — user's choice).
5. Fire the M1-044b escalation resolution:
     - If decision 3 = (a) defer:
         /m1-tick escalate M1-044b → reply "4" → name umbrella as
         "Process fix: A+D+E — verified_stays_green + test pyramid + contracts"
         → skill allocates the next M1-NNN ID and creates the skeleton.
     - If decision 3 = (b) decompose:
         /m1-tick escalate M1-044b → reply "3" → state N=3 subtickets with titles.
     - If decision 3 = (c) refine: skip the umbrella; refine M1-044b in-place.
6. Allocate IDs and draft acceptance for the 3 process-fix subtickets
   (A, D, E). Each gets its own ticket file with sizing, files_scope,
   acceptance items, out_of_scope, test_plan. The umbrella's blocked_by
   lists the three subtickets.
7. /m1-tick start <A's ID> first (it's the smallest and the backstop).
8. Land A. Land D and E in parallel if user wants — verify with the
   parallel-start gate in /m1-tick start.
9. When A+D+E are all done, refine M1-044b against the new architecture:
     - Under D, the "M1-035c/M1-036/M1-037/M1-039/M1-040 tests stay green"
       claim dissolves (those tests no longer exercise the router).
     - The ticket's files_scope shrinks (no helper-only edits needed —
       the handler tests are already refactored).
     - The acceptance items become smaller and behaviorally tighter.
10. /m1-tick reopen M1-044b → /m1-tick start M1-044b → fresh clarity +
    Plan pass against the rewritten ticket → implement → review →
    commit → merge.
```

---

## Things the fresh context should NOT do

- **Do not silently widen M1-044b's files_scope** to include the 7 failing test files. The engineering rule "Never silently expand a ticket's files_budget, files_scope, or out_of_scope" forbids it. The proper paths are refine, decompose, or defer.
- **Do not amend the prior premise-fail-#1 refine commit (ae1c02c)**. It is committed and immutable. A new commit replaces it via the standard refine flow.
- **Do not push.** That's the user's call.
- **Do not abandon the work currently on the m1/M1-044b-inbound-router-intake-splice branch.** The implementation + helper edits are valid against the current ticket; they may still be useful after the refactor (the production code changes are spec-correct; only the test wiring will change shape under D).
- **Do not delete this handoff doc until A+D+E are landed AND M1-044b is done.** Once those are merged, this doc can be deleted in a `process:`-prefixed commit.

---

## Glossary for the fresh context

- **"Shared dispatch surface"**: a file whose changes can affect the behavior observed by tests outside its containing ticket's `files_scope`. Initial heuristic list: `InboundRouter`, `RateCapBucket`, `InviteCodeConsumer`, `BanCheck`, `AutoRegisterService`, any `*CommandHandler` (because handlers can be wrapped/intercepted by the router). The Option A lint refines this over time.
- **"Premise-fail"**: a `/m1-tick escalate` reason; one of "tests fail in a way that suggests the ticket's premise is wrong." See `docs/process/workflow.md` § Immediate escalation triggers.
- **"Out-of-scope expansion"**: the ticket-body section (below `Big-picture notes`) where the author enumerates what the ticket explicitly does NOT touch. The M1-044b out-of-scope expansion's claim about M1-036/M1-039 tests is the load-bearing premise that broke.
- **"verified_stays_green"** (new term, post-A): a frontmatter section enumerating out-of-scope tests that the ticket asserts will stay green, each with a one-line audit rationale. Mechanically checkable by Plan + clarity.
