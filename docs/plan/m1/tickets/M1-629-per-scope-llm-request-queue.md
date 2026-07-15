---
id: M1-629
title: "Investigate the one-in-flight-per-(user, scope) guard under a chat/summary burst (multi-minute-late replies)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Making the LLM itself faster. This ticket is about how the provider handles
    MULTIPLE in-flight/queued chat+summary requests for one (user, scope).
acceptance:
  - >-
    Determine, with evidence, what actually happens when a second interruptible
    request (chat-mode message or user-issued /summary) arrives from the SAME
    (user, scope) while one is in flight. Evidence means: name the code path
    (class and method) that admits, queues, or rejects the second request, plus a
    reproducing test or a captured log trace. Observed in the 2026-07-14/15 live
    test: one user (the synthetic admin, contact 4, DM scope) fired several
    chat/summary requests in quick succession and the replies drained serially
    over minutes (a ~13-minute-late /summary), with no clear "request already in
    progress" reply to the second request.
  - >-
    The spec already settles the intended behaviour — docs/spec/commands.md
    §Surface conventions: "At most one in-flight interruptible request per (user,
    scope). A second request from the same caller while one is in flight returns
    a localized 'request already in progress; use /stop to cancel' reply. ...
    once the first request completes (or is cancelled by /stop) the next is
    accepted normally." — i.e. reject-with-guidance, NOT bound/coalesce. Chat-mode
    agent loops and user-issued /summary are both in the interruptible class
    (D35). If the guard is missing or bypassed for these paths, implement
    conformance to that rule. If investigation shows current behaviour already
    conforms (the observed backlog had a different cause consistent with the
    spec), close with that finding plus a test that pins the reject-with-guidance
    behaviour.
test_plan:
  adds:
    - >-
      A test that pins the reject-with-guidance rule: while one interruptible
      request for a (user, scope) is in flight, a second request from the same
      (user, scope) receives the localized "request already in progress; use
      /stop to cancel" reply and triggers no second LLM call.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
revisions:
  - date: 2026-07-15
    reason: >-
      clarity-fail refine (bounded self-refine via /m1-tick run, decision C).
      Round-1 clarity FAILed with 3 blockers: (1) acceptance item 2 delegated its
      criterion to "whichever the spec intends" without naming a section; (2) the
      frontmatter had no spec_refs at all; (3) the title/body said "per-scope"
      while the spec's guard is keyed per (user, scope), and the ticket did not
      state which burst scenario was observed. Fixed by inlining the settled spec
      rule (commands.md §Surface conventions commits to reject-with-guidance, not
      bound/coalesce; chat + user-issued /summary are the interruptible class per
      D35), adding spec_refs/decision_refs, correcting the guard-key terminology,
      and recording the observed scenario from the Phase-D live-test report: ONE
      user (synthetic admin, contact 4) in one DM scope — exactly the case the
      spec's guard covers. Also applied both clarity WARNINGs on the same edit,
      neither expanding scope: named what counts as evidence in acceptance item 1
      (code path + reproducing test or log trace) and added a test_plan (adds a
      reject-with-guidance pinning test). files_budget, complexity, risk, and the
      out_of_scope boundary are unchanged.
---

Found in the 2026-07-14/15 isolated live test (PHASE-D-REPORT-20260715, finding 3
"Serial LLM backlog"): ONE user — the synthetic admin (contact 4), in their own DM
scope — fired several chat/summary requests in quick succession and the replies
drained one-at-a-time over minutes (a ~13-minute-late /summary reply), with no
clear "request already in progress; use /stop to cancel" reject for the second and
later requests. This is exactly the single-(user, scope) case the spec's
one-in-flight guard commits to rejecting (docs/spec/commands.md §Surface
conventions), so the working hypothesis is that the guard is missing or bypassed
for chat-mode and/or user-issued /summary; the investigation must confirm or
refute that with the evidence named in acceptance item 1.

The backlog's *duration* was amplified by the test instance's slow CPU-bound
embeddings and a large seeded corpus — that amplification (LLM/embedding speed) is
out of scope; only the admission behaviour is in scope.

If a pre-existing test pins a contrary (queueing) behaviour for these paths, this
ticket does NOT pre-authorize modifying it — escalate instead.
