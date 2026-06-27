---
id: M1-504
title: "SimpleX adapter cannot start: queue-address length floor (43) rejects every real bot address (32 chars)"
status: done
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 6
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Any change to the QUEUE_ADDRESS_CHARSET grammar or extractQueueAddressId extraction logic — both are correct against the real link; only the length floor is wrong."
  - "Replacing the length-floor heuristic with full SMP-URI structural validation (recorded as the Alternatives-considered approach; larger surface, separate ticket)."
  - "Making SafeLog surface the IllegalStateException message/stack so this class of startup failure is diagnosable from prod logs (real diagnosability gap, but a separate concern — file as follow-up)."
  - "Any Signal-adapter identity change; only SimpleX queue-address well-formedness is in scope."
acceptance:
  - >-
    SimpleXIdentity.isWellFormed accepts a real SimpleX queue-address id as
    derived from simplex-chat v6.5.4 — a 24-byte URL-safe-base64 value, 32
    characters (e.g. the path segment of the SMP URI in a live /show_address
    contact link). The MIN_QUEUE_ADDRESS_LENGTH floor and its javadoc are
    corrected to the actual SimpleX queue-id width, not the assumed 32-byte/43-char value.
  - >-
    isWellFormed still rejects the short values a length floor can catch: the
    empty string and a short kebab-case slug (e.g. "short-mistyped-slug",
    below the >= 32 floor). A 36-char Signal ACI UUID
    ("00000000-0000-0000-0000-000000000001") is NO LONGER rejected by the
    length gate and that is accepted (refine 2026-06-27): the UUID is longer
    than the 32-char real address, so any floor that admits the real address
    also admits it — a length floor cannot exclude it. This is a known, minor
    M1-208 fail-fast regression (a Signal ACI pasted into the SimpleX admin
    slot now passes the format gate and seeds an unclaimable admin row instead
    of failing fast); see Notes. The pre-existing non-base64 rejection (a value
    with a forbidden character such as '+' or whitespace) is still rejected by
    the charset and stays asserted.
  - >-
    A test exercises the full startup derivation path (FakeSimpleXProcess /
    SimpleXSelfAddressFixture) with a REAL-LENGTH 32-char queue id and asserts
    SimpleXAdapter.start()/deriveAndAdoptIdentity adopts it without throwing
    IllegalStateException — the synthetic >=43-char fixtures
    (QUEUE_A/QUEUE_B in SimpleXAdapterIdentityDerivationTest, WELL_FORMED in
    SimpleXIdentityTest, the 44-char strings in ContactIdWellFormednessTest)
    are updated to realistic 32-char ids so the green suite reflects the real wire format.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "A real-length acceptance case in infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentityTest.java"
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/design/06-messaging.md#6.4.4"
decision_refs:
  - D10
  - D32
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 252
      removed: 32
escalations:
  - date: 2026-06-27
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — surfaced during implementation, not review. A length floor cannot
      satisfy BOTH acceptance item 1 (accept the 32-char real address) AND item 2
      (reject the 36-char Signal ACI UUID): the UUID is LONGER than the real
      address, so any floor <= 32 that admits the real address also admits the
      UUID. The only length-based fix that rejects the UUID is an exact-width
      check (== 32), which breaks 4 out-of-scope provider fixtures (44-45-char
      synthetic simplex.admin values in ProductionAdapterActivationTest,
      MultiAdapterProductionIT, BootstrapAdminParseGateTest, AdminBootstrapIT)
      and exceeds files_budget: 6.
revisions:
  - date: 2026-06-27
    reason: premise-fail (round 1 rework)
    note: >-
      User approved (2026-06-27) keeping a >= 32 length floor and amending
      acceptance item 2 to DROP the 36-char Signal-ACI-UUID rejection, rather
      than an exact-width (== 32) check that would break 4 out-of-scope provider
      fixtures and exceed files_budget. Accepted cost: a minor M1-208 fail-fast
      regression — a Signal ACI pasted into the SimpleX admin slot now passes
      the format gate and seeds an unclaimable admin row instead of failing fast.
    prior_acceptance_item_2: >-
      isWellFormed still rejects the values the floor was meant to catch: the
      empty string, a short kebab-case slug (e.g. "short-mistyped-slug"), and a
      36-char Signal ACI UUID pasted into the SimpleX slot
      ("00000000-0000-0000-0000-000000000001") — verify the UUID case explicitly,
      since lowering the floor narrows the margin that previously rejected it.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-27
    verdict: CLEAN
    base: 31f5ca78f2829a3acb641c223f03875b27f1c937
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-504-2026-06-27.md
    out_of_model_count: 1
    note: |
      CLEAN. One out-of-model item: the accepted M1-208 fail-fast regression
      (a 36-char Signal ACI now passes the simplex.admin format gate). The
      threat-actor classifies it as an operator-misconfiguration risk, not an
      adversary-reachable path (security.md §Trust boundaries; §Authorization
      model "Last-admin protection — Blind spot"). No remediation ticket
      warranted on security grounds.
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings:
    - "spec_ref docs/design/06-messaging.md#6.4.4 uses '#' separator rather than ' §'; section unambiguously resolves (line 532, '6.4.4 Event decoding') — normalize to ' §6.4.4 Event decoding' for convention."
  blockers: []
---

# M1-504: SimpleX adapter cannot start — queue-address length floor (43) rejects every real bot address (32 chars)

## Context

Observed in the running `vps`-profile deployment (2026-06-27): the Provider
boots but the SimpleX adapter never comes up, so the bot is unreachable and
incoming contact requests sit unanswered. The only log line is:

```
ERROR [...MessagingStartup] Adapter simplex failed to start; continuing with
the remaining adapters | exception=java.lang.IllegalStateException
```

(SafeLog strips the message and stack, so the cause is invisible from the
log alone — see the out-of-scope diagnosability note.)

**Root cause (verified empirically, deterministic — not a race).**
`SimpleXAdapter.start()` derives the bot's own queue address — the D10 trust
anchor — by querying the running simplex-chat (`/show_address`) and adopting
the result via `adoptBotQueueAddress` (`SimpleXAdapter.java:399-409`), which
gates on `SimpleXIdentity.isWellFormed`. That gate requires
`length >= MIN_QUEUE_ADDRESS_LENGTH` where `MIN_QUEUE_ADDRESS_LENGTH = 43`
(`SimpleXIdentity.java:28`). Its javadoc assumes "the smallest such identifier
is a 32-byte value, whose base64 form is 43 characters."

That assumption is wrong. The queue id in a real SimpleX contact link (the SMP
URI path segment `smp://<keyhash>@<host>/<queueId>#…`) is a **24-byte** value =
**32** URL-safe-base64 characters. So every real derived address fails the
floor, `adoptBotQueueAddress` throws `IllegalStateException`, and
`MessagingStartup`'s §6.7 per-adapter catch logs it and leaves the adapter down.
Because the address the bot derives is always 32 chars, this fails on **every**
startup regardless of timing — the adapter can never start with this binary.

**Evidence.** A live `/show_address` WebSocket query against the production
data-dir (same simplex-chat v6.5.4 binary, same data-dir the adapter reads)
returns `resp.contactLink.connLinkContact.connFullLink` (structure matches the
codec exactly). Running the real `extractQueueAddressId` over it yields a
32-char id that passes the charset `^[A-Za-z0-9_=.-]+$` but fails `>= 43`, so
`isWellFormed = false`. The bot's address itself is valid and working
(`/show_address` prints a shareable link); only the in-code floor is wrong.

**Why the green suite missed it.** Every SimpleX address fixture is synthetic
and `>= 43` chars — `QUEUE_A = "BotQueueAddrDerivedFromShowAddress000000001A"`
(44), `WELL_FORMED` in `SimpleXIdentityTest`, the 44-char
`SimplexBootstrapAdminQueueAddr…` in `ContactIdWellFormednessTest`. No test ever
fed a real-length 32-char id through the gate, so the wrong floor passed CI.

## Acceptance

See frontmatter. Correct `MIN_QUEUE_ADDRESS_LENGTH` (and its javadoc) to the
real SimpleX queue-id width, keep the empty/slug/short-value rejections, and
re-base the test fixtures on a realistic 32-char id so a real derived address is
proven to start the adapter.

## Out-of-scope

See frontmatter. The charset grammar and `extractQueueAddressId` are correct and
untouched; the structural-validation redesign and the SafeLog diagnosability gap
are separate tickets.

## Notes

- Accepted regression (refine 2026-06-27, user-approved): the corrected gate is
  a `>= 32` length floor. Because the 36-char Signal ACI UUID is *longer* than
  the 32-char real queue address, no floor that admits the real address can
  exclude the UUID — so a Signal ACI pasted into `infochat.adapters.simplex.admin`
  now passes the well-formedness gate and seeds an admin row no real SimpleX
  contact can claim, rather than failing fast (the M1-208 fail-fast property,
  for this one cross-adapter-paste case, is lost). Restoring it would require an
  exact-width or SMP-URI structural check that rejects the longer value; that is
  the structural-validation alternative already deferred below. The exact-width
  (`== 32`) variant was rejected here because it breaks four out-of-scope provider
  fixtures (44-45-char synthetic `simplex.admin` values) and exceeds files_budget.
- `Alternatives considered:` instead of a raw length floor, validate the full
  SMP-URI structure (scheme, authority, queue segment) at adoption. More robust
  against future malformed shapes, but a larger surface than this defect needs —
  recorded for a follow-up, not done here.
- Verify the corrected floor against the SimpleX protocol's documented queue-id
  width (24-byte recipient queue id → 32 base64url chars), not just the one
  captured sample, so the value is principled rather than fitted to one address.
- Diagnosability follow-up: the failure surfaced only as a bare
  `java.lang.IllegalStateException`. Consider a separate ticket to let
  trust-anchor-derivation failures name their cause without leaking the address
  (D37), so the next config/format drift is one log line to diagnose.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-504-*.md
```
