---
id: M1-629
title: "Investigate per-scope LLM request queuing under a burst (multi-minute-late replies)"
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
    MULTIPLE in-flight/queued chat+summary requests for one scope.
acceptance:
  - >-
    Determine (with evidence) whether concurrent chat/summary requests for the same
    scope queue unboundedly. A burst in the live test produced replies arriving many
    minutes late (a ~13-minute-late /summary), and a second request did not get a
    clear "you already have a request in flight; /stop to cancel".
  - >-
    Decide and implement the intended behaviour: reject-with-guidance while one is
    in flight, or bound/coalesce the queue — whichever the spec intends. If current
    behaviour is already correct-by-design, close with that finding + a test that
    pins it.
---

Found in the 2026-07-14/15 isolated live test: firing several chat/summary requests in
quick succession for one scope produced a backlog draining one-at-a-time over minutes
(observed a 13-minute-late summary reply). Unclear whether the per-scope single-in-flight
guard rejects, queues, or coalesces. Investigate and pin the intended behaviour.
Note: partly amplified by the test's slow CPU-bound embeddings + a large seeded corpus.
