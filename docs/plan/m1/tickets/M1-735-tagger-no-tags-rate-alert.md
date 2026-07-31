---
id: M1-735
title: "A tagger that answers empty to every post emits no operational signal"
status: pending
created: 2026-07-31
last_updated: 2026-07-31
blocked_by: [M1-726]
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - docs/spec/llm.md
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The per-post no-tags outcome itself — `{"tags":[]}` resolving to
    `tags='{}'` with no retry, no fallback, no per-post notification is
    M1-726's intended behavior and stays exactly as it is. This ticket
    adds the AGGREGATE detector on top of it; it does not re-persecute
    the single untagged post.
  - >-
    The retry-backoff policy, the schema-violating/zero-valid failure
    paths, and the bootstrap fallback mechanism. Unchanged — the
    existing `tagger.fallback_to_bootstrap` notification keeps its
    current meaning and trigger.
  - >-
    The invalid-rate observability already cited in `spec/llm.md`
    ("sustained high invalid rates surface an operator alert") — that
    sentence covers N-valid + M-invalid counting on NON-empty outcomes.
    This ticket covers the disjoint case the invalid-rate counter
    cannot see: every outcome empty (N=0 AND M=0 on every post).
  - >-
    Re-evaluation of `tags='{}'` posts (M1-736) and any provider-side
    change. The detector lives entirely in the collector's tagger
    stage.
acceptance:
  - >-
    When the share of no-tags outcomes among the tagger's recent
    completions exceeds a configured threshold over a minimum sample,
    `ThrottledAdminNotifier` fires with a DISTINCT error class (not
    `tagger.fallback_to_bootstrap` — the two conditions have different
    meanings and different operator runbooks). Window size, minimum
    sample and threshold live in config with the values recorded in
    `docs/design/05-llm-and-embeddings.md` §5.4.2. The throttle
    semantics are the existing per-key coalescing, so a sustained
    condition alarms once per cooldown, not per post.
  - >-
    A normal trickle of untaggable posts fires NOTHING: a test runs
    the window with a no-tags share below threshold and asserts no
    notification state for the new error class. One untaggable post is
    M1-726's normal outcome and must never alarm.
  - >-
    The all-empty run fires it: a test drives every outcome to
    no-tags past the minimum sample and asserts the notification fired
    — this is the M1-726 round-1 LOW red-team finding's repro, closed.
  - >-
    Cold start does not fire: below the minimum sample the window is
    silent even at 100% no-tags, so a fresh collector tagging its
    first handful of posts cannot false-alarm. Pinned by test.
  - >-
    `docs/spec/security.md` §Failure handling gains the replacement
    detector commitment (e.g. "a sustained all-empty tagger output
    surfaces a throttled admin alert"), so a wholly non-functioning
    tagger stage is once again a spec-committed observable condition —
    the commitment the M1-726 diff removed without replacement.
    `docs/spec/llm.md` §Failure handling's observability sentence and
    `docs/design/05-llm-and-embeddings.md` §5.4.2 record the new
    counter and its parameters.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
      — below-threshold no-tags share fires nothing; all-empty past
      the minimum sample fires the new error class; cold start below
      the minimum sample fires nothing even at 100% no-tags; the
      existing `tagger.fallback_to_bootstrap` class is NOT fired by
      no-tags outcomes (the classes stay distinct).
  preserves:
    - >-
      Every existing TaggerWorkerTest / TaggerWorkerIT assertion,
      including the M1-726 no-tags-without-retry-or-notification
      per-post contract — the aggregate alert must not leak into the
      per-post path.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Failure handling
decision_refs:
  - D19
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-735: a tagger that answers empty to every post emits no operational signal

## Context

M1-726 makes a clean `{"tags":[]}` a terminal per-post success: no
retry, no bootstrap fallback, no per-post notification. That is the
correct disposition for a genuinely untaggable post — but it removed
the only spec-committed detector for a wholly non-functioning tagger
stage (M1-726 round-1 red-team finding, LOW, AUDIT-EVASION). A
degraded or hostile TAGGER endpoint that answers `{"tags":[]}` to
EVERY request now drives the entire corpus to `tags='{}'` while
producing zero operational signal: no `tagger_fallback` row marker, no
WARN, no `ThrottledAdminNotifier` call. The replacement observability
the M1-726 diff cites — "sustained high invalid rates surface an
operator alert" (`docs/spec/llm.md` §Failure handling) — cannot fire
either, because the all-empty case reports N=0 valid AND M=0 invalid
on every post. Per `docs/spec/security.md` §Trust boundaries item 9, a
hostile or compromised endpoint is in scope, and a model regression
after an operator model swap produces the identical reply.

The distinguishing datum is the RATE, not the post. Normal operation
produces a trickle of no-tags outcomes (birthday wishes, cat videos —
M1-726's motivating cases); a dead tagger produces ~100%. A counter
over the recent completion window with a minimum sample separates the
two without ever alarming on a single legitimately untagged post.

## Acceptance

See the `acceptance:` frontmatter — the four behavioral items (fires
on sustained all-empty, silent on a normal trickle, silent on cold
start, distinct error class) plus the spec amendment restoring a
committed detector in `docs/spec/security.md` §Failure handling.

## Out-of-scope

The per-post no-tags outcome (M1-726 owns it), the existing failure
paths and their notification, the invalid-rate alert's cadence and
threshold, the `tags='{}'` re-evaluation sweep (M1-736), and anything
provider-side. If the implementation wants a new counter class rather
than fields on `TaggerWorker`, that is fine — `files_scope` covers the
worker and its test; a NEW collector main class plus its test file is
the anticipated shape, which is why `files_budget` is 6, not 5.

## Notes

- An in-memory sliding window on the collector (single instance per
  deployment) is acceptable: a restart resets the window, and a
  genuinely sustained condition re-fires after the minimum sample
  refills — restart-blindness is bounded by the window size. A
  DB-derived share over recent `tagger_done` posts is the durable
  alternative; either satisfies the acceptance items, and the choice
  is the implementer's.
- Adjacent code: `TaggerWorker`'s `AttemptKind.NO_TAGS` distinction
  (M1-726) is what makes the outcome countable at all;
  `ThrottledAdminNotifier.notifyOnce` and the
  `tagger.fallback_to_bootstrap` key are the existing throttling
  pattern to mirror.
- Lineage: this ticket carries the M1-726 round-1 LOW red-team
  finding. `remediates:` does not apply (M1-726 is not `done`); the
  link is recorded here and in M1-726's §Escalation resolution.
