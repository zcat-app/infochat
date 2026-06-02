# Audit-remediation ticket plan & parallelization map

**Date:** 2026-06-02
**Source:** the four consolidation handouts in this directory (`opus-48-handout.md`
is the authoritative all-nine-run superset; the other three corroborate or are
subsets). Every finding dispositioned **FIX / RESOLVE / FIX-LOW / DOC** is ticketed
here; **Tier-D NON-ISSUE / record-only** findings are excluded by design (see
§Excluded below).

This document is the canonical numbered list (M1-121 … M1-162) plus the
parallelization classification the user requested: which tickets can run
**concurrently in independent worktrees** vs which **serialize**.

---

## How to read parallelization

A ticket is not simply "parallel" or "serial". It belongs to a **lane**. Lanes
run **concurrently** (independent worktrees, no shared files). Tickets **within**
a lane **serialize** (they touch the same hot file or the same migration
sequence). Two mechanisms in the existing M1 workflow encode this:

- **`migration_touch: true`** — the `/m1-tick` skill serializes the *start* of
  migration-touching tickets so two branches don't both grab the next Flyway
  version (V30, V31, …). All schema tickets carry it → they form one serialized
  lane (**MIG**) regardless of which module the logic lives in.
- **`blocked_by: [...]`** — a hard ordering dependency (the blocker must be
  `done` first). Used only where a ticket genuinely cannot start until another
  lands (e.g. tickets that delegate to the `JsonEscaper` extracted in M1-133).

### Contention hot-spots (why some same-module tickets still can't parallelize)

- **`InboundRouter.java`** (provider) is edited by reply-target (125), `/stop`
  (138), and the router-hygiene bundle (155, body-cap order + bidi + lookupGroupId).
  → all in lane **PROV-ROUTER**, serialized.
- **`SsrfGuardedHttpClient.java` + `IpBlocklist.java`** — every SSRF finding is
  bundled into **one** ticket (135) precisely so they don't collide.
- **CT1 shared-helper extraction (133)** touches ~12 handler files across
  collector + provider. Tickets that re-touch those same handlers (146 sweep,
  151 last-admin) declare `blocked_by: [M1-133]` so they rebase onto the helper.

---

## Lanes

| Lane | Scope | Concurrency |
|---|---|---|
| **MIG** | Flyway migrations + datasource wiring (any module) | Serialized (migration_touch) |
| **LLM** | `infochat-llm-adapter` only | Independent |
| **SSRF** | `infochat-ssrf` only | Independent |
| **MSG** | `infochat-messaging-adapter` only | Independent (serialize within: shared adapters) |
| **COLL** | `infochat-collector` only (non-migration) | Independent (serialize within where same worker file) |
| **PROV-ROUTER** | provider `InboundRouter.java` / `AdapterRegistry.java` | Serialized |
| **PROV-CMD** | provider command handlers / digest / chat (not InboundRouter) | Independent of PROV-ROUTER; serialize within where same handler |
| **CORE-SHARED** | `infochat-core` extractions consumed by many modules | Land early; dependents `blocked_by` |
| **DOC** | pure documentation / comments | Fully independent |
| **TEST** | test-only cleanup | Fully independent |

---

## The tickets

Legend: **MIG?** = adds a Flyway migration (migration_touch). **Sec?** = security_relevant.
**PW?** = complexity:high → plan-writer pass at start. Findings column uses the
master handout's canonical IDs.

### Priority 0 — blocks production (fix first)

| # | Title | Lane | Findings | MIG? | Sec? | PW? |
|---|---|---|---|---|---|---|
| M1-121 | June+July 2026 partitions + monthly partition-creator | MIG | A1 | ✓ | | |
| M1-122 | `infochat.reeval.*` keys in main config + @ConfigProperty CI guard | COLL | A5 | | | |
| M1-123 | InstanceLockGuard held-session liveness + collector/provider dedup | CORE-SHARED | A9, M1-54 | | | ✓ |

### Priority 1 — security / correctness criticals

| # | Title | Lane | Findings | MIG? | Sec? | PW? |
|---|---|---|---|---|---|---|
| M1-124 | Anthropic header names + test alignment + narrow catch + unused import | LLM | A2, A2b, opt-import | | ✓ | |
| M1-125 | Per-adapter reply target + AdapterRegistry duplicate-name dedup | PROV-ROUTER | A3, dup-name | | ✓ | ✓ |
| M1-126 | Asset-command extensibility (operator-config driven) + Locale.ROOT | PROV-CMD | A6, asset-locale | | | |
| M1-127 | DB per-service role wiring + audit_log_view redaction | MIG | A4, A11 | ✓ | ✓ | ✓ |
| M1-128 | ReEvaluationJob enumerate filter + cap-exhaustion transition + IT | COLL | A7 | | | |
| M1-129 | DigestScheduler approval_status filter + negative-case fixture | PROV-CMD | A14 | | | |
| M1-130 | ReadyPromoter transaction boundary + IT driven through tick() | COLL | A15 | | | |
| M1-131 | ChatAgent Jackson tool-arg parse + dispatcher catch widening + TOOL-LEAK | PROV-CMD | A8, A8b, tool-leak | | ✓ | ✓ |
| M1-132 | Signal/SimpleX adapter resilience (handler isolation, hung-process, config-validate, send/close race) | MSG | A10, signal-hung, signalconfig, simplex-race | | | |

### Priority 2 — security / correctness mediums & highs

| # | Title | Lane | Findings | MIG? | Sec? | PW? |
|---|---|---|---|---|---|---|
| M1-133 | CT1 shared text/util extraction (JsonEscaper + TagNormalizer + Sha256) + TODO cleanup | CORE-SHARED | json-dup, tag-norm-dup, sha256-dup, todos | | | |
| M1-134 | `quarantine_review` NOTIFY channel completeness (CT2) | MIG | A20, A21, emitter-enum, notify-concat, secdef-actor-cols | ✓ | | ✓ |
| M1-135 | SSRF hardening bundle | SSRF | A16, A26, ipv6-canon, ssrf-304, extraheaders, idn-unassigned, http-client, readbounded-exec, deadline-toctou, ssrf-errmsg, urlredactor-ipv6 | | ✓ | |
| M1-136 | local-only startup guard covers embedding endpoint + remote-embedding confirmation log | LLM | A12 | | ✓ | |
| M1-137 | SimpleX mention canonicalization → exact-bytes compare | MSG | A13 | | ✓ | |
| M1-138 | `/stop` group/DM scope fix + `/help` per-tier filtering | PROV-CMD | A17, A18 | | | |
| M1-139 | Kind-6 repost edge resolution | MIG | A19 | ✓ | | ✓ |
| M1-140 | EmbeddingResult value semantics + embedding SPI size-equals-input contract | LLM | A24, A25 | | | |
| M1-141 | LLM adapter robustness (body cap, 429/503 Retry-After) + router decoupling | LLM | llm-oom, llm-retry, llmrouter-instanceof, taskkey-dup, microprofile-null, configFor | | | |
| M1-142 | NewPostListener reconcile after reconnect | PROV-CMD | notify-reconcile | | | |
| M1-143 | MembershipEventHandler audit-before-effect (Invariant 7) | PROV-CMD | membership-audit | | ✓ | |
| M1-144 | UserRepository extraction + `/promote` FOR UPDATE | PROV-CMD | lookup-dup, promote-forupdate | | ✓ | |
| M1-145 | `/save` personal-tag length + count caps | PROV-CMD | save-unbounded | | ✓ | |
| M1-146 | JSpecify annotation pass + lint-contracts CI + defensive-code sweep (CT4) | CROSS | jspecify-missing, defensive-code | | | |

### Priority 3 — small hardening, capability/contract, hygiene, docs

| # | Title | Lane | Findings | MIG? | Sec? | PW? |
|---|---|---|---|---|---|---|
| M1-147 | Adapter capability-flag reconciliation + cross-adapter contract test (CT5) | MSG | capability-drift, adapter-classify, codec-exc, inmemory-codeformatting, typing-indicator | | | ✓ |
| M1-148 | MessagingAdapter SPI lifecycle (finalize→shutdown, start/stop) + low-level cleanup | MSG | finalize-shadow, spi-lifecycle, membership-spi(reconcile), simplexconfig-lifecycle, adapter-backoff, signal-drain, simplex-handle-table, findfirststring | | | |
| M1-149 | Fetcher pagination cursor URL-encoding | COLL | fetcher-urlencode | | ✓ | |
| M1-150 | Digest hygiene (concurrency guard, tz WARN, broad-catch narrow) | PROV-CMD | digest-concurrency, digest-tzlog, digestworker-catch | | | |
| M1-151 | Typed SSRF / error signals (UrlProbe + last-admin SQLSTATE) | PROV-CMD | urlprobe-msg, lastadmin-msg | | | |
| M1-152 | Schema-hardening migration (stage2_verdict CHECK + V27 audit verb + Nostr composite index) | MIG | stage2-check, v27-audit-verb, nostr-index | ✓ | | |
| M1-153 | Collector worker hygiene (dead semaphores, acquireUninterruptibly, backoff Random, AssetSnapshotFetcher dup, HttpClient timeouts) | COLL | dead-semaphore, acquire-int, nostr-backoff-random, assetfetcher-dup, httpclient-notimeout | | | |
| M1-154 | Provider chat/sanitizer hygiene (pattern caching, closed-list whitespace, dispatcher completeness) | PROV-CMD | sanitizer-perf, closedlist-ws, chattool-completeness | | | |
| M1-155 | InboundRouter hygiene (chat body-cap ordering, bidi-control gap, lookupGroupId Optional) | PROV-ROUTER | bodycap-order, bidi-gap, grouplookup-throw | | | |
| M1-156 | Misc security-low hardening (Redactor separator, invite per-code counter, AddSource userinfo reject) | PROV-CMD | redactor-sep, invite-counter, userinfo-src | | ✓ | |
| M1-157 | Explicit connection-pool sizing per profile | DOC/CFG | conn-pool-size | | | |
| M1-158 | Documentation / stale-comment sweep (CT3) | DOC | ssrf-javadoc, dag-doc, migration-comments, lang-javadoc, dispatchkey-javadoc, normalizedpost-javadoc | | | |
| M1-159 | Test-debt (inner-class extraction, truncateAll completeness, delete IngestSpisLoadTest) | TEST | test-innerclass, truncateall, ingestspis-test | | | |

### Design-dependent — skeleton "investigate & decide" tickets

| # | Title | Lane | Findings | MIG? |
|---|---|---|---|---|
| M1-160 | [INVESTIGATE] summary_anchor scope_kind discriminator | MIG? | summaryanchor-scope | maybe |
| M1-161 | [INVESTIGATE] price_snapshot PK/dedup invariant + new_price_snapshot channel intent | MIG? | price-schema, price-notify-orphan | maybe |
| M1-162 | [INVESTIGATE] confirm-or-drop adapter SPI surfaces vs D47 (onMembershipEvent, Signal group path, ProgressNotifier) | MSG | membership-spi, signal-group-dup, progress-notifier | |

---

## Wave schedule (concurrent execution)

Each **wave** is a set of tickets that can be in-flight simultaneously in
separate worktrees. A later wave starts when its `blocked_by` predecessors are
done. Within the MIG lane, only **one** migration ticket is started at a time
(the workflow enforces this via `migration_touch`).

```
WAVE 1  (all independent, max parallelism)
  MIG          : M1-121 partitions
  COLL         : M1-122 reeval config        | M1-128 reeval-job filter | M1-130 readypromoter
  LLM          : M1-124 anthropic            | M1-136 local-only guard  | M1-140 embedding | M1-141 llm robustness
  SSRF         : M1-135 ssrf bundle
  MSG          : M1-132 signal/simplex resil | M1-137 simplex mention
  PROV-ROUTER  : M1-125 reply-target
  PROV-CMD     : M1-126 asset ext | M1-129 digest filter | M1-145 /save caps
  CORE-SHARED  : M1-123 lock liveness | M1-133 CT1 shared util

WAVE 2  (after their MIG predecessor / CORE-SHARED lands)
  MIG          : M1-127 db roles (after 121) → M1-134 quarantine NOTIFY → M1-139 kind-6 → M1-152 schema-hardening
  PROV-ROUTER  : M1-138 /stop+/help (after 125) → M1-155 router hygiene
  PROV-CMD     : M1-131 chatagent tools | M1-142 notify-reconcile | M1-143 membership audit |
                 M1-144 userrepo+promote (blocked_by 133) | M1-150 digest hygiene | M1-154 chat hygiene |
                 M1-156 misc sec-low
  MSG          : M1-147 capability/contract | M1-148 SPI lifecycle
  COLL         : M1-149 fetcher urlencode | M1-153 worker hygiene
  CROSS        : M1-146 jspecify+defensive sweep (blocked_by 133)

WAVE 3  (cleanup / docs — anytime, fully independent)
  DOC          : M1-157 pool sizing | M1-158 doc sweep
  TEST         : M1-159 test-debt
  MIG/design   : M1-160, M1-161, M1-162 investigate-skeletons (sequence into MIG lane once intent decided)
```

### Migration lane order (single-file-at-a-time, version assigned at start)

`M1-121` → `M1-127` → `M1-134` → `M1-139` → `M1-152` → (`M1-160`/`M1-161` once
their investigation decides a migration is needed). Each grabs the next free
`V<NNN>` at start time; do **not** hardcode the number in the ticket — the
workflow assigns it so parallel branches never collide.

---

## Excluded (Tier-D — recorded, not ticketed)

Per the handouts' adjudication, these are NON-ISSUE / record-only and get **no**
ticket. Reasons preserved so they are not re-raised:

- **SETLOCAL-SQLI** — `actor.id` is a `UUID`; `SET LOCAL` rejects bind params; the
  proposed fix doesn't work; hardening an impossible internal path violates §7.
- **UTF8-CAP** — both the "surrogate bypass" and "SIOOBE" claims are arithmetically
  false (Java encodes a lone surrogate as 1 byte `?`; the for-condition guards `charAt`).
- **PINNED-CLOSE** — `close()` is single-shot with one try-with-resources caller; a
  guard would be defensive code for an impossible scenario.
- **TREEMAP-ALLOC** — the report walks itself back; TreeMap is the correct deterministic
  cache key.
- **DUP-MSG-DEP** — the "duplicate" pom entry is the `test-jar` classifier (canonical idiom).
- **STAGE1-BACKTRACK** — mitigated by the Stage-1 wall-clock watchdog.
- **CIRCUIT-BREAKERS** — D42 failure-counter is the v1 mechanism; adding a library is a v2
  decision + a dependency-approval matter.
- **CONN-CHURN** (structural refactor) — deliberate per-step isolation for the fresh-ban-check
  TOCTOU closure; WATCH only. (Pool sizing is the actionable part → M1-157.)
- **MISSING-V20** — Flyway tolerates version gaps; at most a one-line doc note (folded into M1-158).
- **BOOTSTRAP-PATH-TRAVERSAL** — operator-supplied config path is not a privilege boundary.
- **Perf tail** (FetchScheduler/DigestScheduler unbounded selects, NostrDedupFilter size,
  Levenshtein recompute) — WATCH; no current symptom at v1 cadence.
- **AUDIT-INSERT-DUP** — already tracked under the existing M1-041 `AuditLogWriter` deferral.
- **Stage1WatchdogIT 50ms flake** — tracked in project memory, not an audit finding.
