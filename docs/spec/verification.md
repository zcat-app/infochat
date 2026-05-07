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
  subscriptions never leaks across scopes.

### Schema

- Last-admin protection: cannot revoke `is_admin` from the only admin;                                                                                                                                                                                
  cannot ban the only admin. Trigger-level test, asserts both UPDATE                                                                                                                                                                                  
  and DELETE paths.
- One group admin per group: simulated race of two simultaneous                                                                                                                                                                                       
  `@mention` inserts produces exactly one admin row.
- Soft-delete only: a `/remove-source` followed by re-add flips                                                                                                                                                                                       
  `deleted_at` and reuses the row; no duplicate `(fetcher, url)` rows.
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
  type, asserts allow/deny. New commands added in code without a row                                                                                                                                                                                  
  here fail the test.
- Banned-user intake: a banned user sending any input gets the fixed                                                                                                                                                                                  
  reply; no parser invocation, no DB query past the ban check, no LLM                                                                                                                                                                                 
  call. Verified by mock-call assertions.
- Confirmation token state machine: 30-second timeout rejects late                                                                                                                                                                                    
  confirms; bare `confirm` doesn't fire anything; cross-scope confirm                                                                                                                                                                                 
  rejected; non-`confirm` input cancels with an explicit ack.
- Slash-prefix exclusivity: a message starting with anything other than                                                                                                                                                                               
  `/` always reaches the chat agent, never the command router.
- Onboarding modes: DM-fresh, DM-returning, group-first-mention each                                                                                                                                                                                  
  produce the expected branch.
- Pagination: page size honored, footer-suggested next page actually                                                                                                                                                                                  
  works.
- `/forget` purge (decision D37): after confirm, the calling
  `(user, scope)`'s `chat_memory` and `saved_post` rows are gone;
  another user's data in the same group is untouched; the same user's
  data in a *different* scope (e.g. their DM) is untouched;
  `users.is_admin` / `users.is_banned` / `group_membership` /
  `audit_log` rows are untouched (the audit log is append-only). An
  audit row recording the `/forget` intent is written *before* the
  purge.
- `/forget` confirm + idempotency: late confirm rejected by the
  confirmation state machine; a second `/forget` after the first
  completes returns the friendly no-op reply (no audit row, no DB
  writes beyond read).
- `/export` scope isolation (decision D37): the export from inside a
  group contains only the calling `(user, group)`'s data — no other
  user's chat memory, no other user's saves, no group-wide content
  beyond the caller's own contributions; a DM `/export` does not leak
  any group-scoped state.

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
- Untrusted-content delimiter: a payload trying to forge the closing                                                                                                                                                                                  
  marker fails because the per-call random value differs.
- Chat output sanitizer: a fake LLM emits a reply containing                                                                                                                                                                                          
  `/grant-admin abc`; sanitizer strips it and writes an audit row;                                                                                                                                                                                    
  multi-match replies are refused entirely.
- Tool surface: the LLM's tool list does not include any mutator;                                                                                                                                                                                     
  attempts to call mutator-shaped names from the agent loop are                                                                                                                                                                                       
  rejected at the SPI boundary.
- Rate limits: per-user LLM-trigger cap rejects the 11th call in a                                                                                                                                                                                    
  window; per-turn tool-call cap stops the agent loop.
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

  ---                                                                              

If a question is "what test covers behavior X?", the answer is in
`docs/design/08-verification.md`. If a question is "is behavior X required                                                                                                                                                                            
to be tested at all?", the answer is here.