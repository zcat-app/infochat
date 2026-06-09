---
id: M1-249
title: "Provider lows: RateCapBucket dedup, ban null-guard, NOTIFY parse"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerParseTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - InboundRouter and BanCheck — owned by M1-244; this ticket does not touch the intake path.
  - The bucket maps' tuning values (caps, refill windows) — unchanged; T11 is a dedup of the acquire bodies, not a policy change.
  - The NOTIFY payload schema / emit side — unchanged; T30 only hardens the provider-side parse.
acceptance:
  - "T11: RateCapBucket's parallel tryAcquire* methods delegate to one private tryAcquireFrom(map, key, cap, refillWindow) helper carrying the single synchronized refill/decrement body, so a refill change lives in one place. RateCapBucketTest asserts each public method (per-user, group, group-llm, group-command, stranger) still enforces its cap and refills on its window — behavior identical."
  - "T29: BanCommandHandler.lookupUser drops the adapter == null arm of its guard (adapter is the package-default non-null parameter — engineering-rules §No defensive code / §Method parameter contracts); the contactId == null check STAYS because contactId is declared @Nullable. The existing ban-command tests stay green."
  - "T30: NewPostListener.parsePayload does not put raw inbound JSON into the exception message / log on a parse failure (info-leak hygiene on the NOTIFY-deserialization path); NewPostListenerParseTest asserts a malformed payload is rejected without echoing the raw JSON and a well-formed payload parses to the expected post reference. (The regex stays find()-based — field extraction from a JSON string is what find() is for; do not 'anchor' it.)"
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerParseTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-09
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.adds lists RateCapBucketTest.java
      but that file already exists (555 lines, 18 @Test). T11 requires adding a
      tryAcquireGroupReply cap/refill test that does not yet exist, so the file
      will be MODIFIED, not created.
revisions:
  - date: 2026-06-09
    reason: "clarity-fail rework — clarity FAIL (TEST-CHANGES-AUTHORIZED) flagged RateCapBucketTest.java as a pre-existing 555-line / 18-@Test file wrongly listed under test_plan.adds. T11's acceptance requires asserting the group-reply bucket (tryAcquireGroupReply, RateCapBucket.java:346) enforces its cap and refills, but the existing test file has no group-reply test, so the file will be MODIFIED. Fix: move RateCapBucketTest.java adds→modifies and add an Authorized-test-changes section naming the new group-reply test method(s). Clarity WARN folded in: T29 touches BanCommandHandler (ban-handling), so security_relevant false→true for audit traceability (change itself non-behavioral)."
    prior_values: |
      status (pre-refine, transient): escalated (clarity-fail; never committed as in-progress)
      security_relevant (pre-refine): false
      test_plan (pre-refine):
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
          - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerParseTest.java
        preserves:
          - all tests currently green on main
      (no test_plan.modifies block; no Authorized-test-changes section in body)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-249: Provider lows

## Context

Three low-severity `infochat-provider` findings bundled by module. Source:
`deep-code-review/v3/` UNIFIED-REPORT.md T11 (mimo `07#F3`), T29 (mimo `07#F5`),
T30 (deepseek `#F3`+`#F4`).

- **T11 [medium→simplification].** `RateCapBucket` has parallel bucket maps and
  multiple `tryAcquire*` methods sharing one `synchronized (bucket)`
  refill/decrement body — a refill bug must be fixed in each copy. Extract one
  parameterized helper; not a new abstraction.
- **T29 [low].** `BanCommandHandler.lookupUser`'s guard tests `adapter == null ||
  contactId == null`. `adapter` is package-default non-null, so its arm is a
  defensive check between two internal classes (engineering-rules §No defensive
  code). `contactId` is `@Nullable`, so *that* arm is legitimate and stays —
  only the `adapter == null` arm is removed.
- **T30 [low].** `NewPostListener.parsePayload` puts raw inbound JSON into the
  exception/log on parse failure — worth tightening (info-leak hygiene) on this
  NOTIFY-deserialization path. (The report's companion "unanchored `find()`"
  framing is dropped: `find()` is the correct method for extracting a field value
  from a JSON string; there is nothing to anchor.)

## Acceptance

See frontmatter. In prose: collapse the token-bucket acquire bodies into one
private helper; remove the impossible-null guard; anchor the NOTIFY parse and
keep raw JSON out of logs/exceptions. Named tests pin behavior; `mvn verify` is
0.

## Out-of-scope

See frontmatter. The intake path (M1-244), bucket tuning values, and the NOTIFY
emit side are untouched.

## Authorized test changes

- `RateCapBucketTest.java` (pre-existing, 555 lines / 18 @Test — `modifies`):
  add a `tryAcquireGroupReply` cap/refill test so T11's "group … still enforces
  its cap and refills on its window" is pinned (the file currently has no
  group-reply coverage), e.g. `groupReplyOverCapReturnsFalseAfterExhaustion`
  and `groupReplyRefillsOverWindow`. The pattern mirrors the existing
  group-llm / group-command tests. All 18 pre-existing @Test methods remain
  green — no existing assertion is changed, weakened, or deleted.

## Notes

- T11 is the only non-trivial leg; the seam is a single `tryAcquireFrom(map, key,
  cap, refillWindow)` private method each public method delegates to. Confirm the
  maps differ only in identity (not in refill semantics) before unifying.
- T30 is on a deserialization boundary, so an *anchored* validating parse is
  legitimate (not defensive-code drift) — but the data is internal NOTIFY
  payload, so don't over-validate beyond shape.
</content>
</invoke>
