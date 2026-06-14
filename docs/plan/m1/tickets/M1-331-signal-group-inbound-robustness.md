---
id: M1-331
title: "Signal group inbound: coalesce overlapping bot-mention spans + instanceof-guard JSON accessors"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParser.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SimpleX sibling (SimpleXGroupHandler) — it operates on protocol-validated spans from the codec's reconstruction guard, so it is not the exposed surface; not touched here.
  - The per-span individual validation (start/length bounds, (long)-widening overflow guard) — already correct; this ticket ADDS overlap coalescing on top, it does not change the per-span checks.
  - The getString(name, default) / getInt(name, default) accessors — already safe (they return the default on type mismatch); only the no-default casting accessors (getJsonObject/getJsonArray) are converted.
acceptance:
  - "stripBotMentions coalesces overlapping/adjacent bot-mention spans BEFORE applying deletions: collected spans are sorted ascending, merged left-to-right into non-overlapping intervals (span[0] <= lastMerged[1] extends the interval), then stripped right-to-left over the merged set. Any overlap shape an untrusted Signal peer can author (e.g. {start=5,length=10} and {start=8,length=10}) yields a single contiguous, well-defined strip rather than the order-dependent StringBuilder.delete clamping that silently mutilates the body today."
  - "A test pins the overlap case: a mentions array with two overlapping bot-uuid spans strips to the same well-defined result as a single merged span (the body is not corrupted), and is idempotent across overlap shapes. The existing single-mention / non-overlapping behavior is unchanged."
  - "SignalGroupHandler.handleReceive and SignalMentionParser.botMentioned use the codec's instanceof-guard discipline instead of the casting accessors getJsonObject(name)/getJsonArray(name): a field that is present-but-wrong-typed (e.g. attacker-supplied \"envelope\":\"x\", \"mentions\":\"x\", \"memberJoined\":5) collapses into the same 'not usable -> drop' branch as an absent field, rather than throwing ClassCastException. This makes the boundary guard intrinsic to the handler rather than resting on the incidental catch(RuntimeException) one layer up in SignalJsonRpcClient.dispatchGroupNotification."
  - "A test pins the type-mismatch case: a group frame with a present-but-wrong-typed envelope/dataMessage/groupV2/mentions/memberJoined/memberLeft field is dropped cleanly (no exception escapes handleReceive / botMentioned), matching the spec-sanctioned silent drop. Well-formed signal-cli frames behave unchanged."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (overlap + type-mismatch cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 317
      removed: 20
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: 9a001c51a17ea615fa1ec41597e88ca5124ac60b
    head: "working-tree (worktree-M1-331, uncommitted)"
    verdict_file: docs/plan/m1/redteam/M1-331-2026-06-14.md
    out_of_model_count: 0
    note: |
      In-progress adversarial review of the uncommitted branch tip between
      review APPROVE and commit. Surface: Signal group adapter-inbound parse
      (overlap-span coalescing + instanceof-guarded JSON accessors). CLEAN —
      net-positive boundary hardening, no gap vs §Trust boundaries, nothing
      to remediate before commit.
---

# M1-331: Signal group inbound — robustness against untrusted wire data

## Context

Two deep-review v5.5 findings on the same file
(`SignalGroupHandler.java`), both about untrusted Signal-peer wire data at the
adapter-inbound system boundary:

- **opus-47 `05-module-infochat-messaging-adapter.md` F1** — `stripBotMentions`
  validates each `(start, length)` span individually but never coalesces
  overlapping spans before applying right-to-left deletions. **Verified at source
  2026-06-14:** spans are sorted right-to-left (SignalGroupHandler.java:230) and
  deleted sequentially with no merge step; the per-span guard already recognizes
  "attacker-controlled wire ints" (comment at :216-220). A hostile peer authoring
  overlapping bot-`uuid` spans makes `StringBuilder.delete` clamp/over-delete and
  silently mutilates the inbound body.

- **opus-48 `05-module-infochat-messaging-adapter.md` F1** — `handleReceive` and
  `SignalMentionParser.botMentioned` use casting accessors
  (`getJsonObject`/`getJsonArray`) that throw `ClassCastException` on a
  present-but-wrong-typed field, against the codec's own `instanceof`-guard
  discipline (`SignalMessageCodec.decode`/`extractDm`, pinned by
  `SignalGroupTimestampGuardTest`). **Verified at source 2026-06-14:** casting
  accessors at SignalGroupHandler.java:105,109,113,131,132,201. The blast radius
  is contained today by the `catch(RuntimeException)` in
  `dispatchGroupNotification`, but the protection is incidental (one layer up) and
  the class is documented as testable standalone.

Both harden the same boundary against the same threat model, so they are fixed
together.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Overlap fix: one extra ascending sort + linear merge (O(n log n), n typically
  1–3) before the existing right-to-left strip. Idempotent against any overlap
  shape.
- `instanceof` collapses absent and wrong-typed into one "drop" branch — exactly
  the handler's intent — matching the pattern the codec already documents and a
  test already pins.
