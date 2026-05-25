---
target: M1-079d
date: 2026-05-25
category: AUDIT-EVASION
severity: medium
status: deferred
disposition: |
  Deferred as a spec-level design decision. The spec classifies tag-preference
  and language mutations as "user-preference, not privileged action" — the handlers
  explicitly document "writes zero rows to audit_log." Whether group-scope mutations
  (which affect all members' digests) cross the "privileged action" threshold is a
  spec-amend question, not a code bug. Address via a spec-amend ticket if the
  classification should change.
---

# Finding 3: Group-scope tag/lang mutations lack audit trail

## Promise

"Authorization evaluation order on every inbound message: ... 8. Audit-log the intent."
(security.md §Authorization model step 8)

## Gap

FollowTagCommandHandler, UnfollowTagCommandHandler, and LangCommandHandler now
execute privileged group-admin state mutations (modifying the group's tag preferences
or language, affecting all group members' digests) in group scope with no audit trail.
Before M1-079d, group scope was unconditionally rejected so the lack of audit was moot.
Now the code path is live and a group admin can modify shared group state with no record
in audit_log.

## Repro

A group admin sends `/follow-tag sensitive-topic` in a group. The handler modifies
`scope_tag` for that group (affecting all members' periodic digests). No audit_log row
is written. A later investigation cannot determine who added or removed tag preferences
from the group scope, or when.

## Suggested fix class

audit-log-coverage

## Resolution path

File a spec-amend ticket to decide whether group-scope tag/lang mutations should be
reclassified from "user-preference" to "privileged action." If yes, add AuditLogWriter
injection and audit rows to FollowTag, UnfollowTag, and Lang handlers for the group-scope
code path only (DM-scope remains non-audited per current spec).
