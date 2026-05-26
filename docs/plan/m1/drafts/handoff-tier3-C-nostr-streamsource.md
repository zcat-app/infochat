# Session handoff — Tier 3 Group C: Nostr StreamSource

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T3-C ticket file and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0, Tier 1, and Tier 2 implementation tickets are done and
  merged on main. T3-A (production adapters) and T3-B (polled
  fetchers) may or may not be done — T3-C has NO dependency on them.
- The StreamSource SPI (M1-007a), collector outbox (M1-028), bootstrap-
  sources loader (M1-022), and FetchScheduler are on main.
- There is NO existing StreamSource implementation — T3-C is the first.
- Deferred: M1-019, M1-020, M1-021, M1-031, M1-034, M1-042.
- Branch is main, otherwise clean.

**Verify at authoring time:**

  - Next free ticket ID:
    `ls docs/plan/m1/tickets/ | sort -V | tail`
  - StreamSource SPI shape:
    `cat infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java`
  - NormalizedPost shape:
    `cat infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java`
  - Outbox write path (how Fetcher results get persisted):
    `find . -name "*Outbox*" -path "*/main/*" | head -5`
  - Bootstrap source loader (Nostr entries use kind='nostr',
    config={relays:[...]}):
    `find . -name "BootstrapSources*" -path "*/main/*" | head -5`
  - SsrfGuardedHttpClient (WebSocket connections also route through
    SSRF guard):
    `find . -name "SsrfGuarded*" -path "*/main/*" | head -5`
  - Source kind values in existing migrations/bootstrap:
    `grep -rn "nostr\|kind" infochat-core/src/main/resources/db/migration/ | head -20`
  - Supervised-worker infrastructure (if any exists from asset fetcher):
    `find . -name "*Supervisor*" -o -name "*StreamSource*" | grep -v target | grep main`
  - ThrottledAdminNotifier location (for all-relays-bad notification):
    `find . -name "ThrottledAdminNotifier*" -path "*/main/*"`

## What T3-C creates

One ticket: the Nostr StreamSource implementation. This is the most
complex single ticket in Tier 3 because StreamSource has connection
lifecycle, per-relay degradation, cross-relay dedup, signature
verification, and drain-on-shutdown — none of which the polled
Fetcher SPI requires.

### StreamSource SPI (brief-time shape)

  StreamSource.start(long sourceId, String filterSpec, Consumer<NormalizedPost> deliver)
  StreamSource.stop()

- `start`: opens the subscription, starts background workers that push
  posts through `deliver` as they arrive. Returns once subscription is
  established (does NOT block while events flow).
- `stop`: tears down connections, drains in-flight events to the
  outbox, releases resources. After `stop()` returns, no further
  delivery callbacks may fire.

### NostrStreamSource responsibilities

Per `docs/spec/security.md` §Per-source trust boundaries — Nostr
and `docs/spec/architecture.md` §Ingest SPIs (StreamSource portion):

1. **WebSocket connections to Nostr relays.** Per-relay `wss://`
   connection via infochat-ssrf (SSRF guard applies to outbound
   WebSocket too). The relay set is configured via bootstrap-
   sources.json `config.relays` array.

2. **NIP-01 protocol.** Send `REQ` with the source's filter spec on
   connect; receive `EVENT` messages; `EOSE` signals end of stored
   events. Parse JSON frames.

3. **Signature verification.** Every received event MUST pass
   secp256k1 signature verification against its claimed pubkey BEFORE
   reaching Stage 1. Failed verification → drop + increment counter.
   Never enqueue, never release as READY. No admin notification per
   failure (hostile relay can produce many) — the counter is the audit
   surface.

4. **Kind allowlist.** Only kinds 1 (text notes) and 6 (reposts)
   are parsed in v1. All other kinds dropped without parsing.
   Ordering at the trust boundary: signature verification → kind
   allowlist → outbox write.

5. **Kind-6 repost handling.**
   - Non-empty `content` field: store commentary as post body; write
     `post_reference` edge with `link_type='repost'` keyed by
     `upstream_identifier` (the original event's SHA-256 id).
   - Empty `content`: store empty body; write `post_reference` edge.
   - Reference to disallowed-kind original: write edge only (no
     content about the original is revealed).

6. **Cross-relay dedup.** Same event from N relays → one `posts` row.
   Dedup by stable upstream id (Nostr event id = SHA-256 hash of
   canonical JSON) BEFORE enqueue. The implementation owns dedup,
   not the outbox.

7. **Per-relay degradation.** A misbehaving relay (slow, spamming,
   repeatedly disconnecting, malformed events) is marked unusable for
   a cooldown window; the StreamSource continues on remaining relays.
   Cooldown values are profile-driven (design §1.4.4):
   - Per-relay cooldown initial: 60s (all profiles)
   - Per-relay cooldown max (exp backoff): 30m
   - All-relays-bad cycle cap → terminal `failed`: laptop=20, vps=10,
     pi=5, remote-llm=20
   - Graceful shutdown drain timeout: laptop/vps/remote-llm=10s, pi=5s

8. **All relays in cooldown.** Wait until the earliest cooldown
   expires (not tight-loop). One admin notification per all-relays-bad
   transition (throttled per `(channel, error_class)`). Recovery
   counterpart fires when first relay returns healthy.

9. **Absolute cycle cap → terminal failed state.** After cap
   consecutive all-relays-bad cycles, the StreamSource stops
   reconnecting entirely. One-time admin notification distinct from
   per-cycle throttled notification. Operator must
   `/source-enable <id>` to restart.

10. **Drain on shutdown.** On graceful shutdown, flush in-flight
    events to the outbox before acknowledging shutdown. Events not
    drained within the profile-driven timeout are dropped (NOT
    guaranteed to reappear — Nostr relays don't universally replay).
    Per-relay "events lost" counter exposed via metrics.

11. **`since=last_persisted_event_at` on reconnect.** Issued per
    relay; relays supporting `since` filters replay missed events;
    those that don't may produce gaps.

12. **Asynchronous startup.** StreamSource registration is async —
    a relay unreachable at boot does NOT fail Collector startup or
    readiness. Readiness goes healthy when the scheduler accepts the
    registration, not when every relay connects.

13. **Canonical filter-spec identifier.** Nostr filter-spec
    identifiers are canonicalized before unique-key comparison: JSON
    keys sorted lexicographically, compact JSON (no extra whitespace).
    Applied at bootstrap load time and `/add-source` parse time.

14. **Forever read-only.** The Collector never holds a Nostr private
    key, never signs, never publishes.

### Supervised-worker pattern

The design doc §1.4.4 describes a `StreamSourceSupervisor` at
@Startup @Priority(450). This bean does NOT exist on disk (confirmed).
T3-C creates it. It registers after FetchScheduler (400) and before
any future higher-priority beans.

### SSRF-guarded WebSocket transport (REQUIRED — does not exist)

`SsrfGuardedHttpClient` explicitly rejects `ws://` and `wss://`
schemes (line 40: "deliberately rejected for now: the WebSocket
transport wrapper for StreamSource consumes the same IpBlocklist
policy class but is its own implementation"). T3-C must build a
separate SSRF-guarded WebSocket client that reuses `IpBlocklist`
from infochat-ssrf. This is a non-trivial scope addition. Evaluate
at authoring time whether to:
  - (a) Fold it into the T3-C ticket (increases file count)
  - (b) Create a separate prerequisite ticket in infochat-ssrf

### `post_reference` table (DOES NOT EXIST — migration required)

The `post_reference` table needed for kind-6 repost linking does NOT
exist on disk (confirmed). V7 carries only a comment placeholder:
"tickets land Tier-2 derivatives (post_reference, post_embedding,
...)". `GetReferencesTool.java` says: "post_reference table is
v2-deferred (no migration exists)."

T3-C must create a Flyway migration for `post_reference`. Set
`migration_touch: true` in the ticket frontmatter. Columns needed
(per spec §Kind-6 cross-source linking): `from_post_uid`,
`link_type` (e.g. `'repost'`), `upstream_identifier` (the original
event id — SHA-256 hash, used as the join key), `created_at`.
DB-role grants: Collector INSERT, Provider SELECT.

### NormalizedPost field mapping for Nostr events

  - `upstreamIdentifier` → Nostr event id (SHA-256 hash of canonical
    event JSON)
  - `body` → event `content` field
  - `publishedAt` → event `created_at` (Unix timestamp → Instant)
  - `fetchedAt` → capture time at delivery
  - `rawMetadata` → `{"pubkey": "...", "kind": N, "tags": [...],
    "sig": "..."}`
  - `url` → null (Nostr events have no URL)
  - `title` → null

### What T3-C does NOT create

  - No changes to StreamSource SPI (the two-method shape is sufficient).
  - No changes to existing Fetcher implementations.
  - No changes to the outbox (StreamSource uses the same outbox path
    as Fetcher per spec — the `deliver` callback hands NormalizedPost
    to PostPersister → EvalQueueProducer, same as FetchScheduler).
  - No adapter or LLM work.

## Key seams in the current code

### StreamSource SPI

Location: `infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java`

Methods:
- `start(long sourceId, String filterSpec, Consumer<NormalizedPost> deliver)`
- `stop()`

### Outbox write path

Location: find via `find . -name "*Outbox*" -path "*/main/*"`

- Posts are persisted with `status='RAW'` before being enqueued
- Startup rehydrator re-enqueues unfinished work (status='RAW' +
  per-stage done flags)
- StreamSource's `deliver` callback hands NormalizedPost to the same
  outbox path as Fetcher results

### post_reference table (DOES NOT EXIST)

The table does NOT exist — see §"post_reference table" above. T3-C's
migration creates it. Kind-6 reposts write
`post_reference(from_post_uid, link_type='repost',
upstream_identifier=<original event id>)`. The `upstream_identifier`
column carries the SHA-256 event id for cross-source linking.

### ThrottledAdminNotifier

Location: verify at authoring time. Used for:
- All-relays-bad transition notifications
- Terminal-failed-state one-time notification
- Per-relay degradation recovery notification

### SsrfGuardedHttpClient (HTTP only — no WebSocket)

`SsrfGuardedHttpClient` handles HTTP `GET`/`HEAD` only. WebSocket
(`ws://`, `wss://`) is explicitly rejected (line 40). The `IpBlocklist`
policy class IS reusable — T3-C builds a WebSocket transport that
consumes the same blocklist. See §"SSRF-guarded WebSocket transport"
above.

## Spec sections T3-C cites

- `docs/spec/architecture.md` §Ingest SPIs (line 183) — StreamSource
  shape, async startup, per-relay degradation, all-relays-bad, cycle
  cap, drain on shutdown, source identity, cross-source dedup,
  kind-6 linking
- `docs/spec/security.md` §Per-source trust boundaries — Nostr
  (line 165) — signature verification, kind allowlist, repost handling,
  operator-configured relay list, forever-read-only
- `docs/spec/security.md` §SSRF and outbound connections (line 120)
- `docs/spec/verification.md` — StreamSource reconnect, cross-relay
  dedup, drain, signature verification, kind filter items
- `docs/design/01-architecture.md` §1.3.2 (StreamSource flow), §1.4.4
  (supervised-worker lifecycle, per-profile numeric values)

## Recommended ticket structure

**Single ticket.** The session-grouping-plan estimates 1 ticket.
Evaluate file count at authoring time:

  - SsrfGuardedWebSocketClient.java (SSRF-guarded WS transport,
    reusing IpBlocklist from infochat-ssrf)
  - NostrStreamSource.java (main impl)
  - NostrEventParser.java (NIP-01 JSON → NormalizedPost)
  - NostrSignatureVerifier.java (secp256k1 verification)
  - NostrRelayConnection.java (per-relay WebSocket lifecycle)
  - NostrDeduplifier.java (cross-relay event-id dedup)
  - NostrFilterSpecCanonicalizer.java (JSON key-sort + compact)
  - StreamSourceSupervisor.java (DOES NOT exist — T3-C creates)
  - V??__post_reference.sql (new migration: post_reference table +
    role grants)
  - Config / properties additions
  - Test: NostrStreamSourceTest.java (unit: parser, verifier, dedup)
  - Test: NostrStreamSourceIT.java (integration: fake-relay fixture)
  - Test fixture: canned Nostr event JSON files

  Estimate: 13-17 files. This will exceed 12, so split along the
  connection-management vs event-processing seam:
  - T3-C.1: SSRF-guarded WebSocket client + relay connection +
    reconnection + degradation + cooldown + cycle cap + drain +
    StreamSourceSupervisor + V??__post_reference.sql migration
  - T3-C.2: event parsing + signature verification + kind filter +
    dedup + kind-6 repost handling + post_reference write +
    filter-spec canonicalizer

  The single-ticket shape is unlikely given the WebSocket transport
  and migration. Recommend umbrella+subs from the start.

  - complexity: high (connection lifecycle, crypto verification,
    per-relay state machine, cross-relay dedup, new SSRF-WS transport)
  - risk: high (first StreamSource impl, crypto dependency,
    WebSocket SSRF surface, new migration)
  - security_relevant: true (signature verification is a trust boundary)

## Dependencies

- Depends on Tier 2 completion. For `blocked_by`, use the last done
  M1 ticket at authoring time (verify via
  `ls docs/plan/m1/tickets/ | sort -V | tail`).
- Independent of T3-A, T3-B, T3-D.
- If `/source-enable <id>` command is needed for the terminal-failed
  restart path, verify it exists on disk (M1-053, source-management
  admin commands, should be done).
- Profile-driven values use Quarkus config profiles:
  `%laptop.infochat.nostr.cooldown-initial=60s`,
  `%pi.infochat.nostr.cycle-cap=5`, etc.

## Design-vs-spec drift notes

1. **secp256k1 library.** The spec requires signature verification but
   names no Java library. Options: Bouncy Castle (`bcprov-jdk18on`
   has secp256k1 support), or `nostr-java` if a lightweight NIP-01
   library exists. The authoring session should specify the library in
   the ticket's acceptance criteria or `files_scope`.

2. **WebSocket SSRF transport does NOT exist** (confirmed).
   SsrfGuardedHttpClient explicitly rejects `ws://`/`wss://`.
   T3-C builds a separate WebSocket transport reusing `IpBlocklist`.
   Scope it as a distinct acceptance item.

3. **`post_reference` table does NOT exist** (confirmed). T3-C
   creates it via a new Flyway migration. Set `migration_touch: true`.

4. **StreamSourceSupervisor does NOT exist** (confirmed). T3-C
   creates it at @Startup @Priority(450).

5. **`since` filter on reconnect.** The implementation needs to query
   `SELECT MAX(fetched_at) FROM post WHERE source_id = :sourceId` to
   get `last_persisted_event_at`. This is a Collector-side query; no
   Provider involvement.

## Existing tests to not break

- All fetcher tests (RssFetcherTest, any T3-B fetcher tests if done)
- Stage1WatchdogIT (known marginal, unrelated)
- Full `mvn verify` from repo root

## Task

Author the T3-C ticket files in `docs/plan/m1/tickets/`. Given the
file count (13-17), the umbrella+subs pattern is recommended: one
umbrella (integration IT) + two subtickets (T3-C.1 connection/infra,
T3-C.2 event-processing/crypto). Follow the ticket template at
`docs/process/ticket-template.md`. Use sequential free IDs at the
tail.

After authoring, run `scripts/lint-ticket.py` on the new ticket file
and fix any errors. Do NOT run `/m1-tick start` — only author.
```
