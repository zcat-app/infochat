---
id: M1-627
title: "/approve-group and /reject-group: clarify which group id token to pass"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 5
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The group approval flow itself (D47). Only the id ergonomics / error message change.
acceptance:
  - >-
    An admin can approve/reject a group using the identifier /list-groups displays,
    without ambiguity between the DB UUID and the adapter-internal group number.
    Either /list-groups labels the token to pass, or the commands accept both forms.
  - >-
    Passing a plausible-but-wrong token yields a helpful message (e.g. "did you mean
    <uuid> from /list-groups?") rather than a bare "No group with id X is known".
---

Found in the 2026-07-14/15 isolated live test: /list-groups shows a UUID
(73558b10-…); /approve-group accepts that UUID, but the adapter-internal
upstream_group_id (an int like `1`) is a tempting wrong token and yields "No group with
id `1` is known to the bot." Small clarity fix.
