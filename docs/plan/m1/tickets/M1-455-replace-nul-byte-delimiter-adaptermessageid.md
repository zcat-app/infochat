---
id: M1-455
title: "Replace stray NUL-byte delimiter in adapterMessageId"
status: done
created: 2026-06-25
last_updated: 2026-06-26
blocked_by: []
files_budget: 1
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any change to the adapterMessageId algorithm beyond the single delimiter byte (NUL→space). The id stays `simplex-` + Integer.toHexString(hashCode of the space-joined stableFields); the itemId-present early-return path is untouched."
  - "The delimiter's security role MUST be preserved: the separator stays absent from the queue-address charset [A-Za-z0-9_=.-] so the boundary before the trailing free-text field stays unambiguous (design §6.4.4). A space (U+0020) satisfies this exactly as the NUL did — this is a corruption fix, not a security change, so no threat-model surface moves."
  - "MUST NOT modify any pre-existing test. SimpleXCodecDeterministicIdTest must stay green unchanged (it pins cross-decode determinism + the simplex- prefix, both preserved by a delimiter that is constant within a process)."
  - "Any other change to SimpleXMessageCodec.java or adjacent files — the M1-453 single-pass mention decode, the codec's other methods, InMemoryAdapter, the pom. This ticket is one corrupted byte, nothing else."
acceptance:
  - "SimpleXMessageCodec.adapterMessageId joins its stableFields varargs with a single ASCII space (U+0020) — the separator its own javadoc documents — replacing the stray NUL (U+0000) byte. The itemId-present early-return path and the `simplex-` + Integer.toHexString(hashCode) shape are otherwise unchanged."
  - "SimpleXMessageCodec.java holds zero NUL bytes: `python3 -c \"print(open('infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java','rb').read().count(0))\"` prints 0."
  - "SimpleXCodecDeterministicIdTest stays green unchanged: two decodes of the same itemId-less frame still yield the same adapterMessageId carrying the `simplex-` prefix. No pre-existing test is modified and no new test is added."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - "SimpleXCodecDeterministicIdTest (the itemId-less fallback determinism test) stays green unchanged"
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 11
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-455: Replace stray NUL-byte delimiter in adapterMessageId

## Context

`SimpleXMessageCodec.adapterMessageId` derives a deterministic fallback id
for an itemId-less `newChatItem` frame as
`"simplex-" + Integer.toHexString(String.join(<sep>, stableFields).hashCode())`.
The `<sep>` byte is a stray NUL (U+0000), but the method's own javadoc
documents it as *"joined with a space"* — the byte is an accidental edit
artifact, not a design choice. Discovered while implementing M1-453 (the
2026-06-25 deep-review cleanup batch); it is unrelated to that ticket's
mention-decode fold and so was left for this follow-up per the
surgical-changes rule.

There is **no functional defect**: NUL (U+0000) and space (U+0020) are
behaviorally identical here — both are absent from the validated
queue-address charset, the joined string is hashed (never parsed back),
and the id is ephemeral (never persisted across instances). The harm is
**tooling**: the NUL makes `grep`/`ripgrep`/`file` classify the whole
~900-line source file as binary, so a plain `grep <symbol>` over it
returns no matches — a silent-failure trap in a workflow whose
reviewer / clarity / agent tooling greps source. (`git diff` is
unaffected: the NUL sits past git's first-8 KB binary-detection window,
which is why M1-453's review diff rendered as text.) The fix restores the
delimiter the javadoc already documents and removes the binary-file
hazard.

## Acceptance

See the YAML `acceptance:` list. In short: `adapterMessageId` joins
`stableFields` with a single ASCII space (matching its javadoc), the file
contains zero NUL bytes, the existing itemId-less fallback determinism
test (`SimpleXCodecDeterministicIdTest`) stays green unchanged, and the
full suite is green. No new test is added — the behavior is preserved, so
the existing determinism test is the proof.

## Out-of-scope

See the YAML `out_of_scope:` list. One corrupted byte, nothing else: no
algorithm change beyond NUL→space, the delimiter's security role
(absent-from-charset, design §6.4.4) is preserved, no pre-existing test is
modified, and no adjacent refactor of the codec or its neighbours.

## Notes

- Why a space and not "document the NUL": the existing javadoc already
  states the intended separator is a space, and the byte is an edit
  artifact. Documenting the NUL as intentional would entrench the
  grep-blindness — the whole reason for the fix — so the correct
  direction is code→space, not doc→NUL.
- Verified isolated: a sweep of the entire tracked tree (`git ls-files`)
  found exactly one NUL byte, this one. Not a systemic corruption
  pattern; no root-cause investigation is warranted.
- The fallback path is rare in practice (real simplex-chat frames carry
  `itemId`, so the join almost never executes), but the binary-file
  tooling effect is constant regardless of how often the code runs.
- Adjacent code / reference: the method is
  `SimpleXMessageCodec.adapterMessageId(JsonNode, String...)`; the
  delimiter's role is documented in that method's javadoc and in
  `docs/design/06-messaging.md` §6.4.4.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-455-replace-nul-byte-delimiter-adaptermessageid.md
```
