---
id: M1-778
title: "Generated prose is sometimes not in the scope's /lang language"
status: done
created: 2026-08-06
last_updated: 2026-08-07
clarity_check:
  date: 2026-08-06
  verdict: PASS
  warnings:
    - >-
      lint PASS (0 blockers, 0 warnings); the notes below are developer
      self-check findings, not lint output.
    - >-
      Direction-2 cause confirmed in code: SummaryProseGenerator.buildPrompt
      appends p.title()/p.body() (the source-language fields) while the
      EligiblePostQuery.Post projection already carries titleEn/bodyEn and
      degradedProseFor already promotes them via DisplayHeadline.anchorFirst.
    - >-
      Direction-1 surface confirmed: ChatAgent:558 still calls the 2-arg
      TranslationPipeline.run overload, which declares "en" on the caller's
      behalf; M1-777 added the 3-arg form and migrated no call site.
      TranslationPipeline's prose leg also has no Latin-script echo check —
      condition (d) returns null for a Latin target, so an untranslated
      English reply to a cs scope passes every gate silently.
    - >-
      Constraint the acceptance implies but does not state: the reusable
      `reply.translation.unavailable` note asserts "showing English", so it
      cannot truthfully cover the en direction (non-English prose delivered
      to an en reader). With new bundle keys out of scope, the en direction
      must be closed by PRODUCING English, not by notifying.
    - >-
      Prose nit, claim true: the body cites docs/spec/commands.md:1052 for
      the "per-scope output language" sentence, which now sits at line 1064.
  blockers: []
blocked_by: [M1-777]
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - docs/spec/llm.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyLanguageTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseLanguageTest.java
  - infochat-provider/src/test/java/**
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY BUNDLE FILE. The existing `reply.translation.unavailable` key is
    reused verbatim if a notice is needed. New keys collide with
    M1-781/M1-782 and, per the five-bundle parity rule, would need a
    twin in en/cs/es/ru/tr.
  - >-
    THE M1-437 FALLBACK LEGS. M1-777 owns the notice's firing
    condition; this ticket owns what language the prose is generated
    in. Do not re-litigate the identity check here.
  - >-
    Retrieval-side query anchoring (M1-746). That translates the QUERY
    into the corpus language and is a different leg with its own
    determinism constraints (D58).
acceptance:
  - >-
    THE FAILURE IS BIDIRECTIONAL — BOTH DIRECTIONS MUST BE ADDRESSED,
    and the diff must say whether they share one cause. Observed: (1)
    under `/lang cs` a chat reply came back entirely in English while
    the bundle-localized parts were Czech; (2) under `/lang en` one
    `/summary` cluster came back in Czech among English paragraphs.
  - >-
    THE `en` DIRECTION IS NOT AN ANCHOR-AVAILABILITY PROBLEM. The post
    that leaked Czech has `translation_done=true`, a populated
    `title_en`, and a 207-char `body_en`. A usable English anchor
    existed and was not used. Establish why the summarizer had the
    source-language body in hand at all.
  - >-
    A reply delivered to a scope is in that scope's language, or the
    user is told once, truthfully, that it could not be. Silence is not
    acceptable — the `en` direction currently emits no notice at all,
    which is worse than the `cs` direction.
  - >-
    Pinned by tests on both directions: a `cs` scope whose generator
    returns English, and an `en` scope whose cluster input is
    non-English, each produce a reply in the scope language (or the
    single reused notice).
  - >-
    BOTH FAILURES ARE INTERMITTENT — a re-run produced the correct
    language each time. Tests must therefore drive the failing input
    deterministically (a stubbed generator returning wrong-language
    text), never rely on observing the live model.
  - >-
    AMEND `docs/spec/llm.md` §Failure handling (recap) condition (b) to
    carry the source-language qualifier M1-777 implemented. The spec
    still states the check fails when "the output is byte-identical to
    the input", unqualified, while the code now treats identity as a
    failure only for an input DECLARED English. This is NOT re-litigating
    the identity check — the decision is M1-777's and stands; it is
    writing that decision into the spec sentence in the same commit that
    makes the callers declare truthfully, so the two stop diverging.
    Raised as a SPEC-CONFORMANCE WARN on the M1-777 review (round 1,
    2026-08-06), which could not clear it: `docs/spec/llm.md` was outside
    that ticket's `files_scope`.
  - >-
    THE CATEGORY ROLL-UP CARRIES THE SAME en-DIRECTION DEFECT and is
    fixed with it. `CategoryRollupGenerator` builds its prompt from
    source-language titles with no English anchor, and its
    `ROLLUP_SYSTEM_PROMPT` names no output language — so the default
    categorized `/summary` render, the same surface §F3 exercised, can
    still emit a non-English roll-up prefix to an `en` reader after the
    summarizer leg is fixed. Apply the same two-part repair: anchor the
    prompt operand and pin the prompt's output language.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyLanguageTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseLanguageTest.java
  preserves:
    - >-
      Progress placeholders and the D58 provenance footer already
      localize correctly and must keep doing so — they were right in
      every observed case.
    - >-
      M1-759's anchor-first display block, verified working in this
      test round.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D29
  - D43
redteam_findings:
  - date: 2026-08-06
    category: DOS
    severity: medium
    promise: |
      security.md §Prompt-injection defenses: the outbound closed-list pass is
      "a single left-to-right scan ... linear in the reply length. A regex was
      rejected because matching a flag at any position is either bounded ... or
      super-linear ... on an attacker-influenced reply (§Trust boundaries item 9
      puts a hostile endpoint's reply in scope, so an in-cap reply must not be
      convertible into unbounded CPU)."
    gap: |
      CONDENSED — full verbatim text in the audit file. The new prose-leg call
      DisplayHeadline.displaysAsTheOriginal(postSanitizer1Text, translated) in
      TranslationPipeline.run has BOTH operands unbounded: the model reply has
      no length cut on that path, and the translator reply is bounded only by
      the 1-8 MiB HTTP body cap. The predicate walks String.indexOf(word, from),
      which is a naive O(n*m) substring search — a whitespace-free reply is ONE
      word of unbounded length, so the walk is quadratic, not linear. Every
      pre-existing call site bounds its operands (finishDisplayHit via
      boundForScan=2000, usesAnchor via truncate=200); this one does not.
    repro: |
      CONDENSED — full verbatim text in the audit file. Hostile/compromised LLM
      endpoint (in scope per §Trust boundaries item 9; under D56 one endpoint
      serves both CHAT_AGENT and TRANSLATOR). Chat reply = 4 MiB whitespace-free
      run; translator reply = 8 MiB of the same. Not blank, not byte-identical,
      so (c) and (b) pass and the new check runs ~1.6e13 char comparisons on the
      dispatch worker. Nothing is cached on this path, so it never converges;
      the same shape reaches the digest scheduler thread with no user action.
    suggested_fix_class: input-sanitization
    resolution: |
      FIXED in-branch before merge. TranslationPipeline.boundForEchoScan cuts
      BOTH operands at DisplayHeadline.BODY_SCAN_LIMIT (2000) before the walk —
      the same bound the display-hit leg already applies to this predicate.
      Comparing prefixes can only make a word fail to match, never invent one,
      so the residual is a MISSED late-diverging echo: the direction the
      predicate's javadoc already accepts. Pinned by TranslationPipelineTest
      .nearEchoIsCaughtOnAnInputLongerThanTheEchoScanBound, which drives an
      8 400-char padded echo through the bound and still expects the note.
  - date: 2026-08-06
    category: DOS
    severity: low
    promise: |
      security.md §Trust boundaries item 9 — "Everything a generative or
      embeddings endpoint returns is endpoint-chosen input, not a trusted
      internal value" — plus the linear-scan commitment above.
    gap: |
      CONDENSED — full verbatim text in the audit file. CategoryRollupGenerator
      .buildPrompt now feeds p.titleEn() into DisplayHeadline.of(title, body,
      sanitizer). That title slot is the one operand in DisplayHeadline applying
      no boundForScan, and its no-bound is justified solely by post.title being
      capped at IngestTextNormalizer.TITLE_MAX_LENGTH (200) at the ingest write
      boundary. post.title_en has NO such cap: the column is bare TEXT (V74) and
      IngestTranslationWorker.normalizeTitle calls stripMetadataField only,
      never truncateMetadataField. SavedCommandHandler:128-137 states this
      property of the anchor columns explicitly and bounds them; the new call
      site does not.
    repro: |
      CONDENSED — full verbatim text in the audit file. A non-English source's
      ingest translation returns a multi-megabyte title; nothing truncates it
      into post.title_en. The next scheduled digest pays NFKC + markdown-link
      regex + 24 closed-list matchers + 10 tokenizer scans over that title, per
      post, on the scheduler thread — before the 200-char display cut and before
      the roll-up's own char budget can drop anything. Unattributable stall,
      repeating every render while the rows stay in the retrieval window.
    suggested_fix_class: input-sanitization
    resolution: |
      FIXED in-branch before merge, together with out-of-model item 1 — one
      change closes both, because both faults were the hand-rolled coalesce.
      CategoryRollupGenerator.buildPrompt now calls DisplayHeadline.anchorFirst
      (title, null, titleEn, null, sanitizer).readerLine(). anchorFirst's
      derive applies boundForScan to BOTH operands, so the uncapped title_en
      column is bounded before the sanitizer; and it makes the field choice
      against the ORIGINAL, which is what closes the M1-729 translated-sentinel
      regression and restores M1-743's empty-headline-set skip. The null body
      AND null body anchor keep the body fallback off, so the M1-728
      "titles only" prompt bound is unchanged.

      CONTROL-PRESERVATION (engineering-rules §10): the sanitize unit widens
      from one field to that field's PAIR — title plus its ingest translation,
      joined by a renderer-authored newline. That is the M1-697 unit as widened
      by the 2026-08-05 redteam, not a new one; the join never spans two posts
      or two authors, so a flag-span deletion can still reach nothing but this
      post's own two lines. Pinned by CategoryRollupGeneratorTest
      .titleAnchorIsBoundedBeforeTheSanitizerSeesIt and
      .titlelessPostWithATranslatedSentinelAnchorContributesNoLine.
redteam_audits:
  - date: 2026-08-06
    verdict: FINDINGS
    base: 53efdb26b1d31bfcf8efd8e0f9026febd82e1839
    head: "working tree, branch m1/M1-778-generated-prose-ignores-scope-language"
    verdict_file: docs/plan/m1/redteam/M1-778-2026-08-06.md
    findings_count: 2
    out_of_model_count: 3
    note: |
      Operator-requested, not gate-fired: the ticket carries
      security_relevant: false, so /m1-tick run step 4 skipped the gate. Ran
      AFTER review round 1 returned APPROVE, so any remediation invalidates
      that APPROVE and costs a round-2 review.

      Both findings and out-of-model item 1 are defects this diff INTRODUCES.
      Out-of-model item 1 is the notable one: substituting the anchor BEFORE
      DisplayHeadline.of's renderability test inverts the rule anchorFirst
      documents (choose the field from the ORIGINAL, then take that field's
      anchor), which resurrects the translated-UNTITLED_TITLE headline M1-729
      killed and defeats M1-743's empty-headline-set skip. Items 2 and 3 were
      checked and are clean; item 3 records four non-findings so the next
      reader does not re-derive them.
  - date: 2026-08-07
    verdict: CLEAN
    base: 53efdb26b1d31bfcf8efd8e0f9026febd82e1839
    head: "working tree, branch m1/M1-778-generated-prose-ignores-scope-language (re-audit after in-branch remediation)"
    verdict_file: docs/plan/m1/redteam/M1-778-2026-08-07.md
    out_of_model_count: 1
    note: |
      Round-2 re-audit of the post-remediation diff, run per the cross-cutting
      re-audit rule (a remediation invalidates the audit that prompted it).
      The prompt carried the mandatory framing: both round-1 findings listed
      for verification, CLEAN explicitly authorized, the remediation code
      named as new unaudited surface. Verdict: CLEAN — both claimed fixes
      verified, nothing new found. redteam_findings: keeps the round-1
      entries WITH their resolutions (rather than being reset to [] as a
      first-pass CLEAN would) so the remediation record survives. The one
      out-of-model item is round 1's item 2 carried forward unchanged
      (Stage 1 never scans the LLM-authored anchor columns; the spec
      declines to promise Stage 1 as a complete defense) — advisory,
      disposition reserved to the user.
reviews:
  - round: 1
    date: 2026-08-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 730
      removed: 17
  - round: 2
    date: 2026-08-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 1367
      removed: 26
    note: |
      Round 2 after the redteam-driven remediation (round-1 APPROVE was
      invalidated by the in-branch fixes). Growth vs round 1 is the
      remediation + its tests + the two audit artifacts; the second
      red-team round on this exact diff returned CLEAN (2026-08-07).
      Green verify log reused per the M1-272 skip path — no testable
      file changed since it ran (user stopped a duplicate re-run).
overrides: []
---

## Why

`/lang <code>` is specified as setting "per-scope output language"
(`docs/spec/commands.md:1052`). A reply in another language breaks the one
promise the command makes.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §F3).

## Observed

**Direction 1 — English body under `/lang cs`.** Fixed UI strings were Czech
("Pracuji na tom…", "Odpověď je založena na 8 příspěvcích…"), the model's prose
was entirely English.

**Direction 2 — Czech paragraph under `/lang en`,** silently, mid-reply:

```
Canonical introduced the Enterprise Store, available through Ubuntu Pro…
Tvorba interaktivních aplikací s GUI s využitím projektu GoGPU ukazuje, jak
otevřít okno a pomocí knihovny gg vykreslit 2D scénu…
```

## Expected

```
Canonical introduced the Enterprise Store, available through Ubuntu Pro…
An article on Root.cz demonstrates how to create interactive GUI applications
using the GoGPU project, covering how to open a window and render a 2D scene…
```

## Relationship to M1-777

`blocked_by: [M1-777]` is a serialization, not a logical dependency: both edit
`TranslationPipeline.java`, and M1-777's decision about how the pipeline learns
its input language is an input to this one. If the generator is made
deterministic about language here, M1-777's symptom becomes unreachable in
practice — but its check would still be wrong in principle, which is why it is
fixed first and separately.
