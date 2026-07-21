---
id: M1-649
title: "Conceptual topic corpus: curated answers, intent-shaped matching, code-anchored guards (retrieval only)"
status: done
created: 2026-07-18
last_updated: 2026-07-21
reviews:
  - round: 1
    date: 2026-07-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 2033
      removed: 15
clarity_check:
  date: 2026-07-21
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-649.md
blocked_by:
  - M1-660
  - M1-664
files_budget: 16
files_scope:
  - USER_GUIDE.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpTopicCorpus.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/TopicCorpusBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/HelpTopicCorpusTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/TopicCorpusRetrievalIT.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ALL DELIVERY — the deterministic trigger, the post-sanitize append,
    precedence with the command usage block, the commands.md delivery-mechanism
    description, and the delivery D-row. M1-666. This ticket indexes topics but
    delivers nothing into any chat reply.
  - >-
    HelpLookupTool and ANY model-elected topic path. Topics are delivered
    deterministically (M1-666), never as a model-paraphrased tool result.
    Routing curated prose through the model would (i) be paraphrased — defeating
    "reviewed product copy" — and (ii) be redacted by the sanitizer CLOSED_LIST
    for the user-tier commands topics MUST name (/add-source, /unfollow-source,
    /follow-tag, /unfollow-tag, /lang all appear in LlmOutputSanitizer.CLOSED_LIST).
    Do not add a topic branch to HelpLookupTool.
  - >-
    The doc_embedding read-path hardening (hnsw.iterative_scan + the
    transaction wrapper) — M1-660, a blocker. This ticket CONSUMES the armed
    read path for lookupTopic; it does not build it.
  - >-
    ADMIN_GUIDE.md. Cannot be embedded, whole or chunked. It enumerates the
    closed privileged-command set the sanitizer and probation classifier key
    off, and which destructive paths are confirmation-gated. Admin-tier
    conceptual answers are a separate ticket with its own threat review.
  - >-
    docs/spec/security.md — NO amendment. Deterministic topic delivery (M1-666)
    fits M1-663's existing path-(a) exemption ("any command-usage OR help
    text … deterministic end-to-end"), so §LLM output sanitizer is not touched
    by this feature at all.
  - >-
    docs/spec/** and docs/design/** except docs/spec/commands.md and
    docs/spec/decisions.md (both in scope — the D68 row is written in
    decisions.md, mirroring how M1-664/M1-665 recorded D66/D67).
    commands.md carries deliberate non-disclosure rules (:226 no admin-command
    existence leak) that reading design docs would defeat.
  - >-
    Rewriting USER_GUIDE.md. Only the two confirmed factual defects below are
    corrected, plus the invisible HTML-comment anchor markers the guide-hash
    guard requires (the guide contains zero HTML comments today, so the
    acceptance-item-4a anchors MUST be inserted by this ticket); not a
    documentation-quality pass.
  - >-
    Serving conceptual answers to probation users. Chat mode is closed to them
    (InboundRouter step-5 gate; commandNameOf → "chat-mode" sentinel) and this
    ticket does not change that gate.
acceptance:
  - >-
    CURATED CORPUS (in-code + bundle-localized), covering at minimum: invite/
    access flow, what probation is and how it ends, chat-vs-command mental
    model, the chat assistant's read-only own-scope boundary, DM-vs-group
    semantics including the mention requirement, unfollow-vs-delete ownership,
    why /add-source requires tags, personal-view vs shared-source tags,
    /clear vs /forget, and what /forget does and does not erase. Each topic is
    a HelpTopicCorpus record carrying (a) an intent-shaped MATCH text and
    (b) a served ANSWER as a bundle key. The served answer is reviewed product
    copy, never a raw USER_GUIDE.md slice — so the runtime depends on no
    markdown heading structure.
  - >-
    INTENT-SHAPED MATCH, NOT ANSWER PROSE — the text embedded into
    doc_embedding (doc_kind='topic') per topic is question/synonym-shaped
    (title + intent words, mirroring CommandIntentIndexBuilder.composeIntentText),
    NOT the answer body. TopicCorpusRetrievalIT covers ≥3 phrasings that share
    no content word with the topic title and still resolve (e.g. "why can't I
    post in the group" → the probation topic). Rationale: matching a short user
    question against a long answer embedding is asymmetric and under-recalls the
    tail phrasings this feature exists to serve; HyDE/query-rewrite are
    out-of-scope (D19), so the match surface must itself be question-shaped.
    CI NOTE: the suite's embedders are stubs (fixed vectors), so the IT pins
    the retrieval plumbing with rigged-distance vectors — the established
    M1-664 pattern (HelpLookupToolIT's phrasing test); real no-shared-word
    recall is verified by live calibration (the M1-619 pattern), not by CI.
    Do NOT reach for a real embedding backend in tests.
  - >-
    doc_id NAMESPACE DISJOINTNESS — every topic doc_id is namespaced
    (e.g. "topic:<slug>"), and HelpTopicCorpusTest.topicDocIdsDisjointFromCommandNames
    asserts no topic doc_id equals any HelpCommandHandler.CATALOGUE command
    name. V60's PRIMARY KEY is single-column (doc_id) and the upsert DELETE is
    doc_kind-scoped, so a topic doc_id colliding with a command name would miss
    the command row on DELETE and PK-violate on INSERT — rolling back the batch
    and silently degrading the corpus. Namespacing + this test forecloses it
    without a schema migration.
  - >-
    STALENESS GUARD, CODE-ANCHORED WHERE THE FACT IS CODE.
    (a) Conceptual topics (mental-model, rationale) keep a USER_GUIDE.md
    derivation hash: each records the section anchor + a content hash, and
    HelpTopicCorpusTest.topicDerivationHashesMatchCurrentUserGuide reds the
    build when the guide changes under a topic. Anchor by an explicit stable
    marker (an HTML-comment id or equivalent), NOT heading text, so a heading
    reword does not red the build for a non-reason.
    (b) CODE-FACT topics pin the FACT to the runtime source, not the guide:
    HelpTopicCorpusTest.forgetErasureTopicMatchesPurgeService asserts the
    /forget topic enumerates exactly the categories ForgetPurgeService purges
    and its not-touched set; the probation topic pins its limits/duration to
    the probation config. This catches the drift the guide hash CANNOT — code
    changing while the guide (and the topic) stay stale-green. It is why the
    guide hash alone (which detects guide CHANGE, not guide/code MISMATCH)
    would not have caught either USER_GUIDE defect this ticket also fixes.
  - >-
    TIER-FLAT PIN — every topic is user-tier by construction;
    HelpTopicCorpusTest.noTopicReferencesAdminSurface asserts no topic (match
    text OR answer) names a BOT-ADMIN-tier command. The pin is against the
    bot-admin surface, NOT against CLOSED_LIST membership — the group-admin
    commands topics must name (/add-source, /lang, /follow-tag, …) are
    themselves CLOSED_LIST entries and are expected in topic text. Concrete
    consequence: the invite/access-flow topic describes the flow without
    naming any /invite subcommand (all bot-admin). NOTE this is necessary but
    NOT sufficient for delivery safety: the user-reachable commands topics
    must name are in the sanitizer CLOSED_LIST, which is exactly why M1-666
    delivers deterministically post-sanitize rather than through the model.
  - >-
    BUILDER — TopicCorpusBuilder embeds one intent doc per topic into
    doc_kind='topic' at startup via DocEmbeddingDao, content-hash-skips
    unchanged rows (zero embed calls on an unchanged restart,
    TopicCorpusRetrievalIT.restartWithUnchangedCorpusPerformsNoEmbeddingCall),
    and is scoped to its own doc_kind (its DELETE never touches command rows).
    Threshold statistics differ from commands — but because the match text is
    now intent-shaped (not prose), the distribution is close to the command
    corpus; pick a starting topic threshold as a named constant with a comment,
    recalibration a follow-up.
  - >-
    lookupTopic — CommandIntentIndex gains a topic read (doc_kind='topic',
    returns target_ref = topic id) over M1-660's armed-transaction query shape.
    It returns a POINTER only; the answer is composed at delivery time (M1-666)
    from the bundle-localized corpus. Match-not-assert is preserved in spirit:
    SQL matches, the trusted in-memory corpus asserts, the model is never in
    the loop.
  - >-
    USER_GUIDE.md DEFECT 1 (currency) corrected — USER_GUIDE.md:317 states
    supported currencies usd/eur/czk/btc next to a Kraken example, but
    KrakenSnapshotSource.SUPPORTED_VS (infochat-collector module) =
    {usd, eur, btc}; czk is Coingecko-only.
    State the per-source difference. Prerequisite: deriving a topic from wrong
    text launders the defect into a spoken answer.
  - >-
    USER_GUIDE.md DEFECT 2 (pagination/commands) corrected — the pagination
    bullet at USER_GUIDE.md:319 lists /saved, /get-sources, /list-sources but
    omits /export, which accepts --page N (ExportCommandHandler.java:29,36);
    and /follow-all-sources, a non-admin command, is undocumented (0
    occurrences). Correct the pagination list; document /follow-all-sources in
    §Advanced (reference).
  - >-
    NEGATIVE PIN — this ticket delivers nothing into a chat reply. HelpLookupTool
    is not in files_scope and is not touched. A test asserts no topic answer
    reaches the adapter via this ticket's paths.
  - >-
    docs/spec/commands.md §Chat mode records that the topic corpus exists
    (curated, intent-matched, code-anchored-or-guide-hash staleness guard,
    tier-flat, namespaced doc_id) and names ADMIN_GUIDE.md as deliberately
    excluded. It does NOT describe a delivery mechanism (M1-666).
  - >-
    Decision D68 records the topic-corpus shape: second doc_kind='topic';
    intent-shaped match text vs bundle-localized served answer; the
    code-anchored-or-guide-hash staleness guard split; tier-flat; namespaced
    doc_id. (Re-verify D68 is free immediately before writing the row.)
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/HelpTopicCorpusTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/TopicCorpusRetrievalIT.java
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexIT.java
      change: >-
        Only if adding lookupTopic changes shared test wiring; the M1-660
        interleaving test is that ticket's, not this one's.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D66
  - D68
decomposed_from: M1-648
redteam_findings: []
redteam_audits:
  - date: 2026-07-21
    verdict: CLEAN
    base: ea8b2768bd4e00d4ab3db855cc58af69948aa4e3
    head: working-tree (uncommitted M1-649 implementation; branch had 0 commits at the gate)
    verdict_file: docs/plan/m1/redteam-multi/M1-649-2026-07-21/cross-examination.md
    out_of_model_count: 3
    note: |
      Multi-auditor run (/redteam-multi): claude, opencode, codex all CLEAN
      (0 findings, 0 clusters). Diff = working-tree vs fork ea8b2768 with
      docs/plan/m1/* excluded, passed via --diff (the branch-form range
      resolves empty pre-commit — the known /m1-tick run gate deviation).
      codex's CLEAN carries no rationale (bare verdict line after a
      sandbox-mode retry); corroboration rests on claude + opencode's
      substantive walkthroughs. Out-of-model notes (all from the claude
      auditor): (1) the pre-existing V60 provider-role INSERT+DELETE grant
      on doc_embedding would let a hypothetical Provider SQLi foothold
      steer/deny topic matches across restarts — bounded by
      match-not-assert, accepted DB-internal posture; (2) tier-flat is
      test-enforced against BOT_ADMIN /command literals only — a future
      topic discussing admin-tier CONCEPTS without a slash name would pass
      the guard; forward-hardening note for M1-666; (3) the probation
      answer bakes "24h" and its guard pins to the checked-in
      application.properties, not the runtime-effective config, so an
      operator override drifts the served copy while the build stays
      green — M1-666 may prefer composing the duration at delivery time.
---

# M1-649: Conceptual topic corpus — curated answers, intent-shaped matching, code-anchored guards (retrieval only)

## Context

M1-664 lets the bot resolve "which command does X" without inventing syntax,
because every command answer is composed from the runtime CATALOGUE
(match-not-assert). A second class of question has no such single runtime
source: "what is probation", "why can't I post in the group", "what does
/forget actually erase", "who can change a source's tags", "unfollow vs
delete". Today the chat agent answers these from general knowledge — it makes
them up.

This ticket builds the **corpus and retrieval** for those answers. It is the
drift-exposed half of the feature: the served text IS the answer, so it cannot
have the command path's structural guarantee. The mitigation is a guard — but
the guard must point at the right source of truth. **Delivery is M1-666**;
this ticket indexes topics and delivers nothing.

## What changed from the original M1-649 (decomposition rationale)

The original single ticket routed topic answers through `HelpLookupTool`
(model-elected, pre-sanitize). That is wrong on four counts, all verified:
the sanitizer CLOSED_LIST redacts the user-tier commands topics must name
(`/add-source`, `/unfollow-source`, `/follow-tag`…); the model paraphrases,
defeating "reviewed product copy"; it contradicts M1-665's own guidance that
topics reuse the deterministic delivery; and it re-opens the hallucination
surface. Delivery is therefore deterministic (M1-666), which splits this
feature the same way M1-648 split (safe retrieval / sensitive delivery).
The original also embedded answer PROSE (weak recall) and inherited the
single-column-PK collision and the shared-index iterative_scan under-recall
(now M1-660) without addressing either.

## Corpus shape (design call to confirm in the plan sidecar)

- **Match surface** (English, embedded): intent-shaped text per topic. Mirrors
  the command corpus, which resolves no-shared-word phrasings well.
- **Served surface** (localized): the answer as en/cs bundle values — reviewed
  product copy, delivered verbatim and bundle-localized by M1-666, with no
  markdown-heading coupling and no reliance on TranslationPipeline.
- **Guard metadata**: per topic, either a USER_GUIDE derivation anchor+hash
  (conceptual topics) or a code-fact pin (code-fact topics).

The corpus itself is in-code (a HelpTopicCorpus list, like CATALOGUE), not 12
loose files — this is what makes the served text reviewable product copy while
still getting localization from the bundle machinery.

## Why the guide hash alone is not enough

USER_GUIDE.md is referenced by no code — which is why its two defects survived
the 2026-06-30 claim-by-claim audit (M1-509). A content hash detects guide
CHANGE, not guide/code MISMATCH, so it would not have caught either defect this
ticket fixes, and it stays green when CODE drifts under a stable guide. For the
code-fact topics (/forget erasure, probation params) the drift that matters is
code-vs-doc, so those pin to code. The guide hash is retained only for the
genuinely-conceptual topics, where no runtime source exists.

## Notes

**Probation reachability.** The "what is probation" topic's primary audience —
probation users — cannot reach chat. It serves non-probation callers asking
about the system; keep the onboarding copy and this topic consistent (they can
drift independently). Not solved here.

**Plan sidecar.** complexity:high — sequence: guide fixes first, then the
in-code corpus + intent-doc builder, then lookupTopic + the guards. Confirm the
in-code-corpus + bundle-answer shape against the plan-writer's read.
