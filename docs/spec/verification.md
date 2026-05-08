# Verification strategy

This file describes *what* the test suite must prove and the layers it                                                                                                                                                                                
must prove it at. Concrete fixture file names, helper class names,               
assertion library choices, and the exact test catalogue live in                                                                                                                                                                                       
`docs/design/08-verification.md`.

The goal: every spec-level invariant is enforced by an automated test
that fails if the invariant is violated. The full design notes also
cover smoke flows and operator-side rehearsals.

## Test layers

The suite has four layers, in increasing cost:

1. **Unit tests.** Pure-Java logic with no DB, no LLM, no transport.                                                                                                                                                                                  
   Stage 1 regex catalogue (positive and negative corpora), confirmation                                                                                                                                                                              
   token state machine, command parser, fuzzy-suggestion ranking, output                                                                                                                                                                              
   sanitizer regex, time-window flag parsing, scope key construction.
2. **Persistence / repository tests.** A real Postgres+pgvector instance                                                                                                                                                                              
   (Testcontainers). Migrations applied. Verify schema-level invariants                                                                                                                                                                               
   (last-admin protection trigger, `one_admin_per_group` partial unique
   index, soft-delete FK behavior, partition pruner effects).
3. **Integration tests.** A running Collector and Provider against an            
   in-memory messaging adapter and a fake LLM. Run a full ingest →                                                                                                                                                                                    
   eval → notify → command path against fixture feeds. The fake LLM                                                                                                                                                                                   
   has scriptable verdicts for every Stage-2 outcome.
4. **End-to-end smoke.** `docker-compose up` against a small bootstrap                                                                                                                                                                                
   sources file. Verify the MVP exit criteria                                                                                                                                                                                                         
   (`docs/00-mvp.md` §6) pass on a clean checkout.

## Spec-level invariants the tests must enforce

Every entry below is a spec commitment from `architecture.md`,                                                                                                                                                                                        
`schema.md`, `commands.md`, `security.md`, `llm.md`, `messaging.md`,                                                                                                                                                                                  
or `deployment.md`. Each one corresponds to at least one named test.

### Architecture

- Outbox rehydrator: killing the Collector mid-evaluation and restarting                                                                                                                                                                              
  re-enqueues anything left in `RAW` (or intermediate) state.
- LISTEN/NOTIFY catch-up: a Provider that was down when a `new_post`             
  fired processes the post on next startup via the high-water mark.
- Per-(user, scope) isolation: 100-user fuzz of saves, memory, and
  subscriptions never leaks across scopes. **Cross-scope chat memory
  isolation** (`schema.md` §Chat memory): a recall in scope `S` does
  not surface a row whose scope key is `S' ≠ S`; DM memory never
  surfaces in any group; one group's memory never surfaces in
  another. Saves are excluded from the per-scope isolation assertion
  because saves are per-user-globally (D13 / A10) — the `saved_post`
  fuzz instead asserts that saves made in scope `S` are visible in
  every scope of the same user, and never to a different user in
  any scope.
- StreamSource reconnect (decision D38): kill the relay mid-stream;
  the StreamSource reconnects with backoff and resumes. No duplicate
  events emitted on resume — events delivered before disconnect that
  are re-delivered after reconnect produce zero additional `posts`
  rows (cross-relay/replay dedup combined).
- Per-relay degradation (decision D38): one misbehaving relay (slow,
  spamming malformed events, repeatedly disconnecting) is marked bad
  for the cooldown window; the StreamSource keeps running on the
  remaining relays. The bad relay is retried after cooldown, and a
  successful reconnect clears the bad-relay state.
- Cross-relay event dedup (decision D38): the same Nostr event
  delivered from N relays in any interleaving produces exactly one
  `posts` row.

### Schema

- Last-admin protection: cannot revoke `is_admin` from the only admin;                                                                                                                                                                                
  cannot ban the only admin. Trigger-level test, asserts both UPDATE                                                                                                                                                                                  
  and DELETE paths.
- One group admin per group: simulated race of two simultaneous                                                                                                                                                                                       
  `@mention` inserts produces exactly one admin row.
- Soft-delete only: a `/remove-source` followed by re-add flips
  `deleted_at` and reuses the row; no duplicate `(kind, identifier)`
  rows (decision D38).
- TTL by partition drop: ageing partitions don't take row-level deletes.
- Chat-memory TTL pruner (decision D37): a `chat_memory` row older than
  the configured horizon is removed by the scheduled pruner;
  `/save`d posts in the same `(user, scope)` are untouched; rows newer
  than the horizon are not pruned. Pruner is idempotent (a second run
  on the same state is a no-op).
- Audit-before-effect: a privileged command interrupted between audit
  and side effect leaves an audit row but no state change.

### Commands and chat

- Permission matrix: table-driven test, every command × every actor
  type × {full-access, probation}, asserts allow/deny. The
  probation-blocked list is **derived from the command registry**
  (every write command not in the slow-start allowed list per D45),
  not hand-written. New commands added in code without a matrix row
  here fail the test; new commands added without a default
  probation classification (allow/deny) also fail.
- Banned-user intake: a banned user sending any input gets the fixed                                                                                                                                                                                  
  reply; no parser invocation, no DB query past the ban check, no LLM                                                                                                                                                                                 
  call. Verified by mock-call assertions.
- Confirmation token state machine: a confirm arriving past the
  configured profile timeout is rejected; bare `confirm` doesn't fire
  anything; cross-scope confirm rejected; non-`confirm` input cancels
  with an explicit ack. (The exact timeout value is profile-driven;
  the test asserts the timeout-vs-non-timeout boundary, not a specific
  number.)
- Slash-prefix exclusivity: a message starting with anything other than                                                                                                                                                                               
  `/` always reaches the chat agent, never the command router.
- Onboarding modes: DM-fresh, DM-returning, group-first-mention each
  produce the expected branch. **Invite-code lifecycle:** create
  CONTACT_BOUND → wrong-contact reject → matching-contact accept;
  create OPEN_ADAPTER → cross-adapter reject → first-unknown-contact
  accept; expired code reject; revoked code reject; replayed USED
  code reject; concurrent-race on OPEN_ADAPTER produces exactly one
  USED transition and one new user row; pre-banned contact + invite
  path is rejected at intake; brute-force rate limit triggers after
  the configured threshold and audit-logs the breach.
- **Slow-start tier** (decision D45 + B8 + B35): every write command
  and chat-mode rejected during probation with the localized
  probation reply; allowed list (read-only commands plus `/forget`
  and `/lang`) is fully unblocked; `/vouch` immediately graduates;
  probation expiry (`probation_until < NOW()`) auto-promotes on the
  next request without admin action; the lazy sweep clears
  `probation_until` after expiry without a background job.
- **Fetcher failure ladder** (decision D42): N consecutive failures
  on a single source flips `status = 'failed'`, N-1 does not; the
  scheduler skips `failed` sources; the throttled admin notifier
  fires once per `(channel, error_class)` window; `/source-enable`
  with a probe success returns the source to `active` and resets
  the consecutive-failure counter.
- **StreamSource drain** (decision D38): graceful shutdown drains
  in-flight events to the outbox before acknowledging shutdown; a
  hard-killed test produces an "events lost on shutdown" counter
  increment; reconnect with `since=last_persisted_event_at`
  retrieves missed events from a replay-supporting fake relay; a
  non-replay-supporting fake relay produces a measurable gap that
  is exposed via the per-relay loss counter.
- **Sanitizer match-set derivation** (B41/C41): every command in the
  bot-admin and group-admin permission rows appears in the LLM
  output sanitizer match set; CI fails on a mismatch (test fixture
  adds an admin command without a sanitizer entry → CI red).
- **chat_memory pruner** (decisions D37/D40): the pruner bean is
  registered at startup; runs on the configured cadence; deletes
  rows older than the horizon; the deletion test asserts the
  pruner has fired at least once during a deployment-N controlled
  run; rows newer than the horizon and `/save`d posts are
  untouched.
- **Single-instance lock** (B21): a second Collector or Provider
  startup against the same DB fails to acquire the named
  `pg_advisory_lock` and exits non-zero with a fatal log line that
  references the running instance's heartbeat host id.
- **Stage 2 re-eval BENIGN parity** (B4): a re-eval BENIGN keeps
  Stage 1 redactions in place, matching first-pass behavior; only
  `/quarantine approve` lifts redactions.
- **UNKNOWN re-eval** (B14): an UNKNOWN-verdict post is picked up by
  the re-eval queue with the lower attempt cap; cap exhaustion
  transitions to `NEEDS_REVIEW` and produces a coalesced admin
  notification (B38).
- **Stage 1 / kind-filter ordering** (B3): a Nostr fixture event of
  a disallowed kind is dropped at the kind-filter step before
  Stage 1 runs (Stage 1 sanitizer counter does not increment); a
  signature-failed event is dropped before the kind filter
  (kind-filter counter does not increment).
- Pagination: page size honored, footer-suggested next page actually                                                                                                                                                                                  
  works.
- `/forget` purge (decision D37 + B7): after confirm, the calling
  `(user, scope)`'s `chat_memory`, `chat_session`, and
  `summary_anchor` rows are gone; the **caller's full `saved_post`
  library** is gone (per A10/D13: saves are per-user-globally, so
  `/forget` from any scope wipes them all — verified by saving in
  DM, calling `/forget` from a group, and asserting the DM saves
  are gone too); another user's data in the same scope is
  untouched; the same user's data in a *different* scope is
  untouched **for `chat_memory` / `chat_session` / `summary_anchor`**
  (those are per-scope) but **not** for `saved_post`;
  `users.is_admin` / `users.is_banned` / `group_membership` /
  `audit_log` rows are untouched (the audit log is append-only).
  An audit row recording the `/forget` intent is written *before*
  the purge.
- `/forget` confirm + idempotency: late confirm rejected by the
  confirmation state machine; a second `/forget` after the first
  completes returns the friendly no-op reply (no audit row, no DB
  writes beyond read).
- `/export` scope isolation (decision D37): the export from inside a
  group contains only the calling `(user, group)`'s data — no other
  user's chat memory, no other user's saves, no group-wide content
  beyond the caller's own contributions; a DM `/export` does not leak
  any group-scoped state.
- `/stop` cancellation (decision D35): a chat-mode reply mid-stream is
  interrupted by `/stop`; the LLM stream closes, any in-flight
  read-only tool call is cancelled, the worker thread is freed within
  the cancellation window, and the progress notifier renders a final
  "stopped" state. A `/stop` from one `(user, scope)` does not affect
  another user's in-flight request in the same group. `/stop` with
  nothing in flight returns the friendly idempotent reply. `/stop`
  fired after outbound delivery has begun does **not** unsend the
  message. Periodic digests, ingest, and already-completed work are
  unaffected by `/stop`.
- `/retry` semantics (decision D36): `/retry` after a `/summary` reuses
  the original deterministic post selection (asserted by capturing
  the post-id list across both runs) and re-runs only the prose stage;
  `/retry` exceeding the fixed retry cap returns the friendly error;
  any non-`/retry` input from the same `(user, scope)` clears the
  anchor (verified for `/help`, `/stop`, plain chat, and another
  `/summary`); `/retry` after `/stop` cancelled the prior summary
  returns the friendly error; `/retry` for a periodic group digest
  from a non-admin user is rejected, and from a group admin replaces
  the cached digest (decision D17).
- Asset commands (decision D39): `/zcash` with no sub-verb uses the
  operator-configured default; with a known sub-verb returns the
  matching snapshot; with an unknown sub-verb returns the friendly
  error with fuzzy suggestions. The reply header names the data
  source and the body contains the bare source URL (D30
  attribution). The Provider reads from `price_snapshot` directly;
  the Provider DB role can `SELECT` from `price_snapshot` and
  cannot `INSERT`/`UPDATE`/`DELETE` (DB-role test). When the latest
  snapshot is older than the freshness window the reply includes the
  "data is N minutes old" line; when no row exists the reply is the
  friendly error. Snapshots never appear in `/summary`, `/save`, or
  `/saved` results (assertion: `/save <price-snapshot-row-id>`
  returns the unknown-uid error).
- Periodic group summaries (decision D17): the morning/evening digest
  is generated within the staggered slot window; a follow-up
  `/summary` from the same group during the cache TTL is served from
  cache (no second LLM call); when the worker pool is saturated a
  digest is emitted in the degraded form (headlines + sources, no LLM
  prose) and a regular `/summary` afterwards still produces full
  prose; a `/retry` from group admin replaces the cached digest and
  the next `/summary` reads the replacement.
- Re-evaluation job (security.md §Failure handling): a post with
  `stage2_failed=true` is picked up on the next re-eval tick once the
  fake LLM is restored to a healthy verdict; verdict `BENIGN` lifts
  Stage-1 redactions per the verdict-taxonomy commitment; verdict
  `INJECTION`/`MALWARE`/`UNKNOWN` flips status to `QUARANTINED`; the
  per-post attempt counter is bounded and a post that exhausts
  attempts stays as-is with an admin-notification entry. Re-eval
  cadence and max attempts are profile-driven; the test asserts the
  cadence semantics, not the exact value.
- End-to-end happy path (v1 integration): a single test drives the
  full first-time-user flow against the in-memory adapter and fake
  LLM — auto-registration on first DM, `/help` reply, `/add-source`
  with required tags, fetch tick produces `READY` posts, `/summary`
  returns deterministic clusters with prose, `/save <uid>` adds to
  the user's library, `/saved` lists it, `/retry` re-runs the prose,
  `/forget` (with confirm) purges the user's `chat_memory` and
  `saved_post` rows. No assertion failure at any step is the
  pass condition.

### Security

- Stage 1 regex set has positive (must flag) and negative (must NOT                                                                                                                                                                                   
  flag) corpora. Adversarial Unicode (NFKC equivalence, bidi overrides,                                                                                                                                                                               
  zero-width insertions) is detected.
- Stage 1 ReDoS guard: an adversarial input that would catastrophically                                                                                                                                                                               
  backtrack is detected by the timeout / RE2 path and the post is                
  fail-closed quarantined.
- Stage 2 verdict path: fake LLM returns each of `BENIGN`, `INJECTION`,          
  `MALWARE`, `UNKNOWN`; post status is correct in each case.
- Stage 2 infrastructure failure: fake LLM throws; post is released as
  `READY` with redactions retained, `stage2_failed=true`, throttled                                                                                                                                                                                   
  admin notify; the periodic re-eval job picks it up when the LLM                
  recovers.
- SSRF: every blocked range (`169.254.169.254`, RFC1918, loopback,                                                                                                                                                                                    
  link-local, multicast, CGNAT, host-own interfaces) refuses the fetch.                                                                                                                                                                               
  Redirect to a blocked range mid-fetch is also blocked (TOCTOU).
- Websocket SSRF (decision D38): a `wss://` URL whose hostname
  resolves to a blocked range is refused before the TCP connection is
  established. The same blocklist applies on every reconnect — a
  relay that resolved fine on connect but later resolves to a blocked
  range during reconnect is rejected.
- Nostr signature verification (decision D38): a fixture event with a
  tampered signature is dropped before Stage 1; the failed-sig
  counter increments; nothing reaches `posts`. A fixture event whose
  pubkey doesn't match the claimed signature is dropped with the
  same effect.
- Nostr kind filter (decision D38): events of kind 4 (DM), kind 7
  (reaction), or any other kind not on the v1 allowlist are dropped
  without parsing. Verified by injecting a kind-4 fixture and
  asserting the body is never read by the implementation (e.g. a
  parsing-side counter does not increment).
- Untrusted-content delimiter: a payload trying to forge the closing                                                                                                                                                                                  
  marker fails because the per-call random value differs.
- Chat output sanitizer: a fake LLM emits a reply containing                                                                                                                                                                                          
  `/grant-admin abc`; sanitizer strips it and writes an audit row;                                                                                                                                                                                    
  multi-match replies are refused entirely.
- Tool surface: the LLM's tool list does not include any mutator;                                                                                                                                                                                     
  attempts to call mutator-shaped names from the agent loop are                                                                                                                                                                                       
  rejected at the SPI boundary.
- Rate limits: per-user LLM-trigger cap rejects the call that exceeds
  the profile-configured cap; per-turn tool-call cap stops the agent
  loop. (Specific numeric caps are profile-driven; the test asserts
  the boundary behaviour.)
- DB roles: a SQL-injection mutation attempt from the Provider role                                                                                                                                                                                   
  fails; the admin role can do it.
- User-content log policy (decision D37): with the log capture set to
  TRACE, a fixture that exercises a `/summary`, a chat-mode reply, a
  `/save`, and a `/compress` produces no log line containing the
  bodies of inbound chat messages, the contents of `chat_memory`
  rows, or the body or annotations of `saved_post` rows. Stage
  events, request IDs, scope IDs, and counts are present (positive
  assertion). The audit log records command intent only; user-authored
  prose is not in audit rows either.

### LLM and embeddings

- Determinism: `/summary` returns the same set of post ids on repeated                                                                                                                                                                                
  calls within the same window; only prose differs.
- Routing: a property override picks a different provider for one task                                                                                                                                                                                
  without changing others.
- Embedding model swap is detected (a vector built with one model is                                                                                                                                                                                  
  not silently mixed with another).
- Translation cache: a digest sent to N members translates once.
- Source bodies are never translated (decision D29): with the scope's
  `/lang` set to a non-English code, a fixture run that produces a
  `/summary` and a periodic digest exercises the
  `TranslationProvider`. A spy on the provider asserts that no call
  argument equals or contains the body of any `post` row; only
  presentation strings (cluster prose, headers, system phrasing)
  reach the provider. Source post titles and URLs likewise never
  pass through translation.
- Translation flake fallback: a translator that throws falls back to                                                                                                                                                                                  
  English with a one-line note; the user does not see a hung response.

### Messaging

- Capability fallback: an adapter without `supportsMessageEdit`                                                                                                                                                                                       
  produces one final `send` instead of placeholder + edits; business                                                                                                                                                                                  
  logic is unchanged.
- Identity is contact id, not display name: a user changing display                                                                                                                                                                                   
  name does not change their `users.id`.
- Low-trust adapter rejected unless explicit opt-in.
- Progress notifier never interpolates user input into stage strings                                                                                                                                                                                  
  (assertion-style fuzz: every rendered progress string matches a                                                                                                                                                                                     
  fixed bundle key).
- Placeholder always finalized: an exception in the handler still runs                                                                                                                                                                                
  the try/finally and finalizes the placeholder.

### Deployment

- Idempotent migrations: running both services twice from a clean DB                                                                                                                                                                                  
  ends in the same schema state as running once.
- Bootstrap loader idempotency: re-running with the same JSON does not                                                                                                                                                                                
  duplicate rows or churn `tag` rows.
- Bootstrap admin idempotency: restarting Provider does not produce              
  duplicate `BOOTSTRAP_ADMIN` audit rows when the admin already has                                                                                                                                                                                   
  the flag.
- Readiness probe: stays unhealthy until every required startup bean                                                                                                                                                                                  
  is up.
- LLM-down probe: a deliberately-killed Ollama surfaces as degraded                                                                                                                                                                                   
  but does not fail liveness.

## CI shape

- Unit and persistence tests run on every push.
- Integration tests run on every push (in-memory adapter, fake LLM —                                                                                                                                                                                  
  cheap).
- The end-to-end smoke runs on merges to `main` and on tag.
- The MVP exit-criteria suite is the gate for "MVP done" — until all                                                                                                                                                                                  
  eight criteria pass on a clean checkout, the MVP is incomplete.

## What lives in design notes

- Fixture file names and corpus contents
- Test-helper APIs
- Concrete assertion library and Testcontainers wiring
- Mock-LLM scripting format
- Coverage targets per module
- Long-running fuzz / property-test parameter values
- The exact MVP smoke transcript
- The fake-relay harness for StreamSource tests (canned `.jsonl`
  event streams, scriptable disconnects, multi-relay topologies)

  ---                                                                              

If a question is "what test covers behavior X?", the answer is in
`docs/design/08-verification.md`. If a question is "is behavior X required                                                                                                                                                                            
to be tested at all?", the answer is here.