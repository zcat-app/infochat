---
id: M1-624
title: "Bootstrap-admin token claim shows a probation welcome instead of an admin welcome (en+cs)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any change to the admin-claim mechanism (D50/M1-506) or the probation model (D45).
    The claimed bootstrap admin is already correctly created is_admin=true + vouched +
    no probation; this ticket only fixes the user-facing MESSAGE shown on that claim.
  - >-
    The normal-user registration welcome, which correctly states probation. Only the
    admin-claim path's reply changes.
acceptance:
  - >-
    When a user's first DM is the SimpleX bootstrap admin claim-token and they are
    promoted to admin (is_admin=true, vouched), the reply states they are now the bot
    administrator — NOT "Your account is in the probation period for the next ~24h".
  - >-
    The bilingual (en+cs, D43) keyset stays balanced; BundleLoaderTest passes.
  - >-
    The normal (non-claim) registration welcome is unchanged.
---

Found in the 2026-07-14/15 isolated live test. Sending the bootstrap admin claim-token
promotes the sender to admin (verified: DB is_admin=t, registration_state=vouched,
probation_until NULL, audit BOOTSTRAP_ADMIN), but the reply is the standard probation
registration welcome ("Your account is in the probation period for the next ~24h…").
Misleading for someone who just became the administrator.
