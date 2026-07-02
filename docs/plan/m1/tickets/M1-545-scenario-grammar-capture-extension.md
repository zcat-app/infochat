---
id: M1-545
title: Scenario grammar capture/substitution extension
status: pending
created: 2026-07-02
last_updated: 2026-07-02
blocked_by:
  - M1-544
files_budget: 5
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any live-transport backend change (LiveSimpleXClient /
    SimpleXConversationBackend — that is M1-546)
  - new scenario resource files for the 7 live scenarios (M1-546)
  - any main-scope (production) code change — test-scope only
  - changing existing scenario directives' semantics (send/expect are a
    strict superset after this ticket)
acceptance:
  - Scenario grammar gains "capture <name> <java-regex-with-one-group>" as an
    optional directive after an expect; it binds the first capturing group of
    the regex applied to that step's MATCHED reply to <name>.
  - Send text (and send address tokens) support "${name}" placeholders,
    substituted from previously captured bindings at run time by the runner;
    an unbound placeholder fails the run loudly (names the step and the
    placeholder), never sends the raw "${...}" text.
  - The existing golden-path-smoke scenario and ScenarioRunnerIT pass
    unchanged (grammar superset), and a new InMemory-backed test proves the
    invite flow end-to-end declaratively — admin mints an invite, the code is
    captured from the reply, a fresh contact registers by sending the
    captured code, and receives the welcome reply.
  - mvn verify is green.
test_plan:
  adds:
    - an InMemory-backed @QuarkusTest proving capture+substitution drives the
      invite mint→consume flow declaratively (exact class name bound at
      implementation; likely extending ScenarioRunnerIT)
  preserves:
    - all tests currently green on main, including ScenarioRunnerIT unchanged
spec_refs:
  - docs/spec/verification.md §Test tiers
decision_refs:
  - D-live-5
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-545: Scenario grammar capture/substitution extension

## Context

Live-e2e Phase 4b-3 must run scenarios 3 and 15 (invite mint → consume), which
require a value produced in one step's reply (the invite code UUID) to be sent
in a later step. The M1-539 grammar is a fixed send/expect list with no data
flow, so these scenarios cannot be expressed declaratively — the enumeration
recovered into `docs/plan/live-e2e/README.md` §Phase 1 shows exactly where
this bites. A minimal capture/substitute mechanism closes the gap while
keeping scenarios transport-agnostic (the binding stays in the backend; the
data flow lives in the runner core).

Grammar addition (one directive, one placeholder form):

```
send dm    admin /invite create
expect substring 5000 Invite code:
capture code ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})

send dm    user ${code}
expect substring 5000 Welcome
```

## Notes

- `capture` applies to the immediately preceding step's matched reply; a
  scenario may bind multiple names; later captures may shadow earlier ones.
- Substitution applies to send text AND address tokens (a captured group id
  could route a later group step).
- Unbound `${name}` at send time = hard failure naming step + placeholder
  (never leak the literal `${...}` to a transport).
