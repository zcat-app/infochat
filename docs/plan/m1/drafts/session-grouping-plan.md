# M1 ticket-authoring — session grouping plan

This file is the **index of sessions** for authoring the remaining M1
ticket files. It enumerates every ticket past M1-005 grouped by
shared spec/design context, names the rationale for each group, and
estimates session size.

This is a planning artifact, not a workflow contract. The
`/m1-tick` skill does not read it. Its purpose is to let future
sessions resume the authoring work without re-deriving the grouping
from scratch.

## Conventions

- **Group** = one Claude Code session's worth of ticket authoring.
- **Detailed handoff** = a full prompt with locked decisions, runnable
  acceptance hints, verified spec anchors — paste into a fresh
  session as the opening message.
- **JIT principle.** Detailed handoffs are written **just-in-time**
  for the group about to be authored, NOT all upfront. Two reasons:
  - Tier N+1 tickets reference real class names, file paths, and
    acceptance shapes that don't exist until Tier N has been
    implemented; locking acceptance criteria early risks rework.
  - Authoring 18 handoff files in one session is a huge context
    cost we don't have to pay.

The flow is: implement Tier 0 → author Tier 1 group's detailed
handoff just before its session → run that session → repeat.

## Current state (as of 2026-05-13)

- Tier 0 (foundation) is complete. Twenty tickets are `status: done`
  on `main`: M1-001 through M1-018, including M1-007's umbrella +
  M1-007a/b/c subtickets. The full history is reproducible from
  `git log --grep "^M1-"`.
- Both Tier 0 handoffs were authored and consumed:
  - `handoff-tier0-group1-db-roles-and-advisory-lock.md` → M1-006 + M1-009
  - `handoff-tier0-group2-spi-surfaces.md` → M1-007 + M1-007a/b/c
- M1-010 through M1-018 were consumed by `/m1-tick` skill /
  process work (subagent slimming, STATUS regeneration via script,
  clarity-check refinements, prompt-size alarm, etc.), NOT by the
  originally-planned Tier 1 implementation tickets. In particular,
  M1-016 was originally slotted for the T1-E messaging-adapter
  umbrella and M1-017 for the T1-F first-command work; they were
  instead used for NOLOGIN-on-roles and Flyway-relocation. The
  Tier 1 groups below still need authoring and will get fresh IDs
  at the tail when their handoff session runs.
- Currently pending: M1-019 (API-key stdout redaction) and M1-020
  (exception-message sanitization). Both are being deferred to
  post-MVP hardening because they protect code paths that don't
  exist until Tier 1 lands.
- M1-008 and its subticket IDs (M1-008a/b/c) are still reserved
  for the Tier 1 schema umbrella below — no ticket files exist on
  disk for those IDs yet.
- Detailed handoffs NOT YET written: all Tier 1+ groups below.

### Going forward: process / spec edits skip the ticket flow

The drift of M1-010..M1-018 into process work predates
`CLAUDE.md` §"Commit prefixes", which now formalizes that
process-only edits (`.claude/`, `docs/process/`, `docs/plan/`,
`CLAUDE.md`) ship as `process:` commits and spec-only edits
(`docs/spec/`, `docs/design/`) ship as `spec:` commits, NOT as
ticket commits. The reviewer, clarity check, `mvn verify`, and
STATUS regen are bypassed for those prefixes (per
`docs/process/workflow.md` §"Non-ticket commits"). Consequently,
the Tier 1+ implementation groups below should get fresh IDs at
the tail when their authoring sessions run, rather than the
originally-planned numeric slots; ID allocation tracks only
code, migrations, and spec-coordinated-with-code work.

## Total scope

- 22 ticket files exist on disk; 20 are `done`, 2 (`M1-019`,
  `M1-020`) are about to be deferred to post-MVP hardening.
- Of the 20 done tickets:
  - 5 are Phase 1 scaffolding (M1-001..M1-005);
  - 6 are Tier 0 proper (M1-006, M1-007 + M1-007a/b/c, M1-009);
  - 9 are process work on the `/m1-tick` skill itself
    (M1-010..M1-018), which is not part of the implementation
    tier breakdown.
- Remaining implementation work: M1-008 + M1-008a/b/c (Tier 1
  schema, IDs reserved) plus ~25 tickets across ~15 groups
  whose IDs will be allocated at the tail when their handoffs
  are authored.

## Group index

ID prefixes: T0/T1/T2/T3 = the tier the group belongs to.

### Tier 0 — foundation (complete)

| Group | Tickets | Handoff file |
|---|---|---|
| T0-1 | M1-006, M1-009 | `handoff-tier0-group1-db-roles-and-advisory-lock.md` |
| T0-2 | M1-007 (umbrella), M1-007a, M1-007b, M1-007c | `handoff-tier0-group2-spi-surfaces.md` |

### Tier 1 — MVP vertical slice

After Tier 0 lands, author the detailed handoff for the next group
just-in-time. Each row below names the group, the tickets, the
rationale (why these tickets share a session), and a rough size.

**ID allocation.** T1-A's IDs (M1-008 + M1-008a/b/c) are reserved
and unused — author session uses them as-is. For T1-B through
T1-F, the original numeric slots (M1-010..M1-018) were consumed
by process work; those groups get fresh IDs at the tail at
authoring time. The descriptions below describe the work, not the
identifier.

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T1-A** schema | M1-008 (umbrella), M1-008a (identity + audit + last-admin trigger), M1-008b (sources + tags), M1-008c (posts + subscriptions + scope_preferences + cross-cutting isolation IT) | All cite `docs/spec/schema.md` + `docs/design/02-schema.md` §2.1–2.3. Umbrella+subticket pattern; author all four in one session so the umbrella's integration test path matches the subtickets' out_of_scope listings. | 4 tickets |
| **T1-B** ingest sources | bootstrap loader, RSS Fetcher | Both read `docs/design/07-deployment.md` §7.6 (bootstrap-sources.json format) + `docs/spec/architecture.md` §Ingest SPIs. Bootstrap loader populates source rows that RSS Fetcher polls. | 2 tickets |
| **T1-C** outbox/NOTIFY | outbox + LISTEN/NOTIFY + provider_state + rehydrator | Standalone — `docs/spec/architecture.md` §Inter-service communication + `docs/design/02-schema.md` §2.9. Distinct enough (wiring layer) that pairing with T1-D would split its review focus. | 1 ticket |
| **T1-D** eval pipeline | Stage 1 deterministic security, LLM + Stage 2, tagger + embedding | All cite `docs/spec/security.md` §Ingest pipeline + `docs/spec/llm.md` §SPI shape + §Per-task routing + §Embedding pipeline. Sequentially dependent. Heavy shared spec — best amortized in one session. | 3 tickets |
| **T1-E** adapter + router | umbrella, InMemoryAdapter, router, /help + auto-register + topic IT | Umbrella+subticket pattern. All cite `docs/spec/messaging.md` + `docs/spec/commands.md` §Surface conventions. Same shape as T0-2 and T1-A. Umbrella gets a fresh ID at the tail (M1-016 is consumed); subticket suffixes follow the precedent letters (a/b/c) on the same digit slot. | 4 tickets |
| **T1-F** first commands | /add-source, /summary | Both depend on T1-E's router; both cite their specific section in `docs/spec/commands.md`. After T1-F the MVP exit criteria (design/00-mvp.md §6) can run end-to-end. | 2 tickets |

Tier 1 subtotal: 6 groups, 16 tickets.

### Tier 2 — v1 invariants

Layered on the MVP slice. Each group is a thematic cluster.

**ID allocation.** All Tier 2 IDs get allocated at the tail at
authoring time. The originally-planned slots (M1-019..M1-033)
are unusable: M1-019 and M1-020 are deferred post-MVP hardening
tickets with unrelated content, and the rest were never
allocated. Plus M1-019/020 will eventually point back at these
Tier 2 tickets via their `deferred_on` fields once those tickets
exist (M1-019 → the LLM ticket under T1-D once authored;
M1-020 → the T1-E messaging umbrella once authored — see step (a)
of this prep session's handoff).

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T2-A** onboarding / auth | **M1-044** (umbrella) + M1-044a (intake-step services + V12 brute-force-counter migration) + M1-044b (InboundRouter intake splice + DM-gate carve-out) + M1-044c (/ban + /unban + /invite create/list/revoke handlers); **M1-045** (slow-start probation step 5 + restricted command set + /vouch); **M1-046** (/grant-admin + /revoke-admin + last-admin trigger consumption). All cite `docs/spec/security.md` §Authorization model + §Invite-code registration + §Slow-start tier + §User ban. Tightly coupled — replaces T1-E's auto-register path with the v1 onboarding flow. T2-A.1 used the umbrella+subs escape hatch (per the T2-A handoff): the spec-sentence count was 3, but the implementation-files count for T2-A.1 alone exceeded the 12-file budget (RateCapBucket + InviteCodeConsumer + BanCheck + AutoRegisterService rename-and-narrow + V12 migration + InboundRouter intake splice + 3 admin handlers + bundle keys + 6 tests + 1 IT ≈ 16 files), so the M1-035 / M1-008 pattern was taken. T2-A.2 and T2-A.3 stayed as single tickets. | 6 tickets (1 umbrella + 3 subs + 2 standalone) |
| **T2-B** DM commands on entities | **M1-052** (saved-post library — /save + /saved + /unsave + saved_post snapshot) + **M1-053** (source-management admin commands — /list-sources + /remove-source + /source-enable + /source-disable, with confirm-gate integration via M1-051) + **M1-054** (per-scope tag preferences — /follow-tag + /unfollow-tag + tag-mode state machine, with /unfollow-tag --all confirm-gated). Each cites `docs/spec/commands.md` §Content / §Source management / §Per-scope tag preferences and touches its own schema slice. The three tickets are independent (no `blocked_by` between them); each consumes the shared M1-051 ConfirmStateService unchanged where confirm is required. | 3 tickets (M1-052, M1-053, M1-054) |
| **T2-C** translation | TranslationProvider impls (English + Czech) and /lang | Standalone — `docs/spec/llm.md` §Translation flow + the SPI from M1-007c. Cross-cutting effect (every bot reply runs through it) but small implementation. | 1 ticket |
| **T2-D** chat-mode | chat agent + chat_memory + chat_session + /compress + /clear + agent tools, auto-compress near context-window ceiling, /stop + /retry + summary_anchor | All cite `docs/spec/llm.md` §SPI shape (chat task) + `docs/spec/security.md` §Prompt-injection defenses + `docs/spec/commands.md` §Conversation control. Tightly coupled around the live chat-mode runtime. | 3 tickets |
| **T2-E** privacy | /forget + /export | Standalone — `docs/spec/commands.md` §Conversation control (privacy section) + audit-log invariants from §Audit. Best after T2-D so /forget knows what tables to purge. | 1 ticket |
| **T2-F** groups | group support + /promote + /demote + @mention + /group-timezone, periodic digests + summary_cache + staggered scheduler + degraded fallback | Both cite `docs/spec/schema.md` §Identity (groups) + `docs/spec/commands.md` §Group commands. Digests need groups to exist. | 2 tickets |
| **T2-G** quarantine | /quarantine list/approve/reject + re-evaluation job + NEEDS_REVIEW + admin notification throttling | Standalone — `docs/spec/security.md` §Quarantine workflow + §Re-evaluation job + §Failure handling. Self-contained admin workflow. | 1 ticket |
| **T2-H** assets | **M1-055** (umbrella — cross-Collector roundtrip IT) + **M1-055a** (bootstrap-assets.json parser + V14 asset_config migration + default-row consistency check + Collector @Startup loader) + **M1-055b** (AssetDataSource SPI + per-host impls + AssetSnapshotFetcher + PriceSnapshotStore + V15 price_snapshot migration + per-host tick cadence + NOTIFY emit + per-source failure-counter) + **M1-055c** (AssetCommandRouter + handlers + AssetReplyRenderer + AssetSnapshotReader + AssetRegistry + AssetCommandFamilyOracle impl swap + /help context-awareness + bundle keys). All cite `docs/spec/commands.md` §Asset commands + `docs/design/10-asset-commands.md`. Operator-optional feature; conformant deployment without it. The umbrella + 3-subs split mirrors M1-008 / M1-044 — the file count exceeded the umbrella's files_budget. | 4 tickets (M1-055 umbrella, M1-055a, M1-055b, M1-055c) |

Tier 2 subtotal: 8 groups, 18 tickets.

### Tier 3 — adapters and breadth

Pluggable implementations behind the SPIs from Tier 0.

**ID allocation.** All Tier 3 IDs are allocated fresh at the
tail at authoring time. The originally-planned slots
(M1-034..M1-042) were never allocated and are no longer load-
bearing; the dependency graph (via `blocked_by`) carries
ordering, not the numeric ID.

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T3-A** production adapters | SimpleX adapter, Signal adapter | Both impl `MessagingAdapter` SPI from M1-007c. Share `docs/spec/messaging.md` whole + `docs/spec/security.md` §Per-adapter admin threat profile. Parallel work — shared base class likely. | 2 tickets |
| **T3-B** polled fetchers | Bluesky, Reddit, Nitter, YouTube, Odysee | All impl the `Fetcher` SPI from M1-007a, same template repeated. Could be one session (template-heavy, low per-ticket cost) or split 3+2 if context budget is tight. Each is small (~100 lines). | 5 tickets, 1–2 sessions |
| **T3-C** Nostr StreamSource | Nostr StreamSource | Standalone — `docs/spec/architecture.md` §Ingest SPIs (StreamSource portion) + `docs/spec/security.md` §Per-source trust boundaries §Nostr (signature verification, per-relay degradation, terminal failed state, drain on shutdown). Larger than a Fetcher because StreamSource has connection lifecycle. | 1 ticket |
| **T3-D** Anthropic LLM | Anthropic LLM provider | Standalone — second impl of `LlmProvider` SPI from M1-007b. The OpenAI-compatible impl from the T1-D LLM ticket sets the pattern. | 1 ticket |

Tier 3 subtotal: 4 groups (5 if T3-B splits), 9 tickets.

## Grand total

- Tier 0: 2 groups, 6 tickets (done)
- Tier 1: 6 groups, 16 tickets
- Tier 2: 8 groups, 15 tickets
- Tier 3: 4–5 groups, 9 tickets

**~20–21 authoring sessions across ~46 implementation tickets**
(Tier 0 complete; Tier 1+ pending). The 9 process tickets
(M1-010..M1-018) are not part of this implementation-tier count
because going forward, process work ships as `process:` commits
rather than ticket commits.

## Authoring a new group's detailed handoff

When the time comes to author the detailed handoff for group T<n>-<x>:

1. Confirm the predecessor group(s) the tickets depend on are fully
   merged on `main` (the dependency graph from this session's
   step-3 work is the source of truth).
2. Read the group's tickets' spec_refs from the table above; verify
   each anchor exists by `grep -n '^## \|^### ' <file>`.
3. Use the existing Tier-0 handoffs (`handoff-tier0-group1-*.md`,
   `handoff-tier0-group2-*.md`) as the canonical template. Match
   their structure exactly.
4. Save the new handoff as
   `docs/plan/m1/drafts/handoff-t<tier>-group<n>-<theme>.md`.
5. Paste the fenced block into a fresh Claude Code session.

## What this file does NOT do

- Does not lock acceptance criteria for any ticket past Tier 0.
  Those are deferred to JIT authoring of each group's detailed
  handoff.
- Does not lock numeric `files_budget` or specific `files_scope`
  paths — same JIT rationale.
- Does not commit to a specific ordering across groups within a
  tier when the dependency graph leaves them independent. The
  graph (renderable via `/m1-tick status`) is the source of truth
  for ordering.

## Open questions deferred to future sessions

- **T3-B sizing.** Five Fetchers in one session, or split 3+2? Decide
  when the session starts; whoever runs it should evaluate token
  budget after reading the first Fetcher's spec section.
- **T1-A umbrella IT location.** The MVP-schema umbrella's
  integration test verifies the per-(user, scope) isolation
  invariant end-to-end. Goes in `infochat-core/src/test/`? Or
  `infochat-collector/src/test/`? Decide when T1-A's handoff is
  authored, after T0-2 has run and infochat-core's test directory
  exists.
- **Renumbering after escalations.** If a Tier-N ticket gets
  `decomposed` mid-flight, IDs after the operand stay stable
  (decompose allocates fresh IDs at the tail) but the dependency
  graph shifts. This file does not pre-emptively model
  decomposition; future sessions update the table or live with the
  drift.
