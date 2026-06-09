# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-08 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — V24__identity_audit_remediation.sql:44-53 — `delete_preban_user` writes the `UNBAN_PREBAN_DELETE` audit row without the `actor_contact_id` / `actor_adapter` denormalized columns the schema spec mandates, leaving the row's actor identity dependent on a FK target the spec explicitly says may rotate.

## Detail

### F1. `delete_preban_user` audit row drops the spec-mandated denormalized actor columns

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-core/src/main/resources/db/migration/V24__identity_audit_remediation.sql:40-53` (latest definition of the procedure)

**Current code:**

```sql
    -- Audit-before-effect (Invariant 7). The actor existence is guaranteed
    -- by the check above, so no JOIN needed; actor_contact_id and
    -- actor_adapter are omitted — derivable from actor_user_id by any
    -- reader that needs them, avoiding a second SELECT round-trip.
    INSERT INTO audit_log (
        actor_user_id,
        action, target_kind, target_id, target_contact_id,
        scope_id, request_id, details_json
    )
    SELECT p_actor_id,
           'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
           NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
      FROM users u
     WHERE u.id = p_user_id;
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Audit log defines `actor_contact_id` and `actor_adapter` as columns "denormalized at write time for redaction-free historical lookup; the FK target may rotate." The denormalization exists precisely so that the audit trail remains intact when the `actor_user_id` FK target changes or is later removed. V24's comment ("derivable from actor_user_id by any reader") contradicts that spec rationale: if the actor's `users` row is later mutated (contact rotation) or removed (the spec permits the operator Admin role to DELETE rows, and the pre-ban `/unban` carve-out deletes `users` rows), the "derive at read time" path no longer reaches the actor's contact id/adapter as it stood at write time. The audit row for a permitted DELETE of a `users` row is exactly the kind of record that must survive independent of FK liveness.

This is also an internal inconsistency. The original V5 `delete_preban_user` (V5:386-388) wrote both columns via the same `JOIN users a` pattern. V24 dropped them; later, when V25 made the identical omission in `approve_quarantine` / `reject_quarantine`, V32 explicitly reversed it — V32's header (lines 21-23) calls re-adding "the actor_contact_id / actor_adapter denormalized audit columns (docs/spec/schema.md §Audit log) the V25 bodies dropped" a defect worth a dedicated migration. The same defect in `delete_preban_user` was never remediated, so two SECURITY DEFINER procedures that write audit rows now disagree on whether the spec-mandated denormalization applies.

**Recommended fix:**

```sql
    -- Audit-before-effect (Invariant 7). Denormalize actor_contact_id /
    -- actor_adapter at write time (schema.md §Audit log: "the FK target
    -- may rotate") — same SELECT-JOIN pattern V32 restored for the
    -- quarantine procedures. The actor-admin EXISTS check above
    -- guarantees the actor row exists, so the JOIN yields exactly one row.
    INSERT INTO audit_log (
        actor_user_id, actor_contact_id, actor_adapter,
        action, target_kind, target_id, target_contact_id,
        scope_id, request_id, details_json
    )
    SELECT p_actor_id, a.contact_id, a.adapter,
           'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
           NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
      FROM users u
      JOIN users a ON a.id = p_actor_id
     WHERE u.id = p_user_id;
```

Ship this as a new migration (e.g. `V45__delete_preban_user_audit_denorm.sql`) carrying the complete current procedure body with only the audit INSERT changed — not an in-place edit of V24 — for the same reason V32/V41 cite: an already-migrated database keeps the old body if the edit lands in place.

**Reasoning:**

The fix restores the spec contract and brings `delete_preban_user` back in line with the post-V32 quarantine procedures. The `JOIN users a ON a.id = p_actor_id` is the same shape V5 used and V32 restored; because the actor-admin `EXISTS` check earlier in the body guarantees `p_actor_id` resolves to a real `users` row, the JOIN produces exactly one row and the audit INSERT writes exactly one row — identical control flow to the quarantine procedures. The "avoid a second SELECT round-trip" justification in the current comment does not apply: this is a single INSERT...SELECT against an already-locked actor row, not a separate query.

**Trade-offs:**

One additional table reference in the audit INSERT's `SELECT` (the `JOIN users a`). This is the same cost V5 paid and V32 accepted; it is negligible relative to the DELETE and the durability the denormalization buys. No behavior other than the two extra populated columns changes.
