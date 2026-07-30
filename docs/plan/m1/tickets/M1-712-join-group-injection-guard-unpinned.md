---
id: M1-712
title: "encodeJoinGroupCommand's command-injection guard is the one encode entry point no test pins"
status: done
created: 2026-07-27
last_updated: 2026-07-30
blocked_by: [M1-713]
files_budget: 1
files_scope:
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    `SimpleXMessageCodec.java`. The guard is present and correct at
    `:175`; what is missing is the test that holds it there. Test-only
    diff. If the new assertion goes red, the guard is not doing what the
    javadoc claims and that is an escalation, not an inline fix.
  - >-
    The validator itself. `isValidQueueAddressId`, `QUEUE_ADDRESS_CHARSET`
    and the deliberate no-log-the-raw-id rule at `:213-215` keep their
    current behaviour. Widening or narrowing the accepted charset is a
    design change, not a coverage ticket.
  - >-
    The three already-pinned call sites (`encodeEdit:154`,
    `targetSelector:192`, `targetSelector:196`). They are killed by the
    existing `encodeRejectsContactIdWithCommandInjectionChars` and stay
    exactly as they are.
  - >-
    Decode-time validation. `Ignored`-frame handling via the lower-level
    `isValidQueueAddressId` predicate is a different path with different
    semantics (drop, not PERMANENT) and is not touched.
  - >-
    The join flow's runtime behaviour in `SimpleXAdapter` — membership
    transitions, `userAcceptedGroupSent`, the fire-and-forget send. Only
    the codec's encode-time rejection is in scope.
  - any main-source file, any other module
acceptance:
  - >-
    `SimpleXMessageCodecTest` asserts that `encodeJoinGroupCommand`
    rejects a malformed `adapterGroupId` with
    `MessagingException(FailureCategory.PERMANENT)`, using the same
    injection-shaped inputs as the existing
    `encodeRejectsContactIdWithCommandInjectionChars` — at minimum an id
    carrying an embedded newline plus a forged verb, and an empty id.
  - >-
    The new assertion fails if `requireValidQueueAddressId(adapterGroupId,
    "adapterGroupId")` is deleted from `SimpleXMessageCodec.java:175`.
    Verify by actually deleting the line, watching the test go red, and
    restoring it.
  - >-
    Re-running the §Census enumeration shows all four
    `requireValidQueueAddressId` call sites KILLED, none SURVIVED.
  - >-
    The diff adds assertions only. `encodeJoinGroupCommandEmitsJoinByGroupId`
    keeps its current happy-path assertions unchanged — the refuse-leg is
    added beside it, not folded into it.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - >-
      encodeRejectsContactIdWithCommandInjectionChars() in full — it is
      the pattern this extends and it currently kills three of the four
      call sites. Do not retarget it onto the join path; that would move
      coverage rather than add it.
    - >-
      encodeJoinGroupCommandEmitsJoinByGroupId() — the happy path,
      unchanged.
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
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
      files: 4
      added: 141
      removed: 9
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-30
    verdict: CLEAN
    base: e0710a44
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-712-2026-07-30.md
    out_of_model_count: 0
    note: |
      Pre-review gate audit (/m1-tick run step 4) over the uncommitted
      branch diff. Test-only change: adds the refuse-leg assertion for
      encodeJoinGroupCommand and touches no main-source file, so it adds
      no attack surface. The guard it pins remains present and enforcing.
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-712: encodeJoinGroupCommand's command-injection guard is the one encode entry point no test pins

## Context

`SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars`
opens with a claim about the whole codec:

```java
        // Defense-in-depth (design §6.4.4): every encode entry point
        // re-asserts the validator on the ScopeRef's contactId /
        // adapterGroupId, and on chatItemId for the edit paths.
```

The claim is true of the *code*. It is not true of the *test*: the
2026-07-27 PIT mutation sweep over `infochat-messaging-adapter`
(`-Pmutation`) shows that of the four `requireValidQueueAddressId` call
sites, three die when deleted and one does not.

| Call site | PIT status | Killed by |
|---|---|---|
| `encodeEdit:154` | KILLED | `encodeRejectsContactIdWithCommandInjectionChars()` |
| `targetSelector:192` | KILLED | `encodeRejectsContactIdWithCommandInjectionChars()` |
| `targetSelector:196` | KILLED | `encodeRejectsContactIdWithCommandInjectionChars()` |
| `encodeJoinGroupCommand:175` | **SURVIVED** | — |

`encodeJoinGroupCommand` is covered — `encodeJoinGroupCommandEmitsJoinByGroupId`
kills the return-value mutant one line below, at `:176`. What that test
pins is the happy path. Delete the validator call and it still passes.

This is the refuse-leg pattern: a boundary guard whose accept path is
tested and whose reject path is not.

**What the guard is for.** `encodeJoinGroupCommand` interpolates its
argument straight into a simplex-chat CLI verb:

```java
        requireValidQueueAddressId(adapterGroupId, "adapterGroupId");
        return envelope(corrId, "/_join #" + adapterGroupId);
```

Without the validator, an `adapterGroupId` carrying a newline and a
second verb — the exact shape the existing test uses for contact ids,
`"group\n/_set_contact_typing @v on"` — is concatenated into the command
string. The guard is what stops that, and it is doing its job today. The
defect is that nothing would notice if a future refactor dropped it: the
suite is green with the line deleted.

That is precisely the failure mode CLAUDE.md §"Preserve the controls of
a path you replace" exists to prevent, seen one step earlier — a control
with no test is one reroute away from being silently gone.

## Census

The class is "every `requireValidQueueAddressId` call site, and whether a
test kills its removal".

```bash
grep -n "requireValidQueueAddressId(" \
  infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java

python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse('infochat-messaging-adapter/target/pit-reports/mutations.xml')
for m in t.getroot():
    if 'requireValidQueueAddressId' in (m.findtext('description') or ''):
        print(m.get('status'), m.findtext('mutatedMethod'), m.findtext('lineNumber'))
EOF
```

Run 2026-07-27 the grep returns four call sites plus the helper's own
declaration at `:211`, and the mutation query returns the four-row table
above. Re-run at `start`: a fifth call site means the class grew and this
ticket's scope is wrong. The census returning all-KILLED is an acceptance
item.

## Acceptance

- A refuse-leg assertion covers `encodeJoinGroupCommand` with
  injection-shaped and empty `adapterGroupId` values, asserting
  `FailureCategory.PERMANENT`.
- The assertion is verified to fail when `:175` is deleted — checked by
  deleting it, not by reading the test.
- The §Census shows four of four call sites KILLED.
- Assertions added only; the existing happy-path and contact-id tests
  keep their current shape.
- `mvn verify` from the repo root is green.

## Out-of-scope

`SimpleXMessageCodec.java` is not in `files_scope`. The validator, the
charset, the deliberate no-log-the-raw-id rule, the three already-pinned
call sites, decode-time `Ignored`-frame handling, and the adapter-side
join flow are all untouched.

## Notes

- **`security_relevant: true`.** The guard this ticket pins is a
  command-injection defence on a string interpolated into a simplex-chat
  verb, and the existing test that covers its siblings says so in its own
  name. The diff is test-only and adds no attack surface, but the
  `/redteam` gate is cheap here and the flag should follow the control,
  not the diff size. This is a coverage gap, **not** a live
  vulnerability: the guard is present and enforcing today.

- **Do not retarget the existing test.** The obvious shortcut is to add
  the join case to `encodeRejectsContactIdWithCommandInjectionChars`.
  That is fine as placement, but the three existing assertions inside it
  must survive intact — they are what currently kills `:154`, `:192` and
  `:196`. An "add the join case" edit that reshapes the method into a
  parameterised loop and drops one of the original inputs would trade
  coverage for tidiness.

- **Found by mutation testing, not review.** `:175` has been unpinned
  since the join path was written and neither code review nor the green
  suite surfaced it. Sibling tickets from the same sweep: M1-710
  (SimpleX outbound pacing) and M1-711 (SimpleX mention-strip).
