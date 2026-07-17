# v1 completion plan — M2 through M5

> **Created:** 2026-05-26, T3-A planning session.
> **Context:** M1 is complete (117 done, 4 deferred). The original
> `implementation-plan.md` defined 11 milestones (M0–M10) to deliver v1.
> M1 expanded far beyond its original "narrow vertical slice" scope,
> absorbing the bulk of M2–M5 and all of M7 and M10. This document
> replaces the original M2–M10 milestone definitions with a revised
> plan based on a code-level audit of what M1 actually built.
>
> **All milestones below are v1.** M = milestone (implementation phase),
> not major version. M1 + M2 + M3 + M4 + M5 = v1 feature-complete.

---

## 1. M1 completion status

| Metric | Count |
|---|---|
| Done | 117 |
| Deferred (carry to M2) | 3 (M1-031, M1-042, M1-079) |
| Deferred (decomposed, no carry) | 1 (M1-034 → M1-034a/b, both done) |
| **Total** | **121** |

Final M1 commit: `93d61ba M1-087: BlueskyFetcher — AT Protocol polled feed`.

### What M1 absorbed from later milestones

| Original milestone | What M1 implemented | Key tickets |
|---|---|---|
| M2 (eval pipeline, admin commands) | Re-eval job, quarantine NOTIFY/commands, /audit, /ban, /unban, /vouch, /grant-admin, /revoke-admin, /invite lifecycle, throttled admin notifier, tagger partial-valid, admin-review TTL, per-source UNKNOWN auto-disable, NEEDS_REVIEW depth alert | M1-081a, M1-081b, M1-083, M1-044c, M1-045, M1-046, M1-058 |
| M3 (groups, remaining commands) | Full group infra, /promote, /demote, /group-timezone, auto-promote, membership events, /save, /saved, /unsave, /follow-tag, /unfollow-tag, /stop, /retry, /forget, /export, source admin commands | M1-079a–e, M1-084, M1-052, M1-054, M1-065–067, M1-053 |
| M4 (periodic digests) | Complete — scheduler, worker, degraded fallback, /retry --digest, missed-slot, summary cache | M1-080, M1-080a–c |
| M5 (fetchers + URL routing) | All 6 fetcher impls + polymorphic dispatch + URL kind-resolution | M1-086–091, KindResolver |
| M7 (asset commands) | Complete — bootstrap-assets, asset_config, price_snapshot, /zcash, /monero | M1-055, M1-055a–c |
| M10 (Anthropic + routing) | AnthropicProvider + all 6 ModelTask routes + local-only conflict guard | M1-085, LlmRouter, LlmRouterStartupGuard |

---

## 2. Gap analysis — code-level audit

Methodology: for each original M2–M10 acceptance criterion, searched
production code, test code, and DDL migrations for the implementing
artifact. Confirmed via `find`, `grep`, and file reads.

### Confirmed COMPLETE (no further work needed)

| Item | Evidence (file path) |
|---|---|
| Per-source UNKNOWN auto-disable | `collector/eval/reeval/PerSourceUnknownTracker.java` + test |
| NEEDS_REVIEW depth alert | `collector/eval/reeval/ReEvaluationJob.java` (config key `infochat.reeval.needs-review-depth-threshold`) |
| URL kind-resolution | `provider/source/KindResolver.java` — closed host-pattern table, 6 kinds |
| Embedding model identity guard | `collector/eval/embedding/EmbeddingMetadataStartupGuard.java` + `V11__post_embedding.sql` |
| Local-only routing conflict | `llm/routing/LlmRouterStartupGuard.java` + `LocalOnlyConflictStartupIT` |
| Per-task LLM routing (6 tasks) | `llm/routing/LlmRouter.java` — SECURITY_JUDGE, TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, TRANSLATOR |
| Bot-removed / group-deleted / user-left | `provider/group/MembershipEventHandler.java` — sets removed_at, clears is_group_admin |
| AnthropicProvider | `llm/impl/anthropic/AnthropicProvider.java` — native Messages API + prompt caching |

### Confirmed REMAINING (v1 scope, zero or partial code)

#### 2a. Entity extraction + cross-source linking

**Spec authority:** `spec/schema.md` §Post entity, §Post reference; `spec/decisions.md` D6;
`spec/architecture.md` §Ingest pipeline (entity extraction → embedding → READY).

**Current state:**
- `ModelTask.ENTITY` exists in enum; `LlmRouter` routes it. No extraction code.
- No `post_entity` DDL in any migration. V7 comment: "later T1 tickets land Tier-2 derivatives (post_reference, post_embedding, quarantine)".
- No `post_reference` DDL. `GetReferencesTool.java` comment: "post_reference table is v2-deferred (no migration exists)". `ClusterTraversal.java`: "post_reference graph as empty in MVP".
- No `LinkingJob` code.

**What exists and can be reused:**
- `GetReferencesTool` (Provider) — exists, returns empty; just needs data.
- `ClusterTraversal` (Provider) — connected-component algorithm ready.
- `EmbeddingWorker` — post_embedding table + vectors exist; cosine similarity search is wirable.
- `ModelTask.ENTITY` + `LlmRouter` routing — routing infrastructure ready.

#### 2b. Fetcher failure ladder (D42)

**Spec authority:** `spec/decisions.md` D42; `spec/schema.md` §Sources and tags (status state machine).

**Current state:**
- `FetchScheduler.java` lines 71–79 comment: "No update to `source.consecutive_failures` / `last_fetch_at` / `last_success_at` / `status` — that wiring is T2-B's D42 work... T1-C's failure-handling contract is 'log and keep ticking'".
- Schema columns `consecutive_failures`, `last_fetch_at`, `last_success_at`, `status` exist on `source` table but are not driven by fetcher logic.
- `/source-enable` command exists (M1-053) but the failure-ladder trigger path that would flip status='failed' does not.

#### 2c. Nostr StreamSource

**Spec authority:** `spec/architecture.md` §Ingest SPIs — StreamSource; `spec/security.md` §Per-source trust boundaries — Nostr; `spec/decisions.md` D38.

**Current state:**
- `StreamSource` SPI interface exists: `core/ingest/StreamSource.java` with `start(sourceId, filterSpec, deliver)` and `stop()`.
- `BootstrapLoader` validates Nostr config format (relay list, kind filter).
- `BootstrapLoader` references future `StreamSourceSupervisor` at `@Priority(450)`.
- Zero runtime code: no supervisor, no Nostr impl, no WebSocket, no signature verification.

#### 2d. SimpleX adapter

**Spec authority:** `spec/messaging.md` §Per-adapter trust level — SimpleX; `spec/security.md` §Per-adapter admin threat profile — SimpleX; D32, D46.

**Current state:**
- `MessagingAdapter` SPI exists with full contract (start, send, update, finalize, setTyping, close, capabilities, trustLevel).
- `InMemoryAdapter` is the only implementation.
- Zero SimpleX code.

**Open decision:** SimpleX adapter shape — subprocess + WebSocket vs embedded. Gates identity-material layout and packaging.

#### 2e. Signal adapter

**Spec authority:** `spec/messaging.md` §Per-adapter trust level — Signal; `spec/security.md` §Per-adapter admin threat profile — Signal; D32, D46.

**Current state:**
- Same SPI as SimpleX. Zero Signal code.

**Open decision:** Signal adapter underlying tech — `signal-cli` JSON-RPC subprocess vs `libsignal-service-java` in-process vs `signald`. Gates identity-material layout, packaging, supply-chain.

#### 2f. Deferred M1 tickets

| Ticket | Title | Scope | Blocker status |
|---|---|---|---|
| M1-031 | Provider catch-up hardening (3 M1-030 OUT-OF-MODEL advisories) | Provider-side defense-in-depth: NewPostHandler, NewPostListener, NewPostReconciler, ProviderStateDao | Unblocked (no deps) |
| M1-042 | Operator-config + startup-guard hardening | LlmRouter unknown-default fallback, startup guard URL widening, outbox pagination, fetcher log redaction | Unblocked (no deps) |
| M1-079 | Group lifecycle roundtrip IT | Single IT file exercising full group lifecycle (auto-promote, /promote, /demote, user-left, re-auto-promote, DM-only gate) | Unblocked (M1-084 done — tryAutoPromote re-promote + MembershipEvent handler wiring resolved) |

---

## 3. Revised milestone structure

### Dependency graph

```
Phase 2 (eval pipeline + hardening: M1-092..094 + reopens)
 │
 └──→ Phase 3 (Nostr StreamSource: M1-095..101)
        needs: post_reference DDL from M1-093

Phase 4 (SimpleX adapter: M1-102..105)
 │
 └──→ Phase 5 (Signal adapter: M1-106..109)
        needs: multi-adapter scaffolding proven by Phase 4
```

Phases 2 and 4 are **independent** — no dependency between them.

### Recommended execution order

**Phase 2 → Phase 4 → Phase 5 → Phase 3**

| Order | Phase | Rationale |
|---|---|---|
| 1st | Phase 2 | Closes eval pipeline gaps. Small scope (6 tickets). Every later phase benefits. |
| 2nd | Phase 4 (SimpleX) | First production adapter. Proves multi-adapter wiring with real external system. |
| 3rd | Phase 5 (Signal) | Firm v1 requirement (user standing instruction). SimpleX patterns still fresh. |
| 4th | Phase 3 (Nostr) | Most technically complex subsystem. Lower urgency than the messaging adapters users interact through. M1-093's post_reference DDL will be long-landed. |

Alternative: **Phase 2 → Phase 3 → Phase 4 → Phase 5** follows dependency chain strictly, front-loads the hardest subsystem, but delays the adapters.

---

## 4. Ticket decomposition

> **Numbering decision (2026-05-26).** All v1 tickets use the `M1-NNN`
> numbering and the `/m1-tick` workflow. The "M2–M5" labels below are
> thematic phases, not ticket-ID prefixes. Deferred M1 tickets (M1-079,
> M1-031, M1-042) are reopened via `/m1-tick reopen`, not re-created.
>
> **Correction (2026-07-17) — do NOT reopen the deferred tickets named in
> this doc.** This is a superseded 2026-05-26 snapshot (its counts are
> historical). Current disposition: **M1-079** and **M1-042** are DONE;
> **M1-031** is ABANDONED (`superseded` — advisory #2 shipped via
> M1-142/M1-309, #3 relocated by M1-499, #1 accepted out-of-model
> residual). The Phase-2 rows below listing M1-079/M1-031/M1-042 as
> "(reopen)" are stale.

### Phase 2 — Eval pipeline completion + hardening

**Goal:** Complete entity extraction, cross-source linking, fetcher failure ladder. Close deferred M1 hardening tickets.

| ID | Title | Complexity | Risk | Blocked by | Key files |
|---|---|---|---|---|---|
| M1-092 | post_entity DDL + EntityExtractor pipeline stage | high | medium | — | New migration V26, new `collector/eval/entity/` package. Integrate into eval pipeline parallel with embedding, gated by tagger. |
| M1-093 | post_reference DDL + LinkingJob + GetReferencesTool wiring | high | medium | M1-092 | New migration V27. LinkingJob: entity match (precision) + cosine similarity (recall). Wire GetReferencesTool + ClusterTraversal. |
| M1-094 | Fetcher failure ladder (D42) | medium | low | — | FetchScheduler: wire consecutive_failures counter, status transition, throttled admin notify. /source-enable: reset counter. |
| M1-079 | Group lifecycle roundtrip IT (reopen) | medium | medium | — | Single test-only file. M1-084 resolved the premise-fail blockers. Reopen via `/m1-tick reopen M1-079`. |
| M1-031 | Provider catch-up hardening (reopen) | medium | low | — | NewPostHandler, NewPostListener, NewPostReconciler, ProviderStateDao. Reopen via `/m1-tick reopen M1-031`. |
| M1-042 | Operator-config hardening (reopen) | medium | low | — | LlmRouter, LlmRouterStartupGuard, OutboxRehydrator, FetchScheduler. Reopen via `/m1-tick reopen M1-042`. |

**Parallelism:** M1-092 → M1-093 is the only sequential dependency. M1-094, M1-079, M1-031, M1-042 are all independent.

---

### Phase 3 — Nostr StreamSource

**Goal:** Implement StreamSource supervisor and Nostr v1 (kinds 1 + 6, signature verification, multi-relay, async startup, graceful drain).

**Blocked on:** M1-093 (post_reference DDL for kind-6 linking).

| ID | Title | Complexity | Risk | Blocked by | Notes |
|---|---|---|---|---|---|
| M1-095 | StreamSourceSupervisor — lifecycle, async startup, drain framework | medium | medium | — | Manages StreamSource registration. Readiness doesn't wait for relay connect. Drain with hard timeout. |
| M1-096 | NostrStreamSource — WebSocket relay pool + reconnect | high | high | M1-095 | Multi-relay connections. Reconnect with `since=last_persisted_event_at`. **Open decision: WebSocket library.** |
| M1-097 | Nostr event verification + kind filter | medium | medium | M1-096 | Signature verification before Stage 1. Kind allowlist (1, 6). Failed-sig counter. |
| M1-098 | Cross-relay dedup | medium | medium | M1-096 | Same event from N relays → 1 post row via upstream_identifier. |
| M1-099 | Per-relay degradation + all-relays-bad cycle cap | medium | medium | M1-096 | Bad relay → cooldown. All-relays-bad → admin notify. Cycle cap → terminal `source.status='failed'`. |
| M1-100 | Kind-6 cross-source linking | medium | low | M1-098, M1-093 | Repost: commentary as body, post_reference edge keyed by upstream_identifier. |
| M1-101 | SSRF on wss:// relays | low | low | M1-096 | DNS re-resolve per connect. Blocked-range refusal. Peer-IP-change → hard close. Reuses infochat-ssrf. |

**Open decision (blocking M1-096):** Nostr WebSocket library — `java.net.http` WebSocket client vs Nostr-specific library. Spec is silent.

---

### Phase 4 — SimpleX adapter

**Goal:** First production messaging adapter. Proves multi-adapter Provider wiring.

**Blocked on:** Nothing (SPI + group infra exist).

| ID | Title | Complexity | Risk | Blocked by | Notes |
|---|---|---|---|---|---|
| M1-102 | SimpleX adapter skeleton — capabilities, identity material | medium | medium | — | trustLevel=HIGH. Capability flags per spec. Identity material loader + startup validation. |
| M1-103 | SimpleX connection — inbound/outbound messaging | high | high | M1-102 | Wire protocol. Message encoding/decoding. Reconnect. Per-adapter resilience. **Open decision: adapter shape.** |
| M1-104 | SimpleX group support — mention recognition, membership events | medium | medium | M1-103 | Queue-address mention recognition (byte equality). Open: does SimpleX expose native user_left_group? |
| M1-105 | Multi-adapter Provider wiring + cross-adapter isolation IT | medium | low | M1-103 | SimpleX + InMemory coexist. (adapter, contact_id) isolation. Readiness = at-least-one-up. |

**Open decision (blocking M1-103):** SimpleX adapter shape — subprocess + WebSocket vs embedded. Gates identity-material layout.

---

### Phase 5 — Signal adapter

**Goal:** Second production adapter. Proves multi-adapter production deployment.

**Blocked on:** Phase 4 (SimpleX proves multi-adapter scaffolding).

| ID | Title | Complexity | Risk | Blocked by | Notes |
|---|---|---|---|---|---|
| M1-106 | Signal adapter skeleton — capabilities, ACI identity | medium | medium | — | trustLevel=HIGH. mentionUuid (ACI) as contact_id. |
| M1-107 | Signal subprocess integration | high | high | M1-106 | signal-cli JSON-RPC or chosen tech. Inbound/outbound. Per-adapter resilience. **Open decision: underlying tech.** |
| M1-108 | Signal mention recognition + group support | medium | medium | M1-107 | mentionUuid matching. Membership events (Signal exposes natively). |
| M1-109 | Multi-adapter production shape IT | medium | low | M1-108, M1-105 | SimpleX + Signal simultaneous. Cross-adapter blast radius. Last-admin global across adapters. |

**Open decision (blocking M1-107):** Signal adapter tech — `signal-cli` JSON-RPC vs `libsignal-service-java` vs `signald`.

---

## 5. Summary

| Phase | Tickets | High-complexity | Open decisions |
|---|---|---|---|
| 2 (eval + hardening) | 3 new (M1-092–094) + 3 reopen (M1-079, M1-031, M1-042) | 2 | None |
| 3 (Nostr) | 7 (M1-095–101) | 1 | WebSocket library |
| 4 (SimpleX) | 4 (M1-102–105) | 1 | Adapter shape |
| 5 (Signal) | 4 (M1-106–109) | 1 | Underlying tech |
| **Total** | **21** | **5** | **3** |

After Phase 5, v1 is feature-complete per spec.

---

## 6. Open decisions requiring user input

All resolved (2026-05-26). Tickets for all phases are written.

| Decision | Resolved | Rationale |
|---|---|---|
| Nostr WebSocket library | JDK `java.net.http` WebSocket client | Zero-dependency. Only NIP-01 subscribe/receive needed — no special Nostr library features required. |
| SimpleX adapter shape | Provider-managed `simplex-chat` subprocess + WebSocket JSON API | Lifecycle coupling is inherent (simplex-chat is useless without Provider). Simpler operator experience (3 services not 5). Automatic partial enablement. |
| Signal adapter tech | `signal-cli` JSON-RPC, Provider-managed subprocess | Most mature, actively maintained. Same subprocess pattern as SimpleX (consistent architecture). JSON-RPC is a clean protocol boundary. |

---

## 7. Next steps

1. ~~**Write Phase 2 ticket files.**~~ Done (2026-05-26): M1-092, M1-093, M1-094 created.
2. **Reopen deferred tickets.** `/m1-tick reopen M1-079`, `/m1-tick reopen M1-031`, `/m1-tick reopen M1-042`.
3. ~~**Resolve open decisions.**~~ Done (2026-05-26): JDK WebSocket for Nostr, Provider-managed subprocess for both adapters, signal-cli JSON-RPC.
4. ~~**Write Phase 3–5 ticket files.**~~ Done (2026-05-26): M1-095..M1-101 (Nostr), M1-102..M1-105 (SimpleX), M1-106..M1-109 (Signal). All pass `lint-ticket.py`.
5. **Update `implementation-plan.md`** with a §"Post-M1 revision" addendum noting that M2–M10 as originally defined are superseded by this document.
