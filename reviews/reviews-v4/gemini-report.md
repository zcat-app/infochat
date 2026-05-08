# Infochat Specification Independent Audit Report

## 1. Executive Summary

This independent review of the infochat specification identified significant architectural rigor and a clear focus on determinism, least privilege, and bounding LLM blast radius. The distinction between logic (spec-level) and tuning (design notes) is well-maintained. 

However, several material gaps and inconsistencies remain across the documentation. A total of 25 substantive findings were identified, spanning inconsistencies in the identity and invitation models, unhandled edge cases in stream processing and lifecycle management, verification blind spots, and nuanced security gaps in multi-stage processing like translation and summary generation. Notably, the interaction between the new invitation model and the initial mention-based auto-registration requires reconciliation. By addressing these findings, the specification will mature from a stringent design into a water-tight implementation contract capable of guiding robust engineering execution.

## 2. Findings

### [AUTH-01] Contradictory Onboarding Mechanics (Invites vs Auto-Register)
**Severity**: High
**Category**: Inconsistency / Security gap
**Location**: `docs/spec/security.md` (§ Invite-code registration) vs `docs/spec/commands.md` (§ Onboarding) vs `docs/spec/schema.md` (§ Identity and access)
**Confidence**: High
**What the spec says**: `security.md` introduces single-use invite codes (`/invite create`) as "the application-level entry gate for DM access". It states unknown DM contacts require a pending code matching the body. `commands.md` states "Users self-register on first message (auto-create + welcome with `/help`)" and "Groups: first user to `@mention` the bot in a new group is auto-promoted". 
**Why it's a problem**: It is fundamentally unclear if the system is closed-registration (invite only) or open-registration (first message auto-registers). If single-use invites gate DM access, but a user can simply `@mention` the bot in any group to auto-register without an invite, the DM invite gate is entirely trivial to bypass.
**Suggested resolution**: Clarify the onboarding matrix. Reconcile `commands.md` introductory text to reflect the invite-only DM gate. Define the rules for group onboarding: must a user be registered *before* they can issue the first `@mention` that bootstraps a group admin? 

### [SEC-01] Invite Code Exhaustion / Brute-Forcing
**Severity**: High
**Category**: Security/authorization gaps
**Location**: `docs/spec/security.md` (§ Invite-code registration)
**Confidence**: High
**What the spec says**: Unknown DM contact's first message is checked against the invite table. On failure, returns fixed reply and drops. 
**Why it's a problem**: For `--open` invites, an attacker (or random user on the adapter) can rapidly brute-force the UUID invite code by repeatedly messaging the bot. There is no rate limit explicitly defined for unauthenticated/unregistered users beyond the transport limit, and failed guesses are silently "dropped" without an audit log.
**Suggested resolution**: Implement strict rate limits and short lockouts on failed invite code guesses per contact id.

### [ARCH-01] Asset Snapshot Notifications Bypassing Provider High-Water Mark
**Severity**: High
**Category**: Inconsistencies / Architecture
**Location**: `docs/spec/commands.md` (§ Asset commands) vs `docs/spec/architecture.md` (§ Inter-service communication)
**Confidence**: High
**What the spec says**: Provider has `SELECT`-only permission on `price_snapshot`. Collector emits `NOTIFY new_price_snapshot`. Provider can keep an in-process cache warmed/invalidated from `NOTIFY`. `architecture.md` mandates a high-water mark for `LISTEN/NOTIFY` correctness.
**Why it's a problem**: The `price_snapshot` table architecture lacks a catch-up high-water mark mechanism. If the Provider is disconnected, it misses `NOTIFY new_price_snapshot`. Because Provider reads latest snapshot *on invocation* (demand-driven), the caching mechanism is prone to serving stale data if it isn't invalidated properly after reconnects.
**Suggested resolution**: Explicitly define that the in-process cache is flushed entirely on Provider reconnect, forcing a DB read on the next request, effectively sidestepping the need for a snapshot high-water mark.

### [SEC-02] Post UID Reassignment Attack
**Severity**: High
**Category**: Failure-mode coverage
**Location**: `docs/spec/schema.md` (§ Posts and derivatives)
**Confidence**: Medium
**What the spec says**: Post UID derived deterministically from `(source_id, upstream_identifier)` with a content-hash fallback.
**Why it's a problem**: For sources without canonical IDs (relying on content hash), an attacker can trivially change the body of an RSS item slightly to generate a new `UID` and evade deduplication. In combination with Stage 1 fails, this allows relentless hammering of the quarantine queue by continuously mutating the input slightly.
**Suggested resolution**: Fallback hashing must aggressively normalize inputs (e.g. stripping dynamic ad tracking links, timestamps) or limit total daily fetch volume for hash-fallback sources.

### [ARCH-02] Stop Command Worker Release Non-Determinism
**Severity**: Medium
**Category**: Ambiguities
**Location**: `docs/spec/commands.md` (§ Conversation control: `/stop`)
**Confidence**: High
**What the spec says**: "The DB-side query itself may continue to completion server-side... what the spec promises is that the *worker* and the *user-visible state* are released without waiting for it."
**Why it's a problem**: If the worker thread releases but the Postgres connection remains blocked executing the query, the connection pool will eventually drain if users spam slow `/summary` commands followed by `/stop`. 
**Suggested resolution**: Explicitly mandate `pg_cancel_backend(pid)` for the released connection or set strict `statement_timeout`s on long-running queries so they inherently bounded.

### [SEC-03] Translation Output Sanitizer Evasion
**Severity**: Medium
**Category**: Spec/design layering violations / Security
**Location**: `docs/spec/security.md` (§ LLM output sanitizer) vs `docs/spec/llm.md` (§ Translation flow)
**Confidence**: High
**What the spec says**: The "chat output sanitizer" runs on LLM-generated text to strip admin commands *before* delivery. "Translated outputs are cached by `(hash(text), target_language)`".
**Why it's a problem**: Does the output sanitizer run *before* or *after* the `TranslationProvider`? If it runs before, translation might introduce forbidden strings (unlikely, but possible model hallucination), bypassing the sanitizer. If it runs after, the translation cache might store sanitizer-rejected payloads or the regex might fail on foreign language admin command approximations.
**Suggested resolution**: Explicitly state the pipeline order: `Summarizer → TranslationProvider → Output Sanitizer → Translation Cache`. The sanitizer must always be the *last* step before delivery.

### [PERF-01] Purge Cascade Costs for /forget
**Severity**: Medium
**Category**: Architecture
**Location**: `docs/spec/commands.md` (§ Conversation control: `/forget`)
**Confidence**: High
**What the spec says**: `/forget` does an immediate purge of `chat_memory` and `saved_post` rows. 
**Why it's a problem**: Hard row deletes invoke cascading index updates. A power user with thousands of saves doing a `/forget` might lock tables or cause statement timeouts.
**Suggested resolution**: Consider soft-deletes or tombstoning for `/forget`, letting the asynchronous background partition drop cleanly reap the data.

### [MODEL-01] Inconsistent Soft-Delete Semantics
**Severity**: Medium
**Category**: Schema Invariants
**Location**: `docs/spec/schema.md` (§ Operational) vs `docs/spec/schema.md` (§ Identity and access: Target vs Eventual removal)
**Confidence**: High
**What the spec says**: `deleted_at IS NULL` filters sources. Schema invariant 4 says `source` is never hard-deleted.
**Why it's a problem**: If sources are never hard-deleted, but `post` data ages out by partition, orphaned `source` rows effectively grow indefinitely over the system lifecycle.
**Suggested resolution**: Allow operator role to hard delete sources that have no linked posts in active partitions, or clarify the boundless growth is accepted.

### [AUTH-02] Probation Tier Vouch Auditing Gaps
**Severity**: Medium
**Category**: Verification gaps
**Location**: `docs/spec/security.md` (§ Slow-start tier) vs `docs/spec/verification.md` (§ Commands and chat)
**Confidence**: High
**What the spec says**: `/vouch <contact>` graduates user from probation immediately. Audit-logged. 
**Why it's a problem**: Missing verification requirement. There's no test defined forcing the assertion that a vouched user correctly bypasses the time-delay and gains mutate-state commands immediately.
**Suggested resolution**: Add a specific integration test ensuring `/vouch` successfully unblocks forbidden commands for a new user.

### [ARCH-03] Notification Flooding from Failed Redrive Jobs
**Severity**: Medium
**Category**: Failure-mode coverage
**Location**: `docs/spec/security.md` (§ Re-evaluation job)
**Confidence**: High
**What the spec says**: `stage2_failed=true` posts are picked up. Exhausted attempts permanently mark post `NEEDS_REVIEW` and notify admin.
**Why it's a problem**: During an extended LLM outage, hundreds of posts might exhaust attempts concurrently. The throttle groups by `(channel, error_class)`, but the `NEEDS_REVIEW` transition is a status, not purely an infra error.
**Suggested resolution**: Clarify if `NEEDS_REVIEW` notifications are bundled or sent per-post.

### [STATE-01] StreamSource Non-graceful Disconnect Replay Vector
**Severity**: Medium
**Category**: Failure mode
**Location**: `docs/spec/architecture.md` (§ Ingest SPIs)
**Confidence**: High
**What the spec says**: Graceful shutdown drains in-flight events. Events not drained reappear on next connection. Duplicate deliveries deduplicated by upstream ID before outbox.
**Why it's a problem**: On *non-graceful* crashes (OOM, SIGKILL), the `StreamSource` cursor/ack mechanism is undefined. The spec relies entirely on the upstream stable ID deduplication. If millions of read-events are continually re-streamed because the `StreamSource` never acknowledges them to the relay, startup becomes a CPU-bound deduplication storm.
**Suggested resolution**: `StreamSource` interface must feature a persisted high-water mark or cursor sync, separate from relying purely on INSERT deduplication.

### [SEC-04] Admin Ban vs Mention Auto-Promote Race
**Severity**: Low
**Category**: Edge Case
**Location**: `docs/spec/schema.md` (§ Invariants)
**Confidence**: High
**What the spec says**: First mention wins for group admin. Bot admin can `/ban`.
**Why it's a problem**: If a suspended/banned user achieves the first mention in a group, their row is created as group admin but disabled. The spec accounts for banning an existing admin (via promote-swap) but parsing this entry event is murky.
**Suggested resolution**: `users.is_banned` must be checked before conferring the auto-admin role during the first-mention logic.

### [OP-01] Inflexible Timeout Values
**Severity**: Low
**Category**: Operator usability
**Location**: `docs/spec/commands.md` (§ Surface conventions)
**Confidence**: Medium
**What the spec says**: "The timeout is the same for every confirmable command in a given deployment (no per-command bespoke values); the exact duration is a profile-driven value".
**Why it's a problem**: Operator tuning here is unnecessarily rigid. Banning a user requires different mental verification time than clearing a context window. Consolidating this to a profile-wide boolean hurts operational flexibility.
**Suggested resolution**: Shift the exact timeout duration from `profile` defaults to specific `application.properties` keys per command category.

### [VER-01] Rate Limit Exhaustion Missing Integration Tests
**Severity**: Low
**Category**: Verification gaps
**Location**: `docs/spec/verification.md` (§ Security)
**Confidence**: High
**What the spec says**: Rate limits verify the 11th call rejects. Modulo agent tool loops limit.
**Why it's a problem**: MVP end-to-end tests omit verification that real transport-level rejection is behaving. A user could saturate the Tomcat engine queue before application rate limits trigger.
**Suggested resolution**: Explicitly add bounded load assertion tests proving the application doesn't drop parallel healthy traffic during a single-user flood.

### [COMP-01] Context Window Ceiling Truncation Handling
**Severity**: Low
**Category**: Smells
**Location**: `docs/spec/commands.md` (§ Conversation control: `/compress`)
**Confidence**: Medium
**What the spec says**: Auto-triggered near the context-window ceiling.
**Why it's a problem**: No fallback specified if the output of `/compress` results in a memory blob that is *still* significantly large. 
**Suggested resolution**: Introduce a hard sliding-window truncate mechanism (e.g. discard oldest X messages) prior to compression if byte size exceeds model threshold.

## 3. Cross-cutting observations

1. **Immaculate Determinism Boundary**: The hard line drawn between deterministic SQL data retrieval and stochastic LLM prose generation is an excellent defense-in-depth tactic that ensures replayability.
2. **Minimal Trust Scope**: Moving `is_admin` out of the LLM tool boundary and forcing an intercept layer is secure by design.
3. **Partitioning as Garbage Collection**: Relying on Postgres partitioning to effectively `TRUNCATE` stale data removes the need for brittle nightly deletion cron jobs. 
4. **V1 Simplicity Tax**: The pushout of multi-host Provider syncing, multi-adapter Sybil defences, and complex group-bans into v2 keeps v1 lean but shifts substantial moderation burdens onto manual Operator intervention. 

## 4. Spec evaluation

The infochat specification demonstrates high structural coherence. Layering violations are virtually non-existent; the distinction between *Spec* (the commitments) and *Design* (the implementation numerics) is uniformly respected. The testing strategy successfully maps spec assertions directly back to CI fixtures. 

Where the spec falters is primarily around concurrent lifecycle loops: the edge cases of where invite rules intersect with group-auto-mention logic, how StreamSources handle abrupt non-graceful state tracking, and sequence ordering of translation and output sanitizers.

## 5. Pros and cons of the current state

### Pros
- Outstanding threat modeling handling `[REDACTED]` quarantine markers.
- Robust failure handling (Verdict infra split versus Stage infra split).
- Excellent use of Postgres native features (`LISTEN/NOTIFY`, `pgvector`, Partition drops) eliminating external unneeded infrastructure (Kafka, Redis).
- Strong SPI boundaries future-proofing LLMs and Transport.

### Cons
- The invite validation schema contradicts group auto-registration flows.
- Polled Asset fetching doesn't align natively with the stateless `NOTIFY` design.
- Rate-limiting rules don't cover unauthenticated brute-forcing effectively.

## 6. Recommended next actions

1. **Re-draft the Onboarding Flow**: Clarify whether unknown identities joining via group mentions must *already* be verified via a DM invite, or if the group acts as a Sybil-vector loophole. 
2. **Define Sanitizer Pipeline Topology**: Explicitly dictate the operation order within `docs/spec/llm.md` regarding Translation and Sanitization regex checks.
3. **Draft Eventual Hard-delete rules**: Reconcile schema invariant bounds regarding orphaned `source` rows.
4. **Add Invite Rate Limiting**: Push logic into `docs/spec/commands.md` defining how to throttle invalid `UUID` attempts and logging thresholds.
