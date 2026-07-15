---
id: M1-624
title: "Bootstrap-admin token claim shows a probation welcome instead of an admin welcome (en+cs)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: medium
round_cap: 2
security_relevant: true
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
test_plan:
  modifies:
    - >-
      SimpleXAdminClaimTokenTest.firstDmMatchingTokenRegistersContactAndSetsAdmin
      (lines 100-123): the reply-body assertion changes from
      List.of(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH),
      commandPermissions.renderProbationCommandList())) to the new distinct
      admin-welcome string (the claim reply must NOT be the shared probation
      welcome). The DB assertions on that same test (is_admin=true,
      registration_state='vouched', probation_until IS NULL — lines 107-112) stay
      UNCHANGED; only the reply-string expectation changes.
  preserves:
    - >-
      SimpleXAdminClaimTokenTest.secondPresentationFromDifferentContactGrantsNothingAndRepliesLikeInvalid
      and .nearMissTokenDoesNotClaimAndRepliesLikeInvalid (non-claim paths that
      still assert the fixed invalid-invite reply) — unchanged.
    - >-
      BundleLoaderTest (D43 balanced keyset) stays green because the new
      admin-welcome key is added to BOTH en.properties and cs.properties.
    - >-
      The normal (non-claim) invite-accept welcome path
      (InboundRouter.welcomeReply / REPLY_WELCOME_DM_FRESH) and its coverage
      (e.g. GoldenPathJourneyIT) — untouched (acceptance item 3).
    - all tests currently green on main
revisions:
  - date: 2026-07-15
    reason: >-
      clarity-fail refine (bounded self-refine via /m1-tick run, decision C).
      Clarity BLOCKER TEST-CHANGES-AUTHORIZED: making the claim reply state
      "administrator" necessarily breaks the pre-existing
      SimpleXAdminClaimTokenTest.firstDmMatchingTokenRegistersContactAndSetsAdmin
      equality assertion against the shared REPLY_WELCOME_DM_FRESH welcome, but
      the ticket carried no test_plan authorizing that edit. Added a test_plan
      (modifies that one assertion; preserves the non-claim paths and
      BundleLoaderTest). Also applied the two clarity WARNINGs on the same edit,
      neither expanding scope: risk low->medium and security_relevant
      false->true, because the diff sits in the SimpleX bootstrap-admin claim
      branch of the DM-intake gate (D50) — flipping security_relevant makes the
      redteam gate run. files_budget and out_of_scope unchanged.
---

Found in the 2026-07-14/15 isolated live test. Sending the bootstrap admin claim-token
promotes the sender to admin (verified: DB is_admin=t, registration_state=vouched,
probation_until NULL, audit BOOTSTRAP_ADMIN), but the reply is the standard probation
registration welcome ("Your account is in the probation period for the next ~24h…").
Misleading for someone who just became the administrator.
