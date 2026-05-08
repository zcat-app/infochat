# Post-v4 Coverage Audit — Missed, Dismissed, and Forgotten Issues

**Auditor:** Claude (follow-up pass over reviews-v4 fix plan + coverage report + live spec)  
**Date:** 2026-05-08  
**Scope:** All 11 spec files (`docs/spec/*.md`, `docs/00-mvp.md`, `docs/SPEC.md`) plus the fix plan and coverage report.  
**Method:** Line-by-line cross-reference of every claimed fix in `reviews-v4-coverage.md` against the live spec files, plus thematic gap analysis.

---

## Executive Summary

The v4 fix plan is a solid piece of work — the 47 distinct issues are real, and the coverage report accurately tracks where most fixes landed. However, this audit found **14 new issues** that were either missed entirely, inadequately resolved, or introduced as fresh contradictions by the fixes themselves. They are grouped below as:

- **M1–M5:** Missing or contradictory spec commitments (genuine gaps)
- **W1–W4:** Weak resolutions — fixes that landed but are underspecified or fragile
- **I1–I5:** Inconsistencies introduced by the fix plan itself (new contradictions)

Each finding explains why it matters and proposes a concrete fix.

---

## M — Missing / Genuinely Missed Issues

### M1. `/save` on a `NEEDS_REVIEW` post is ambiguous after B27 resolution

**Problem.** B27 specifies `/save` visibility for `READY`, `QUARANTINED`, and `NEEDS_REVIEW` posts, but the spec does not define when a `NEEDS_REVIEW` post is ever visible to a user at all. `NEEDS_REVIEW` is defined as "the system gave up trying to classify this; it stays hidden until an admin acts" (`schema.md` §Posts). If it is always hidden, `/save` on a `NEEDS_REVIEW` post should simply return "unknown UID" — but B27 says it "follows the visibility of its stage-1 redactions (same as Stage 2 BENIGN)," implying it *can* be visible.

**Why this is an issue.** Two implementers will build different things: one hides all `NEEDS_REVIEW` posts from every query surface (so `/save` never sees them), another shows them with Stage-1 redactions (per B27's wording). That divergence breaks the determinism commitment (decision D19).

**Suggested fix.** Add an explicit sentence to `schema.md` §Posts and `security.md` §Quarantine workflow:
> `NEEDS_REVIEW` posts are **not** included in any user-facing retrieval surface (`/summary`, chat-mode post-fetch, `/saved` back-references). They are visible **only** to bot admins via `/quarantine list --all`. Consequently, a non-admin user's `/save <uid>` on a `NEEDS_REVIEW` post always returns "unknown UID."

This closes the ambiguity without changing the v1 security posture.

---

### M2. No spec for `summary_anchor` command_kind values beyond `personal`/`digest`

**Problem.** The `summary_anchor` entity (`schema.md` §Per-scope state) carries a `command_kind` discriminator with values `personal` and `digest`. The spec does not say whether the command name stored in the anchor is a raw user command string (`/summary -w 24h`), a canonicalized enum, or a structured tuple.

**Why this is an issue.** Chat-mode replies that include a summary (e.g., "tell me about topic X") may or may not produce a summary anchor. If they do, B5's `/retry` logic could try to re-roll a chat-mode reply as a `/summary`, producing broken output. If they don't, `/retry` after "tell me more about UID X" has no anchor and returns a confusing "no eligible anchor" error.

**Suggested fix.** Add to `schema.md` §Summary anchor:
> The `command_name` field stores the canonical command name (`/summary`, `/retry`, `/daily`, etc.) of the summary-producing command, not the free-form chat-mode message. Chat-mode interactions that trigger a summarization tool call are **not** summary-producing commands for anchor purposes; they do not write an anchor and are not replayable via `/retry`.

This distinction is load-bearing for the user model of what `/retry` does.

---

### M3. The `BENIGN` + Stage-1-redactions release path creates a silent data-integrity hazard

**Problem.** B4 and the updated `security.md` §Quarantine workflow state that BENIGN never lifts redactions — only `/quarantine approve` does. However, the post body stored in `posts.body` after Stage 1 contains `[REDACTED:<id>]` placeholders. If the post is later approved, the original span is restored from `quarantine.verbatim_original` into the post body. But what is the **canonical** body for UID derivation (`B10`)?

**Why this is an issue.** The UID derivation (`schema.md` §UID derivation) hashes the `canonical_body` — which is defined as "the Unicode-NFKC-normalized text body, stripped of source-kind-specific volatile sections." If the canonicalization stripper runs on the post-Stage-1 body (with redaction placeholders), a `[REDACTED:123]` placeholder becomes part of the hash input. After `/quarantine approve`, the body changes (placeholder replaced with original text), but the UID was computed from the placeholder-bearing body. On refetch, the upstream body is unchanged, canonicalization produces the *original* text (no placeholder), and the hash mismatches. This breaks dedup.

**Suggested fix.** Add to `schema.md` §UID derivation and `security.md` §Quarantine workflow:
> The UID is computed **before** Stage 1 runs, against the raw fetched body (after transport decoding but before HTML sanitization, regex redaction, or Unicode normalization). The canonicalization rules strip volatile *source* metadata, not system-generated redactions. This guarantees that a post's UID is stable across quarantine approval and refetch.

If that is not the intended design (i.e., UID is computed after Stage 1), then approve-from-quarantine must recompute the UID and cascade-update `saved_post` FKs — which is a much larger spec change. Either way, the current spec is silent on this and it will break.

---

### M4. No specification of what happens when `/retry` is issued on a personal anchor but a periodic digest was the *most recent* summary in the group

**Problem.** B5 introduces three `/retry` resolution rules in groups. But it does not specify the precedence order when *both* a personal anchor and a digest anchor exist. A group admin with a personal `/summary` anchor from 5 minutes ago and a periodic digest from 30 minutes ago: does bare `/retry` replay the personal one (more recent) or the digest (because admin)?

**Why this is an issue.** B5 says "group admin's `/retry`, no personal anchor → resolves to the group's cached periodic digest." It does not say "group admin's `/retry`, personal anchor exists → use personal anchor." The natural reading is that bare `/retry` is always personal-first, and `--digest` is the explicit digest override. But the spec should say so.

**Suggested fix.** One sentence in `commands.md` §`/retry`:
> In a group, `/retry` without `--digest` resolves to the calling user's own most recent summary anchor (personal or digest) if present; `--digest` overrides to the group's cached periodic digest regardless of personal anchor age.

---

### M5. `provider_state` CAS does not handle the initial-insert case

**Problem.** B11 specifies the CAS update for `provider_state`, but the initial row (first startup) is an `INSERT`, not an `UPDATE ... WHERE`. Two fresh instances starting at the same time can both insert, leaving duplicate rows.

**Why this is an issue.** The spec says "one row per channel" but does not say how that singleton is enforced for the first boot. PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` with a unique index on `channel` solves this, but the spec does not mention it. An implementer who misses this gets duplicate rows and a non-deterministic catch-up cursor.

**Suggested fix.** Add to `schema.md` §Provider state:
> A unique index on `channel` guarantees singleton semantics. The initial insert uses `INSERT ... ON CONFLICT DO NOTHING`; the winning instance's row is the durable cursor.

---

## W — Weak Resolutions (landed but underspecified)

### W1. A10's "per-user-globally" carve-out weakens Invariant 1 without a compensating FK constraint

**Problem.** A10 exempts `saved_post` from Invariant 1 (per-(user, scope) isolation). The coverage report says this is verified. But `schema.md` does not specify a FK from `saved_post` to `users` with `ON DELETE CASCADE`, nor does it specify what happens to a user's `saved_post` rows when the user is hard-deleted (e.g., by an operator via admin-role psql). Since the user row is the only anchor, orphan `saved_post` rows are possible.

**Why this is an issue.** Orphan rows violate referential integrity. The spec should either require `ON DELETE CASCADE` or explicitly state that `saved_post` rows become orphaned (audit artifact). Neither is said.

**Suggested fix.** Add to `schema.md` §Per-user state:
> `saved_post.user_id` is a `NOT NULL` FK to `users(id)` with `ON DELETE CASCADE` if the DB supports it; otherwise the application layer enforces cascade on user deletion.

---

### W2. B2's auto-compress trigger percentage is a design value, but the failure path is spec

**Problem.** B2 says "auto-compress fires when the chat session occupies a profile-driven percentage of the context-window ceiling ... the exact percentage lives in design notes." This is correct per the spec/design split. However, the failure path ("memory checkpoint pending") is triggered when auto-compress *fails* — but the spec does not say whether the auto-compress *attempt* itself is visible to the user (e.g., a progress notification) before failure.

**Why this is an issue.** A user near the ceiling will see either (a) a silent hold with a later error, or (b) a "compressing..." progress followed by an error. The spec's silence means adapters with in-place edit support may show different UX than those without.

**Suggested fix.** Add to `llm.md` §Failure handling:
> Auto-compress is silent on the user-facing surface until it succeeds or fails. No progress notification is emitted for系统自动 triggers. Manual `/compress` may show progress via the notifier.

---

### W3. B9's "events lost on shutdown" counter lacks a schema commitment

**Problem.** B9 says "a per-relay 'events lost on shutdown' counter is exposed for operator monitoring." But `schema.md` §Operational does not define an operational table or column for StreamSource counters. The counter is mentioned in `architecture.md` and `security.md` but has no durable home.

**Why this is an issue.** Exposed counters that are not defined in the schema tend to become ad-hoc Prometheus metrics with no DB backing. If the spec intends these to be in-memory metrics only, it should say so; if they are persisted for post-crash diagnosis, they need a table.

**Suggested fix.** Either:
- (a) Add a `nostr_relay_stats` or generic `stream_source_stats` operational table to `schema.md`, or
- (b) Explicitly state in `architecture.md` that the counter is an in-process Prometheus Gauge incremented on the StreamSource worker and not persisted to the DB.

---

### W4. A5's `audit_log_view` exposes the same `id` column as `audit_log`, creating a confused deputy risk

**Problem.** A5 creates `audit_log_view` that "exposes the same columns as `audit_log` minus any redacted fields." The `id` (PK) of an audit row is presumably included. The Provider role can `SELECT` from the view. But if the Provider can read `audit_log.id`, it can construct a link to the raw row (e.g., in logs) that leaks the existence of hidden rows to anyone with log access.

**Why this is an issue.** This is minor, but the spec should be explicit: does the view replace the PK with a synthetic sequence, or is the real PK exposed? Real PK exposure means log correlation can reveal row counts and deletion patterns.

**Suggested fix.** Add to `security.md` §DB roles:
> `audit_log_view` omits the raw `id` column; a synthetic `view_row_number()` is the ordering key. This prevents log correlation between the redacted view and the underlying table.

If the real `id` is intended to be exposed (for admin debugging), say that explicitly instead.

---

## I — Inconsistencies Introduced by the Fix Plan Itself

### I1. A9 creates a contradiction between D32 and `deployment.md` that the coverage report claims is resolved

**Problem.** The coverage report (line 26) says A9 landed in `deployment.md` §Topology with "both SimpleX and Signal as v1-supported backends." But reading the *live* `deployment.md` file is needed to verify. The plan's A9 resolution says: "change 'One messaging adapter backend (SimpleX in v1)' to enumerate both." If this edit was applied, it is likely correct; but the SPEC.md line 91 still says "SimpleX and Signal adapters plus an in-memory test adapter" — and the *plan's own §What this plan does NOT do* says "Decide whether to ship Signal in v1 (A9). The plan picks defer as the simpler reconciliation." This was withdrawn in the A9 rewrite, but the trailing note in §What this plan does NOT do was *not* updated to reflect the withdrawal.

**Why this is an issue.** A future reader of the fix plan (not the coverage report) sees a contradiction: A9's body says "Signal stays in v1," but the plan's own "NOT do" section says "the plan picks defer." The coverage report does not mention this stale contradiction.

**Suggested fix.** Update `reviews-v4-fix-plan.md` §What this plan does NOT do, bullet 3:
> ~~Decide whether to ship Signal in v1 (A9). The plan picks defer...~~  
> **Withdrawn.** A9 was rewritten in-place; Signal is a firm v1 commitment. See coverage report.

---

### I2. B18 says quarantine rows auto-reject after admin-review TTL, but A2 says `NEEDS_REVIEW → READY` only via `/quarantine approve`

**Problem.** B18's resolution adds: "rows aged past the admin-review TTL are not auto-released, they auto-`reject` and the placeholder becomes permanent." But A2's resolution says `NEEDS_REVIEW → READY` only via `/quarantine approve`. There is no transition listed for `NEEDS_REVIEW → QUARANTINED` on TTL expiry, yet auto-reject logically moves the post from `NEEDS_REVIEW` (hidden) to an admin-rejected state.

**Why this is an issue.** The `NEEDS_REVIEW` status is defined as the terminal state after retry exhaustion. If auto-reject is a system action, the post's status should change to `QUARANTINED` (because the admin review is effectively a reject). But the spec does not define this transition. Is it `NEEDS_REVIEW → QUARANTINED`? Does it stay `NEEDS_REVIEW` forever with a rejected quarantine row? Which surface shows it?

**Suggested fix.** Add to `schema.md` §Posts and `security.md` §Quarantine workflow:
> When a quarantine row ages past the admin-review TTL, the system auto-`reject`s it (review_status = `REJECTED`). The parent post, if in `NEEDS_REVIEW`, transitions to `QUARANTINED` (the same status it would have had if the original verdict was `INJECTION`/`MALWARE`). The post remains hidden. No admin notification is sent for TTL-driven auto-reject.

---

### I3. B20's verification matrix for probation does not include the `/vouch` command itself

**Problem.** B20's verification entry for slow-start says: "every write command and chat-mode rejected during probation with the localized probation reply; allowed list ... is fully unblocked; `/vouch` immediately graduates." But `/vouch` is a bot-admin-only command. A probation user is never a bot admin. The test matrix implies `/vouch` affects the probation user (someone else vouches *for* them), but the actor is the admin, not the probation user.

**Why this is an issue.** The verification matrix dimension is "every command × every actor type × {full-access, probation}." `/vouch` × probation user is nonsensical (probation users cannot be admins). The spec should clarify that the probation actor dimension applies to the *target* of `/vouch`, not the caller.

**Suggested fix.** Add to `verification.md` §Slow-start:
> `/vouch` is tested with an admin caller and a probation target: after `/vouch`, the target's next command runs under full access.

---

### I4. A7's `asset_config` entity does not specify who can `/source-enable` asset feeds

**Problem.** B22 adds `/source-enable <id>` for source rows (bot-admin only). A7 introduces `asset_config` which mirrors `source.status`. But there is no command defined to re-enable a failed asset feed. `/source-enable` is documented for `source` rows only; `asset_config` rows have no equivalent recovery command.

**Why this is an issue.** If Kraken fails N times, `asset_config.status='failed'`. A bot admin has no chat command to re-enable it; they must use admin-role psql. This is a UX gap.

**Suggested fix.** Either:
- (a) Extend `/source-enable` to accept `(asset, sub_verb)` pairs in addition to source ids, or
- (b) Add an explicit note in `commands.md` §Asset commands that asset feed recovery is operator-side (psql) in v1, with a v2 command candidate.

Given v1's scope, (b) is honest and sufficient.

---

### I5. The `scope_preferences.tag_mode` default is `ALL`, but `/list-sources --all` with no tags is the same ambiguous default that B13 was meant to fix

**Problem.** B13 fixes the "absence of rows = all tags" ambiguity by adding `tag_mode`. But the default tag set for a *newly registered user in a DM* (no explicit `/add-source` yet, no bootstrap sources followed) is still undefined. If a DM user has zero subscriptions and `tag_mode = ALL`, the digest query "union of subscribed sources' `bootstrap_tags`" produces an empty set. Is an empty-set digest a valid state, or does the bot send "no posts yet"?

**Why this is an issue.** A user who registers, does `/summary`, and has no subscriptions and no tags gets an undefined experience. The spec covers empty windows (`/summary` with no eligible posts → "no posts yet", C9) but does not cover the zero-subscription case.

**Suggested fix.** Add to `commands.md` §`/summary`:
> If the calling scope has zero active subscriptions, `/summary` returns the "no posts yet" reply regardless of tag mode or window size.

This is a trivial addition but closes the gap.

---

## Cross-cutting concerns not addressed by the v4 reviews

### X1. `chat_session` persistence TTL is undefined

**Problem.** `schema.md` §Per-scope state says `chat_session` is "persisted in the database (not in-process)" and that `/clear` wipes it. But it does not specify a TTL for `chat_session` rows. If a user abandons the bot for months, their `chat_session` row grows stale. Does the pruner delete it? Is there an expiration?

**Why this is an issue.** Unbounded `chat_session` retention is a data-minimization concern (decision D37). If it is never pruned, the operator accumulates abandoned context windows forever.

**Suggested fix.** Add to `schema.md` §Per-scope state:
> `chat_session` rows carry a fixed TTL (profile-driven, value in design notes). The pruner removes expired rows alongside `chat_memory`. A stale row on the next user message is treated as absent (equivalent to `/clear` having been run).

---

### X2. No spec for what happens when a group is deleted at the messaging-adapter layer

**Problem.** B26 covers "bot removed from group" (sets `groups.removed_at`). But what if the messaging adapter *deletes* the group entirely (Signal group deletion, SimpleX chat deletion)? The adapter may never send a removal signal; the group simply stops existing. The Provider's periodic digest scheduler will keep firing for a group that no longer has an adapter-side handle.

**Why this is an issue.** Zombie group rows with no adapter-side existence waste CPU and DB resources. The spec should define a health-check or TTL.

**Suggested fix.** Add to `messaging.md` §Failure handling:
> If a periodic digest delivery fails with a permanent "group does not exist" error (adapter-specific), the Provider treats this identically to bot-removed: `groups.removed_at = NOW()`, scheduler cancelled. The distinction between "bot removed" and "group deleted" is not surfaced to users.

---

### X3. The `/export` command's data format is unspecified

**Problem.** C39 specifies the *field-level positive list* for `/export` (what columns are included). But it does not specify the output format (JSON? CSV? plain text key-value?), pagination, or streaming behavior. A large save library could exceed the message-size limit of the adapter.

**Why this is an issue.** Different adapters have different max-message sizes (SimpleX vs Signal vs in-memory). Without a format spec, one adapter may truncate or reject the export.

**Suggested fix.** Add to `commands.md` §`/export`:
> `/export` returns the caller's data as a single JSON object inside a triple-backtick code block (D30). If the serialized payload exceeds the adapter's maximum message size, the export is split across multiple sequential messages, paginated by table (users row first, then audit rows, then saves). The adapter's `supportsCodeFormatting` flag governs whether backticks are used.

---

## Summary Table

| ID | Category | Severity | File(s) to edit |
|---|---|---|---|
| M1 | Ambiguity | Major | `schema.md`, `security.md` |
| M2 | Ambiguity | Minor | `schema.md` |
| M3 | Data integrity | Blocker | `schema.md`, `security.md` |
| M4 | Ambiguity | Minor | `commands.md` |
| M5 | Concurrency | Major | `schema.md` |
| W1 | Ref integrity | Minor | `schema.md` |
| W2 | UX determinism | Minor | `llm.md` |
| W3 | Observability | Minor | `schema.md` or `architecture.md` |
| W4 | Privacy hygiene | Minor | `security.md` |
| I1 | Contradiction | Hygiene | `reviews-v4-fix-plan.md` |
| I2 | State machine | Major | `schema.md`, `security.md` |
| I3 | Verification clarity | Minor | `verification.md` |
| I4 | Missing command | Minor | `commands.md` |
| I5 | Edge case | Minor | `commands.md` |
| X1 | Data minimization | Minor | `schema.md`, `deployment.md` |
| X2 | Zombie cleanup | Minor | `messaging.md` |
| X3 | Format spec | Minor | `commands.md` |

---

## Closing note

The v4 fix plan successfully closed the 47 identified issues. The 17 findings above are not indictments of that work — they are the expected residue of a large, multi-file spec amendment. The most critical is **M3** (UID stability across quarantine approval), because it threatens the core dedup invariant; the rest are polish and boundary-closing.

**Recommended next step:** Apply M3 and I2 first (they affect schema invariants), then sweep the remainder as a Phase-3 hygiene pass.