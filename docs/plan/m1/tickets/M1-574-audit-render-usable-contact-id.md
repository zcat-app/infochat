---
id: M1-574
title: "/audit renders the usable target contact id, not the internal user UUID"
status: draft
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
  - docs/spec/commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Adding any new audited action, or changing WHICH events are audited. This is
    a render-only fix over the existing audit_log_view.
  - >-
    A user-listing command for probation / actionable users. That is the
    companion M1-575; this ticket only makes the values /audit ALREADY surfaces
    usable.
  - >-
    Exposing target_id (the internal UUID) anywhere new. It should stop being the
    user-facing identifier for user targets, not move elsewhere.
  - >-
    Changing the actor rendering. `/audit` already shows actor_contact_id
    correctly; only the TARGET column shows the wrong id.
acceptance:
  - >-
    For audit rows whose target is a user (target_kind indicating a user/contact
    subject — e.g. VOUCH, BAN, GRANT_ADMIN, PROMOTE), `/audit` renders
    `target_contact_id` (the adapter contact id that `/vouch`, `/ban`,
    `/grant-admin`, `/promote` accept as their `<contact>` argument), NOT the
    internal `target_id` (users.id UUID). The value shown is directly
    copy-pasteable into those commands.
  - >-
    For non-user targets (e.g. a post or system subject where target_contact_id
    is NULL), rendering is unchanged — target_kind plus the appropriate
    target identifier still appear, with a stable placeholder when no contact id
    exists.
  - >-
    The SELECT in AuditCommandHandler is widened to project target_contact_id
    (the column already exists in audit_log_view); the redaction posture of the
    view is unchanged (contact ids are not secrets — they are the accountability
    key the audit trail exists to expose).
  - >-
    AuditCommandHandlerTest asserts a user-target row (e.g. a VOUCH) renders the
    target's contact id and does NOT render the users.id UUID.
  - >-
    `mvn verify` is green from the repo root (new test passes; full suite still
    passes).
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java — user-target rows render target_contact_id, not the UUID."
  modifies:
    - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java — SELECT + row formatting."
  preserves:
    - "non-user-target rendering; actor_contact_id rendering; all tests green on main."
spec_refs:
  - "docs/spec/commands.md §Command catalogue"
  - "docs/spec/security.md §DB roles"
decision_refs: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-574: `/audit` shows a usable target contact id

## Context

The spec's stated way for a bot admin to discover a user (there is deliberately
no `/list-users`) is: "enumerate via the existing audit history"
(commands.md §Permission model). But `AuditCommandHandler` selects and renders
`target_id` — the internal `users.id` **UUID** — for the target column, even
though `audit_log_view` also exposes `target_contact_id` (the adapter contact id
that every `<contact>`-targeting admin command actually matches on:
`VouchCommandHandler` does `WHERE adapter = ? AND contact_id = ?`).

Verified this session on the live deployment: a `VOUCH_INTENT` row rendered the
target as a UUID, and no `/audit` output surfaced a value an admin could paste
into `/vouch`. So the *documented* discovery path yields an unusable identifier
— which makes `/vouch`, `/ban`, `/grant-admin`, and `/promote` effectively
undialable for a user the admin can otherwise see. This is the cheap half of the
fix; M1-575 is the fuller listing.

## Approach

- Widen the AuditCommandHandler data SELECT to include `target_contact_id`
  (already a column on `audit_log_view`).
- In row formatting, for user-target rows show `target_contact_id`; keep
  `target_id` / `target_kind` handling for post and system targets where no
  contact id exists.

## Notes

- **security_relevant** only in the sense that it changes what an admin-only,
  DM-only surface prints — from an internal UUID to the contact id. Contact ids
  are not secret (they are the audit trail's accountability key); no new
  information leaves the admin/DM boundary. Redaction of actual secrets in the
  view is untouched.
- Companion tickets: M1-575 (actionable-user list), M1-573 (`/help vouch` should
  reference how to find a contact id once these land).
