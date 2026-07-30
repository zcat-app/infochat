---
id: M1-711
title: "SimpleX mention-strip: multi-mention ordering and mid-text whitespace collapse are untested"
status: done
created: 2026-07-27
last_updated: 2026-07-30
blocked_by: [M1-713]
files_budget: 1
files_scope:
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    `SimpleXGroupHandler.java` itself. This is a test-only ticket: the
    production code is believed correct and simply unpinned. If a new
    test goes red, that is a real defect and an escalation — fix it in a
    ticket of its own rather than quietly repairing production code under
    a coverage ticket.
  - >-
    Mention *anchoring*. Which spans count as bot mentions is decided by
    cryptographic member id per decision D10 and is already pinned by
    `mentionByMemberId_delivered`, `mentionByDisplayName_ignored` and
    `mentionSpanStripIsAnchoredToProtocolEntry_plainTextBotNameKept`.
    This ticket covers what happens to the text *after* the anchor
    decision, not the decision.
  - >-
    The Signal side. `SignalGroupHandler.stripBotMentions` and
    `SignalGroupInboundRobustnessTest.overlapStripIsIdempotentAcrossOverlapShapes()`
    are the working reference this mirrors. The Signal boundary survivors
    at `:220`, `:242` and `:259` are a separate, smaller gap and are not
    claimed here.
  - >-
    `SimpleXMentionParserTest` and the codec-level span extraction. The
    gap is in the handler's strip step, not in parsing the spans.
  - any main-source file, any other module
acceptance:
  - >-
    A test strips two or more bot mentions from one message and asserts
    the exact resulting text. It must fail if
    `SimpleXGroupHandler.java:110`'s descending `botSpans.sort(...)` is
    deleted — verify by actually deleting the sort, watching the test go
    red, and restoring it.
  - >-
    A test delivers a message whose bot mention sits mid-text (spaces on
    both sides) and asserts the junction collapses to a single space —
    the `"hey @bot do x"` → `"hey do x"` behaviour the code comment at
    `:114-117` claims. Today lines `:119`-`:121` are NO_COVERAGE: every
    existing test mentions the bot at offset 0, so the short-circuit
    means the collapse never executes.
  - >-
    Re-running the §Census enumeration leaves no SURVIVED or NO_COVERAGE
    mutant in `SimpleXGroupHandler.stripBotMentions`.
  - >-
    The diff adds test methods only. No existing assertion is weakened,
    retargeted or deleted, and no main source file is touched.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  preserves:
    - >-
      All thirteen existing SimpleXGroupHandlerTest methods, unchanged.
      `coMentionOfOtherMemberNotStripped` and
      `mentionSpanStrippedBeforeDelivery` in particular pin the
      single-mention path and must keep their current assertions — the
      new cases are additions beside them, not replacements.
    - >-
      SignalGroupInboundRobustnessTest.overlapStripIsIdempotentAcrossOverlapShapes()
      — the reference this mirrors, unchanged.
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D10
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 100
      removed: 9
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-711: SimpleX mention-strip: multi-mention ordering and mid-text whitespace collapse are untested

## Context

`docs/spec/messaging.md` §"Required SPI surface" commits that group
messages arrive only when the bot is `@mentioned` and that "the mention
is stripped before delivery (the adapter may do the strip)". Both v1
adapters implement the strip. As with M1-710, only the Signal one has
tests that would notice if it broke.

Found by the 2026-07-27 PIT mutation sweep over
`infochat-messaging-adapter` (`-Pmutation`). Two specific gaps, both in
`SimpleXGroupHandler.stripBotMentions`:

**1. Multi-mention deletion order is unpinned.** The code deletes spans
right-to-left so earlier offsets stay valid:

```java
        // Delete right-to-left so earlier spans' offsets stay valid.
        botSpans.sort((a, b) -> Integer.compare(b.start(), a.start()));
```

Deleting that `sort` call survives. The Signal twin's equivalent sort at
`SignalGroupHandler.java:239` is KILLED by
`overlapStripIsIdempotentAcrossOverlapShapes()`. Same contract, one
adapter tested:

| Site | PIT status | Killed by |
|---|---|---|
| `SignalGroupHandler.stripBotMentions:239` | KILLED | `overlapStripIsIdempotentAcrossOverlapShapes()` |
| `SimpleXGroupHandler.stripBotMentions:110` | **SURVIVED** | — |

Nothing in `SimpleXGroupHandlerTest` sends a message with two bot
mentions, so the ordering that makes multi-mention stripping correct is
asserted by nothing.

**2. The mid-text whitespace collapse never executes.** Lines `:119`-`:121`:

```java
            int junction = span.start();
            if (junction > 0 && junction < stripped.length()
                    && Character.isWhitespace(stripped.charAt(junction - 1))
                    && Character.isWhitespace(stripped.charAt(junction))) {
                stripped.deleteCharAt(junction);
            }
```

PIT reports the `junction > 0` mutants as KILLED but the second
conjunct and both `isWhitespace` checks as NO_COVERAGE. The reason is
short-circuit evaluation: every existing test mentions the bot at offset
0, so `junction > 0` is false and the rest of the condition is never
evaluated. The behaviour the comment above it describes — `"hey @bot do
x"` becoming `"hey do x"` rather than `"hey  do x"` — has never run in a
test.

Neither gap is a failing test today, and neither would ever become one
on its own: the suite is green and stays green with the `sort` call
deleted outright.

## Census

The class is "every mutant in `SimpleXGroupHandler.stripBotMentions`
that no test kills". Enumerated from the sweep's report rather than
assumed:

```bash
python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse('infochat-messaging-adapter/target/pit-reports/mutations.xml')
for m in t.getroot():
    if (m.findtext('mutatedClass','').endswith('SimpleXGroupHandler')
            and m.findtext('mutatedMethod') == 'stripBotMentions'
            and m.get('status') != 'KILLED'):
        print(m.get('status'), m.findtext('lineNumber'), m.findtext('description'))
EOF
```

Run 2026-07-27 (regenerate the report first with the `-Pmutation`
invocation documented in the reactor `pom.xml`) this returns six rows:
`:110` SURVIVED (the removed sort) and five NO_COVERAGE rows across
`:119`-`:121` (the whitespace-junction condition). Both acceptance
criteria above target one of those two groups; the census returning
clean is itself an acceptance item.

## Acceptance

- A multi-bot-mention test asserts exact stripped text and fails when the
  `:110` sort is deleted — verified by deleting it, not by reading.
- A mid-text mention test asserts the whitespace junction collapses to
  one space, executing `:119`-`:121`.
- The §Census enumeration returns no SURVIVED or NO_COVERAGE row for the
  method.
- Test methods added only; no existing assertion weakened or retargeted;
  no main source touched.
- `mvn verify` from the repo root is green.

## Out-of-scope

`SimpleXGroupHandler.java` is not in `files_scope` — this ticket buys
coverage of behaviour believed already correct. Mention anchoring (D10),
the Signal implementation, its three remaining boundary survivors, and
the codec-level span parsing are all untouched.

## Notes

- **If a new test goes red, escalate rather than fix.** The premise is
  that the production code is right and unpinned. A red test falsifies
  that premise, which makes it a defect with its own blast radius
  (multi-mention group messages delivered with corrupted bodies) and its
  own ticket. Repairing `SimpleXGroupHandler.java` inside a ticket whose
  `files_scope` excludes it would be scope drift in the most literal
  sense.

- **`security_relevant: false`.** Mention *anchoring* is a spoofing
  control — `docs/spec/messaging.md` is explicit that a mention is
  decided only by cryptographic contact id (D10), never display-name
  matching, precisely so a peer cannot fake one. That control is not in
  this diff: by the time `stripBotMentions` runs, the anchor decision is
  already made and the spans are known-bot spans. What this ticket
  covers is cosmetic text surgery on the body afterwards. Test-only diff,
  no control changed.

- **Shares a root cause with M1-710.** Same structural failure — a
  contract implemented in both adapters with coverage on the Signal side
  only. Disjoint files, no `blocked_by`; see M1-710 §Notes.
