---
id: M1-453
title: "cleanup: single-pass SimpleX group-mention decode + drop redundant InMemoryAdapter finalized map + fix stale @NonNull pom comment"
status: done
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-core/pom.xml
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Item 1 (codec) MUST NOT change the set of mentions/spans recognised or the validation rules — it folds two existing walks of the same array into one, preserving every current accept/reject outcome. No new field is parsed; the trust-boundary discipline (isValidQueueAddressId per mention) is unchanged."
  - "Item 1 MUST preserve the invariant that mention queue addresses are still returned even when span reconstruction (regionMatches / full-coverage guard) fails — addresses and spans have independent fates (the D10-recognition-survives-failed-span invariant at SimpleXMessageCodec.java:519-521). Do not couple the address list to the span guard."
  - "Item 2 (InMemoryAdapter) is a TEST-DOUBLE adapter (D46 test-time deployment only) — do NOT change its observable behaviour (finalize / double-finalize / history semantics), only remove the internal redundant map. Do not touch the production SimpleX/Signal adapters."
  - "Item 3 (pom comment) is a comment-only change. Do NOT remove or alter the jspecify dependency itself — it stays (it provides @Nullable, which the module uses). Only the stale @NonNull mention in the comment is corrected."
  - "Any unrelated refactor of SimpleXMessageCodec, InMemoryAdapter, or the core pom beyond these three items. The three are independent low-severity items batched into one cleanup ticket; do not let any of them grow into an adjacent rewrite."
acceptance:
  - "SimpleXMessageCodec.decodeGroupNewChatItem walks the untrusted formattedText array exactly once (the two former helpers extractMentionQueueAddresses and extractMentionSpans, which each iterated the array, are folded into a single pass). A new test in SimpleXMessageCodecTest asserts: (a) a group newChatItem whose span reconstruction SUCCEEDS yields the same mention queue addresses AND the same spans as before; (b) a group newChatItem whose span reconstruction FAILS (regionMatches / coverage guard) still yields the correct mention queue addresses with an empty span list — proving the address-survives-failed-span invariant is preserved across the merge."
  - "InMemoryAdapter no longer declares the `finalized` map (the field at InMemoryAdapter.java:78, its write at :148, and its clear at :341 are removed); the already-finalized guard at :359 instead derives finality from the per-handle history list (an isFinal event), and the existing InMemoryAdapterTest / InMemoryAdapterGroupTest finalize and double-finalize assertions stay green unchanged."
  - "infochat-core/pom.xml no longer references a hand-written @NonNull in its dependency comment (engineering-rules §7a: @NonNull is not written by hand; the module uses only @Nullable). `grep -n '@NonNull' infochat-core/pom.xml` returns no match. The jspecify dependency element itself is unchanged."
  - "All test changes are additive (one new SimpleXMessageCodecTest case); this ticket modifies the assertions of NO pre-existing test (no test_plan.modifies)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "SimpleXMessageCodecTest.java — a new case asserting the single-pass group-mention decode preserves (a) addresses+spans on successful span reconstruction and (b) addresses-only (empty spans) when the span guard fails, proving the address/span independence invariant survives the merge."
  preserves:
    - all tests currently green on main (notably the existing InMemoryAdapter finalize / double-finalize tests, unchanged)
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
      files: 6
      added: 188
      removed: 78
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

# M1-453: Low-severity cleanup batch (codec single-pass + InMemoryAdapter map + pom comment)

## Context

Three independent low-severity items surfaced by the 2026-06-25 deep code review,
batched into one cleanup ticket to avoid three separate one-line tickets. They are
unrelated in subject but uniformly small, low-risk, and verified as real (each
survived adversarial re-verification against current code).

1. **(perf) `SimpleXMessageCodec` group-mention decode walks `formattedText`
   twice.** `decodeGroupNewChatItem` calls `extractMentionQueueAddresses`
   (`:489-502`) and `extractMentionSpans` (`:530-545`), each with its own
   `for (JsonNode element : formattedText)` loop over the *same* untrusted array,
   each re-running the `format.type == "mention"` + `isValidQueueAddressId`
   validation. This is the per-inbound-group-message hot path the rest of the
   module deliberately keeps allocation-frugal under hostile flood. The two helpers
   are **not** mechanically identical: `extractMentionSpans` carries a stricter
   reconstruction guard (`text.regionMatches` per segment, full-coverage check) and
   bails to `List.of()` on mismatch, whereas `extractMentionQueueAddresses` collects
   unconditionally. A single-pass merge must therefore **keep the two outcomes
   independent**: addresses are returned even when the span guard fails (the
   `:519-521` D10-recognition-survives-failed-span invariant). Folding to one walk
   also removes the risk of the two `memberRef` validations drifting apart.

2. **(simplify) `InMemoryAdapter` keeps a redundant `finalized` map.**
   `InMemoryAdapter` is a test-double (class Javadoc: "lives in the production
   classpath but is only activated by the test-time deployment shape …
   `infochat.adapters=inmemory` exclusively per D46"). `finalizeMessage` (`:147-148`)
   records finality in *both* the per-handle `history` list (an `UpdateEvent` with
   `isFinal=true`) and a separate `finalized` map; the map is read only by the
   already-finalized guard at `:359`. The same fact is already derivable from
   history (`finalizedBodies()` at `:324-331` iterates history and tests
   `isFinal()`). Removing the map (decl `:78`, write `:148`, clear `:341`) and
   deriving the guard from history is a ~4-line simplification; the O(1)→O(n) lookup
   is negligible at test scale (the class's own thread-safety Javadoc says so).

3. **(doc) `infochat-core/pom.xml` comment names a hand-written `@NonNull`.** The
   comment at `:67` justifies the jspecify dependency as "JSpecify (`@NonNull`)",
   but engineering-rules §7a states `@NonNull` is **not** written by hand (NullAway
   makes non-null the package default); the module uses only `@Nullable`. The
   comment is stale — the dependency stays (it provides `@Nullable`), only the
   `@NonNull` mention is wrong.

The deep review also raised an `InMemoryAdapter`-adjacent constant-time-mention-
compare comment and a `fromConfigName` "case-insensitive" javadoc as candidate
findings; both were **rejected on re-verification** (the mention-compare comment is
defensible as written; `fromConfigName` already lowercases its input, so the doc and
code agree) and are intentionally NOT in this ticket.

## Acceptance

See the YAML `acceptance:` list. In short: one-pass group-mention decode with a new
test proving the address/span independence invariant survives; `InMemoryAdapter`
loses the redundant `finalized` map with its existing finalize tests still green;
the pom comment loses the stale `@NonNull` reference; full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. No behaviour change to recognised mentions/spans,
no change to the test-double's observable semantics, no removal of the jspecify
dependency, and no adjacent refactor of any of the three files.

## Notes

- Item 1 is the substantive item (a careful hot-path merge); items 2 and 3 are
  trivial. The reviewer should weigh item 1 hardest — the merge is only correct if
  addresses are still collected when the span reconstruction guard later voids the
  spans. The new SimpleXMessageCodecTest case is the proof of that invariant.
- Item 2 touches only the test-double; if the history-derived guard turns out to
  read awkwardly, prefer the simplest form that keeps the existing finalize /
  double-finalize tests green rather than introducing a helper.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-453-cleanup-simplex-single-pass-inmemory-map-pom-comment.md
```
