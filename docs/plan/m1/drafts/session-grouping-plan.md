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

## Current state (as of this file's commit)

- Done and merged: M1-001, M1-002, M1-003.
- Drafted as untracked files on `main`: M1-004, M1-005.
- Detailed handoffs written:
  - `handoff-tier0-group1-db-roles-and-advisory-lock.md` (covers M1-006 + M1-009)
  - `handoff-tier0-group2-spi-surfaces.md` (covers M1-007 umbrella + M1-007a/b/c)
- Detailed handoffs NOT YET written: all Tier 1+ groups below.

## Total scope

- ~47 tickets in the full M1 plan (4 tiers + done set).
- Of those, 11 covered by existing detailed handoffs or already
  authored (M1-001..M1-009, but M1-008 is Tier 1 — see the umbrella
  there).
- Remaining: ~36 tickets across ~16 groups (~16 sessions).

## Group index

ID prefixes: T0/T1/T2/T3 = the tier the group belongs to.

### Tier 0 — foundation (handoffs already written)

| Group | Tickets | Handoff file |
|---|---|---|
| T0-1 | M1-006, M1-009 | `handoff-tier0-group1-db-roles-and-advisory-lock.md` |
| T0-2 | M1-007 (umbrella), M1-007a, M1-007b, M1-007c | `handoff-tier0-group2-spi-surfaces.md` |

### Tier 1 — MVP vertical slice

After Tier 0 lands, author the detailed handoff for the next group
just-in-time. Each row below names the group, the tickets, the
rationale (why these tickets share a session), and a rough size.

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T1-A** schema | M1-008 (umbrella), M1-008a (identity + audit + last-admin trigger), M1-008b (sources + tags), M1-008c (posts + subscriptions + scope_preferences + cross-cutting isolation IT) | All cite `docs/spec/schema.md` + `docs/design/02-schema.md` §2.1–2.3. Umbrella+subticket pattern; author all four in one session so the umbrella's integration test path matches the subtickets' out_of_scope listings. | 4 tickets |
| **T1-B** ingest sources | M1-010 (bootstrap loader), M1-011 (RSS Fetcher) | Both read `docs/design/07-deployment.md` §7.6 (bootstrap-sources.json format) + `docs/spec/architecture.md` §Ingest SPIs. Bootstrap loader populates source rows that RSS Fetcher polls. | 2 tickets |
| **T1-C** outbox/NOTIFY | M1-012 (outbox + LISTEN/NOTIFY + provider_state + rehydrator) | Standalone — `docs/spec/architecture.md` §Inter-service communication + `docs/design/02-schema.md` §2.9. Distinct enough (wiring layer) that pairing with T1-D would split its review focus. | 1 ticket |
| **T1-D** eval pipeline | M1-013 (Stage 1 deterministic security), M1-014 (LLM + Stage 2), M1-015 (tagger + embedding) | All cite `docs/spec/security.md` §Ingest pipeline + `docs/spec/llm.md` §SPI shape + §Per-task routing + §Embedding pipeline. Sequentially dependent. Heavy shared spec — best amortized in one session. | 3 tickets |
| **T1-E** adapter + router | M1-016 (umbrella), M1-016a (InMemoryAdapter), M1-016b (router), M1-016c (/help + auto-register + topic IT) | Umbrella+subticket pattern. All cite `docs/spec/messaging.md` + `docs/spec/commands.md` §Surface conventions. Same shape as T0-2 and T1-A. | 4 tickets |
| **T1-F** first commands | M1-017 (/add-source), M1-018 (/summary) | Both depend on T1-E's router; both cite their specific section in `docs/spec/commands.md`. After T1-F the MVP exit criteria (design/00-mvp.md §6) can run end-to-end. | 2 tickets |

Tier 1 subtotal: 6 groups, 16 tickets.

### Tier 2 — v1 invariants

Layered on the MVP slice. Each group is a thematic cluster.

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T2-A** onboarding / auth | M1-019 (invite-gating + /ban + /unban), M1-020 (slow-start + /vouch + restricted command set), M1-021 (/grant-admin + /revoke-admin) | All cite `docs/spec/security.md` §Authorization model + §Invite-code registration + §Slow-start tier + §User ban. Tightly coupled — replaces T1-E's auto-register path with the v1 onboarding flow. | 3 tickets |
| **T2-B** DM commands on entities | M1-022 (/save + /saved + /unsave + saved_post snapshot), M1-023 (source management: /list-sources + /remove-source + /source-enable/disable + status machine), M1-024 (tag preferences: /follow-tag + /unfollow-tag + /tag-mode + scope_tag) | Same surface conventions (`docs/spec/commands.md` §DM commands); each touches its own schema slice. Three small commands that share the parsing+permission scaffolding. | 3 tickets |
| **T2-C** translation | M1-025 (TranslationProvider impls — English + Czech — and /lang) | Standalone — `docs/spec/llm.md` §Translation flow + the SPI from M1-007c. Cross-cutting effect (every bot reply runs through it) but small implementation. | 1 ticket |
| **T2-D** chat-mode | M1-026 (chat agent + chat_memory + chat_session + /compress + /clear + agent tools), M1-027 (auto-compress near context-window ceiling), M1-028 (/stop + /retry + summary_anchor) | All cite `docs/spec/llm.md` §SPI shape (chat task) + `docs/spec/security.md` §Prompt-injection defenses + `docs/spec/commands.md` §Conversation control. Tightly coupled around the live chat-mode runtime. | 3 tickets |
| **T2-E** privacy | M1-029 (/forget + /export) | Standalone — `docs/spec/commands.md` §Conversation control (privacy section) + audit-log invariants from §Audit. Best after T2-D so /forget knows what tables to purge. | 1 ticket |
| **T2-F** groups | M1-030 (group support + /promote + /demote + @mention + /group-timezone), M1-031 (periodic digests + summary_cache + staggered scheduler + degraded fallback) | Both cite `docs/spec/schema.md` §Identity (groups) + `docs/spec/commands.md` §Group commands. Digests need groups to exist. | 2 tickets |
| **T2-G** quarantine | M1-032 (/quarantine list/approve/reject + re-evaluation job + NEEDS_REVIEW + admin notification throttling) | Standalone — `docs/spec/security.md` §Quarantine workflow + §Re-evaluation job + §Failure handling. Self-contained admin workflow. | 1 ticket |
| **T2-H** assets | M1-033 (asset commands /zcash + /monero + bootstrap-assets.json + asset_config + price_snapshot + asset Fetchers) | Standalone — `docs/spec/commands.md` §Asset commands + `docs/design/10-asset-commands.md`. Operator-optional feature; conformant deployment without it. | 1 ticket |

Tier 2 subtotal: 8 groups, 15 tickets.

### Tier 3 — adapters and breadth

Pluggable implementations behind the SPIs from Tier 0.

| Group | Tickets | Rationale | Size |
|---|---|---|---|
| **T3-A** production adapters | M1-034 (SimpleX adapter), M1-035 (Signal adapter) | Both impl `MessagingAdapter` SPI from M1-007c. Share `docs/spec/messaging.md` whole + `docs/spec/security.md` §Per-adapter admin threat profile. Parallel work — shared base class likely. | 2 tickets |
| **T3-B** polled fetchers | M1-036 (Bluesky), M1-037 (Reddit), M1-038 (Nitter), M1-039 (YouTube), M1-040 (Odysee) | All impl the `Fetcher` SPI from M1-007a, same template repeated. Could be one session (template-heavy, low per-ticket cost) or split 3+2 if context budget is tight. Each is small (~100 lines). | 5 tickets, 1–2 sessions |
| **T3-C** Nostr StreamSource | M1-041 | Standalone — `docs/spec/architecture.md` §Ingest SPIs (StreamSource portion) + `docs/spec/security.md` §Per-source trust boundaries §Nostr (signature verification, per-relay degradation, terminal failed state, drain on shutdown). Larger than a Fetcher because StreamSource has connection lifecycle. | 1 ticket |
| **T3-D** Anthropic LLM | M1-042 | Standalone — second impl of `LlmProvider` SPI from M1-007b. The OpenAI-compatible impl in M1-014 set the pattern. | 1 ticket |

Tier 3 subtotal: 4 groups (5 if T3-B splits), 9 tickets.

## Grand total

- Tier 0: 2 groups, 6 tickets in handoffs (M1-006..M1-009 excluding done/drafted)
- Tier 1: 6 groups, 16 tickets
- Tier 2: 8 groups, 15 tickets
- Tier 3: 4–5 groups, 9 tickets

**~20–21 authoring sessions across ~46 tickets** (after Tier-0
drafts plus the three already done).

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
