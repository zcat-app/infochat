# infochat v1 — Implementation Plan

## 1. Summary

infochat is a two-service Quarkus application — a headless **Collector** (RSS + social ingest, LLM eval pipeline, post storage) and a user-facing **Provider** (messaging adapters, command router, chat agent, periodic group digests) sharing a Postgres+pgvector instance. **No production code exists yet.** The spec is comprehensive and recently reviewed; this plan is the source from which implementation tasks and tests will be cut.

The v1 stack is **locked**: JDK 25 + Quarkus 3.33 LTS, virtual threads + imperative blocking, Maven multi-module, PostgreSQL+pgvector, `quarkus-langchain4j`, SmallRye in-memory channels, Quarkus Scheduler. `decisions.md` D1 was amended on 2026-05-09 to reflect the JDK 25 + Quarkus 3.33 LTS commitment.

The plan is **eleven milestones** (M0–M10). M0 is documentation alignment only — every drift the design-note triage surfaced is routed to a specific design-note edit before any production code lands. M1 is a **vertical slice with full v1 rules** (no MVP-style relaxations of D44 invite-gating, D45 slow-start, D29/D43 translation/localization, D37 audit-log breadth, D35/D36 cancellation/retry, capability-flag invariants, or scope-discriminator design). M2–M10 expand breadth: full eval pipeline + `LISTEN/NOTIFY` breadth, group support, periodic digests, all source kinds, Nostr (StreamSource), asset commands, SimpleX, Signal, Anthropic LLM provider.

Ten stack-level choices are not pinned by the spec or `decisions.md` (persistence layer, migration tool, Signal/SimpleX adapter tech, deployment target, test infra, container base, secrets handling, observability baseline, plus two design-tier ambiguities). Section 5 surfaces each as 2–4 options with a recommendation; Section 7 lists genuine spec ambiguities the plan cannot resolve. Section 8 expands M1 into ten PR-sized tasks pointing back at the milestone scenarios they satisfy.

---

## 2. Stack confirmation (do not relitigate)

- **JDK 25 LTS** — for JEP 491 virtual-thread `synchronized`-pinning fix, Compact Object Headers, Generational ZGC.
- **Quarkus 3.33 LTS** (Mreleased 2026-03-25) — first LTS line with full official JDK 25 support. (`spec/decisions.md` D1, amended 2026-05-09.)
- **Concurrency**: virtual threads + imperative blocking via `@RunOnVirtualThread` for I/O-heavy paths (LLM calls, JDBC, messaging-adapter outbound, RSS fetchers). Mutiny only at inherently event-loopy adapter boundaries (e.g., SimpleX WebSocket reads). **No** Hibernate Reactive. **No** end-to-end Mutiny pipelines.
- **Maven multi-module** — six modules per `docs/design/09-reference.md` §9.1 plus the new `infochat-ssrf` shared module mandated by `spec/security.md` §SSRF (current 09-reference is missing it; M0 fix).
- **Storage**: PostgreSQL + `pgvector`. HNSW for `laptop`/`vps`/`remote-llm`; IVFFlat for `pi` (D27).
- **LLM integration**: `quarkus-langchain4j` for SPI plumbing; OpenAI-compatible `LlmProvider` (covers Ollama, llama.cpp, OpenAI, OpenRouter, NanoGPT) + Anthropic provider (D32).
- **Messaging**: SmallRye Reactive Messaging in-memory channels for the eval queue (Kafka swap is a v2 amendment per D3); PostgreSQL `LISTEN/NOTIFY` for Collector→Provider events (D4).
- **Scheduling**: Quarkus Scheduler for fetch ticks, periodic-digest slots, re-eval job, chat-memory pruner.
- **Hardware profiles** (D27): `laptop` / `vps` / `pi` / `remote-llm`. The `remote-llm` name (renamed from `remote`) means local DB + services + remote LLM API.
- **Adapters**: SimpleX + Signal + in-memory test adapter (D32, D46). v1 ships all three; the user's standing instruction is that Signal must remain in v1 (memory: `feedback_signal_in_v1.md`).
- **Deployment topology** (D41): exactly one Collector + exactly one Provider per deployment, enforced via `pg_advisory_lock` (`infochat.collector`, `infochat.provider`).

---

## 3. Design-note triage

Every design note has been classified against its paired spec file and the cross-cutting decisions log. The default classification is **Adopt**; divergence requires a citation. M0 fixes every drift listed below.

| Design note | Pairs with | Classification | Drift summary (M0 ticket) |
|---|---|---|---|
| `00-mvp.md` | (historical) | **Superseded** | Add a banner pointing at this plan. Body unchanged. |
| `01-architecture.md` | `spec/architecture.md` | **Discard and redo** | Legacy `(fetcher, url)` source key; 2-channel NOTIFY diagram (`new_post`, `quarantine`) — should be 3 (`new_post`, `new_price_snapshot`, `quarantine_review` with tagged payload); profile names use `remote` not `remote-llm`; missing `pg_advisory_lock` enforcement; missing StreamSource async-startup / drain / cycle-cap details; `last_ready_post_at` scalar cursor instead of compound `(cursor_high, cursor_low_kind, cursor_low_id)` per-channel cursor. |
| `02-schema.md` | `spec/schema.md` | **Adopt with revisions** | Missing entities: `asset_config`, `price_snapshot`, `summary_anchor`, `invite_code`. `registration_state` enum has `bootstrap` instead of spec's `vouched`. `provider_state` keyed by `provider_instance` (multi-instance!) instead of per-channel singleton. `quarantine.status` enum missing `BENIGN_CLOSED`. TTL policy table says `chat_memory` indefinite — contradicts D40 / Invariant 9. `users` table missing `(adapter, contact_id)` composite key. `groups` missing `(adapter, upstream_group_id)` split + `removed_at`. `group_membership` missing `removed_at`. Missing `audit_log_view` definition. |
| `03-commands.md` | `spec/commands.md` | **Adopt with revisions** | Missing slow-start allowed-command list enumeration; `/export` field-level exclusions not stated; `/stop` cancellation primitive (`pg_cancel_backend(pid)` + per-tool `statement_timeout`) and the closed list of interruptible vs non-interruptible operations underspecified; permission matrix needs `/source-enable`, `/source-disable`, `/vouch`, `/invite create\|list\|revoke`, `/forget`, `/export`, `/stop`, `/retry`, `/zcash`, `/monero` rows. |
| `04-security.md` | `spec/security.md` | **Adopt with revisions** | Tool surface names drift: design says `searchByTag`, `getPostById`, `listSavedPosts`; spec mandates exactly `searchPosts`, `getPost`, `listSaves` (CI-load-bearing). Provider role missing EXECUTE on `approve_quarantine` / `reject_quarantine`. Stage 1 regex engine wording too permissive ("either RE2/J or `java.util.regex`" — spec is committed to `java.util.regex` + watchdog only in v1; RE2 is v2). |
| `05-llm-and-embeddings.md` | `spec/llm.md` | **Adopt as-is** | Verified clean. |
| `06-messaging.md` | `spec/messaging.md` | **Adopt with revisions** | Defers Signal to v2 (line 521 says "Telegram, Matrix, Signal — deferred to v2") — contradicts SPEC.md §4 v1 scope and D32. Need full Signal adapter design notes parallel to SimpleX. Ambiguity on whether SimpleX exposes native `memberLeft` signal in §6.4.4. |
| `07-deployment.md` | `spec/deployment.md` | **Adopt with revisions** | "Java" version not pinned to 25; profile name `remote` should be `remote-llm`; bootstrap admin per-adapter optional + union-non-empty constraint not explicit; bootstrap admin drift behaviour (rotation creates new row, leaves old in place, last-admin protection global) not documented. |
| `08-verification.md` | `spec/verification.md` | **Adopt as-is** | Verified clean. |
| `09-reference.md` | `spec/decisions.md`, `spec/architecture.md` | **Adopt with revisions** | Module DAG missing the `infochat-ssrf` shared module that `spec/security.md` §SSRF mandates (used by both Collector and Provider). |
| `10-asset-commands.md` | `spec/commands.md` §Asset commands + D39 | **Adopt as-is** | Verified clean. |

Spec-side drift caught alongside the design triage: `spec/architecture.md` line 8 still says "Java 21". The user has indicated D1 is the binding statement; line 8 is a secondary mention to sync as part of M0 (or the user can amend in-place).

---

## 4. Milestone build order

Each milestone follows the same template: **Goal**, **Spec sections / decision IDs implemented**, **Maven modules and packages created or touched**, **SPI seams introduced or extended**, **Schema entities created or modified**, **Acceptance criteria**, **Given/When/Then scenarios**, **Blocking dependencies on earlier milestones**, **Test surface**, **Decision dependency**.

### Reading guide

- **Spec citations** point at `docs/spec/<file>.md` plus the section heading and, where applicable, the D-number from `docs/spec/decisions.md`.
- **Design-note citations** point at `docs/design/<file>.md` and a section number when the design file uses one.
- **Module names** follow the 6-module DAG: `infochat-core`, `infochat-llm-adapter`, `infochat-messaging-adapter`, `infochat-collector`, `infochat-provider`, plus the new `infochat-ssrf`.
- **Decision dependency** lines name the open stack questions in §5 that gate concrete code shape.

### Milestone 0 — Documentation alignment (no production code)

#### Goal
Bring the design notes into byte-level alignment with the spec so Milestone 1 onwards starts from a consistent map. No production code, no schema, no SPI work — purely doc edits and a new module slot in the DAG note.

#### Spec sections / decision IDs implemented
- All triage drift cited inline below, plus `spec/architecture.md` line 8 (Java 25 — already correct on spec side, design must align), `spec/SPEC.md` §4 (v1 scope), D1, D38, D41, D44, D45, D46, D27, D29, D30, D32, D34, D43.

#### Maven modules and packages created or touched
- No code. Documentation only:
  - `docs/design/01-architecture.md`
  - `docs/design/02-schema.md`
  - `docs/design/03-commands.md`
  - `docs/design/04-security.md`
  - `docs/design/06-messaging.md`
  - `docs/design/07-deployment.md`
  - `docs/design/09-reference.md`
  - `docs/design/00-mvp.md` (superseded banner only — do NOT rewrite content)

#### SPI seams introduced or extended
- None.

#### Schema entities created or modified
- None at the DDL level. The schema design note rewrite re-enumerates entities the spec already commits to (asset_config, price_snapshot, summary_anchor, invite_code, audit_log_view, provider_state per-channel, registration_state enum); these are doc-only adjustments at this milestone.

#### Acceptance criteria (one ticket per drift item)

### Task 0.1 — Redo `01-architecture.md`
Drift inventory (each line = an item to fix):
- §1.1 ASCII diagram lists 2 NOTIFY channels (`new_post`, `quarantine`); spec commits to 3 (`new_post`, `new_price_snapshot`, `quarantine_review` with tagged payload `(target_kind, target_id, new_status)`) — `spec/architecture.md` §Inter-service communication.
- §1.2 module list is 5 modules; v1 also requires `infochat-ssrf` per `spec/security.md` §SSRF and outbound connections.
- §1.4.2 / §1.6 use the legacy `(fetcher, url)` source key and `infochat.adapter.simplex.url` style single-adapter config; spec commits to `(kind, identifier)` (D38) and multi-adapter (D46).
- §1.5 / §2.8 cite a scalar `last_ready_post_at` cursor and `provider_state` keyed by `provider_instance` — spec commits to per-channel `provider_state` rows with compound cursor `(cursor_high, cursor_low_kind, cursor_low_id)` and CAS update (`spec/schema.md` §Operational — Provider state).
- §1.7 profile names use `remote`; spec uses `remote-llm` (D27, glossary).
- Single-instance enforcement via `pg_advisory_lock` (`infochat.collector`, `infochat.provider`) is missing from the architecture note (`spec/architecture.md` §Deployment topology).
- `StreamSource` async-startup, drain on shutdown, all-relays-bad cycle cap → terminal `failed` state are missing — `spec/architecture.md` §Ingest SPIs.
- Asynchronous startup carve-out (relay unreachable does not fail readiness) needs to be cross-referenced from §1.4.2 startup-bean ordering.
- Ingest SPI separation: `Fetcher` (polled) and `StreamSource` (long-lived event stream) — both feed the same outbox; design must enumerate the per-tick pagination cap and the asset-Fetcher direct-write-to-`price_snapshot` path (D38, D39).

Acceptance: a side-by-side diff between `spec/architecture.md` and the rewritten `design/01-architecture.md` finds **zero contradictions**; CI grep for the strings `last_ready_post_at` (scalar form), `(fetcher, url)`, `remote` (used as profile name without `-llm`), and `quarantine` (channel name without `_review`) returns zero matches in `design/01-architecture.md`.

### Task 0.2 — Revise `02-schema.md`
Drift inventory:
- Add: `asset_config` (per `spec/schema.md` §Operational — Asset config), `price_snapshot` (per §Operational — Price snapshot), `summary_anchor` (per §Per-scope state — Summary anchor with two partial unique indexes for `command_kind ∈ {personal, digest}`), `invite_code` (per §Identity and access — Invite code, including `invite_type ∈ {CONTACT_BOUND, OPEN_ADAPTER}` and `expected_contact_id` CHECK constraint).
- Replace `users.contact_id UNIQUE` with `(adapter, contact_id)` composite key; add `adapter` column (`spec/schema.md` §Identity and access — User entity, D46).
- Replace `registration_state` enum value `bootstrap` with `vouched` (per `spec/schema.md` §Identity and access — bootstrap admins are `vouched`, not a distinct `bootstrap` value; see also `spec/deployment.md` §Bootstrap behavior — Bootstrap-seeded admin row shape).
- Replace `quarantine.status` enum: must include `BENIGN_CLOSED` between `PENDING` and `APPROVED/REJECTED` (`spec/schema.md` §Posts and derivatives — Quarantine).
- Replace `provider_state` shape: drop the `provider_instance` PK, replace with `(channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)` with `UNIQUE (channel)` and CAS UPDATE clause (`spec/schema.md` §Operational — Provider state).
- Add `audit_log_view` definition: redact `actor_contact_id`, `target_contact_id`, and `details_json` per the secrets-catalogue redactor (`spec/schema.md` §Operational — Audit log view; `spec/security.md` §DB roles).
- Update §2.9 TTL policy table: `chat_memory` is **not** indefinite — D40 commits to a fixed TTL with a scheduled pruner; `chat_session` and `summary_anchor` share the same pruner per Invariant 9 (`spec/schema.md` §Invariants — 9).
- Add `groups`: split `adapter_group_id` into `(adapter, upstream_group_id)` composite natural key, add `removed_at` (`spec/schema.md` §Identity and access — Group; `spec/messaging.md` §Failure handling — Bot removed from group).
- Add `group_membership.removed_at` (`spec/schema.md` §Identity and access — Group membership user-departure lifecycle).
- Audit log: drop `scope_kind` text-blob; add `actor_user_id`, `actor_contact_id`, `actor_adapter`, `target_kind` (closed enum), `target_id`, `target_contact_id`, `scope_id`, `request_id`, `details_json` per `spec/schema.md` §Identity and access — Audit log; mark INSERT-only role (`spec/security.md` §DB roles).
- Add stored procedures `approve_quarantine(quarantine_id, actor_id)` and `reject_quarantine(quarantine_id, actor_id)` (`spec/security.md` §Quarantine workflow).
- Add `last-admin protection` trigger serialization requirement (`SHARE ROW EXCLUSIVE` lock or `SELECT … FOR UPDATE`) across both UPDATE and DELETE paths — invariant 2 in `spec/schema.md`.
- Add Invariant 10 (audit-log append-only — `spec/schema.md` §Invariants — 10).
- Drop the `users` `DELETE` carve-out except for `registration_state = 'preban'` (the single permitted application-issued DELETE per Invariant 2 carve-out).

Acceptance: every entity bullet in `spec/schema.md` §Entities resolves to a section in `design/02-schema.md`; every invariant from `spec/schema.md` §Invariants is implemented either as a constraint, a trigger, or a documented test.

### Task 0.3 — Revise `03-commands.md`
Drift inventory:
- Add slow-start allowed-command list (the spec commits to it in `spec/security.md` §Slow-start tier; design has no enumeration today). Required entries: `/help`, `/status`, `/get-tags`, `/get-sources`, `/list-sources`, `/summary`, `/saved`, all operator-configured asset commands, `/export`, `/forget`, `/lang`, `/stop`.
- Add `/export` field-level positive list per table (per `spec/commands.md` §Conversation control — `/export`).
- Document `/stop` cancellation primitive (`pg_cancel_backend(pid)` + per-tool statement timeout) and the closed list of interruptible vs non-interruptible operations (`spec/commands.md` §Conversation control — `/stop`; D35).
- Permission matrix: add `/source-enable`, `/source-disable`, `/vouch`, `/invite create|list|revoke`, `/forget`, `/export`, `/stop`, `/retry [--digest]`, `/group-timezone`, `/list-sources --include-deleted`, `/zcash`, `/monero`. Mark probation column on every row.
- Replace single-NOTIFY-channel mention (`quarantine`) with `quarantine_review` and the tagged-payload contract.
- Update onboarding section: replace the legacy "auto-register on first DM" with the v1 invite-gated flow (D44) plus the slow-start probation (D45) and the three welcome modes branching on `(DM-fresh, DM-returning, group-first-mention)` — `spec/commands.md` §Onboarding.

Acceptance: every command in `spec/commands.md` §Command catalogue has a row in the design's permission matrix with explicit DM / group-member / group-admin / bot-admin / probation cells; every privileged-tier command in `spec/commands.md` §Permission model "Closed list" appears in both the matrix and the LLM-output sanitizer match-set table.

### Task 0.4 — Revise `04-security.md`
Drift inventory:
- Tool surface table (§4.3): rename `searchByTag` → `searchPosts`; rename `getPostById` → `getPost`; rename `listSavedPosts` → `listSaves`; keep `getReferences` and `recallMemory`. Set is closed at exactly five — `searchPosts`, `getPost`, `getReferences`, `recallMemory`, `listSaves` (`spec/security.md` §Prompt-injection defenses).
- Stage 1 regex engine wording: pin to `java.util.regex` + watchdog only in v1; remove the "either RE2/J or `java.util.regex`" wording (`spec/security.md` §Ingest pipeline — Stage 1 regex engine commitment). RE2-style swap is a v2 candidate, document as such.
- Provider role description (§4.x DB roles): add `EXECUTE` on `approve_quarantine` / `reject_quarantine` stored procedures and remove any `SELECT` on the raw `original_html` column (`spec/security.md` §DB roles, §Quarantine workflow).
- Add the LLM output sanitizer match-set derivation rule: derived from the closed bot-admin + group-admin tier list at spec level; CI fails on mismatch (`spec/security.md` §LLM output sanitizer — Match-set derivation).
- Add the placeholder format: literal `[REDACTED:<id>]` with the `<id>` token randomized per row (`spec/security.md` §Ingest pipeline — Stage 1).
- Add the per-`(adapter, contact_id)` brute-force rate cap on invite-code attempts and the transport-level rate cap (step 1.5) (`spec/security.md` §Authorization model, §Invite-code registration).
- Add the SSRF library promotion: SSRF gating lives in the new `infochat-ssrf` shared module used by both Collector (every outbound feed/redirect/StreamSource connect) and Provider (every `/add-source` URL probe) (`spec/security.md` §SSRF and outbound connections).

Acceptance: greppable for the five tool names, each appearing exactly once as the canonical name; `searchByTag` / `getPostById` / `listSavedPosts` produce zero matches in the `design/` tree.

### Task 0.5 — Revise `06-messaging.md`
Drift inventory:
- Drop "Telegram, Matrix, Signal — deferred to v2" (line 521). Signal is **in v1** per `SPEC.md` §4 v1 scope and D32.
- Add the Signal adapter design note (parallel to the existing SimpleX section): wire protocol path (`signal-cli` JSON-RPC subprocess, `libsignal-service-java` in-process, or `signald` — flagged as an open decision, see Open questions); identity assertion (Signal ACI — UUID surfaced by `signal-cli` as `mentionUuid` — per `spec/messaging.md` §Per-adapter trust level — Signal); capability flags (`trustLevel = HIGH`, `supportsCodeFormatting`, `supportsMarkdownLinks = false`, `supportsMessageEdit`, `supportsMentionByContactId = true`, `supportsMembershipEvents` per protocol availability); `mentionUuid` is the v1 mention-anchoring rule (`spec/messaging.md` §Required SPI surface — Mention-recognition rule).
- Capability flags table: rename `supportsMarkdownCode` → `supportsCodeFormatting`; add `supportsMarkdownLinks` (must be false in v1, validated at startup); add `supportsMembershipEvents` and `supportsMentionByContactId` (`spec/messaging.md` §Capability flags).
- Add the per-Provider multi-adapter topology rule (D46): one Provider may run any non-empty subset of {SimpleX, Signal, InMemory test adapter}; per-adapter resilience (one adapter's connect failure does not abort Provider startup); readiness probe = at-least-one-adapter-up; per-adapter bot identity material owned by the adapter (`spec/deployment.md` §Topology, §Operator inputs item 7).
- Document the SimpleX `user_left_group` capability question explicitly (open: does SimpleX expose a native left-group event? If not, set `supportsMembershipEvents = false` for the SimpleX adapter and rely on permanent-delivery-failure cleanup per `spec/messaging.md` §Failure handling — User left group).
- Inbound message size cap as transport-layer first defense, application-level cap as second defense (`spec/messaging.md` §Required SPI surface — Inbound message size cap).
- Transient-vs-permanent failure categorisation rules: max 3 attempts (1 + 2 retries), exponential backoff with full jitter (`spec/messaging.md` §Failure handling).

Acceptance: the design note has separate, parallel sections for SimpleX and Signal of comparable depth; the InMemoryAdapter section explicitly notes default `trustLevel = LOW` and the production-deployment exclusion.

### Task 0.6 — Revise `07-deployment.md`
Drift inventory:
- Pin Java to JDK 25 explicitly in §7.x (D1, `spec/architecture.md` line 8).
- Replace profile name `remote` with `remote-llm` everywhere in property keys and tables (D27, glossary).
- Document the per-adapter optional bootstrap admin contact + the union-non-empty constraint (`spec/deployment.md` §Operator inputs item 2).
- Document the bootstrap admin drift behavior: rotation creates a new admin row, leaves prior in place; pruning is an explicit operator action via `/revoke-admin` from the new admin's chat; last-admin protection is global across adapters (`spec/deployment.md` §Bootstrap behavior — Bootstrap admin drift; D46).
- Document the Bootstrap-seeded admin row shape: `is_admin = true`, `is_banned = false`, `probation_until = NULL`, `registration_state = 'vouched'` (`spec/deployment.md` §Bootstrap behavior — Bootstrap-seeded admin row shape).
- Document `bootstrap-assets.json` file-state semantics (3 cases) — path unset → assets disabled OK; path set, file absent → fatal startup; path set, file malformed → fatal startup (`spec/deployment.md` §Bootstrap behavior — Asset bootstrap; `spec/SPEC.md` §4).
- Document the `pg_advisory_lock` single-instance enforcement and the heartbeat row pattern (`spec/architecture.md` §Deployment topology).
- Document the StreamSource asynchronous-startup carve-out from the "bean failure refuses startup" default (`spec/deployment.md` §Bootstrap behavior; `spec/architecture.md` §Ingest SPIs — Asynchronous startup).
- Switch `infochat.adapter=…` single-adapter config to a list of enabled adapters with per-adapter sections; the list is closed at startup.
- (Mid-M0 addition; not spec drift) Commit to a `scripts/` directory of one-click wrappers for build and run (§7.7.1: `build.sh`, `dev.sh`, `run-collector.sh`, `run-provider.sh`, `down.sh`, plus the already-named `reembed.sh` and `backup.sh`). Cross-reference from §7.7's raw bash block, §7.8.1 directory layout, §7.9 first-run steps 9-10, §7.10 cron block. Scripts themselves are deferred to M1-PR10; M0 commits the contract only.

Acceptance: `grep -E '\bremote\b' design/07-deployment.md` returns only contextual prose, never a profile name; `grep -E 'jdk.21|java.21' design/` returns zero matches; the asset bootstrap §lists all three file-state cases verbatim; `grep -nE 'scripts/build.sh|scripts/dev.sh|scripts/run-collector.sh|scripts/run-provider.sh|scripts/down.sh' design/07-deployment.md` returns matches inside §7.7.1.

### Task 0.7 — Revise `09-reference.md`
Drift inventory:
- §9.1 module DAG: add `infochat-ssrf` as a sibling shared module under `infochat-core`. Edge list: `infochat-collector → infochat-ssrf` and `infochat-provider → infochat-ssrf`. The build still rejects a `collector → messaging-adapter` edge.
- The diagram's six-node graph supersedes the current five-node graph.

Acceptance: the DAG diagram in `design/09-reference.md` matches the parent POM's reactor module list and the dependency declarations in `infochat-collector/pom.xml` and `infochat-provider/pom.xml` (those POMs do not exist yet — the alignment will be checked at Milestone 1).

### Task 0.8 — Add a "superseded by SPEC.md §4" banner to `design/00-mvp.md`
- The MVP doc remains as historical context. Add a banner at the top: "**Superseded.** Milestone 1 of the v1 build follows the same vertical-slice shape as this MVP but applies every v1 invariant (D44 invite-gating, D45 slow-start, D29/D43 translation/localization, D37 audit-log breadth, D35/D36 cancellation/retry, capability-flag invariants, scope-discriminator design). See the v1 milestone plan."
- Do **not** rewrite the MVP body.

Acceptance: the file's first content line after the H1 is the superseded banner.

#### Given/When/Then scenarios
N/A (no behavior code lands at Milestone 0).

#### Blocking dependencies on earlier milestones
None.

#### Test surface
- CI markdown-lint or doc-grep checks (greppable substrings above) — runnable as a small shell script.

#### Decision dependency
- None of the open stack questions affect Milestone 0; doc edits are stack-agnostic.

---

### Milestone 1 — Vertical slice with full v1 rules (no relaxation)

#### Goal
Drive a single end-to-end flow against the in-memory test adapter using only `rss` as the source kind and a small command set, but with **every v1 invariant** in force from day one. By the end of Milestone 1, "narrow breadth" is the only relaxation; no rule is.

#### Spec sections / decision IDs implemented
- `spec/SPEC.md` §4 (subset).
- `spec/architecture.md` §Service split, §Inter-service communication (only `new_post` channel is wired), §Deployment topology (`pg_advisory_lock` enforcement), §Architectural principles 1, 3, 4, 5, 6, 7.
- `spec/security.md` §Threat model, §Trust boundaries, §Ingest pipeline (Stage 1 only — Stage 2 wired but allowed to be a stub LLM-judge in tests), §SSRF and outbound connections, §Authorization model (steps 1–9), §User ban (intake-blocking only; admin paths land in M2/M3), §Invite-code registration (D44, full flow), §Slow-start tier (D45), §LLM output sanitizer, §DB roles, §Secrets handling.
- `spec/schema.md` §Identity and access (User, Audit log, Invite code), §Sources and tags (Source, Source subscription, Tag), §Posts and derivatives (Post, UID derivation, Quarantine — without re-eval), §Per-user state (Saved post — schema only, command in M3), §Per-scope state (Scope preferences, Summary anchor, Chat memory, Chat session), §Operational (Provider state — `new_post` channel only, Audit log view), §Invariants 1, 2, 4, 5, 7, 8, 9, 10.
- `spec/commands.md` §Surface conventions, §Command catalogue Discovery (`/help`, `/status`, `/get-tags`, `/get-sources`, `/list-sources` (own scope)), Content (`/summary` with cluster cap), Source management (`/add-source` DM only, `/list-sources` non-`--all`), Conversation control (`/stop`, `/forget`, `/lang` (DM), `/clear`, `/compress`), §Onboarding (DM-fresh, DM-returning), §Permission model.
- `spec/llm.md` §SPI shape (LlmProvider, EmbeddingProvider, TranslationProvider, ModelTask, Router, Call context), §Prompt-injection-aware prompt shape, §Translation flow (English passthrough at minimum, `cs` bundle keys present), §Determinism boundary, §Memory retrieval, §Failure handling (Stage 2 verdict + infra-failure, tagger fallback, embedding skip, compression failure).
- `spec/messaging.md` §Required SPI surface, §Capability flags (full list including `supportsMarkdownLinks=false` startup validation), §Message handles, §Progress notifications, §Per-adapter trust level (InMemory only at this milestone), §Failure handling (transient/permanent retry policy).
- `spec/deployment.md` §Topology (single Provider, single adapter at this milestone), §Operator inputs (1, 2, 3, 5, 6, 7), §Bootstrap behavior, §Configuration surface, §Health and observability.
- `spec/verification.md` — every test layer (unit, persistence, integration, smoke) and the spec-level invariants relevant to the wired surfaces.
- D1, D2, D3, D4, D5, D7, D8, D9 (bot admin tier — group admin tier deferred to M3), D10, D11 (intake check), D12, D13 (`saved_post` schema only), D17 (cache mechanism scaffolded; periodic schedule M5), D18 (on-the-fly), D19, D20, D21, D22 (Stage 1 + Stage 2 only — re-eval M2), D23, D24, D25, D27, D29, D30, D31, D32 (InMemory only here), D33 (TTL by partitioning where applicable), D34, D35, D36, D37, D40, D41, D43, D44, D45.

#### Maven modules and packages created or touched

### `infochat-core` (new)
- `org.infochat.core.scope` — `Scope`, `ScopeKind`, `ScopeId` value types (the per-(user, scope) discriminator that backs Invariant 1).
- `org.infochat.core.id` — `PostUid` (UID derivation per `spec/schema.md` §UID derivation), `ContactId`, `AdapterId`.
- `org.infochat.core.audit` — `AuditAction` enum (closed verb set), `AuditTargetKind` enum, `AuditEvent` record. No I/O.
- `org.infochat.core.error` — `E1xxx` / `E2xxx` / `E3xxx` / `E4xxx` code constants per `design/09-reference.md` §9.2.
- `org.infochat.core.locale` — `LocaleBundleKey` enum (one entry per deterministic string the system emits at this milestone).
- `org.infochat.core.command` — `CommandName` enum (the closed Milestone-1 subset), `CommandTier` enum (`PUBLIC`, `BOT_ADMIN`, `GROUP_ADMIN`), `ProbationPolicy` enum (`ALLOWED`, `BLOCKED`).
- `org.infochat.core.llm` — `ModelTask` enum (`SECURITY_JUDGE`, `TAGGER`, `ENTITY`, `SUMMARIZER`, `CHAT_AGENT`, `TRANSLATOR`); embedder is not a `ModelTask` per `spec/llm.md` §SPI shape.
- `org.infochat.core.messaging` — `Capability` enum (the closed v1 set per `spec/messaging.md` §Capability flags), `TrustLevel` enum.

### `infochat-ssrf` (new)
- `org.infochat.ssrf.api` — `SsrfGuard` SPI (sketch below), `SsrfReject`, `SsrfDecision`.
- `org.infochat.ssrf.impl` — internal allowlist/blocklist matcher; DNS re-resolve, redirect cap, body-size cap, timeout caps. No CDI, no Quarkus.

### `infochat-llm-adapter` (new)
- `org.infochat.llm.spi` — `LlmProvider`, `EmbeddingProvider`, `TranslationProvider`, `LlmCallContext`.
- `org.infochat.llm.routing` — `LlmRouter` (resolves `(ModelTask, scope_language)` → `LlmProvider`).
- `org.infochat.llm.impl` — `OpenAiCompatibleLlmProvider` (covers Ollama in M1), `EnglishPassthroughTranslationProvider`, `OllamaEmbeddingProvider`.
- `org.infochat.llm.sanitizer` — `LlmOutputSanitizer` (admin-command match set derived from the closed privileged-tier list).

### `infochat-messaging-adapter` (new)
- `org.infochat.messaging.spi` — `MessagingAdapter`, `MessageHandle`, `InboundMessage`, `OutboundMessage`, `AdapterCapabilities`.
- `org.infochat.messaging.impl.inmemory` — `InMemoryAdapter` (default `trustLevel = LOW`; tests opt into HIGH via builder).
- `org.infochat.messaging.progress` — no-op (Provider owns the notifier).

### `infochat-collector` (new)
- `org.infochat.collector.bootstrap` — `BootstrapLoader` (idempotent on `(kind, identifier)`), bootstrap-tags vocabulary union.
- `org.infochat.collector.fetcher.spi` — `Fetcher` (sketch below).
- `org.infochat.collector.fetcher.rss` — `RssFetcher` impl (only kind in M1).
- `org.infochat.collector.scheduler` — `FetchScheduler` (per-kind, profile-driven interval; pagination cap honored even on RSS where v1 expects 1 page typical).
- `org.infochat.collector.eval` — `OutboxRehydrator`, Stage 1 implementation (HTML sanitizer, NFKC + bidi/zero-width strip, regex set with `java.util.regex` + watchdog, `[REDACTED:<id>]` placeholder), Stage 2 invocation (LLM judge stub-able), Tagger, Embedding worker, READY transition, `NOTIFY new_post`.
- `org.infochat.collector.notify` — `NewPostNotifier` (publishes `new_post` payload per `spec/architecture.md` §Inter-service communication).
- `org.infochat.collector.lock` — `pg_advisory_lock('infochat.collector')` acquisition with heartbeat row.

### `infochat-provider` (new)
- `org.infochat.provider.intake` — `MessageIntake` (steps 1, 1.5, 1.7, 2, 3-N/A-here, 4, 6, 7, 8, 9 from `spec/security.md` §Authorization model).
- `org.infochat.provider.invite` — `InviteCodeService` (D44; CONTACT_BOUND + OPEN_ADAPTER; conditional UPDATE consume), `InviteCodeRateLimiter` (per-`(adapter, contact_id)`).
- `org.infochat.provider.probation` — `ProbationService` (D45 lazy check; passive sweep on next request; allow-list classifier derived from the closed privileged-tier list).
- `org.infochat.provider.command` — `CommandRouter`, parsers for the M1 command set, `ConfirmationService` (in-memory state machine, profile-tunable timeout).
- `org.infochat.provider.audit` — `AuditWriter` (audit-before-effect, INSERT-only; carve-out for zero-row `/forget`).
- `org.infochat.provider.notify` — `NewPostListener` (LISTEN), `NewPostReconciler` (high-water-mark catch-up), `NewPriceSnapshotListener` (registered; no-op in M1 since no asset Fetcher), `QuarantineReviewListener` (registered; no-op in M1 since admin path not wired).
- `org.infochat.provider.progress` — `ProgressNotifier` (D31, localization-bundle strings only, never user-input).
- `org.infochat.provider.summary` — `SummaryCommand` handler (deterministic SQL, cluster computation, `summary_anchor` write, prose generation through summarizer LLM, sanitizer, translation, sanitizer-2, adapter delivery — per `spec/llm.md` §Pipeline order).
- `org.infochat.provider.cancel` — `StopCommand`, `CancellationRegistry`, `pg_cancel_backend(pid)` invocation, per-tool `statement_timeout`.
- `org.infochat.provider.lock` — `pg_advisory_lock('infochat.provider')` acquisition with heartbeat row.
- `org.infochat.provider.locale` — `LocalizationBundle` (bundle for `en` and `cs`; missing `en` key is startup error, missing `cs` key is CI error per D43).

#### SPI seams introduced or extended

```
// infochat-core
package org.infochat.core.scope;
public sealed interface ScopeKind permits ScopeKind.Dm, ScopeKind.Group { …Dm(…) record; …Group(…) record; }
public record Scope(ScopeKind kind, ScopeId id) {}

// infochat-ssrf
package org.infochat.ssrf.api;
public interface SsrfGuard {
  SsrfDecision check(URI url);                          // pre-connect
  SsrfDecision recheckAfterRedirect(URI nextUrl);       // hop-by-hop
  void enforceSocketLevelPeerIp(InetAddress observed);  // StreamSource per-reconnect
}

// infochat-llm-adapter
package org.infochat.llm.spi;
public interface LlmProvider {
  LlmReply chat(LlmRequest request, LlmCallContext ctx);
  <T> T classify(LlmRequest request, Class<T> schema, LlmCallContext ctx);
  Set<Capability> capabilities();
}
public interface EmbeddingProvider {
  List<float[]> embed(List<String> texts, LlmCallContext ctx);
  String modelIdentifier();
  int dimensionality();
}
public interface TranslationProvider {
  TranslationResult translate(String englishText, String targetLanguage, LlmCallContext ctx);
}
public interface LlmRouter {
  LlmProvider forTask(ModelTask task, String scopeLanguage);
}

// infochat-messaging-adapter
package org.infochat.messaging.spi;
public interface MessagingAdapter {
  String name();
  AdapterCapabilities capabilities();
  TrustLevel trustLevel();
  ContactId botContactId();              // derived from per-adapter identity material at startup
  void start(InboundConsumer consumer);
  MessageHandle send(Scope scope, OutboundMessage body);
  void update(MessageHandle handle, OutboundMessage body);
  void finalize(MessageHandle handle, OutboundMessage body);
  void setTyping(Scope scope, boolean on);
  void close();
}

// infochat-collector
package org.infochat.collector.fetcher.spi;
public interface Fetcher {
  SourceKind kind();
  FetchOutputType outputType();           // POST_OUTBOX or PRICE_SNAPSHOT (D39 — POST_OUTBOX in M1)
  FetchResult fetch(SourceRow source, FetchTickContext ctx);  // honors per-tick pagination cap
}

// infochat-provider
package org.infochat.provider.cancel;
public interface CancellationRegistry {
  CancellationToken register(Scope scope, RequestId reqId, int dbBackendPid);
  boolean cancel(Scope scope);            // /stop entry point; idempotent
}
```

#### Schema entities created or modified

Per `spec/schema.md`, with status filter "wired in M1":
- **User** (incl. `(adapter, contact_id)` composite key, `registration_state`, `probation_until`, last-admin protection trigger on UPDATE and DELETE paths with serialization lock).
- **Invite code** (full row shape, `invite_type` discriminator, conditional UPDATE consume, per-adapter PENDING caps).
- **Audit log** + **Audit log view** (redacted view; INSERT-only on the underlying table for both Collector and Provider roles).
- **Source** (`(kind, identifier)` unique key, `status` state machine `active`/`failed`/`disabled`, `deleted_at` orthogonal to status, `bootstrap_tags`).
- **Source subscription** (`(scope, source)`).
- **Tag** (controlled vocabulary; append-only in v1 per `spec/schema.md` §Sources and tags — Vocabulary lifecycle).
- **Post** (status `RAW`/`READY`/`QUARANTINED`/`NEEDS_REVIEW`; per-stage flag bitmap is the durable cursor; `ready_at` indexed for the high-water-mark scan; UID derivation pre-Stage-1).
- **Quarantine** (state machine `PENDING`/`BENIGN_CLOSED`/`APPROVED`/`REJECTED`; raw original column readable only by Admin DB role; placeholder format `[REDACTED:<id>]`).
- **Saved post** (schema present; commands land in M3).
- **Scope preferences** (per-(scope) language, `tag_mode`, subscription versions).
- **Summary anchor** (two partial unique indexes for `command_kind ∈ {personal, digest}`).
- **Chat memory**, **Chat session** (per-(user, scope) isolation; same TTL pruner per Invariant 9).
- **Provider state** (per-channel singleton row for `new_post` channel only in M1, with compound cursor and CAS UPDATE).
- **Stored procedures** `approve_quarantine` / `reject_quarantine` (created; called by Provider role only; admin chat path lands in M2).

#### Acceptance criteria

### A1. Bootstrap and single-instance enforcement
- Both services run Flyway migrations on startup; concurrent boot of both services produces one applied migration set with the loser waiting (`spec/deployment.md` §Topology — Dual-startup race).
- A second Collector or Provider against the same DB exits non-zero with a fatal log line citing the holder's heartbeat host id (`spec/verification.md` §Single-instance lock; `spec/architecture.md` §Deployment topology).
- BootstrapLoader is idempotent on `(kind, identifier)`; running it twice on the same JSON produces no duplicate `source` rows and no churn on `tag` rows (`spec/verification.md` §Bootstrap loader idempotency; `spec/deployment.md` §Bootstrap behavior).
- Bootstrap admin per adapter is optional per adapter; the union must be non-empty or Provider refuses to start (`spec/deployment.md` §Operator inputs item 2).
- Bootstrap-seeded admin row has `registration_state = 'vouched'` and `probation_until = NULL` (`spec/deployment.md` §Bootstrap-seeded admin row shape).

### A2. Invite-code DM gating (D44)
- An unknown DM contact's first message is checked against `invite_code` rows; the conditional UPDATE consume is race-safe; the user-visible reply is the fixed "access requires an invitation" string for any failure (expired, wrong contact, wrong adapter, missing) — no information leak (`spec/security.md` §Invite-code registration; `spec/schema.md` §Identity and access — Invite code).
- `--contact` and `--open` are mutually exclusive at the parser; neither = hint reply with both flags (`spec/commands.md` §Admin — `/invite create`).
- Pre-banned-contact `/invite create --contact <id>` returns the friendly error and creates no invite (`spec/security.md` §Invite-code registration — Pre-banned contact + invite).
- Per-`(adapter, contact_id)` brute-force rate limit fires at the configured threshold (the threshold value is profile-driven; the test asserts the boundary, not the value) (`spec/security.md` §Invite-code registration — Brute-force rate limit; `spec/verification.md` §Invite-code lifecycle).
- Pre-normalization of the invite-code consume body (NFKC + bidi/zero-width/whitespace strip per step 1.7) — a code surrounded by zero-width chars is consumed correctly; a homoglyph-substituted code does **not** match (`spec/verification.md` §Onboarding modes — Pre-normalization).

### A3. Slow-start tier (D45)
- Newly registered user (via invite or via group @mention path which is structurally supported even though no group adapter is wired) gets `probation_until = NOW() + profile-driven horizon`.
- Permission check is `probation_until IS NULL OR probation_until < NOW()` (lazy check, no background sweep needed) (`spec/security.md` §Slow-start tier).
- Allowed during probation: `/help`, `/status`, `/get-tags`, `/get-sources`, `/list-sources`, `/summary`, `/saved`, `/lang`, `/forget`, `/stop`. Blocked: every other write/chat command (`spec/security.md` §Slow-start tier).
- `/help` filtering during probation: returns exactly the slow-start allowed subset, with the probation note attached (`spec/verification.md` §Slow-start tier — `/help` filtering during probation).
- A passive sweep clears `probation_until` on the next request from a promoted user (`spec/security.md` §Slow-start tier — lazy promotion).

### A4. Authorization step ordering (full)
- Steps 1, 1.5, 1.7, 2, 4, 6, 7, 8, 9 from `spec/security.md` §Authorization model fire in order.
- Step 1.5 transport-level rate cap: over-cap inbound is dropped silently for the rest of the cap window (no fixed reply, no audit) (`spec/security.md` §Authorization model — step 1.5).
- Step 1.7 normalization: the normalized body replaces the raw body for all downstream processing — verified by a fixture asserting the LLM never receives the raw bytes for a body containing zero-width chars (`spec/security.md` §Authorization model — step 1.7).

### A5. Ingest pipeline (Stage 1 + Stage 2)
- Stage 1 runs on every post; HTML sanitizer + NFKC + bidi/zero-width strip + `java.util.regex` regex set + per-input watchdog (`spec/security.md` §Ingest pipeline — Stage 1; `spec/verification.md` §Stage 1 ReDoS guard).
- Watchdog timeout = Stage 1 infrastructure failure → fail-closed `QUARANTINED` (`spec/security.md` §Failure handling — Stage 1 infrastructure failure).
- Stage 2 invoked only on Stage 1 hits; a fake LLM scripted to return each of `BENIGN`, `INJECTION`, `MALWARE`, `UNKNOWN` produces the correct post status (`spec/verification.md` §Stage 2 verdict path).
- Stage 2 infra failure (after 1 retry) → release as `READY` with redactions retained, `stage2_failed = true`, throttled admin notify (`spec/security.md` §Failure handling — Stage 2 infrastructure failure).
- Tagger fallback to `source.bootstrap_tags` on retry-exhaustion or zero-valid output (`spec/llm.md` §Failure handling — Tagger).
- Embedding failure → release without vector; semantic-similarity queries filter `WHERE embedding IS NOT NULL` (`spec/schema.md` §Posts and derivatives — Post embedding).
- UID derivation runs **before** Stage 1 against the raw body (`spec/schema.md` §UID derivation).
- Outbox + rehydrator: killing the Collector mid-eval and restarting re-enqueues anything left `RAW` (`spec/verification.md` §Architecture — Outbox rehydrator).

### A6. NOTIFY catch-up (`new_post` channel only in M1)
- Provider's `new_post` cursor is the per-channel `provider_state` row with compound cursor `(ready_at, post_id)`; CAS UPDATE prevents a slow processor rolling back a fast one's mark (`spec/schema.md` §Operational — Provider state; `spec/architecture.md` §Inter-service communication — Catch-up).
- A Provider that was down when `NOTIFY new_post` fired processes the post on next startup via the high-water mark (`spec/verification.md` §LISTEN/NOTIFY catch-up).
- First-boot insert race: two fresh Provider instances both attempt `INSERT … ON CONFLICT (channel) DO NOTHING` — exactly one row is produced (`spec/verification.md` §Provider state first-boot concurrency).

### A7. Capability flag invariants
- `supportsMarkdownLinks = true` from any registered adapter at startup → Provider fails fast with a fatal log line identifying the adapter (`spec/messaging.md` §Capability flags; `spec/SPEC.md` §4 v1 scope — `supportsMarkdownLinks` MUST be false in v1).
- `supportsCodeFormatting` is honored (the InMemory adapter is configurable in tests to exercise both true/false).
- `supportsMentionByContactId = false` on a group-mode adapter → group SPI disabled. (Group mode is not wired in M1 but the validator runs.)
- `trustLevel = LOW` adapter (the InMemory default) requires `infochat.adapter.allow-low-trust=true` operator opt-in or Provider refuses to start (`spec/messaging.md` §Per-adapter trust level — InMemory).

### A8. ProgressNotifier wired
- Stage events `STARTED`, `RETRIEVING`, `GENERATING`, `TRANSLATING`, `FINALIZING` (per D31) are emitted by `/summary` (`spec/messaging.md` §Progress notifications).
- Stage strings are looked up by enum from the localization bundle; user input is **never** interpolated (`spec/messaging.md` §Progress notifications — Constraints; `spec/verification.md` §Messaging — Progress notifier never interpolates user input).
- Adapter without `supportsMessageEdit` collapses to a single final `send` (the InMemory adapter exercises both modes via test config).
- Try/finally guarantees finalize on exception (`spec/verification.md` §Messaging — Placeholder always finalized).

### A9. `summary_anchor` written
- Every user-issued `/summary` writes a `summary_anchor` row keyed `(user_id, scope_id, command_kind = 'personal')`; cluster mapping and frozen UID set are stored (`spec/schema.md` §Per-scope state — Summary anchor; `spec/commands.md` §Conversation control — `/retry`).
- `/retry` is implementable from M1 (the handler may be deferred to M2 if scope is tight, but the schema and the anchor write are present).

### A10. `/stop` implementable from day one
- `CancellationRegistry` registers an in-flight LLM call's DB backend pid; `/stop` invokes `pg_cancel_backend(pid)`; per-tool `statement_timeout` is set on every chat-mode read-only tool query (`spec/commands.md` §Conversation control — `/stop`; D35).
- `/stop` against nothing in flight returns the friendly idempotent reply.
- `/stop` cancels any pending confirmation (`spec/commands.md` §Surface conventions — Confirmation).

### A11. Audit log breadth (full, not narrow)
- Every privileged action writes audit-before-effect; the carve-out is exactly zero-row `/forget` (Invariant 7 carve-out per `spec/schema.md`).
- Audit log is append-only (Invariant 10): no UPDATE/DELETE path in either service; DB role grants withhold UPDATE/DELETE from Collector and Provider.
- `audit_log_view` redacts `actor_contact_id`, `target_contact_id`, and `details_json` per the secrets catalogue; Provider role has SELECT only on the view (`spec/schema.md` §Operational — Audit log view; `spec/security.md` §DB roles).

### A12. Scope-discriminator design exercised
- Every user-state row carries a scope discriminator; queries filter on it; `saved_post` is the documented exception with no scope discriminator (`spec/schema.md` §Invariants 1).
- A 100-user fuzz of saves and chat-memory rows never leaks across scopes; saves made in DM are visible from any group of the same user; never to another user (`spec/verification.md` §Per-(user, scope) isolation).
- The cross-scope `chat_memory` isolation invariant is verified end-to-end (`spec/verification.md` §Per-(user, scope) isolation — Cross-scope chat memory isolation).

### A13. TranslationProvider SPI
- `TranslationProvider` SPI is implemented; `EnglishPassthroughTranslationProvider` is the default; `cs` requires a working translator (a deterministic test stub is acceptable in M1).
- Source post bodies are **never** translated; spy assertion verifies no `post.body` value reaches the provider (`spec/verification.md` §LLM and embeddings — Source bodies are never translated).
- Pipeline order per `spec/llm.md` §Pipeline order: LLM prose → LLM output sanitizer #1 → translation (skip if `en`) → sanitizer #2 (re-run on translated text) → translation cache write keyed by `(hash(post-sanitizer-1 English), target_language)` → adapter delivery.
- Localization bundle: `en` is the registry; missing `en` key is startup error; missing `cs` key fails CI (`spec/llm.md` §Translation flow — Deterministic strings; D43; `spec/verification.md` §Localization bundle completeness).

### A14. LLM output sanitizer
- Sanitizer match-set is **derived** from the closed bot-admin and group-admin command tier list at spec level; CI fails on mismatch (`spec/security.md` §LLM output sanitizer — Match-set derivation).
- Multi-match replies are refused entirely; single-match replies are stripped + audit-logged (`spec/security.md` §LLM output sanitizer; `spec/verification.md` §Security — Chat output sanitizer).

### A15. Tool surface (closed allowlist)
- The agent's tool registry contains exactly `searchPosts`, `getPost`, `getReferences`, `recallMemory`, `listSaves` (`spec/security.md` §Prompt-injection defenses; `spec/verification.md` §Security — Tool surface).
- CI fails on any mismatch in either direction.
- Each tool input has length cap; SQL is scope-filtered; never returns another user's data.

### A16. SSRF (`infochat-ssrf` shared module)
- Every outbound feed/redirect/StreamSource connect runs through `SsrfGuard.check`; every redirect re-resolves DNS; a redirect to `169.254.169.254` mid-fetch is blocked (`spec/security.md` §SSRF and outbound connections; `spec/verification.md` §Security — SSRF).
- `/add-source` URL probe uses the same `SsrfGuard` (`spec/commands.md` §Source management — URL validation).

### A17. DB roles (D34)
- Three Postgres roles created at migration time: `infochat_collector`, `infochat_provider`, `infochat_admin`.
- Provider role: SELECT on `audit_log_view` (not on `audit_log` directly), INSERT-only on `audit_log`, EXECUTE on `approve_quarantine` and `reject_quarantine`, no SELECT on raw quarantine column, SELECT-only on `price_snapshot` and `asset_config` (these tables exist but are not populated until M5/M7).
- Collector role: INSERT/UPDATE on ingest-owned tables; INSERT-only on `audit_log`; LISTEN/NOTIFY.
- DELETE on `source` is REVOKED from both Collector and Provider (Invariant 4 enforcement).
- A SQL-injection mutation attempt from the Provider role fails; the Admin role can do it (`spec/verification.md` §Security — DB roles).

### A18. Compose / smoke surface
- A `docker-compose up` brings up Postgres+pgvector, Ollama (or a fake LLM container), Collector, Provider, and exercises the in-memory adapter through a deterministic transcript (`spec/verification.md` §End-to-end smoke; `spec/deployment.md` §Local development).
- The eight original MVP exit criteria from `design/00-mvp.md` §6 all pass on a clean checkout, **with these v1 reinforcements**:
  - Step 3 ("first DM, auto-registration") is replaced by "first DM with valid invite code → registered into probation; probation reply on attempted write commands."
  - The deferred-list items in `design/00-mvp.md` §5 that are **not** deferred in v1 (specifically: invite-gate, slow-start, `summary_anchor`, `chat_memory`, `chat_session`, `/forget`, `/stop`, `/lang`, audit-log breadth) all light up.

#### Given/When/Then scenarios (each cites spec/decision)

### Onboarding — happy path

- **G** the deployment has one bot admin bootstrapped on the InMemory adapter, and an admin issues `/invite create --adapter inmemory --contact alice`
- **W** the admin reply contains a single PENDING invite UUID (`spec/commands.md` §Admin — `/invite create`)
- **W** Alice (unknown contact) sends the UUID as her first DM
- **T** a `users` row is created with `registration_state = 'invited'` and `probation_until = NOW() + profile horizon`; the invite row transitions to `USED` via the conditional UPDATE consume; the welcome message is sent (`spec/security.md` §Invite-code registration; `spec/schema.md` §Identity and access — Invite code).
- **T** an audit row is written before the user-row INSERT with `action = 'INVITE_ACCEPTED'`, `actor_user_id = NULL`, `details_json.cause = 'invite_consume'` (`spec/schema.md` §Invariants — Audit-before-effect).

### Onboarding — banned (intake-blocked)

- **G** Alice exists with `is_banned = true`
- **W** Alice sends any inbound message (slash command or chat-mode)
- **T** the ban check fires after step 1.5 normalization succeeds; Alice receives the fixed "Your access has been revoked" reply (localization-bundle string); no parser, no DB query past the ban check, no LLM call (`spec/security.md` §User ban; D11; `spec/verification.md` §Banned-user intake).
- **T** if Alice exceeds the transport-level rate cap, she receives **no** reply (including no fixed ban reply) for the rest of the cap window (`spec/security.md` §Authorization model — step 1.5).

### Onboarding — no-invite DM (D44)

- **G** Bob has no `users` row and no PENDING invite for `(inmemory, bob)`
- **W** Bob sends "hello" or "/help" or any other input
- **T** Bob receives the fixed "access requires an invitation" reply; **no `users` row is created**; the drop counter increments; no audit row beyond the rate-counter (`spec/security.md` §Invite-code registration).

### Slow-start probation user (D45)

- **G** Alice is registered with `probation_until = NOW() + 24h`
- **W** Alice runs `/help`
- **T** Alice receives only the slow-start allowed subset listing, with the probation footer (`spec/commands.md` §Discovery — `/help`; `spec/verification.md` §Slow-start tier — `/help` filtering).
- **W** Alice runs `/add-source --type rss --url https://example.com/feed --tags news`
- **T** Alice receives the localized "account is in the probation period, access broadens in N hours" friendly error; no `source` row, no `source_subscription` row, no audit row beyond the probation-rejection (`spec/security.md` §Slow-start tier).
- **W** Alice runs `/forget` (allowed) on an empty scope
- **T** Alice receives the friendly no-op reply; **no audit row** is written (Invariant 7 carve-out, `spec/schema.md`).
- **W** Alice runs `/lang cs` (allowed)
- **T** `scope_preferences.language = 'cs'`; audit row written before the UPDATE.

### `/summary` on the happy path

- **G** Alice is past probation, has subscribed to one RSS source, and 3 `READY` posts exist matching her scope
- **W** Alice runs `/summary -w 24h`
- **T** the deterministic SQL returns exactly those 3 posts in deterministic order (D19), the cluster set is computed by SQL traversal of `post_reference` (empty in M1 since linking is M2), the `summary_anchor` row is written for `(alice, alice_dm, 'personal')` with `frozen_uids = [3 UIDs]` (`spec/schema.md` §Per-scope state — Summary anchor).
- **T** the LLM call is wrapped in `<<<UNTRUSTED:{uuid}>>>…<<<END:{uuid}>>>` with a per-call random uuid; sanitizer #1 strips any admin command shape; (translation skipped because `en`); sanitizer #2 is a no-op on `en`; the reply is delivered via the InMemory adapter (`spec/llm.md` §Pipeline order; `spec/security.md` §LLM output sanitizer).
- **T** the ProgressNotifier emits `STARTED → RETRIEVING → GENERATING → FINALIZING` (no `TRANSLATING` for `en`).

### `/summary` — same input → same cluster set across runs (determinism boundary)

- **G** the DB state is unchanged between two runs
- **W** Alice runs `/summary -w 24h` twice in a row
- **T** the post-id list is byte-identical across both runs; the prose differs (LLM is non-deterministic) (`spec/llm.md` §Determinism boundary; `spec/verification.md` §LLM and embeddings — Determinism).

### `/summary` — Stage 2 down → degraded fallback

- **G** the security judge LLM is unreachable; one post is in the eligible window with `stage1_flagged = true`
- **W** the post enters the eval pipeline
- **T** Stage 2 retries once, fails, the post is released `READY` with redactions retained and `stage2_failed = true`; the throttled admin notification fires per `(channel = 'eval', error_class = 'stage2_infra')` (`spec/security.md` §Failure handling — Stage 2 infrastructure failure).
- **W** Alice runs `/summary -w 24h` while the summarizer LLM is also down
- **T** `/summary` falls back to headlines + URLs + post UIDs (no prose); the fallback notice is a localization-bundle string; the deterministic post selection is unaffected (`spec/commands.md` §Content — `/summary`).
- **W** the LLM recovers; Alice runs `/retry`
- **T** `/retry` regenerates the prose using the original frozen UID set from `summary_anchor`; only the prose layer is re-rolled (D36).

### Tagger retry exhausted → bootstrap-tag fallback

- **G** the tagger LLM returns an unparseable reply twice for one post
- **W** the eval pipeline processes that post
- **T** the post inherits `source.bootstrap_tags`; `post.tagger_fallback = true`; throttled admin notify per `(channel = 'eval', error_class = 'tagger_fallback')` (`spec/llm.md` §Failure handling — Tagger; `spec/security.md` §Failure handling — Tagger).

### Embedding failure → release without vector

- **G** the embedding provider throws on a batch of 1 post (the same batch retries once and also fails per `spec/llm.md` §Embedding pipeline — One-failure-fails-batch retry)
- **W** the eval pipeline processes that post
- **T** the post is released `READY`, `post_embedding` row is absent; semantic-similarity queries filter `WHERE embedding IS NOT NULL` and exclude this post; deterministic queries (`/summary`) still return it (`spec/schema.md` §Posts and derivatives — Post embedding).

### ReDoS watchdog trip → fail-closed quarantine

- **G** an adversarial input is crafted to backtrack catastrophically against one of the Stage 1 patterns
- **W** the post hits Stage 1
- **T** the watchdog fires within the per-input wall-clock cap; the post is immediately `QUARANTINED` with `rule_id = 'regex_timeout'`; throttled admin notify; never auto-released (`spec/security.md` §Ingest pipeline — Stage 1 regex engine commitment; §Failure handling — Stage 1 infrastructure failure).

### NOTIFY missed during Provider downtime

- **G** the Provider is down when `NOTIFY new_post` fires for one post
- **W** the Provider restarts
- **T** `NewPostReconciler` reads the `new_post` `provider_state` row and runs the catch-up query `WHERE (ready_at, post_id) > (cursor_high, cursor_low_id) ORDER BY ready_at, post_id`; the missed post is processed; the cursor advances in the same DB transaction as the side effect (`spec/architecture.md` §Inter-service communication — Catch-up; `spec/verification.md` §LISTEN/NOTIFY catch-up).

### `/stop` cancels mid-stream chat reply

- **G** Alice is in chat mode and the agent is mid-stream against the chat LLM, with one in-flight `searchPosts` tool call
- **W** Alice issues `/stop`
- **T** the LLM stream is closed; `pg_cancel_backend(pid)` is invoked on the released connection; the worker is freed within the cancellation window; the progress notifier renders a final "stopped" state on the in-place message; per-(user, scope) isolation — another user's in-flight request in the same group is unaffected (`spec/commands.md` §Conversation control — `/stop`; D35; `spec/verification.md` §`/stop` cancellation).
- **T** if outbound delivery has already begun, the message is **not** unsent (`spec/commands.md` §Conversation control — `/stop`).

### `/forget` on the calling scope

- **G** Alice has 5 `chat_memory` rows, 3 `chat_session` rows, 1 `summary_anchor` row in DM scope, plus 12 `saved_post` rows globally
- **W** Alice runs `/forget` (and `/forget confirm`)
- **T** the audit row is written **before** the purge with counts: `chat_memory_count = 5`, `chat_session_count = 3`, `summary_anchor_count = 1`, `saved_post_count = 12`; no UID lists (`spec/commands.md` §Conversation control — `/forget`).
- **T** all 5 + 3 + 1 + 12 rows are deleted; `users.is_admin`, `users.is_banned`, `group_membership`, `audit_log` rows are untouched (`spec/verification.md` §`/forget` purge).
- **W** Alice runs `/forget` again (idempotent)
- **T** friendly no-op reply; **no audit row** (Invariant 7 carve-out).

### Capability flag startup invariant

- **G** an InMemory adapter test fixture declares `supportsMarkdownLinks = true`
- **W** Provider starts
- **T** Provider exits non-zero with a fatal log line identifying the offending adapter (`spec/messaging.md` §Capability flags; A7 above).

### Audit-before-effect interruption proof

- **G** an admin issues `/grant-admin <contact>`; a fault is injected between the audit-write and the side-effect
- **W** the transaction is interrupted (the side effect does not commit)
- **T** an `audit_log` row records the intent (`action = 'GRANT_ADMIN'`); no `users.is_admin` mutation occurred (`spec/schema.md` §Invariants — 7; `spec/verification.md` §Schema — Audit-before-effect). (Note: in M1 only the bootstrap admin path exercises grant-admin's audit row; the chat command lands in M2.)

### Scope-discriminator structural support (group scope present even without a group adapter)

- **G** a fixture seeds a single group row + a group_membership row + a `chat_memory` row keyed by `(user, group)`
- **W** Alice (the same user, in DM) calls the chat agent and triggers `recallMemory(['kw'])`
- **T** the recall **does not** return the group-keyed row; the cross-scope isolation invariant holds even though no group adapter is wired (`spec/schema.md` §Per-scope state — Chat memory; `spec/verification.md` §Per-(user, scope) isolation — Cross-scope chat memory isolation).

#### Blocking dependencies on earlier milestones
- Milestone 0 (every drift fix that this milestone references must already be in the design notes; otherwise developers will diverge from spec).

#### Test surface
- **Unit:** Stage 1 regex catalogue (positive/negative corpora), confirmation token state machine, command parser, fuzzy-suggestion ranking, LLM output sanitizer, slow-start permission classifier, scope key construction, UID derivation, invite-code conditional UPDATE consume, capability-flag startup validator, ProgressNotifier event coalescing.
- **Persistence (Testcontainers Postgres+pgvector):** last-admin protection trigger (UPDATE + DELETE paths, with concurrent-revoke serialization), invite-code race-safe consume, soft-delete-only-for-sources invariant, `audit_log_view` redaction, `provider_state` per-channel singleton with first-boot concurrency, `chat_memory`/`chat_session`/`summary_anchor` TTL pruner, `summary_anchor` two partial unique indexes (personal vs digest).
- **Integration:** running Collector + Provider against InMemoryAdapter + scriptable fake LLM; full ingest → eval → notify → command path; the 100-user cross-scope fuzz; the Stage 2 verdict + infra-failure path; `/stop` mid-stream cancellation; ProgressNotifier under both `supportsMessageEdit` true/false.
- **End-to-end smoke:** the eight MVP exit criteria reinforced with v1 invariants (per A18) on `docker-compose up`.

#### Decision dependency
- **Persistence layer choice** (Hibernate ORM + Panache vs raw JDBC + Quarkus Reactive SQL Client in blocking mode vs jOOQ): gates how the schema-access layer is shaped in `infochat-core`. The acceptance criteria above are written in capability terms (per-channel CAS UPDATE, `RETURNING` row count for the `/forget` no-op carve-out, conditional UPDATE consume for the invite race) so they admit any of the three choices.
- **Migration tool** (Flyway vs Liquibase): `spec/deployment.md` §Topology and §Bootstrap behavior name Flyway by name; treat as the soft default, but flag.
- **Test infra** (Quarkus Dev Services + Testcontainers stance): gates how the persistence test layer is wired.
- **Container base image / supply-chain**: gates the `docker-compose.yml`.
- **Secrets handling** (env vars only per `spec/security.md` §Secrets handling; specific mechanism is open): gates DB / LLM key plumbing.
- **Observability baseline** (structured JSON, Micrometer, OpenTelemetry): gates metrics, trace ids, log structure.

---

### Milestone 2 — Full eval pipeline + LISTEN/NOTIFY breadth

#### Goal
Light up the rest of the eval pipeline (entity extraction, cross-source linking via hybrid named-entity + embedding match, re-evaluation job with separate caps for infra-failure and UNKNOWN classes, throttled admin notifier, NEEDS_REVIEW transition, per-source UNKNOWN auto-disable) and the remaining NOTIFY channels (`quarantine_review` with the tagged-payload contract).

#### Spec sections / decision IDs implemented
- `spec/security.md` §Re-evaluation job (full); §Failure handling (Stage 2 infra-failure → re-eval; UNKNOWN → re-eval; Admin notifications throttled).
- `spec/architecture.md` §Inter-service communication — `quarantine_review` channel (full tagged-payload).
- `spec/llm.md` §Failure handling — Tagger partial-valid; Entity; Embedding model identity guard; Local-only conflict.
- `spec/schema.md` §Posts and derivatives — `NEEDS_REVIEW` post status; Quarantine state machine including `BENIGN_CLOSED`; Invariant 6 admin-review TTL auto-reject.
- `spec/commands.md` §Admin — `/quarantine list [--all]`, `/quarantine approve`, `/quarantine reject`, `/audit`.
- D6, D9 (bot admin command tier — group admin lands in M3), D22 (full), D34 (Provider EXECUTE on stored procedures exercised), D38 (cross-source linking by `upstream_identifier` for kind-6 prep).

#### Maven modules and packages created or touched

### `infochat-core`
- Extend `org.infochat.core.audit` with `RE_EVAL_RELEASED`, `UNBAN_DELETED_PREBAN_ROW`, `INVITE_REVOKED`, `BAN`, `UNBAN`, `GRANT_ADMIN`, `REVOKE_ADMIN`, `PROMOTE_GROUP_ADMIN`, `DEMOTE_GROUP_ADMIN`, `VOUCH`, `QUARANTINE_APPROVE`, `QUARANTINE_REJECT`, `REMOVE_SOURCE`, `SOURCE_ENABLE`, `SOURCE_DISABLE`, `DIGEST_SLOT_MISSED`, `BOOTSTRAP_ADMIN`.

### `infochat-collector`
- `org.infochat.collector.eval.entity` — Entity extractor.
- `org.infochat.collector.linking` — `LinkingJob` (driving set: posts where `last_linked_at IS NULL OR last_linked_at < fetched_at` per `design/01-architecture.md` §1.3; named-entity match + cosine similarity over pgvector; bidirectional INSERTs into `post_reference`).
- `org.infochat.collector.eval.reeval` — `ReEvaluationJob` (separate caps for infra-failure class and UNKNOWN class; per-post attempt counter; `RE_EVAL_RELEASED` audit + throttled admin notify).
- `org.infochat.collector.eval.unknown` — Per-source UNKNOWN rate tracker; auto-disable via `source.status = 'failed'`.
- `org.infochat.collector.notify.quarantine` — emits `quarantine_review` NOTIFY with tagged payload.
- `org.infochat.collector.notify.admin` — `ThrottledAdminNotifier` coalesces by `(channel, error_class)` over a profile-driven window.

### `infochat-provider`
- `org.infochat.provider.notify.quarantine` — `QuarantineReviewListener` consumes the tagged payload; routes `('quarantine', id, 'PENDING')` and `('post', id, 'NEEDS_REVIEW')` to the throttled admin notifier; advances the cursor for non-action transitions.
- `org.infochat.provider.command.admin` — `/quarantine list [--all]`, `/quarantine approve`, `/quarantine reject` (the latter two call the stored procedures via Provider EXECUTE), `/audit` (reads `audit_log_view`).
- `org.infochat.provider.command.admin.user` — `/ban`, `/unban` (incl. the `preban` row deletion carve-out and the group-admin restoration disclosure), `/grant-admin`, `/revoke-admin`, `/vouch`, `/invite list`, `/invite revoke`.

#### SPI seams introduced or extended
- No new SPIs.
- Extend `Fetcher`: still pre-existing; nothing changes.
- Extend the audit-action enum in `infochat-core`.

#### Schema entities created or modified
- Add `NEEDS_REVIEW` to the `post.status` enum.
- Add `BENIGN_CLOSED` to the `quarantine.status` enum.
- Add `provider_state` row for the `quarantine_review` channel (compound cursor `(reviewed_at, target_kind, target_id)`).
- `post_entity`, `post_embedding`, `post_reference` partition tables wired (per `design/02-schema.md` §2.4); pgvector index per profile (HNSW for laptop/vps/remote-llm; IVFFlat for pi).
- Admin-notification-state table (backing the throttled notifier per `spec/schema.md` §Operational — Admin notification state).

#### Acceptance criteria

### A1. Re-evaluation job
- A post with `stage2_failed = true` is re-submitted to Stage 2 on the configured cadence; verdict `BENIGN` keeps Stage 1 redactions in place (parity with first-pass per `spec/security.md` §Quarantine workflow), clears `stage2_failed`, fires `RE_EVAL_RELEASED` audit + throttled admin notification (`spec/security.md` §Re-evaluation job; `spec/verification.md` §Re-evaluation job).
- An UNKNOWN-verdict post is re-submitted with the **lower** attempt cap; cap exhaustion transitions the post to `NEEDS_REVIEW` and fires the throttled admin notification (`spec/security.md` §Re-evaluation job; `spec/verification.md` §UNKNOWN re-eval).
- Stage-2 infra-failure → NEEDS_REVIEW exhaustion path also exercised (`spec/verification.md` §Stage-2 infra-failure → NEEDS_REVIEW exhaustion).

### A2. Per-source UNKNOWN auto-disable
- A source whose Stage 2 UNKNOWN rate exceeds the profile-driven threshold over the rolling window has `source.status` flipped to `failed`; throttled admin notification cites source id + observed rate + threshold; in-flight posts continue through their current evaluation stage (`spec/security.md` §Re-evaluation job — Per-source UNKNOWN auto-disable).

### A3. Absolute NEEDS_REVIEW depth alert
- When the `NEEDS_REVIEW` queue exceeds the profile-driven threshold, the operator alert fires independent of any per-source ratio (`spec/security.md` §Re-evaluation job — Absolute NEEDS_REVIEW depth alert).

### A4. `quarantine_review` channel
- Provider's listener consumes the tagged payload `(target_kind, target_id, new_status)`; it drives the throttled admin notifier on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions; it advances the cursor for `BENIGN_CLOSED`, `APPROVED`, `REJECTED` transitions without user-visible effect (`spec/architecture.md` §Inter-service communication; `spec/schema.md` §Operational — Provider state).
- The cursor is the compound `(reviewed_at, target_kind, target_id)`; CAS UPDATE protects against backwards moves.

### A5. Admin quarantine commands
- `/quarantine list` defaults to `PENDING` rows; `--all` (bot-admin only) includes every status (`spec/commands.md` §Admin — `/quarantine list`).
- `/quarantine approve <id>` calls `approve_quarantine(id, actor_id)` stored procedure under Provider EXECUTE; restores the original span, transitions `PENDING → APPROVED` (or `BENIGN_CLOSED → APPROVED`), fires `NOTIFY new_post` for the post; the Provider re-renders via the standard high-water-mark path (`spec/security.md` §Quarantine workflow).
- `/quarantine reject` similarly transitions to `REJECTED`.

### A6. `/audit` reads the redacted view
- `/audit` reads through `audit_log_view`; redacted columns surface as masked; bot admin sees deployment-wide audit history (`spec/commands.md` §Admin — `/audit`; `spec/security.md` §DB roles).

### A7. Cross-source linking (D6)
- `LinkingJob` runs on the configured cadence; for each driving post within the candidate window, finds candidates by shared `post_entity` (precision) and cosine-similarity over `post_embedding` (recall); bidirectional INSERTs cap at N per post (highest score wins).
- `last_linked_at` cursor advances on success.
- Posts without an embedding are skipped from the semantic-similarity step.

### A8. Tagger partial-valid handling
- A fake LLM emits a tag list of three valid + one out-of-vocab entries; the post is tagged with the three valid entries; bootstrap-tags fallback does **not** fire; the per-post counter records "3 valid + 1 invalid" (`spec/llm.md` §Failure handling — Tagger partial-valid handling; `spec/verification.md` §Tagger partial-valid output).

### A9. Embedding model identity guard
- On startup the `EmbeddingProvider` reports `modelIdentifier()` and `dimensionality()`; mismatch with the stored singleton row refuses startup with a descriptive error referencing the re-embed procedure (`spec/llm.md` §Embedding pipeline — Model identity guard).

### A10. Local-only routing conflict
- Startup with the local-only property + a per-task override pointing at a remote provider fails fast with a fatal log line identifying the task and the provider (`spec/llm.md` §Per-task routing rules — Local-only).

### A11. Admin command surface (per `spec/commands.md` §Admin)
- `/ban`, `/unban` (incl. preban-row deletion + group-admin restoration disclosure), `/grant-admin`, `/revoke-admin` (last-admin protection, global across adapters), `/vouch` (two effects in one transaction: `probation_until = NULL` + advance `registration_state` from `group_only` to `vouched`), `/invite list`, `/invite revoke` (requires confirm).
- `/promote`/`/demote` defer to M3 (group adapter not wired yet; the schema-level constraints already exist via M1).

#### Given/When/Then scenarios

### Stage 2 infra-failure recovery
- **G** the security judge LLM is offline; one Stage-1-flagged post enters the eval pipeline
- **W** Stage 2 retries once and fails
- **T** the post is released `READY` with redactions retained, `stage2_failed = true`; a throttled admin notification is queued under `(channel = 'eval', error_class = 'stage2_infra')` and coalesced with any other infra failures within the window (`spec/security.md` §Failure handling).
- **W** the LLM recovers; `ReEvaluationJob` runs
- **T** the post is re-submitted; verdict `BENIGN` clears `stage2_failed`, transitions the quarantine row `PENDING → BENIGN_CLOSED`; Stage 1 redactions remain in the body; `RE_EVAL_RELEASED` audit row is written; throttled admin notification fires (`spec/security.md` §Re-evaluation job — `BENIGN` on a Stage-2-infra-failure post).

### UNKNOWN re-eval cap exhaustion
- **G** a post is `QUARANTINED` with verdict UNKNOWN; the lower UNKNOWN cap is N
- **W** N consecutive re-eval attempts also produce UNKNOWN
- **T** the post transitions `QUARANTINED → NEEDS_REVIEW`; the `quarantine_review` channel fires `('post', post_id, 'NEEDS_REVIEW')`; the Provider's listener drives the throttled admin notifier (coalesced per `(channel, error_class)`).

### `/quarantine approve` lifts redactions
- **G** an admin reviews a `PENDING` quarantine row attached to a `READY` post that has Stage 1 redactions in the body
- **W** the admin runs `/quarantine approve <id>`
- **T** the stored procedure restores the original span, transitions the quarantine row `PENDING → APPROVED`, audits the action, fires `NOTIFY new_post` with the post's `(ready_at, post_id)` cursor (`spec/security.md` §Quarantine workflow).
- **T** Provider's `new_post` listener picks up the cursor and re-renders the unredacted body via the standard high-water-mark path.

### Admin-review TTL auto-reject
- **G** a `PENDING` quarantine row has aged past the admin-review TTL; it is attached to a `NEEDS_REVIEW` post
- **W** the TTL job fires
- **T** the quarantine row transitions `PENDING → REJECTED`; the post transitions `NEEDS_REVIEW → QUARANTINED`; the placeholder body becomes permanent; **no admin notification is sent** (the throttled notifier already paged when the post entered `NEEDS_REVIEW`) (`spec/schema.md` §Invariants — 6; `spec/verification.md` §Admin-review TTL auto-reject).
- **G** a `BENIGN_CLOSED` row has also aged past the TTL
- **T** the row stays `BENIGN_CLOSED`; no transition fires (`spec/verification.md` §Admin-review TTL auto-reject).

### Last-admin protection under concurrent revoke
- **G** exactly two bot admins exist
- **W** both `/revoke-admin` calls fire concurrently against different rows
- **T** the trigger's serialization (SHARE ROW EXCLUSIVE lock or SELECT FOR UPDATE) ensures exactly one revoke succeeds; the other fails with the last-admin protection error; the deployment never reaches zero admins (`spec/schema.md` §Invariants — 2).

### `/unban` of a `preban` row → row deletion
- **G** Bob's `users` row has `is_banned = true` and `registration_state = 'preban'`
- **W** an admin runs `/unban bob`
- **T** the row is `DELETE`d (the single permitted application-issued DELETE on `users` per Invariant 2 carve-out); the audit row is `UNBAN_DELETED_PREBAN_ROW`; the reply discloses the deletion and that a fresh invite is required for DM (`spec/security.md` §User ban — Pre-ban → unban does NOT grant DM access; `spec/schema.md` §Invariants — 2).

#### Blocking dependencies on earlier milestones
- M1 (Stage 1 + Stage 2 single-pass, audit log, Provider EXECUTE on stored procedures, `new_post` channel cursor mechanism).

#### Test surface
- Unit: re-eval cap classification (infra vs UNKNOWN), throttled-notifier coalescing window, admin-action enum closed-set check.
- Persistence: `quarantine_review` cursor CAS, NEEDS_REVIEW transition trigger, partition-drop pruner for `post_entity`/`post_embedding`/`post_reference`, last-admin protection serialization under concurrent revokes.
- Integration: full re-eval cycle (BENIGN, UNKNOWN, MALWARE on re-eval), per-source UNKNOWN auto-disable, `/quarantine approve` end-to-end.
- E2E: extends the M1 smoke transcript with an admin reviewing a quarantined fixture.

#### Decision dependency
- Persistence layer choice still open.
- Observability baseline gates the throttled-notifier metric shape.

---

### Milestone 3 — Group support + per-group admin + remaining commands

#### Goal
Wire group scope end-to-end: per-group admin tier (D9), the group SPI on the InMemory adapter (and a non-trivial fake adapter that emits `@mention` payloads + `user_joined_group` / `user_left_group` events), the remaining commands (`/save`, `/saved`, `/unsave`, `/follow-tag`, `/unfollow-tag`, `/promote`, `/demote`, `/list-sources --all`, `/list-sources --include-deleted`, `/remove-source`, `/source-enable`, `/source-disable`, `/group-timezone`, `/retry [--digest]`, `/export`, `/audit`).

#### Spec sections / decision IDs implemented
- `spec/architecture.md` §Service split (Provider's group-mode handling); `spec/security.md` §Authorization model step 3 (group auto-register), §User ban (group-admin restoration disclosure on `/unban`).
- `spec/commands.md` §Discovery (group filtering of `/help`), §Content (`/save`, `/saved`, `/unsave`), §Source management (full), §Per-scope tag preferences, §Conversation control (`/clear` in groups, `/group-timezone`, `/retry --digest`, `/export`).
- `spec/messaging.md` §Identity and groups; §Failure handling (Bot removed from group, Group deleted upstream, User left group, Permanent failure threshold > 1).
- `spec/schema.md` §Identity and access — Group membership user-departure lifecycle; §Per-user state — Saved post (cap atomically enforced).
- D7, D9 (full), D11 (group-admin restoration disclosure on `/unban`), D13, D14, D15, D16, D17 (cache scaffolded; periodic schedule still M5), D26, D32 (still no Signal/SimpleX in production; structural multi-adapter support exercised in tests).

#### Maven modules and packages created or touched
- `infochat-provider/org.infochat.provider.group` — `GroupRegistry`, `GroupAutoPromote` (first non-banned non-probation `@mention` wins, race-safe via `INSERT … ON CONFLICT DO NOTHING` against the partial unique index).
- `infochat-provider/org.infochat.provider.command.group` — `/promote`, `/demote`, group permission checks for `/add-source`, `/follow-tag`, `/unfollow-tag`, `/lang`, `/group-timezone`, `/unfollow-source`.
- `infochat-provider/org.infochat.provider.command.user` — `/save`, `/saved`, `/unsave` (per-user-globally per D13).
- `infochat-provider/org.infochat.provider.command.export` — `/export` (in-band delivery, paginated, scope-isolated).
- `infochat-provider/org.infochat.provider.retry` — `/retry`, `/retry --digest`, anchor lookup, status filter on frozen UID set, per-group serialization for `--digest` (`spec/commands.md` §Conversation control — `/retry`).
- `infochat-messaging-adapter/org.infochat.messaging.impl.inmemory.group` — extend the InMemory adapter with group-mode primitives + membership events.

#### SPI seams introduced or extended
- `MessagingAdapter` extended:
  ```
  interface MembershipEventConsumer {
    void onUserJoined(GroupId g, ContactId c);
    void onUserLeft(GroupId g, ContactId c);     // Optional — gated by supportsMembershipEvents
    void onBotRemovedFromGroup(GroupId g);
    void onGroupDeleted(GroupId g);
  }
  ```
  with capability-flag-gated wiring per `spec/messaging.md` §Required SPI surface — Membership events.

#### Schema entities created or modified
- `groups` table fully wired (`(adapter, upstream_group_id)` natural key; `removed_at` column; `timezone` defaults to `UTC` operator-side default).
- `group_membership.removed_at` wired (soft-clear on left-group; `is_group_admin` cleared in same transaction when the leaving user was admin).
- `saved_post.user_id` denormalized counter (`users.save_count` + trigger) for the per-user cap O(1) check (per `design/02-schema.md` §2.6).
- `summary_anchor` digest-row support (`user_id IS NULL`, `command_kind = 'digest'`).

#### Acceptance criteria

### A1. Group auto-register + first-mention auto-promote
- Unknown contact's first `@mention` in a group auto-registers the user with `registration_state = 'group_only'` and `probation_until = NOW() + horizon`; the `users` row exists; the user is **not** auto-promoted on the same message (probation users are ineligible) (`spec/security.md` §Authorization model — step 3).
- The first eligible (non-banned, non-probation) @mention winner is auto-promoted via `INSERT … ON CONFLICT DO NOTHING` against the `one_admin_per_group` partial unique index (`spec/security.md` §Authorization model — Auto-promote race protection).
- `/promote` demotes the existing group admin in the same transaction.

### A2. `/save` per-user-globally with atomic cap
- `/save <uid>` succeeds for any non-banned user in DM and group; the row is global to the user; the per-user cap is enforced atomically via `users.save_count` (`spec/commands.md` §Content — `/save`; D13).
- `/saved` lists all saves regardless of calling scope; the reply header discloses this (`spec/commands.md` §Content — `/saved`).
- `/save` on `QUARANTINED` or `NEEDS_REVIEW` posts returns the unknown-UID error (`spec/commands.md` §Content — `/save` Visibility-of-target rules).

### A3. `/follow-tag` / `/unfollow-tag` / tag-mode transitions
- `ALL → EXPLICIT` mode flip on first `/follow-tag` seeds rows for the followed tag only; `ALL → EXPLICIT` mode flip on first `/unfollow-tag` seeds rows for all currently subscribed-source `bootstrap_tags` minus the unfollowed tag; `EXPLICIT → ALL` flip when row count drops to 0; `/unfollow-tag --all` requires confirm and resets to `ALL` (`spec/commands.md` §Per-scope tag preferences).

### A4. `/remove-source` / `/source-enable` / `/source-disable`
- `/remove-source` is bot-admin only, requires confirm, soft-deletes the source row and cascade-deletes `source_subscription` rows in the same transaction (`spec/commands.md` §Source management — `/remove-source`).
- `/source-enable` against a soft-deleted source requires confirm; clears `deleted_at`; **does not restore subscriptions**; reply discloses this (`spec/commands.md` §Source management — `/source-enable`; `spec/verification.md` §Fetcher failure ladder — Soft-deleted source re-enable).
- `/source-disable` transitions `active → disabled`; scheduler stops scheduling on next tick; existing posts remain visible.

### A5. `/group-timezone`
- Group admin or bot admin only; IANA tz parse with fuzzy-suggestion error; default is `UTC`; mutated via per-group `groups.timezone`; audit-logged before effect (`spec/commands.md` §Conversation control — `/group-timezone`).

### A6. `/retry` and `/retry --digest`
- `/retry` reuses the frozen UID set from `summary_anchor`; status-filter at retry time (excludes UIDs no longer `READY`); cap exhaustion returns friendly error and leaves the anchor intact; any non-`/retry` input clears the anchor (`spec/commands.md` §Conversation control — `/retry`).
- Routing rules in groups: regular member's `/retry` matches their personal anchor; group admin's `/retry` defaults to personal anchor when both exist; `/retry --digest` requires group admin or bot admin and matches the `digest` anchor; `--digest` in DM is a friendly error (`spec/commands.md` §Conversation control — Routing rules in groups).
- Per-group serialization: at most one `/retry --digest` in flight per group (`spec/commands.md` §Conversation control — Concurrent `/retry --digest`).

### A7. `/export` field-level positive list
- `/export` reply is in-band, paginated; output format respects the field-level positive list (`spec/commands.md` §Conversation control — `/export`).
- Group `/export` is scoped to `(user, group)` for per-scope tables and to the user globally for `saved_post`.
- Audit row written before the read.

### A8. Bot removed from group / group deleted upstream / user left group
- `groups.removed_at = NOW()` + cancel periodic-digest scheduler entries on bot-removed; group state preserved (`spec/messaging.md` §Failure handling — Bot removed from group; permanent-failure threshold > 1).
- Group-deleted treated identically.
- User-left soft-clears the `group_membership` row (`removed_at = NOW()`); if the user was group admin, `is_group_admin` is cleared in the same transaction; group is admin-less until the next `/promote` or first-mention auto-promote on a fresh @mention from a registered user.
- A `removed_at IS NOT NULL` row is **not eligible** as a first-mention auto-promote winner.

#### Given/When/Then scenarios

### Group first-mention by an unregistered contact
- **G** the bot is added to a fresh group; no member is registered with the bot
- **W** Carol @mentions the bot
- **T** Carol is auto-registered with `registration_state = 'group_only'`, `probation_until = NOW() + horizon`; the auto-promote slot is **not** consumed (she is in probation); group is admin-less; bot reply is the Mode-3 welcome (`spec/security.md` §Authorization model — step 3; `spec/commands.md` §Operator note — Fresh group of unregistered users).

### Banned target /promote rejection
- **G** Dave is in the group with `is_banned = true`
- **W** an admin runs `/promote dave`
- **T** friendly error directing the admin to `/unban` first; no `is_group_admin` set (`spec/commands.md` §Admin — `/promote` / Banned target rejection).

### `/unban` of a previously-group-admin user
- **G** Eve was group admin in two groups, then was banned; her `is_group_admin = true` rows remain but are unreachable
- **W** an admin runs `/unban eve`
- **T** the reply enumerates both groups whose `is_group_admin = true` is being reinstated, with a `/demote` hint; the audit row carries the same list under `details_json.restored_group_admin` (`spec/security.md` §User ban — Banning a user who is a group admin; `spec/commands.md` §Admin — `/unban`).

### `/save` of a `QUARANTINED` post
- **G** Frank tries to `/save <uid>` where the post is `QUARANTINED` (Stage 2 verdict INJECTION) and therefore invisible
- **W** the command runs
- **T** the response is the unknown-UID error (no leak of existence vs no-access) (`spec/commands.md` §Content — `/save` Visibility-of-target rules).

### Concurrent `/retry --digest`
- **G** two group admins simultaneously issue `/retry --digest` in the same group
- **W** both arrive at the Provider in the same instant
- **T** exactly one runs to completion (replaces `summary_cache` once); the other receives the localized "a digest retry is already in progress for this group" reply with no LLM call, no anchor read, no second `summary_cache` write (`spec/commands.md` §Conversation control — Concurrent `/retry --digest`; `spec/verification.md` §Concurrent `/retry --digest` per-group serialization).

#### Blocking dependencies on earlier milestones
- M2 (admin command surface, throttled notifier, `quarantine_review` channel — needed for the disclosure rules and the audit-row paths).

#### Test surface
- Unit: tag-mode transition logic; `/retry` anchor routing in groups; group-membership soft-clear logic.
- Persistence: `one_admin_per_group` partial unique index race; `users.save_count` trigger correctness under concurrent `/save`; `summary_anchor` digest-row partial unique index.
- Integration: group SPI on a fake adapter that emits `@mention` payloads and membership events; full per-group admin lifecycle; `/forget` from a group with the remaining-scopes disclosure.
- E2E: a group transcript: bot added → first @mention auto-registers → bot admin promotes → group admin runs `/add-source` → digest cycle → user `/save` → `/forget`.

#### Decision dependency
- Persistence layer choice still open (this milestone exercises trigger semantics + transactional cascade — Hibernate vs raw JDBC affect how the trigger contract is asserted in code).

---

### Milestone 4 — Periodic group digests + scheduling

#### Goal
Wire the periodic morning + evening digest scheduler with per-group timezone, staggered slot windows, summary cache (subscription-version-keyed), degraded fallback, and missed-slot skip semantics. `/retry --digest` already exists from M3; this milestone adds the scheduler that produces the original digests it retries.

#### Spec sections / decision IDs implemented
- `spec/commands.md` §Periodic group digests (full).
- `spec/architecture.md` §Hardware profiles (summary worker count is profile-driven).
- `spec/messaging.md` §Failure handling — Bot removed from group (digest cancel).
- `spec/schema.md` §Operational — Summary cache.
- `spec/llm.md` §Per-task routing rules — summarizer language-aware capability.
- D16, D17, D18, D31 (progress notifier on long-running digest generation).

#### Maven modules and packages created or touched
- `infochat-provider/org.infochat.provider.digest` — `DigestScheduler` (CRON-like, every minute), `DigestWorker` (staggered slot offset), `SummaryCache` (keyed by `(group, slot, tag_subscription_version, source_subscription_version)`), `DegradedFallbackRenderer` (headlines + sources, no LLM prose).
- `infochat-provider/org.infochat.provider.digest.audit` — `digest_slot_missed` audit + `digest_slots_missed_total` counter.

#### SPI seams introduced or extended
- None new; this milestone exercises the existing `LlmRouter`, `TranslationProvider`, `ProgressNotifier`, `MessagingAdapter`.

#### Schema entities created or modified
- `summary_cache` table (per `spec/schema.md` §Operational — Summary cache).
- Subscription-version counters on `scope_preferences` already exist from M1 (per `design/02-schema.md` §2.5).

#### Acceptance criteria

### A1. Slot scheduling
- Two operator-configured global slot center hours (morning + evening), interpreted in each group's `groups.timezone` (`spec/commands.md` §Periodic group digests).
- Each digest fires within a profile-driven window centered on the slot hour, staggered to avoid worker-pool slamming (`spec/commands.md` §Periodic group digests; D17).

### A2. Cache behavior
- Cache key is `(group, slot, tag_v, src_v)`; a follow-up `/summary` from the same group during the cache TTL is served from cache (no second LLM call) (`spec/verification.md` §Periodic group digests).
- Subscription-version increments on `/follow-tag`, `/unfollow-tag`, `/add-source`, `/remove-source`, `/unfollow-source` yield a fresh cache miss (per `design/01-architecture.md` §1.4.1 + `design/02-schema.md` §2.5).

### A3. Degraded fallback
- When the worker pool is saturated at slot-window-end, a degraded digest (headlines + sources, no LLM prose) is sent; the cache row is written with the same TTL as full-prose; `/retry --digest` on a degraded slot regenerates full prose if the worker pool is free (`spec/commands.md` §Periodic group digests — Degraded-fallback exit; `spec/verification.md` §Periodic group digests).

### A4. Missed slot skip
- Provider down for the entire slot window → on next startup the slot is skipped; per-group `digest_slot_missed` audit row written; `digest_slots_missed_total` counter increments by exactly one for that group; next slot fires normally (`spec/commands.md` §Periodic group digests — Missed slot behaviour).

### A5. Zero-eligible-posts digest
- When no eligible posts exist for the group, the digest sends the fixed "no posts yet" reply (deterministic localization-bundle string), not a silent skip (`spec/commands.md` §Periodic group digests — Zero-eligible-posts digest).

### A6. Bot removed from group → digest cancel
- `groups.removed_at = NOW()` cancels the periodic-digest scheduler entries for that group (`spec/messaging.md` §Failure handling — Bot removed from group).

#### Given/When/Then scenarios

### Morning digest happy path
- **G** group G has `timezone = Europe/Prague`; the operator-configured morning slot center hour is 08:00 local; the worker pool is healthy
- **W** the scheduler ticks at 07:55 local
- **T** a generation slot is enqueued with stagger offset; the worker generates the digest within the window, writes `summary_cache` keyed by `(G, morning, tag_v, src_v)`, and delivers the digest via the InMemory adapter (`spec/commands.md` §Periodic group digests).

### Saturated worker pool → degraded fallback → next slot full prose
- **G** the worker pool is saturated when the morning slot fires
- **W** the slot window ends without the digest having started
- **T** the degraded fallback is generated and sent; `summary_cache` row is written with the same TTL.
- **W** later that day the evening slot fires with the worker pool restored
- **T** the evening slot runs full-prose unconditionally; degraded mode is **not** sticky across slots (`spec/commands.md` §Periodic group digests — Degraded-fallback exit).

### Provider down for entire slot
- **G** Provider is down from 07:55 through 08:30 local while the morning slot fires
- **W** Provider restarts at 09:00
- **T** the morning slot is **skipped**; a `digest_slot_missed` audit row is written; the counter increments by 1; the next scheduled slot (evening) fires normally (`spec/commands.md` §Periodic group digests — Missed slot behaviour; `spec/verification.md` §Periodic group digests — Missed slot).

#### Blocking dependencies on earlier milestones
- M3 (group support, `summary_anchor` digest row, `/retry --digest`).

#### Test surface
- Unit: cache-key composition; degraded-fallback rendering; staggered slot offset.
- Integration: end-to-end digest cycle; missed-slot skip with fake clock; `/summary` cache hit after digest.
- E2E: group transcript exercising morning + evening digests across two days.

#### Decision dependency
- Observability baseline gates the `digest_slots_missed_total` counter shape.

---

### Milestone 5 — All remaining source kinds (Fetcher-shaped) + URL routing

#### Goal
Add the remaining Fetcher-shaped source kinds — `bluesky`, `nitter`, `reddit`, `youtube`, `odysee` — and the URL-routing table in `/add-source` with the kind-resolution rules + URL-validation probe.

#### Spec sections / decision IDs implemented
- `spec/architecture.md` §Ingest SPIs — Fetcher (full pagination cap behavior across paginated upstreams).
- `spec/commands.md` §Source management — Kind resolution (closed table); URL validation before insert (`infochat-ssrf` probe).
- D38 (Fetcher kinds), D42 (Fetcher failure ladder).

#### Maven modules and packages created or touched
- `infochat-collector/org.infochat.collector.fetcher.bluesky` — Bluesky impl.
- `infochat-collector/org.infochat.collector.fetcher.nitter` — Nitter impl.
- `infochat-collector/org.infochat.collector.fetcher.reddit` — Reddit impl.
- `infochat-collector/org.infochat.collector.fetcher.youtube` — YouTube impl.
- `infochat-collector/org.infochat.collector.fetcher.odysee` — Odysee impl.
- `infochat-provider/org.infochat.provider.command.parse` — kind-resolution table per `spec/commands.md` §Source management — Kind resolution (1) `--type` wins, (2) host-pattern table, (3) RSS auto-detection, (4) ambiguous → friendly error.

#### SPI seams introduced or extended
- No new SPI; each new Fetcher implements `Fetcher` from M1.

#### Schema entities created or modified
- None new; existing `source` row shape covers all kinds.

#### Acceptance criteria

### A1. Per-kind interval scheduler
- Each kind has its own poll cadence (profile-driven); the scheduler ticks per-kind (`spec/architecture.md` §Ingest SPIs — Fetcher).

### A2. Pagination
- Paginated kinds (Bluesky, Reddit, Nitter) paginate within a single tick up to a per-source max-page cap; backlog beyond the cap is picked up on subsequent ticks via the "what's new since last time" query (`spec/architecture.md` §Ingest SPIs).
- Pagination cap saturation across multiple ticks fires a throttled admin notification (`spec/architecture.md` §Ingest SPIs — Pagination cap saturation).

### A3. URL routing rules
- The closed host-pattern table is honored byte-for-byte; ambiguous URLs return the friendly error listing the supported kinds (`spec/commands.md` §Source management — Kind resolution).
- `--type rss` and the `.xml`/`.rss`/`/feed` auto-detection both work.
- URL-validation probe through `infochat-ssrf` runs before the source row is written; 4xx/5xx/SSRF rejection/timeout produces a friendly error and **no row** is written.

### A4. Fetcher failure ladder (D42)
- N consecutive failures flip `source.status = 'failed'`; N-1 does not; throttled admin notification fires once per `(channel, error_class)` window; `/source-enable` (with probe success) returns the source to `active` and resets the consecutive-failure counter (`spec/verification.md` §Fetcher failure ladder).

#### Given/When/Then scenarios

### URL routing — bare `bsky.app` URL
- **W** `/add-source https://bsky.app/profile/foo`
- **T** the kind resolves to `bluesky` without `--type`; the URL-validation probe runs through `infochat-ssrf` (`spec/commands.md` §Source management — Kind resolution).

### URL routing — ambiguous self-hosted Nitter
- **W** `/add-source https://my-nitter.example.com/feed`
- **T** the URL matches no host-pattern row and the path-pattern fallback (`/feed`) resolves to `rss`, but the probe returns `text/html` not RSS — the call falls through to the ambiguous path and rejects with the friendly error instructing the caller to supply `--type` (`spec/commands.md` §Source management — Kind resolution).

### Fetcher failure → `failed` transition
- **G** an RSS source fails N consecutive times
- **T** `source.status` flips to `failed`; throttled admin notification fires; the scheduler skips the source on subsequent ticks (`spec/security.md` §Failure handling — Fetcher failure; D42).

#### Blocking dependencies on earlier milestones
- M1 (Fetcher SPI), M2 (admin throttled notifier).

#### Test surface
- Unit: kind resolution table.
- Persistence: per-kind interval honoring; pagination.
- Integration: each new fetcher against a fixture endpoint + the URL-validation probe.
- E2E: deployment serves multiple kinds; `/add-source` exercises every routing branch.

#### Decision dependency
- Test infra (Testcontainers stance for upstream-shaped fakes — WireMock vs hand-rolled).

---

### Milestone 6 — Nostr (StreamSource SPI) + per-source trust boundary

#### Goal
Implement the `StreamSource` SPI and the Nostr v1 implementation: kinds 1 and 6 only, signature verification before Stage 1, cross-relay dedup by stable upstream id, per-relay degradation, all-relays-bad cycle cap → terminal `failed` state, async startup, drain on shutdown, kind-6 cross-source linking by `upstream_identifier`.

#### Spec sections / decision IDs implemented
- `spec/architecture.md` §Ingest SPIs — StreamSource (full).
- `spec/security.md` §Per-source trust boundaries — Nostr (full).
- `spec/schema.md` §UID derivation (Nostr event id = `upstream_identifier`); §Posts and derivatives (`post_reference` for kind-6 keyed by `upstream_identifier`).
- D38 (full).

#### Maven modules and packages created or touched
- `infochat-collector/org.infochat.collector.streamsource.spi` — `StreamSource`, `StreamSourceSupervisor`.
- `infochat-collector/org.infochat.collector.streamsource.nostr` — Nostr impl: WebSocket connection per relay, signature verification, kind allowlist, cross-relay dedup, per-relay degradation, all-relays-bad cycle cap, drain on shutdown.

#### SPI seams introduced or extended
```
package org.infochat.collector.streamsource.spi;
public interface StreamSource {
  SourceKind kind();                                 // 'nostr' in v1
  void start(StreamSourceContext ctx);               // async; returns immediately
  void drain(Duration hardTimeout);                  // graceful shutdown
}
```

#### Schema entities created or modified
- `source.config` JSONB column already exists; the Nostr `config` shape (relay list, kind filter) is added in design (per `spec/deployment.md` §Operator inputs item 3).

#### Acceptance criteria

### A1. Trust boundary ordering
- Per-event order: signature verification → kind allowlist → outbox write → Stage 1 (`spec/security.md` §Per-source trust boundaries — Nostr Kind allowlist).
- A signature-failed event is dropped with the failed-sig counter incrementing; nothing reaches `posts` (`spec/verification.md` §Nostr signature verification).
- A kind-4 / kind-7 / any-disallowed-kind event is dropped at the kind filter; the body is never read by the implementation (`spec/verification.md` §Nostr kind filter).

### A2. Cross-relay dedup
- The same Nostr event delivered from N relays in any interleaving produces exactly one `posts` row (`spec/verification.md` §Cross-relay event dedup).

### A3. Async startup
- Collector readiness goes healthy when the scheduler accepts the StreamSource registration, not when every relay is connected; an unreachable relay surfaces as ordinary per-relay degradation (`spec/architecture.md` §Ingest SPIs — Asynchronous startup; `spec/deployment.md` §Bootstrap behavior — Exception).

### A4. Per-relay degradation + all-relays-bad cycle cap
- A misbehaving relay is marked bad for the cooldown window; StreamSource keeps running on remaining relays; admin notification fires once per all-relays-bad transition (`spec/architecture.md` §Ingest SPIs — All relays in cooldown).
- After the configured number of consecutive all-relays-bad cycles, the StreamSource transitions to terminal `source.status = 'failed'` and stops reconnecting; one-time admin notification fires; an admin must run `/source-enable` to recover (`spec/architecture.md` §Ingest SPIs — Absolute cycle cap → terminal failed state).

### A5. Drain on shutdown
- Graceful shutdown drains in-flight events to the outbox before acknowledging shutdown; events not drained within the hard timeout are dropped and counted on the per-relay loss counter; reconnect issues `since=last_persisted_event_at` (`spec/architecture.md` §Ingest SPIs — Drain on shutdown; `spec/verification.md` §StreamSource drain).

### A6. Kind-6 cross-source linking
- A kind-6 repost stores commentary text as the post body (when present), and writes a `post_reference` edge with `link_type = 'repost'` keyed by the original event's `upstream_identifier` (not the derived UID, which may not exist yet) (`spec/architecture.md` §Source identity — Kind-6 cross-source linking).
- A kind-6 referencing a disallowed-kind original stores only the reference, never fetches the original.

### A7. SSRF on `wss://` relays
- A `wss://` URL whose hostname resolves to a blocked range is refused before TCP connect; the same blocklist applies on every reconnect; a peer-IP change observed at the socket layer is a hard close (`spec/security.md` §SSRF — DNS re-resolved, peer-IP-change hard-close; `spec/verification.md` §Websocket SSRF).

#### Given/When/Then scenarios

### Tampered signature
- **G** a fixture event's signature is tampered
- **T** the event is dropped before Stage 1; the failed-sig counter increments; nothing reaches `posts` (`spec/verification.md` §Nostr signature verification).

### Cross-relay duplicate event
- **G** the same event is delivered by 3 relays interleaved
- **T** exactly one `posts` row is produced (`spec/verification.md` §Cross-relay event dedup).

### All relays bad → terminal failed
- **G** every configured relay is in cooldown; the cycle cap is K
- **W** the K-th consecutive all-relays-bad cycle elapses
- **T** the StreamSource transitions to terminal `failed`; one-time admin notification fires; only `/source-enable <id>` recovers.

#### Blocking dependencies on earlier milestones
- M1 (outbox + Stage 1), M5 (Fetcher failure ladder for the parallel pattern; bot-admin `/source-enable` from M3).

#### Test surface
- Unit: signature verification, kind filter, canonicalized identifier comparison.
- Persistence: dedup by `upstream_identifier`; kind-6 `post_reference` resolution when the original arrives later.
- Integration: fake-relay harness (canned `.jsonl` event streams; scriptable disconnects; multi-relay topologies per `design/08-verification.md`).

#### Decision dependency
- Open: Nostr WebSocket library choice (`Java.net.http` HTTP client supports WebSocket, but a Nostr-specific library may exist; spec is silent — flag).

---

### Milestone 7 — Asset commands (`/zcash`, `/monero`)

#### Goal
Wire asset commands per D39: `bootstrap-assets.json` loader (3 file-state cases), per-host refresh interval Fetchers writing direct-to-`price_snapshot`, `new_price_snapshot` NOTIFY (cache-flush-on-reconnect correctness), Provider's read path with mandatory attribution + freshness contract, sub-verb allowlist, default-row consistency.

#### Spec sections / decision IDs implemented
- `spec/commands.md` §Asset commands (full).
- `spec/architecture.md` §Ingest SPIs — output type discriminator (`PRICE_SNAPSHOT` Fetchers); §Inter-service communication — `new_price_snapshot` channel.
- `spec/schema.md` §Operational — Asset config (incl. `is_default` partial unique index, default-row consistency); Price snapshot.
- `spec/deployment.md` §Bootstrap behavior — Asset bootstrap (3 file-state cases).
- D39 (full).

#### Maven modules and packages created or touched
- `infochat-collector/org.infochat.collector.bootstrap.asset` — `AssetBootstrapLoader` (3 file-state cases).
- `infochat-collector/org.infochat.collector.fetcher.coingecko` / `kraken` / `bitfinex` — public-endpoint Fetchers; `outputType = PRICE_SNAPSHOT`; per-host refresh interval keyed by `sub_verb`.
- `infochat-collector/org.infochat.collector.notify.price` — emits `new_price_snapshot` with payload `(asset, sub_verb)`.
- `infochat-provider/org.infochat.provider.command.asset` — `/zcash`, `/monero`; sub-verb allowlist resolution; default-sub-verb resolution; freshness-window check + "data is N minutes old" line; cache flush on Postgres reconnect.

#### SPI seams introduced or extended
- `Fetcher.outputType()` already exists from M1; this milestone is the first to use `PRICE_SNAPSHOT`.

#### Schema entities created or modified
- `asset_config` (per `spec/schema.md` §Operational — Asset config; `is_default` partial unique index).
- `price_snapshot` (per `spec/schema.md` §Operational — Price snapshot; partitioned by `captured_at`).

#### Acceptance criteria

### A1. Bootstrap file-state semantics (3 cases)
- Path unset → asset commands disabled; info log; rest of v1 ships normally (`spec/deployment.md` §Bootstrap behavior — Asset bootstrap).
- Path set, file absent → fatal startup with log line identifying the path.
- Path set, file malformed → fatal startup with log line identifying the parse / validation error (incl. `is_default = true AND enabled = false` rejection per `spec/schema.md` §Operational — Default-row consistency).

### A2. Per-host refresh interval
- All `coingecko` snapshots across enabled assets share one tick cadence; same for `kraken` and `bitfinex` (`spec/commands.md` §Asset commands — Polled, cached, refreshed on a tick; `spec/verification.md` §Asset commands — Per-host refresh interval).

### A3. `new_price_snapshot` correctness
- Provider's in-process cache is flushed entirely on every Postgres reconnect; correctness comes from the table read, not from the notification (`spec/architecture.md` §Inter-service communication; `spec/commands.md` §Asset commands — Provider/Collector contract).

### A4. Mandatory attribution + freshness honesty
- Every reply names the data source in the header (`Zcash (kraken)`); includes the bare source URL (D30); includes the snapshot's capture timestamp; "data is N minutes old" line when within the freshness window but stale; friendly error when no row exists at all (`spec/commands.md` §Asset commands).

### A5. Sub-verb allowlist + default-row consistency
- `/monero binance` is rejected because XMR is not on Binance.
- Bare `/zcash` resolves to the per-asset default sub-verb; absence of a default → friendly "not configured" error (`spec/commands.md` §Asset commands).
- DB-role test: Provider can SELECT from `price_snapshot`; cannot INSERT/UPDATE/DELETE (`spec/verification.md` §Asset commands).

#### Given/When/Then scenarios

### Bare `/zcash` with default sub-verb configured
- **G** `asset_config` has `is_default = true AND enabled = true` for `(zcash, coingecko)`
- **W** Alice runs `/zcash`
- **T** the reply uses the latest `price_snapshot` for `(zcash, coingecko)`; header reads `Zcash (coingecko)`; bare source URL included; capture timestamp visible (`spec/commands.md` §Asset commands).

### Default-but-disabled at runtime
- **G** an injected runtime row has `is_default = true AND enabled = false`
- **W** Alice runs `/zcash`
- **T** friendly error "default sub-verb is currently disabled; pass an explicit sub-verb" with enabled sub-verbs listed; no implicit fallback (`spec/commands.md` §Asset commands — Default-but-disabled fallback).

### Missed `new_price_snapshot` during connection blip
- **G** the Provider's connection to Postgres blips; a `new_price_snapshot` fires during the blip
- **W** the Provider reconnects
- **T** the in-process cache is flushed entirely; the next `/zcash` invocation reads the latest snapshot from `price_snapshot` directly; no stale row served past the reconnect (`spec/architecture.md` §Inter-service communication; `spec/commands.md` §Asset commands — Provider/Collector contract).

#### Blocking dependencies on earlier milestones
- M1 (Fetcher SPI), M2 (throttled notifier — for asset Fetcher failures using D42's per-source counter), M3 (audit row breadth).

#### Test surface
- Unit: file-state semantics (3 cases); sub-verb allowlist; default-row resolution.
- Persistence: `is_default` partial unique index; partition rotation for `price_snapshot`.
- Integration: full `/zcash coingecko` cycle through fake CoinGecko; cache flush on Postgres reconnect.

#### Decision dependency
- Test infra: how to fake the public endpoints.

---

### Milestone 8 — SimpleX adapter

#### Goal
Implement the SimpleX adapter as a production messaging adapter per `spec/messaging.md` §Per-adapter trust level — SimpleX, including the `mention_by_contact_id` capability, group support, and the `user_left_group` question.

#### Spec sections / decision IDs implemented
- `spec/messaging.md` §Per-adapter trust level — SimpleX; §Required SPI surface (full).
- `spec/security.md` §Per-adapter admin threat profile — SimpleX.
- D32 (SimpleX impl), D46 (multi-adapter Provider topology).

#### Maven modules and packages created or touched
- `infochat-messaging-adapter/org.infochat.messaging.impl.simplex` — `SimpleXAdapter`, encoder/decoder, capability defaults, identity-material loader.

#### SPI seams introduced or extended
- None new; SimpleX implements the existing `MessagingAdapter` SPI.

#### Schema entities created or modified
- None.

#### Acceptance criteria

### A1. SimpleX adapter capabilities
- `trustLevel = HIGH`; `supportsCodeFormatting = false`; `supportsMarkdownLinks = false`; `supportsMessageEdit = true`; `supportsMentionByContactId = true` (queue-address-anchored); `supportsMembershipEvents` per protocol availability (open question — see Open questions); `supportsTypingIndicator = false` (per `design/06-messaging.md` §6.4) (`spec/messaging.md` §Per-adapter trust level — SimpleX).

### A2. Identity material owned by adapter
- The adapter validates its own bot identity at startup; misconfigured/unreachable identity store fails the adapter's startup (per-adapter resilience rule); Provider does not synthesize bot identity (`spec/deployment.md` §Operator inputs item 7).
- The per-adapter bot contact id is derived from this identity material (not an operator-typed property).

### A3. Per-adapter resilience + readiness rule
- A SimpleX connection failure does not abort Provider startup or prevent the InMemory/Signal adapters from coming up; readiness reports ready when at least one adapter is connected (`spec/deployment.md` §Bootstrap behavior — Per-adapter resilience).

### A4. Mention recognition
- A group message counts as an `@mention` only when the SimpleX mention payload references the bot's queue address (byte-equality); display-name string matching is **never** sufficient (`spec/messaging.md` §Required SPI surface — Mention-recognition rule).

#### Given/When/Then scenarios

### SimpleX admin flow
- **G** an operator configures the SimpleX adapter with a bootstrap admin contact id
- **W** Provider starts
- **T** the SimpleX adapter validates its identity material, connects, and the bootstrap admin row exists with `is_admin = true`, `registration_state = 'vouched'` (`spec/deployment.md` §Bootstrap-seeded admin row shape).

### Cross-adapter isolation invariant
- **G** an InMemory adapter and a SimpleX adapter are both enabled
- **W** an invite scoped to `(simplex, contact-A)` is presented from `(inmemory, contact-A)` (byte-equal contact id)
- **T** the invite does **not** match; the byte-equal contact on a different adapter is a different `users` row (`spec/messaging.md` §Per-adapter trust level — Signal cross-adapter isolation invariant; analogous for SimpleX).

#### Blocking dependencies on earlier milestones
- M3 (group support), M4 (digest scheduler), M5/M6 (other source kinds), M7 (asset commands).

#### Test surface
- Unit: SimpleX wire encoder/decoder.
- Integration: end-to-end SimpleX flow against a fake `simplex-cli` WebSocket fixture.
- E2E: production-shape deployment with SimpleX as the only adapter.

#### Decision dependency
- **SimpleX adapter shape** (subprocess + WebSocket vs embedded) — open; gates on-disk identity-material layout.
- Container base image / supply-chain.

---

### Milestone 9 — Signal adapter

#### Goal
Implement the Signal adapter as a production messaging adapter per `spec/messaging.md` §Per-adapter trust level — Signal, including the `mentionUuid` (ACI) anchoring rule, group support, and the documented per-adapter compromise threat profile.

#### Spec sections / decision IDs implemented
- `spec/messaging.md` §Per-adapter trust level — Signal; §Required SPI surface — Mention-recognition rule (Signal ACI surfaced by `signal-cli` as `mentionUuid`).
- `spec/security.md` §Per-adapter admin threat profile — Signal.
- D32, D46.

#### Maven modules and packages created or touched
- `infochat-messaging-adapter/org.infochat.messaging.impl.signal` — `SignalAdapter`, identity-material loader (`signal-cli` account directory or equivalent), encoder/decoder, capability defaults.

#### SPI seams introduced or extended
- None new.

#### Schema entities created or modified
- None.

#### Acceptance criteria

### A1. Signal adapter capabilities
- `trustLevel = HIGH`; `supportsCodeFormatting` (per protocol — typically false in Signal text rendering); `supportsMarkdownLinks = false`; `supportsMessageEdit = true` (`spec/messaging.md` §Per-adapter trust level — Signal lists Signal as supporting edit ~24h window); `supportsMentionByContactId = true` (anchored to ACI); `supportsMembershipEvents = true` (Signal exposes membership events).

### A2. ACI as mention anchor
- A group message counts as `@mention` only when the `mentionUuid` matches the bot's per-adapter contact id (`spec/messaging.md` §Required SPI surface — Mention-recognition rule).

### A3. Multi-adapter Provider on a single instance
- A Provider running SimpleX + Signal simultaneously shares the LLM worker pool, the per-user rate-limit budget, and the DB; the schema's `(adapter, contact_id)` keying isolates user identity per adapter (`spec/deployment.md` §Topology).
- Last-admin protection is global across adapters (verified via the trigger lock + count) (`spec/security.md` §Authorization model — Last-admin protection).

### A4. Per-adapter compromise blast radius
- `/grant-admin` is inbound-adapter-scoped; a compromised Signal admin cannot grant admin on SimpleX (`spec/security.md` §Per-adapter admin threat profile).

#### Given/When/Then scenarios

### Multi-adapter onboarding
- **G** the Provider runs SimpleX + Signal; bootstrap admins are configured per adapter (only one is required by the union-non-empty rule, but here both are configured)
- **W** Provider starts
- **T** both bootstrap admin rows exist with their respective `(adapter, contact_id)` keys; both are `is_admin = true`, `registration_state = 'vouched'`; the in-memory test adapter is **not** enabled in this production deployment shape (`spec/messaging.md` §Per-adapter trust level — InMemory; `spec/deployment.md` §Topology).

### Signal SIM-swap mitigation (operator guidance)
- **G** the operator has placed admin only on SimpleX (the higher-trust adapter); Signal serves users with no Signal-side admin
- **T** the union-non-empty constraint is satisfied; the deployment is conformant; a Signal compromise cannot escalate to admin (`spec/security.md` §Per-adapter admin threat profile — Operator-side mitigations).

#### Blocking dependencies on earlier milestones
- M8 (SimpleX adapter — proves the multi-adapter scaffolding).

#### Test surface
- Unit: Signal wire encoder/decoder; `mentionUuid` recognition.
- Integration: end-to-end Signal flow against a fake `signal-cli` JSON-RPC fixture.
- E2E: production-shape deployment with SimpleX + Signal both enabled simultaneously.

#### Decision dependency
- **Signal adapter underlying tech** (`signal-cli` JSON-RPC subprocess vs `libsignal-service-java` in-process vs `signald`) — open; gates on-disk identity-material layout, packaging, supply-chain story.

---

### Milestone 10 — Anthropic LLM provider + per-task routing breadth

#### Goal
Add the Anthropic provider (native messages API, prompt caching) as the second `LlmProvider` impl, exercise per-task routing across both providers, and verify the local-only conflict guard at startup.

#### Spec sections / decision IDs implemented
- `spec/llm.md` §Per-task routing rules — full (incl. Local-only conflict).
- D32 (Anthropic impl).

#### Maven modules and packages created or touched
- `infochat-llm-adapter/org.infochat.llm.impl.anthropic` — `AnthropicProvider` (native messages API, supports prompt caching).

#### SPI seams introduced or extended
- None new.

#### Schema entities created or modified
- None.

#### Acceptance criteria

### A1. Anthropic provider implements `LlmProvider`
- Capability flags: `JSON_MODE`, `TOOL_CALLS`, `PROMPT_CACHING`, `SUPPORTS_LANGUAGE_EN`, `SUPPORTS_LANGUAGE_CS` (per model).

### A2. Per-task routing exercised
- A property override picks Anthropic for SUMMARIZER while leaving the SECURITY_JUDGE on Ollama; the router resolves correctly per call without changing the others (`spec/llm.md` §Per-task routing rules; `spec/verification.md` §LLM and embeddings — Routing).

### A3. Local-only conflict guard
- Startup with the local-only property set + a per-task override pointing at Anthropic fails fast with a fatal log line identifying the task and the provider (`spec/llm.md` §Per-task routing rules — Local-only).

### A4. No fallback chain
- An unreachable per-task provider degrades **only that task** to its task-specific failure path; the router does not silently switch to a different configured provider (`spec/llm.md` §Per-task routing rules — No fallback chain).

#### Given/When/Then scenarios

### Anthropic SUMMARIZER + Ollama SECURITY_JUDGE
- **G** Anthropic is configured for SUMMARIZER; Ollama for SECURITY_JUDGE; local-only is **not** set
- **W** the eval pipeline processes a Stage-1-flagged post and Alice runs `/summary`
- **T** the security judge call goes to Ollama; the summarizer call goes to Anthropic; the trace ids tie both to their respective provider+task labels (`spec/llm.md` §SPI shape — Call context).

#### Blocking dependencies on earlier milestones
- M1 (LlmProvider SPI + LlmRouter).

#### Test surface
- Unit: routing resolution.
- Integration: provider-swap test against the fake LLM harness.

#### Decision dependency
- **Container base image / supply-chain** (HTTP client choice, TLS trust store).
- **Secrets handling** (Anthropic API key from env var per `spec/security.md` §Secrets handling).

---

---

## 5. Decisions needed from the user

Each decision below is **not pinned by the spec or `decisions.md`**. Pick or override the recommendation; the milestone plan is written to admit any of the listed options without an architectural rewrite.

### D-A. Persistence layer

| Option | Trade-off |
|---|---|
| **Hibernate ORM + Panache** (recommended) | Quarkus-native ergonomics; static-field "active record" style is clean for CRUD; pgvector via `vector-jpa` or native query. Forces explicit native SQL for the load-bearing operations (conditional `UPDATE … WHERE status='PENDING'` invite consume; CAS `UPDATE provider_state … WHERE (cursor_high, cursor_low_kind, cursor_low_id) < (…)`; `RETURNING` row count for `/forget` no-op carve-out). |
| Raw JDBC (`agroal` + `JdbcTemplate`-like helper) | Maximum control; every SQL statement is hand-written. High consistency cost across feature modules; no first-class entity mapper. |
| **jOOQ** | Type-safe DSL; pgvector-aware extension exists. Adds a code-generation build step + commercial license consideration for some Postgres features in the Pro tier. |

**Recommendation**: **Hibernate ORM + Panache for entity-shaped reads/writes; native SQL for the small set of load-bearing operations** (invite consume, `provider_state` CAS, `audit_log_view` reads, partition-rotation DDL, last-admin trigger is DB-side anyway). Reason: best fit for the Quarkus-imperative-blocking model; easy to test under Testcontainers; the native-SQL escape-hatch satisfies every spec invariant that requires precise SQL semantics.

### D-B. DB migration tool

| Option | Trade-off |
|---|---|
| **Flyway** (recommended) | Named in `spec/deployment.md` §Topology and §Bootstrap behavior. First-class Quarkus extension. Versioned SQL migrations + repeatable migrations for views/grants. |
| Liquibase | YAML/XML changelog can be split per feature module; rollback support out of the box. Heavier; less idiomatic in Quarkus. |

**Recommendation**: **Flyway**. The spec's soft default plus Quarkus-native extension support make it the lowest-friction choice; no operational benefit from Liquibase's rollback feature given the strict append-only invariants for `audit_log` and partition-drop TTL.

### D-C. Maven module layout

Settled by `docs/design/09-reference.md` §9.1 plus the M0 fix that adds `infochat-ssrf`:

```
                    infochat-core
                   / |         \
   infochat-llm-adapter | infochat-messaging-adapter
                   \ |         /
                  infochat-ssrf  (sibling shared library)
                   \ |         /
        infochat-collector  infochat-provider
```

`infochat-collector` MUST NOT depend on `infochat-messaging-adapter` (enforced by parent POM and CI).

### D-D. Signal adapter underlying tech

| Option | Trade-off |
|---|---|
| **`signal-cli` JSON-RPC subprocess** (recommended) | Battle-tested; clean process boundary; ACI / `mentionUuid` surfaced naturally; identity material lives on disk in `signal-cli`'s account directory; OS-packaged on most distros. Adds a runtime dependency the operator must install. |
| `libsignal-service-java` in-process | No subprocess; lower latency; richer access to protocol primitives. Native libraries to ship; LGPL implications; in-process state is heavier to test; account-state corruption is harder to recover from. |
| `signald` | Bridge daemon; protobuf API. Fewer maintainers; uncertain long-term support. |

**Recommendation**: **`signal-cli` JSON-RPC subprocess**. Reason: matches the SimpleX subprocess pattern (operationally consistent), avoids in-process native-library packaging, easiest to recover when a Signal account state corrupts (rebuild the account directory), and the spec's per-adapter resilience rule (one adapter's identity-store failure does not abort Provider) maps cleanly to subprocess crash/restart semantics.

### D-E. SimpleX adapter shape

| Option | Trade-off |
|---|---|
| **`simplex-chat` CLI subprocess + WebSocket** (recommended) | The path the SimpleX project documents. Clean isolation. Existing `design/06-messaging.md` §6.4 was drafted around this assumption. |
| Embedded SimpleX library | Would eliminate the subprocess. No library exists for JVM today. Extracting one is a multi-year project. |

**Recommendation**: **`simplex-chat` CLI subprocess + WebSocket**. Only viable v1 option.

### D-F. v1 production deployment target

| Option | Trade-off |
|---|---|
| **Docker Compose (laptop / dev) + bare VPS + systemd (production)** (recommended) | Aligns with `spec/deployment.md` §Local development for the laptop profile and §Topology's exactly-one-Collector-and-one-Provider rule (D41). Operational footprint matches the `vps` and `pi` profiles. |
| Docker Compose for production | Acceptable but adds the Docker daemon to the ops surface. Fine for a small operator. |
| Kubernetes | Adds complexity with no scaling benefit (D41 forbids horizontal scaling in v1). Reserve for v2. |

**Recommendation**: **Docker Compose for dev/laptop; bare VPS + systemd unit files for production**. Reason: D41's "exactly one of each service" rule is incompatible with the autoscaler ergonomics that justify K8s; systemd's restart-on-failure plus `pg_advisory_lock` covers the single-instance enforcement story.

### D-G. Test infrastructure

| Option | Trade-off |
|---|---|
| **Quarkus Dev Services + Testcontainers + WireMock + custom fake-relay harness for Nostr** (recommended) | Quarkus Dev Services auto-starts Postgres+pgvector for `quarkus:dev` and integration tests. Testcontainers for explicit persistence-layer tests. WireMock for upstream HTTP fakes (RSS, Bluesky, Reddit, asset endpoints). Custom fake-relay harness needed for Nostr (already flagged in `design/08-verification.md` §What lives in design notes). Fake LLM = scriptable in-process verdicts. |
| Skip Dev Services; explicit Testcontainers per test class | More boilerplate; slightly faster startup for unit-only test runs. |
| External test DB | Operationally cumbersome; defeats the purpose of CI containers. |

**Recommendation**: **Dev Services + Testcontainers + WireMock + custom fake-relay harness**. Reason: lowest-friction match for Quarkus's testing posture; WireMock is the standard for HTTP fakes; the fake-relay harness is unavoidable for Nostr's protocol shape.

### D-H. Container base image / supply-chain

| Option | Trade-off |
|---|---|
| **Red Hat UBI 9 minimal + Eclipse Temurin JDK 25** (recommended) | Hardened base; long-term security updates; supports JDK 25; well-supported by Quarkus's container-image extensions. |
| Distroless | Smallest image; harder to debug; no shell. |
| Eclipse Temurin Alpine | Smallest size; musl libc occasionally surprises (signals, DNS). |

**Recommendation**: **UBI 9 minimal + Temurin JDK 25**. Reason: balance between security posture, size, and debuggability; the production deployment is single-host long-running, image size is not a hot constraint.

### D-I. Secrets handling for v1

| Option | Trade-off |
|---|---|
| **Env vars (LLM keys, DB password) + adapter identity material on disk in protected directories** (recommended) | Matches `spec/security.md` §Secrets handling commitment ("LLM API keys are read from environment variables, not the DB"). Operator runbook documents systemd `EnvironmentFile=` and Docker Compose `env_file:`. |
| HashiCorp Vault integration | Operator-side concern; out of v1 scope. |
| Kubernetes Secrets | Tied to deployment-target choice; out of v1 scope. |

**Recommendation**: **Plain env vars + filesystem permissions for adapter identity material**. Reason: exactly what the spec commits to; operator can layer Vault or K8s Secrets later without application code change.

### D-J. Observability baseline

| Option | Trade-off |
|---|---|
| **Quarkus structured-JSON logging + Micrometer with Prometheus registry + opt-in OpenTelemetry tracing** (recommended) | All Quarkus-native; low-overhead; metrics names + alert expressions live in design notes per `spec/llm.md` §Bounded concurrency and observability. JSON logging satisfies the "stage events, request IDs, scope IDs, counts loggable; user-authored prose not loggable" rule (`spec/security.md` §Secrets handling — User-content logging). |
| Plaintext logging + Prometheus only | Smaller dep set; harder to search at scale. |
| ELK / cloud-managed (Datadog, New Relic, …) | Operator-side; remains an operator override. ELK heap (≥ 4 GB) crowds out the bot/LLM/Postgres on `pi`/`vps`; cloud-managed pulls logs across a third-party trust boundary that conflicts with the self-hosted SimpleX/Signal threat model. |

**Recommendation**: **`quarkus-logging-json` + `quarkus-micrometer-registry-prometheus` + `quarkus-opentelemetry` (traces opt-in)**. Reason: covers every spec-mandated observability surface (per-task LLM latency/token counts, eval-stage counters, Stage-2 verdict/infra-failure rates, source-level fetch metrics, throttled-admin-notifier coalescing window, `digest_slots_missed_total`); structured JSON is the only viable option to satisfy D37's "user-authored prose never in non-audit logs" without manual log scrubbing.

This is the **in-bot** surface only. The matching **operator-side** stack — what scrapes `/q/metrics`, ingests the JSON log stream, and routes alerts — is design-tier guidance, not a plan deliverable: `design/07-deployment.md` §7.13.2 nominates **Prometheus + Alertmanager + Grafana + Loki** as the recommended self-hosted default, with §7.12 listing the v1 starter alert set (`LlmDown`, `AdapterDown`, `BootstrapAssetsBroken`, `SignalAdapterAuthFailed`). M1-PR10's scope is unchanged — the bot ships standards-compliant Prometheus metrics and JSON logs that any modern observability stack consumes; picking and deploying the stack is the operator's runbook step, not application code.

---

## 6. Risk register

| # | Risk | Threatened spec / decision | Mitigation |
|---|---|---|---|
| R1 | **Stage 2 LLM throughput cap on `pi` profile** — a burst of Stage 1 hits saturates the local LLM and stalls ingest; back-pressure to outbox grows unbounded. | `spec/security.md` §Failure handling — Stage 2; D22 | Bounded eval queue depth per profile (`spec/llm.md` §Bounded concurrency); throttled admin notify on `Stage2FailureSpike` alert; per-source UNKNOWN auto-disable already protects against quarantine-flood (`spec/security.md` §Re-evaluation job — Per-source UNKNOWN auto-disable). |
| R2 | **`pg_advisory_lock` heartbeat-staleness window** — hard-killed Collector leaves the lock held; a fast restart inside the staleness window fails to acquire. | `spec/architecture.md` §Deployment topology; D41 | Profile-driven heartbeat interval (e.g., 5 s); staleness threshold ≈ 3× interval; documented operator runbook for `pg_advisory_unlock` if needed. |
| R3 | **`pgvector` index cliff on `pi`** — HNSW builds are expensive; IVFFlat works but has lower recall; switching mid-deployment requires a re-embed. | D27, `spec/llm.md` §Embedding pipeline | Spec already commits to per-profile choice (HNSW for `laptop`/`vps`/`remote-llm`, IVFFlat for `pi`); embedding-model identity guard catches accidental cross-model swaps (`spec/llm.md` §Embedding pipeline — Model identity guard). |
| R4 | **Translation cache cross-scope timing side-channel** — a user observing translation latency could infer that another scope translated the same string moments earlier. | `spec/security.md` §What's intentionally NOT in v1 — Translation cache cross-scope timing side-channel; D37 | Spec explicitly accepts this trade-off (presentation prose, hash-keyed); per-scope cache partitioning is a v2 candidate; flag for monitoring if a concrete attack surfaces. |
| R5 | **`java.util.regex` ReDoS reaches the watchdog cap** — the watchdog timeout is the only defense against catastrophic backtracking. A buggy watchdog or a too-generous cap value could stall the eval pipeline. | `spec/security.md` §Ingest pipeline — Stage 1 regex engine commitment | ReDoS unit tests with adversarial corpus (`spec/verification.md` §Stage 1 ReDoS guard); profile-driven cap value; the timeout itself is a Stage 1 infrastructure failure (fail-closed `QUARANTINED`). RE2 swap is a v2 amendment. |
| R6 | **Multi-adapter Provider concurrency concentration on `laptop`** — one Provider runs SimpleX + Signal + InMemory simultaneously; LLM concurrency cap is per-process (D46); slow LLM degrades all adapters together. | D46, `spec/deployment.md` §Topology | Spec acknowledges this is intentional (admin-placement guidance in `spec/security.md` §Per-adapter admin threat profile). Operators with high-availability needs run the heavier `vps` profile. |
| R7 | **`signal-cli` subprocess crash recovery** — if `signal-cli` dies mid-flight, in-flight messages may double-deliver on restart. | `spec/messaging.md` §Failure handling | Bounded transient-retry (3 attempts + permanent escalation); audit-before-effect ensures no privileged action is silently re-applied (`spec/schema.md` §Invariants — 7); per-adapter resilience rule keeps Provider up. |
| R8 | **Last-admin protection trigger correctness under contention** — two simultaneous `/revoke-admin` against different admin rows could both observe pre-state and both succeed under READ COMMITTED. | `spec/schema.md` §Invariants — 2 | Spec mandates serialization (SHARE ROW EXCLUSIVE lock or SELECT FOR UPDATE) inside the trigger body — this is part of the invariant, not optimization. M1 persistence test exercises concurrent-revoke fuzz. |
| R9 | **`new_post` channel high-water-mark advancement bug** — a bug in the compound-cursor CAS could cause posts to be silently skipped or duplicated. | `spec/architecture.md` §Inter-service communication; `spec/schema.md` §Operational — Provider state | Spec's compound cursor `(ready_at, post_id)` advance is in the same transaction as the side effect; CAS UPDATE pattern (`UPDATE … WHERE (cursor_high, cursor_low_kind, cursor_low_id) < (…)`); `verification.md` §LISTEN/NOTIFY catch-up tests no-skip + no-duplicate. |
| R10 | **Group-admin auto-promote race** — two simultaneous first-mention payloads from non-banned non-probation users could both win the slot if the partial unique index is wrong. | `spec/security.md` §Authorization model — Auto-promote race protection; `spec/schema.md` §Invariants — 3 | `INSERT … ON CONFLICT DO NOTHING` against the `one_admin_per_group` partial unique index; loser produces no error and no row; `verification.md` §One-group-admin test asserts exactly one row. |

---

## 7. Open questions (genuine spec / design ambiguities — not stack picks)

These are cases the spec is silent on, contradictory on, or where a design-tier choice carries cross-milestone weight. Each cites the spec / design location that raises it.

1. **SimpleX `user_left_group` capability.** Does SimpleX's protocol expose a native left-group event, or must the SimpleX adapter set `supportsMembershipEvents = false` and rely on permanent-delivery-failure cleanup per `spec/messaging.md` §Failure handling — User left group? `design/06-messaging.md` §6.4.4 is silent; resolve during M8 by inspecting `simplex-chat` JSON output.
2. **Audit-action enum source-of-truth file location.** `spec/schema.md` §Identity and access — Audit log says the closed verb enum lives in design notes; the M2 expansion adds many entries (`RE_EVAL_RELEASED`, `UNBAN_DELETED_PREBAN_ROW`, `INVITE_REVOKED`, `BAN`, `UNBAN`, `GRANT_ADMIN`, `REVOKE_ADMIN`, `PROMOTE_GROUP_ADMIN`, `DEMOTE_GROUP_ADMIN`, `VOUCH`, `QUARANTINE_APPROVE`, `QUARANTINE_REJECT`, `REMOVE_SOURCE`, `SOURCE_ENABLE`, `SOURCE_DISABLE`, `DIGEST_SLOT_MISSED`, `BOOTSTRAP_ADMIN`). Recommend: `org.infochat.core.audit.AuditAction` enum + a Flyway-checked DB CHECK constraint generated from it. Confirm the file location early to avoid divergence.
3. **Nostr WebSocket library choice.** `java.net.http.HttpClient` supports WebSocket natively; alternatives include `nv-websocket-client`, Tyrus, or a Nostr-specific library. `spec/architecture.md` §Ingest SPIs is silent. Design-tier; flag during M6.
4. **`/help` per-command bundle key naming convention.** `spec/commands.md` §Discovery — Bundle composition commits to one key per command for the help line + separate header/footer keys; the exact key naming (e.g., `help.command.summary.short` vs `help.summary`) is design-tier; settle in M0 design rewrite.
5. **`audit_log.action` enum closed-set evolution rule.** `spec/security.md` §Secrets handling commits that **adding** a key-shape pattern to the secrets-redactor catalogue is a design-note edit and **removing** one is a spec amendment (the asymmetry prevents silent weakening). The same asymmetry should apply to `audit_log.action` and the LLM tool-name set, but the spec only states it for the secrets catalogue. Confirm whether the same rule extends to the other closed sets, or document the divergence.
6. **`/help` filtering on partial-permission commands.** Group-admin-only commands like `/group-timezone` should appear in `/help` for a group admin in a group context but not for the same user in DM context. `spec/commands.md` §Discovery — `/help` says context-aware filtering; the exact rule for a bot admin operating from DM (do they see group-admin commands?) is implicit. Resolve in M0 design rewrite.

---

## 8. Recommended next step — Milestone 1 expanded into PR-sized tasks

Each task is one PR-sized unit of work. Order respects technical dependencies (skeleton → schema → SPIs → handlers → end-to-end). Acceptance criterion citations point at the M1 numbered ACs (A1–A18) and the M1 G/W/T scenarios.

### M1-PR1. Maven multi-module skeleton + CI build enforcement

**Scope**: Parent POM; the six modules per the post-M0 DAG (`infochat-core`, `infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter`, `infochat-collector`, `infochat-provider`); enforce-maven plugin or `dependency:analyze` to fail the build if `infochat-collector` declares a dependency on `infochat-messaging-adapter`; `infochat-core` is Quarkus-free; CI pipeline (mvn build + dependency check + module-DAG check). Quarkus 3.33 LTS, JDK 25 toolchain.
**Acceptance**: clean build; `mvn dependency:tree -pl infochat-collector` shows no `messaging-adapter` ancestor; `mvn dependency:tree -pl infochat-core` shows no Quarkus core artifact.
**Scenarios it satisfies**: build-only; no behaviour scenarios.
**Decision dependencies**: D-A (persistence), D-B (Flyway), D-C (module layout — settled).

### M1-PR2. Schema migrations + DB role grants + load-bearing triggers and procedures

**Scope**: Flyway migrations for the M1 entity set listed in §4 Milestone 1 Schema entities (User with `(adapter, contact_id)` composite key + `registration_state` enum + `probation_until`; Invite code with conditional UPDATE consume + per-adapter PENDING caps; Audit log + `audit_log_view` redacted view; Source with `(kind, identifier)` unique key + status state machine + `deleted_at`; Source subscription; Tag; Post incl. UID derivation pre-Stage-1 + status state machine + per-stage flag bitmap; Quarantine state machine incl. `BENIGN_CLOSED`; Saved post; Scope preferences; Summary anchor with two partial unique indexes; Chat memory; Chat session; Provider state per-channel singleton with compound cursor + CAS UPDATE; Stored procedures `approve_quarantine` / `reject_quarantine`); three Postgres roles (`infochat_collector`, `infochat_provider`, `infochat_admin`) with the grant matrix from `spec/security.md` §DB roles; last-admin protection trigger on UPDATE + DELETE paths with serialization lock; `one_admin_per_group` partial unique index.
**Acceptance**: A1 (subset — Flyway idempotency, dual-startup race), A11 (audit-log append-only), A12 (scope discriminator structurally present), A17 (DB roles).
**Scenarios**: M1 G/W/T "Last-admin protection under concurrent revoke"; M1 G/W/T "Audit-before-effect interruption proof".
**Decision dependencies**: D-A, D-B.

### M1-PR3. `infochat-core` foundations

**Scope**: Value types (`Scope`, `ScopeId`, `ScopeKind`, `ContactId`, `AdapterId`, `PostUid`); enums (`CommandName` for the M1 subset, `CommandTier`, `ProbationPolicy`, `ModelTask`, `Capability`, `TrustLevel`, `AuditAction`, `LocaleBundleKey`); error-code constants per `design/09-reference.md` §9.2; UID derivation algorithm per `spec/schema.md` §UID derivation. No Quarkus, no I/O.
**Acceptance**: A1 (subset — UID derivation), A12 (scope discriminator types).
**Scenarios**: pure-Java unit tests for UID derivation against fixtures from RSS / synthetic stream sources.
**Decision dependencies**: none (stack-agnostic).

### M1-PR4. `infochat-ssrf` shared module

**Scope**: `SsrfGuard` SPI + impl; allowlist/blocklist matcher (RFC1918, loopback, link-local, multicast, CGNAT, host-own interfaces, cloud metadata `169.254.169.254`); DNS re-resolve after every redirect (TOCTOU defense); redirect cap; body-size cap; per-redirect IP recheck; transport-agnostic so `wss://` connections share the gate; per-reconnect re-validation hook for StreamSource (peer-IP change is a hard close).
**Acceptance**: A16.
**Scenarios**: SSRF blocks each blocked range; redirect to blocked range mid-fetch; `wss://` URL whose hostname resolves to blocked range refused before TCP connect.
**Decision dependencies**: D-G (test infra — WireMock fixtures for redirect chains).

### M1-PR5. `infochat-messaging-adapter` SPI + InMemory adapter + capability validator

**Scope**: `MessagingAdapter` SPI (`receive`, `send` returning opaque handle, `update`, `finalize`, `setTyping`); `AdapterCapabilities` record carrying the closed flag set (`trustLevel`, `supportsCodeFormatting`, `supportsMarkdownLinks`, `supportsMessageEdit`, `minEditInterval`, `supportsTypingIndicator`, `supportsMentionByContactId`, `supportsMembershipEvents`); message-handle contract documented (in-memory only, single-request lifecycle); InMemory adapter (default `trustLevel = LOW`; tests opt into HIGH); capability-flag startup validator (Provider exits non-zero on `supportsMarkdownLinks = true`).
**Acceptance**: A7, A12 (subset — InMemory exercises both `supportsMessageEdit` true/false in tests).
**Scenarios**: M1 G/W/T "Capability flag startup invariant"; "Identity is contact id, not display name"; "Low-trust adapter rejected without opt-in".
**Decision dependencies**: D-G.

### M1-PR6. `infochat-llm-adapter` SPI + OpenAI-compatible provider + LLM output sanitizer + localization bundle

**Scope**: `LlmProvider`, `EmbeddingProvider`, `TranslationProvider` SPIs; `LlmCallContext` (trace id, scope id, task, language); `LlmRouter` resolving `(ModelTask, scope_language)` → `LlmProvider`; `OpenAiCompatibleLlmProvider` covering Ollama (M1) — chat completion + structured-output classification; `EnglishPassthroughTranslationProvider`; `OllamaEmbeddingProvider` with model-identity guard (singleton metadata row); `LlmOutputSanitizer` with match-set derived from the closed bot-admin + group-admin tier list; localization bundle skeleton (`en` registry + `cs` translation); CI check that fails on any missing key in any shipped bundle and a startup error if a key is missing in `en`; pipeline order (LLM prose → sanitizer #1 → translation (skip if `en`) → sanitizer #2 → translation cache write keyed by `(hash(post-sanitizer-1 English), target_language)` → adapter delivery).
**Acceptance**: A13, A14, A15.
**Scenarios**: M1 G/W/T "Source bodies never translated"; "Localization bundle completeness"; "Tool surface registry exact match"; "ReDoS watchdog trip" (Stage 1 watchdog wired here for the regex set, but the regex set itself ships in PR7).
**Decision dependencies**: D-G, D-J.

### M1-PR7. `infochat-collector` ingest pipeline (RSS, Stage 1, Stage 2 stub-able, tagger, embedding, outbox, NOTIFY, advisory lock)

**Scope**: `BootstrapLoader` (idempotent on `(kind, identifier)`; tag-vocabulary union); `Fetcher` SPI; `RssFetcher` impl; `FetchScheduler` (per-kind interval; pagination cap honored); `OutboxRehydrator` (resumes from per-stage flag bitmap); Stage 1 implementation (HTML allowlist sanitizer; NFKC + bidi + zero-width strip; `java.util.regex` regex set with per-input wall-clock watchdog; `[REDACTED:<id>]` placeholder with random per-row id); Stage 2 invocation (LLM judge — stub-able fake LLM in tests); Tagger with bootstrap-tags fallback on retry-exhaustion; Embedding worker (one retry; release without vector on failure); READY transition; `NewPostNotifier` publishing the `(ready_at, post_id)` cursor; `pg_advisory_lock('infochat.collector')` acquisition with heartbeat row.
**Acceptance**: A1 (full), A5, A6 (subset — `new_post` cursor advance + first-boot insert race is in PR9 on the listener side).
**Scenarios**: M1 G/W/T "/summary on the happy path" (post path); "/summary determinism boundary"; "/summary Stage 2 down → degraded fallback"; "Tagger retry exhausted → bootstrap-tag fallback"; "Embedding failure → release without vector"; "ReDoS watchdog trip → fail-closed quarantine"; outbox rehydrator after Collector kill (`spec/verification.md` §Architecture — Outbox rehydrator).
**Decision dependencies**: D-A, D-G.

### M1-PR8. `infochat-provider` intake + invite-code service + probation + audit + bootstrap admin + advisory lock

**Scope**: `MessageIntake` enforcing auth steps 1, 1.5, 1.7, 2, 4, 6, 7, 8, 9 from `spec/security.md` §Authorization model in order; `InviteCodeService` (D44 — CONTACT_BOUND + OPEN_ADAPTER; conditional UPDATE consume; cross-adapter isolation; pre-banned-contact rejection; per-adapter PENDING caps); `InviteCodeRateLimiter` (per-`(adapter, contact_id)`); `ProbationService` (D45 — lazy check `probation_until IS NULL OR probation_until < NOW()`; passive sweep on next request; allow-list classifier derived from the closed privileged-tier list); `AuditWriter` (audit-before-effect, INSERT-only on `audit_log`, zero-row `/forget` carve-out); `BootstrapAdmin` (per-adapter optional contact id; union-non-empty constraint at startup; bootstrap-seeded admin row with `is_admin = true`, `is_banned = false`, `probation_until = NULL`, `registration_state = 'vouched'`; audit row with `details_json.cause = 'bootstrap'`); `BootstrapLoader` invocation; `pg_advisory_lock('infochat.provider')` acquisition with heartbeat row.
**Acceptance**: A1 (full), A2, A3, A4, A11.
**Scenarios**: M1 G/W/T "Onboarding — happy path"; "Onboarding — banned (intake-blocked)"; "Onboarding — no-invite DM (D44)"; "Slow-start probation user (D45)"; "Audit-before-effect interruption proof".
**Decision dependencies**: D-A, D-D / D-E (for the eventual production adapters' bootstrap-admin paths — InMemory exercises the same flow in M1).

### M1-PR9. `infochat-provider` command set + ProgressNotifier + `/stop` + `/forget` + `/lang` + `/clear` + `/compress` + `new_post` listener / reconciler

**Scope**: `CommandRouter` with parsers for the M1 command set (`/help`, `/status`, `/get-tags`, `/get-sources`, `/list-sources` (own scope), `/summary`, `/saved`, `/add-source` (DM, scope-restricted), `/lang` (DM), `/clear`, `/compress`, `/stop`, `/forget`, `/quarantine list` is M2 — stub here for permission-matrix completeness); `ConfirmationService` (in-memory state machine with profile-tunable timeout; cancellation by any non-`confirm` input including `/stop`); `ProgressNotifier` emitting `STARTED`, `RETRIEVING`, `GENERATING`, `TRANSLATING`, `FINALIZING`; localization-bundle stage strings only; never user-input interpolation; coalescing edits per `minEditInterval`; try/finally finalize guarantee; `NewPostListener` (`LISTEN new_post`); `NewPostReconciler` (high-water-mark catch-up using `provider_state` per-channel row with compound cursor `(ready_at, post_id)` and CAS UPDATE); `SummaryCommand` (deterministic SQL; cluster computation by SQL traversal of `post_reference` (empty graph in M1 since linking lands in M2); writes `summary_anchor` row with `command_kind = 'personal'`; runs prose generation through the M1-PR6 pipeline order); `CancellationRegistry` + `StopCommand` (registers DB backend pid; `pg_cancel_backend(pid)` on stop; per-tool `statement_timeout` safety net; idempotent friendly reply when nothing in flight); `ForgetCommand` (audit-before-effect with counts only; remaining-scopes disclosure when relevant; idempotent zero-row no-op carve-out); `LangCommand`.
**Acceptance**: A6 (full), A8, A9, A10.
**Scenarios**: M1 G/W/T "/summary on the happy path"; "/summary — same input → same cluster set across runs"; "/stop cancels mid-stream chat reply"; "/forget on the calling scope"; "NOTIFY missed during Provider downtime"; "Scope-discriminator structural support".
**Decision dependencies**: D-A, D-J (ProgressNotifier metric shape).

### M1-PR10. End-to-end smoke + docker-compose + observability baseline + operator runbook + one-click scripts

**Scope**: `docker-compose.yml` (Postgres+pgvector, Ollama or fake-LLM container, Collector, Provider, InMemory adapter wired); deterministic transcript test exercising the eight reinforced MVP exit criteria (per A18) — invite acceptance, /help filtering, /add-source, fetch tick → READY post, /summary deterministic clusters with prose, /forget purge, ReDoS quarantine, outbox rehydrator after Collector kill; structured-JSON logging via `quarkus-logging-json`; basic Micrometer metrics (`eval.stage1.flagged`, `eval.stage2.verdict{result}`, `eval.stage2.infra_failures`, `llm.calls.total{task,provider,outcome}`, `notify.new_post.published`, `provider_state.cursor.advanced{channel}`, `chat_memory.pruned`, `intake.banned.dropped`, `invite_code.consumed`); operator runbook (markdown) for first-boot (env vars, bootstrap-sources.json shape, bootstrap admin per adapter); `audit_log_view` redaction validated against the secrets catalogue; the `scripts/` one-click wrapper set committed in `design/07-deployment.md` §7.7.1 (`build.sh`, `dev.sh`, `run-collector.sh`, `run-provider.sh`, `down.sh`, `backup.sh`, `reembed.sh`) — each script `set -euo pipefail`, echoes the wrapped command, returns the wrapped command's exit code, and supports `--help`.
**Acceptance**: A18.
**Scenarios**: M1 G/W/T "Onboarding — happy path" (end-to-end variant); "/summary on the happy path" (end-to-end variant); "NOTIFY missed during Provider downtime" (end-to-end via Postgres restart).
**Decision dependencies**: D-F, D-H, D-I, D-J.

---

### M1 verification end-to-end

The M1 acceptance criteria above each map to a `spec/verification.md` invariant. The end-to-end smoke (M1-PR10) is the gate for declaring M1 complete. Subsequent milestones extend the same four test layers (unit / persistence-Testcontainers / integration / E2E smoke); the test inventory grows with each milestone but the layer shape does not change.

To run M1's end-to-end after the work above lands (raw form on the left, one-click form via the wrappers from `design/07-deployment.md` §7.7.1 on the right):

```bash
# raw
mvn clean install
docker compose -f docker-compose.yml up -d postgres ollama
mvn -pl infochat-collector quarkus:dev &
mvn -pl infochat-provider quarkus:dev
# in a separate shell: drive the InMemory adapter through the deterministic transcript fixture
mvn -pl infochat-provider verify -Dtest=*EndToEndSmokeIT

# wrappers (equivalent)
scripts/build.sh
scripts/dev.sh                       # brings up compose stack + both services
mvn -pl infochat-provider verify -Dtest=*EndToEndSmokeIT
scripts/down.sh
```

The smoke test asserts the eight exit criteria as a single green/red signal.
