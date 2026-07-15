---
id: M1-623
title: "/summary degrades ungracefully on a large post window (cap before the summarizer LLM call)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 8
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Changing the retrieval/eligibility set a scope sees (M1-621 world predicate) or
    the summarizer model/provider. This ticket bounds the number of posts handed to
    the summarizer per /summary; it does not change WHICH posts are eligible.
  - >-
    The remote-llm=500 post render cap already present. That cap trims the RENDER;
    the problem is the LLM prompt is still built from the (up to) capped set and can
    exceed the summarizer timeout on a dense window.
acceptance:
  - >-
    When a /summary window contains many posts (observed live: 549 posts in the
    default window over a dense corpus), the summarizer either (a) receives a bounded
    input that reliably completes within infochat.llm.summarizer.timeout-ms, or (b)
    the reply clearly tells the user the window is too large and to narrow it (e.g.
    "showing the N most recent; narrow with --since"), BEFORE attempting a doomed LLM
    call that times out to "Summarizer LLM is unreachable".
  - >-
    The existing graceful-degradation path (headlines + source URLs + UIDs) still
    works, but is reached by an explicit size decision, not only by an LLM timeout.
  - >-
    A regression test pins the boundary: a window over the cap produces the bounded/
    explained reply, not a timeout-driven "unreachable" degrade.
---

Found in the 2026-07-14/15 isolated live test (report
`/home/infochat/PHASE-D-REPORT-20260715.md`). `/summary` over a dense window built a
prompt from ~549 posts; the summarizer hit its 90s timeout and the reply read
"Summarizer LLM is unreachable" (misleading — the LLM was reachable; the prompt was
too large). Partly amplified by the test's seeded corpus (all posts look recent by
fetched_at), but the failure mode — an unbounded post set feeding one summarizer call —
is real for any busy scope. Cap/steer before the LLM call and make the degrade
intentional.
