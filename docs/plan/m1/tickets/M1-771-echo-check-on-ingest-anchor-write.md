---
id: M1-771
title: "Echo check on the ingest anchor write"
status: pending
created: 2026-08-05
last_updated: 2026-08-05
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A TARGET-SCRIPT CHECK. `TranslationPipeline.missingTargetScript` is
    the display-hit leg's equivalent guard and it is USELESS here:
    it returns null the moment the target script is LATIN, and the
    anchor's target is English. Porting it to the ingest leg would add a
    guard that can never fire. This is recorded because the 2026-08-05
    M1-765 audit proposed exactly that fix; do not re-derive it.
  - >-
    THE RENDER SURFACES. `DisplayHeadline`, `SavedCommandHandler`,
    `DigestRenderer` and `ClusterBlockRenderer` are unchanged. A NULL
    anchor already renders correctly everywhere (the anchor-absent
    branch, M1-759/M1-765); this ticket only stops a non-translation
    from being STORED as an anchor.
  - >-
    THE DISPLAY-HIT LEG. `TranslationPipeline`'s own byte-identity
    handling (condition (b), NO_OP on an unchanged reply) already exists
    and is correct; this ticket does not touch it.
  - >-
    RETRY / ATTEMPT POLICY. Whether an echoed reply should burn an
    attempt or be retried is `IngestTranslationWorker`'s existing ladder
    (M1-760's re-drive included); this ticket changes what is PERSISTED,
    not how many attempts a post gets.
acceptance:
  - >-
    ECHO IS NOT AN ANCHOR. When the ingest translator returns a title or
    body byte-identical to the input it was given, that field persists
    as SQL NULL rather than as an anchor. `docs/spec/llm.md` §Translation
    flow and `docs/spec/security.md` state the Latin-target form of the
    "did it translate" condition as byte identity — this makes the
    ingest leg apply the condition the spec already names, matching what
    `TranslationPipeline` condition (b) does on the display side.
  - >-
    PER-FIELD, NOT PER-POST. Title and body are judged independently: a
    post whose title legitimately translates to itself (a proper-noun
    headline) while its body translates properly keeps the body anchor
    and NULLs only the title. Collapsing to a whole-post decision would
    discard good anchors.
  - >-
    THE CURSOR STILL ADVANCES. `translation_done` is still set TRUE and
    the post is still released — an echoed reply is a completed
    translation attempt, not a failure. It must not re-enter the
    re-drive path M1-760 built for exhausted attempts, or an
    untranslatable post loops forever.
  - >-
    NAMED TESTS. `IngestTranslationWorkerTest` gains cases pinning:
    an echoed title persists NULL while a properly translated body
    persists its anchor; `translation_done` is TRUE either way; and a
    normal translation is unaffected.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  preserves:
    - >-
      M1-760's re-drive for translations that exhausted their attempts —
      an echo is a COMPLETED attempt and must not be re-driven.
    - >-
      The D29 invariant that the original title/body are retained
      byte-identical; this ticket only changes the DERIVED columns.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-771: Echo check on the ingest anchor write

## Context

Filed 2026-08-05 from M1-765's red-team gate (out-of-model item 3;
verdict `docs/plan/m1/redteam/M1-765-2026-08-05.md`).

D29 (c) makes an **unbracketed** line mean "this is already in your
language". Since M1-759 the digest surfaces, and since M1-765 `/saved`,
render `post.title_en` / `post.body_en` unbracketed to an English
reader. Nothing verifies that column is English.

`IngestTranslationWorker` writes it after two checks only: the
`[refused-action]` structured-refusal marker, and non-emptiness
(`persistTranslation`, and the `trimmed.isEmpty()` / `titleValue
.isEmpty()` guards above it). A translator that echoes its input — a
weak model, a hostile feed engineering a passthrough, a provider
returning the prompt — produces a non-empty, non-refusal reply that is
stored as the anchor and later rendered bare to a reader who cannot
read it.

## Why NOT a target-script check

The M1-765 audit proposed porting the display-hit leg's
`TranslationPipeline.missingTargetScript`. That fix cannot work, and the
reason is worth recording so it is not proposed a third time:

```java
if (targetScript == null
        || targetScript == UnicodeScript.LATIN     // ← always true here
        || containsScript(text, targetScript)) {
    return null;
}
```

The anchor's target language is English, whose script is Latin, so the
guard short-circuits to "no problem" before looking at the text. It is
scoped that way deliberately — its own javadoc says so, quoting the
spec: *"for non-Latin target scripts the output contains zero
target-script characters; for Latin target scripts the output is
byte-identical to the input"*.

So the spec already names the right condition for a Latin target:
**byte identity**. That is the check this ticket adds, and it is the
same condition `TranslationPipeline` applies as condition (b) on the
display-hit leg.

## What this does and does not catch

Catches the realistic failure: an exact echo. Does **not** catch a
translator that returns a lightly-modified original, or fluent output in
the wrong language — detecting those needs language identification,
which D29 deliberately refuses ("languages are declared, never
inferred"). This ticket closes the mechanically-detectable case and
leaves the rest as a stated residual; that is a deliberate bound, not an
oversight.

## Notes

- Not folded into M1-765: that ticket's `out_of_scope` item 4 fences
  "THE INGEST LEG and the anchor write path", it is a different module
  (collector vs provider), and the fix benefits the M1-759 digest
  surfaces equally rather than `/saved` alone.
- `security_relevant: true` because the property at stake is D29 (c)'s
  bracket invariant — the one signal a reader has for "I can trust this
  line is in my language."
