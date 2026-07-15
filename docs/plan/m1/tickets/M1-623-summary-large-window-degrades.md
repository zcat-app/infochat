---
id: M1-623
title: "/summary degrades ungracefully on a large post window (cap before the summarizer LLM call)"
status: done
created: 2026-07-15
last_updated: 2026-07-15
reviews:
  - round: 1
    date: 2026-07-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 171
      removed: 7
clarity_check:
  date: 2026-07-15
  verdict: WARN
  warnings:
    - >-
      Acceptance item 1's example text "narrow with --since" names a flag /summary
      does not have; the real narrowing flag is -w <duration> (SummaryArgs.java:75).
      Do not ship the example verbatim.
    - >-
      out_of_scope entry 2 describes a "remote-llm=500 post render cap" distinct from
      the LLM prompt, but no separate render cap exists: infochat.summary.cluster-cap
      bounds both the SQL retrieval and the per-cluster generation fan-out (one LLM
      call per cluster); reply.summary.cap_excess_notice prefixes the reply but does
      not skip generation.
    - >-
      Acceptance items 1 and 3 refer to "the cap" without pinning whether it is the
      existing infochat.summary.cluster-cap retuned or a new distinct threshold; left
      as implementer discretion.
  blockers: []
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
