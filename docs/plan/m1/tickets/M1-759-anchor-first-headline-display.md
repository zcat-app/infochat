---
id: M1-759
title: "Anchor-first headline display: reader-language line, bracketed original beneath"
status: done
created: 2026-08-04
last_updated: 2026-08-05
blocked_by:
  - M1-756
files_budget: 17
files_scope:
  - docs/spec/decisions.md
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE INGEST LEG. `IngestTranslationWorker`, the anchor write path and
    the `translation_done` cursor are untouched. This ticket only READS
    `title_en`/`body_en`.
  - >-
    RE-DRIVING A FAILED ANCHOR. When the anchor is NULL on a non-English
    source this ticket RENDERS the case (bracketed original in the primary
    slot) and stops. Repairing it is M1-760, collector-side. A display-time
    translator retry is explicitly forbidden — it would put a generative
    call back on the scheduled digest path that M1-756's cost model
    excludes.
  - >-
    THE `originál:` LABEL FORM. The bracket is punctuation, not localized
    text, so this ticket adds no bundle key. The label was previously
    deferred because es/tr/ru were in flight; those landed
    (M1-716/718/719/720) so nothing blocks a key any more, and it now
    stays out by an OPEN DESIGN DECISION. Its strongest argument — that a
    bracketed system annotation next to bracket-wrapped feed text is
    spoofable — is DISCHARGED by this ticket removing the annotation
    (see the marker item below), so the bracket is no longer carrying a
    known wrinkle. Revisiting the label is a separate ticket.
  - >-
    `fallbackWithNote`'s `(translation unavailable — showing English)`
    note. It is NOT the machine-translation marker and it is NOT
    redundant with the bracketed original: it reports that a translation
    was ATTEMPTED AND FAILED, which position cannot convey. It stays, on
    both legs, unchanged.
  - >-
    `REJECTED_BY_TARGET_SCRIPT_CHECK` — M1-761's in-band cache sentinel.
    Its javadoc and the `DisplayHitTranslationTest` cases around it also
    say "marker", but it is a DIFFERENT object: a never-rendered cache
    value whose leading U+000A is unforgeable by the translator. Those
    assertions pin a security property, not a display affordance
    (CLAUDE.md §"Preserve the controls of a path you replace" — tests are
    controls too). Do not touch it while removing the display marker.
  - >-
    `saved_post` ANCHOR SNAPSHOTTING. `/saved` renders a pure snapshot
    read and `saved_post` carries no `title_en`/`body_en`, so on this
    ticket `/saved` can only ever render the anchor-ABSENT form. Adding
    the columns is precedented (V76 snapshotted `source_language` at
    `/save` time via `SaveCommandHandler`'s existing post->source join)
    but needs a migration this ticket excludes. Follow-up: M1-765.
  - >-
    DEGRADED-SURFACE PARITY. `DegradedDigestRenderer` and
    `SummaryProseGenerator.degradedProseFor` call
    `DisplayHeadline.of(Post, …)` and are NOT in `files_scope`; they keep
    rendering the original. That is why the anchor must enter through a
    NEW entry point rather than by changing `of(Post, …)`. Giving the
    degraded path the anchor — where a column read still works with no
    LLM — is a follow-up: M1-766.
  - >-
    Digest selection, ordering, category caps, or the per-render
    translator budget landed by M1-756. If the taller block proves too
    costly in chunks, that is an escalation, not a licence to re-tune the
    caps inside this ticket.
  - >-
    Chat-mode / D21 `UNTRUSTED_CONTENT` paths and prompt builders. Prompt
    inputs stay untranslated and anchor-free (M1-747).
    `CategoryRollupGenerator` uses the `(String, String, sanitizer)`
    overload and is therefore already outside the change.
acceptance:
  - >-
    SIZING GATE, before any code — RESTATED. The prior wording asked for
    the per-message worst case "against the adapter body cap" and said to
    ESCALATE on a breach. There is NO per-message cap to breach:
    `SimpleXAdapter.send` chunks unconditionally via
    `SimpleXOutboundChunker.chunk`, and its own comment records that
    before chunking (M1-283) "a digest past the 4 000-byte SimpleX text
    cap failed PERMANENT and the recipient received nothing" — i.e. an
    over-cap digest is the known handled case, already true on `main`.
    `MAX_OUTBOUND_TEXT_BYTES` is a per-CHUNK frame size; Signal declares
    no outbound cap at all. State instead, in the outline: the worst-case
    CHUNK COUNT before and after, at `max-categories=8` ×
    `category-headline-count=5` = 40 entries in ONE batched message
    (`DigestDelivery` joins all sections on `"\n\n"`; only a LEAD section
    leaves the batch). Bytes grow ONLY by the bracketed original (~200-600
    B per NON-English entry); the layout itself costs ~2 B per entry, so
    chunk count scales with the non-English share of the corpus, not with
    the layout. Name the two consequences the old gate omitted: each chunk
    draws its own rate-limiter token, and a chunked send is not atomic
    (a mid-sequence failure delivers a prefix). ESCALATE only if the
    worst-case chunk count is judged unacceptable — do not silently shrink
    the headline count.
  - >-
    ANCHOR READ — THREE PROJECTION SITES, not two. `EligiblePostQuery`,
    `DigestPostCollector` AND `RetryCommandHandler.SELECT_POSTS_BY_UIDS`
    project the English anchor alongside the original. `/retry` replays
    `--flat` through the same `ClusterBlockRenderer` as `/summary --flat`
    — `RetryCommandHandler` dispatches `flat` to `ClusterBlockRenderer`
    while `full` and `bare` go to `DigestRenderer.renderSummarySections`,
    which renders per-cluster prose and never calls `appendHeadlines`, so
    the headline block reaches `/retry` through `flat` ALONE and a case
    written against `--full` would be vacuous — so omitting its
    projection would render a different primary line than
    the summary it replays, breaking the D19/D36 byte-identical-replay
    property `ClusterBlockRenderer` pins. The reader-language line derives
    from `coalesce(title_en, title)` / `coalesce(body_en, body)` — the
    same shape `EmbeddingWorker` already uses (`EmbeddingWorker.java:581`)
    — so a NULL anchor degrades to the original rather than to an empty
    headline. `DisplayHeadline`'s bound -> flatten -> sanitize -> truncate
    ORDER is preserved exactly; M1-747 documents that order as
    load-bearing and the anchor must enter at the same point the original
    does, not around it.
  - >-
    NEW ENTRY POINT, NOT CHANGED SEMANTICS. `DisplayHeadline.of(Post, …)`
    has four callers, two of them outside `files_scope`
    (`DegradedDigestRenderer`, `SummaryProseGenerator.degradedProseFor`).
    The anchor enters via a new entry point so those two keep their
    current bytes; changing `of(Post, …)` in place would alter two
    surfaces with no authorized test.
  - >-
    SANITIZE UNIT UNCHANGED. `DisplayHeadline`'s M1-697 rule — ONE
    author's field per `sanitize` call, never a concatenation — governs
    the anchor too: the reader-language line and the bracketed original
    are each derived by their own call, and the two are never joined
    before the sanitizer sees them. The flag-bearing closed-list entries
    delete the span from command word to flag token, so a widened input
    would let a command word in one line and a flag in the other erase
    everything between them. Per CLAUDE.md §"Preserve the controls of a
    path you replace", the UNIT is part of the control, not just the
    call.
  - >-
    SENTINEL x ANCHOR — M1-729's BODY FALLBACK MUST SURVIVE.
    `DisplayHeadline.headlineSource` treats a title byte-equal to
    `IngestTextNormalizer.UNTITLED_TITLE` ("untitled") as "no title" and
    falls back to the body. `IngestTranslationWorker` has NO sentinel
    guard — it skips only `source.language='en'` and requires a non-empty
    translated title — so a non-English titleless post can carry a
    `title_en` that is a TRANSLATION of the sentinel and therefore not
    byte-equal to it, which would defeat the exact-equality check and
    resurrect a dead headline. Rule: choose the field (title vs body)
    from the ORIGINAL, then take that same field's anchor. This is a
    control of the displaced path, not an optimization.
  - >-
    NO NEW SANITIZER PATH IS NEEDED, and the ticket must show why rather
    than assume it. `IngestTranslationWorker` runs its output through the
    shared `LlmOutputSanitizerCore` — the same transform the provider's
    `LlmOutputSanitizer` delegates to, with `CLOSED_LIST` a literal alias
    of the core's — so the anchor arrives already sanitized to display
    grade. Verify this still holds at implementation time; if it has
    drifted, that is a blocker, not a fix-inline.
  - >-
    ENGLISH READER, NON-ENGLISH SOURCE renders the anchor plus the
    bracketed original, and makes ZERO translator calls — asserted with a
    provider spy. This is the case the amendment exists for: it is a
    column read. A regression that turns it into a model call puts
    generative cost on every English result set in the deployment.
  - >-
    NON-ENGLISH READER translates FROM THE ANCHOR, not from the raw
    original: `runForDisplayHit`'s source language becomes `en` when an
    anchor is present AND the post's source language DIFFERS from the
    reader's. The second condition is not optional — `docs/spec/llm.md`
    §Translation flow scopes the amended D29 bullet to "a headline whose
    source language differs from the reader's", and an unconditional rule
    would round-trip a cs-source post for a cs reader through cs -> en ->
    cs where `DigestRenderer.translatesUnderThisScope` correctly no-ops
    today. Subject to that gate, the collapse the amendment names holds:
    one direction per reader language instead of one per (source, reader)
    pair.
  - >-
    THE MACHINE-TRANSLATION MARKER IS REMOVED — ALREADY LANDED BY M1-758,
    which merged 2026-08-04 while this ticket was in flight and deleted
    `reply.translation.hit_marker` from `BundleKeys` and all five bundles
    as part of its disclosure-correctness work, replacing it with a
    bracketed original built inline in `finishDisplayHit`. On the rebase
    those six files left this ticket's diff entirely (identical deletions)
    and were dropped from `files_scope`; what remains here is the
    `finishDisplayHit` half, where this ticket supersedes M1-758's inline
    `"\n[" + original + "]"` with the renderer-composed block (see the
    bracketing-rule item). Rationale for the removal, unchanged and still
    the record of WHY: it was an M1-747 implementation choice, and it is NOT
    decision D30 — D30 governs plain-text formatting (backticks, bare
    URLs) and says nothing about translation provenance. It does however
    carry ONE spec reference, which the pre-refine wording denied: D29's
    trailing clause names it and asserts it unchanged. That clause is
    amended by this ticket in the same commit (see the D29 item below);
    the removal is therefore a deliberate spec change, not the deletion
    of an unmandated affordance. It is redundant in every composition
    this ticket produces — position already carries "derived", and the
    only compositions with no bracket are the ones showing the
    publisher's own words — and removing it deletes the spoof target
    named in §"Known wrinkle" outright instead of mitigating it. Its
    absence is asserted on the fallback path too; assertions that become
    vacuous are DELETED, never weakened. `DisplayHeadline.truncate`'s
    `markerSafeCut` protects `LlmOutputSanitizer
    .REDACTED_COMMAND_REPLACEMENT`, a different marker, and is untouched.
  - >-
    D29's MARKER CLAUSE IS AMENDED IN THE SAME COMMIT — and after the
    M1-758 rebase this ticket is the ONLY place that amendment happens:
    M1-758 removed the marker in CODE but left D29 still asserting it
    "unchanged", so the spec and the build disagree on `main` until this
    lands. `docs/spec/decisions.md` D29 currently ends "The D30
    machine-translation marker and D43's bundle-not-translator split are
    unchanged; the bracket is punctuation, not localized text, so it adds
    no bundle key." That sentence is false as of M1-758, so
    this ticket edits it: D43's split and the no-new-bundle-key statement
    both STAY (they remain true), and the marker half is replaced by the
    removal plus its reason — position carries "derived", and a bracketed
    system annotation stacked on bracket-wrapped feed text is a spoof
    target this layout deletes rather than mitigates. This is the ONLY
    spec edit in scope and `docs/spec/decisions.md` is in `files_scope`
    for it alone: a grep for the marker across `docs/spec` and
    `docs/design` returns `decisions.md:46` and nothing else — in
    particular `docs/spec/llm.md` §Translation flow never mentions it, so
    do not restate the removal there or anywhere else.
  - >-
    security.md's TWO "unbracketed" SENTENCES ARE AMENDED. Added
    2026-08-04 on the M1-758 rebase, which is what created the conflict:
    M1-758 promoted the per-render and per-page translator budgets into
    `docs/spec/security.md` §"LLM-triggering operations", and both entries
    describe the degraded shape as rendering "untranslated and
    unbracketed" (the digest budget) / "untranslated, unbracketed" (the
    `/saved` page). Those are exactly two of the three paths the
    bracketing rule below brackets, so the sentences become false when
    this ticket lands. Amend BOTH to say untranslated but BRACKETED, with
    D29 (c) as the reason and the cost claim preserved verbatim in
    substance — the bracket is renderer-added punctuation and draws no
    generative call, so nothing about the budget control changes. This is
    why `docs/spec/security.md` joins `files_scope` mid-ticket; it was not
    in the original scope because the sentences did not exist when this
    ticket was written.
  - >-
    BRACKETING RULE — STATED ONCE, APPLIED ON EVERY PATH. The primary
    line renders BARE when it is known to be in the reader's language and
    BRACKETED otherwise. Known means: for an English reader, the anchor
    was used OR the post's declared source language is `en`; for a
    non-English reader, the display-hit leg returned TRANSLATED or NO_OP.
    It is NOT known — so the line is bracketed — on the three paths where
    the leg is skipped or fails while the declared source language
    differs from the reader's: the per-render translator budget is
    exhausted (`DigestRenderer`), the `/saved` per-user `LlmRateCap` draw
    is rejected or the page budget is spent (`SavedCommandHandler`), and
    a DEGRADED cluster (`ClusterBlockRenderer`). All three currently
    render bare, which is exactly the indistinguishability the invariant
    exists to remove; none is reachable through the anchor-absent item
    above, so stating the rule here is what makes them non-optional. A
    NULL or unknown declared source language stays UNBRACKETED — D29
    declares, never infers. This rule re-pins
    `ClusterBlockRendererTest`'s degraded byte assertion; that test's
    zero-translator-call assertion is the load-bearing half and survives
    verbatim (no translator call is added to any degraded or
    budget-exhausted branch — `docs/spec/security.md` §Failure handling
    pins degraded output to no LLM calls).
  - >-
    THE BRACKET MUST NOT COMPLETE A SYSTEM-MARKER IMPERSONATION. Added
    2026-08-04 on the user's direct instruction, resolving the low
    INJECTION finding in `redteam_findings` — recorded here rather than
    applied silently, so every changed line still traces to an acceptance
    item. Because the renderer supplies the brackets around wholly
    publisher-controlled text, a feed title of bare `redacted command` or
    `REDACTED:<anything>` would wrap into a string byte-identical to
    `LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT` or to a Stage 1
    `[REDACTED:<id>]` placeholder — both committed byte-exact by
    `docs/spec/security.md` for exact-match recognition — for a post that
    was never flagged and produced no `LLM_OUTPUT_SANITIZED` audit row.
    `DisplayHeadline.bracketed` detects the collision (the command literal
    by value, the placeholder by SHAPE, since the per-row `<id>` need not
    be guessed to fool a reader) and breaks it with ONE renderer-authored
    space inside EACH bracket, which no feed text can reproduce because
    every operand arrives through `flattenToOneLine`'s `strip()`. The
    detection is by SPAN, not by whole-string equality: an occurrence the
    wrap synthesized must start at index 0 or end at the last index,
    because those are the only positions the renderer's own brackets
    occupy — REWORDED 2026-08-04 after the round-2 finding, which showed
    the round-1 whole-string check closing only a payload that supplies no
    bracket of its own (`redacted command] x` wraps to
    `[redacted command] x]`, and the symmetric `x [redacted command` is
    closed by the renderer's own `]`). The placeholder SHAPE excludes
    whitespace from its `<id>` class — the genuine id is base32
    (`PlaceholderIds`) — since otherwise a broken `[REDACTED:<id> ]` would
    still satisfy the shape and the break would close nothing. Two
    properties are pinned together: neither literal can be forged, AND a
    GENUINE redaction still renders its marker byte-exact, so operator
    exact-match correlation against `audit_log` keeps working. NOTE the
    bound: this closes the collision the bracket WRAPPER creates, not the
    pre-existing one — a title already containing `[redacted command]`
    renders those bytes on `main` today, on every surface, and hardening
    that repo-wide is a separate ticket.
  - >-
    LAYOUT — UNIFORM THREE-LINE BLOCK. Reader-language line, then the
    bracketed original, then the URL on its own line, with a blank line
    between entries. The bracketed line is SUPPRESSED when it would equal
    the reader-language line, so a post already in the reader's language
    renders as two lines rather than three. Byte-identity for the
    all-English corpus is explicitly NOT a goal and the previous wording
    claiming it is withdrawn — it was unachievable alongside this layout
    (today's entry is the single line `· <headline>  <url>`) and it bought
    only test churn, not chunk count. The shape is uniform across source
    languages so an entry's height never leaks whether it was translated;
    it also matches `DegradedDigestRenderer`, which already renders its
    URL on its own line. Bracket wrapping happens AFTER truncation so a
    cut can never drop the closing bracket.
  - >-
    ANCHOR-ABSENT CASE. A non-English source with a NULL anchor promotes
    the bracketed original to the primary slot. The invariant to pin: an
    UNBRACKETED line always means "already in the reader's language"
    (`docs/spec/llm.md` §Translation flow states it in those terms), so
    this case must not render bare — that is exactly what makes it
    indistinguishable from a genuinely English post today.
  - >-
    FALLBACK PATH MUST NOT DOUBLE-PRINT. `fallbackWithNote` returns
    `original + "\n" + note`, so a renderer that infers "was it
    translated?" from string inequality against the leg's input renders
    the headline TWICE — once as the failed line, once as the bracketed
    original. `runForDisplayHit` must return a DISCRIMINATED result
    (translated / no-op / fallback) and the renderer must branch on it.
    Byte-sniffing the return value is the defect, not the fix.
  - >-
    CONSISTENT ACROSS SURFACES — SAME DERIVATION, NOT SAME BYTES. The
    four surfaces have four different line templates today (digest
    `· h  url`; `DegradedDigestRenderer` `h — source\nurl`;
    `ClusterBlockRenderer` renders no URL at all; `/saved` is the
    `MessageFormat` template `reply.saved.line`, also with no URL), so
    "renders identically" was never true and is not the goal. What must
    hold on every surface in scope is the DERIVATION and the INVARIANT:
    the same anchor-first field choice, and an unbracketed line always
    meaning the reader's language. Per surface: digest and `/summary`
    render the full block; `/retry --flat` renders byte-identically to
    the `/summary --flat` it replays (the `--full` and `--bare` replay
    forms render prose, not headlines, and are untouched); `/saved`
    renders the anchor-absent
    form because its snapshot carries no anchor, and threads the block
    shape through its `MessageFormat` line template (no URL line, since
    the template has no URL).
  - >-
    PERSISTED-BYTE PINS. `digest_section.content` stores rendered bytes
    and `DigestRetryService.replayMissing` replays them verbatim, so
    sections written before this ticket replay in the old shape after it
    — acceptable, but state it. Every byte assertion that changes is
    re-pinned knowingly. Per CLAUDE.md §"Preserve the controls of a path
    you replace", enumerate what each changed assertion INCIDENTALLY
    pinned (truncation bounds, sanitizer output, marker placement,
    zero-translator-call properties) and carry each across; a byte
    assertion that pins a security property is load-bearing beyond its
    name. Known literal-bearing sites beyond the bundle files:
    `DigestRendererTest`'s `CS_MARKER` constant and
    `ClusterBlockRendererTest`'s two `[strojový překlad]` assertions — a
    key-name grep alone does not find these.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  preserves:
    - >-
      M1-747's zero-translator-call assertions for `en` scopes — the
      property survives the amendment; only the bytes change, and only for
      non-English sources.
    - >-
      M1-761's `REJECTED_BY_TARGET_SCRIPT_CHECK` assertions — the
      unforgeable leading U+000A and the never-rendered property. These
      say "marker" but are not the display marker being removed.
    - >-
      M1-755's `/saved` per-user cache partitioning and rate-cap metering.
    - >-
      M1-729's untitled-sentinel body fallback, against a non-English
      titleless post whose anchor is a translated sentinel.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D30
  - D43
reviews:
  - round: 1
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: WARN
      assertion_adequacy: PASS
    diff_stats:
      files: 22
      added: 2144
      removed: 285
    note: |
      Round-1 APPROVE, no rework items. The single WARN is informational and
      does not block: `docs/spec/llm.md` §Translation flow states the
      unbracketed-line invariant absolutely, and after this diff
      DegradedDigestRenderer and SummaryProseGenerator.degradedProseFor still
      render foreign headlines bare. Both are named in this ticket's
      `out_of_scope` and are the filed follow-up M1-766; the round-3 red-team
      raised the same residual as an out-of-model advisory.
overrides: []
aborted_attempts: []
reopens: []
redteam_audits:
  - date: 2026-08-04
    verdict: FINDINGS
    base: 817bf593f846f22e865bf81594c13f10fce23388
    head: working tree (m1/M1-759-anchor-first-headline-display)
    verdict_file: docs/plan/m1/redteam/M1-759-2026-08-04.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Audited at the /m1-tick run gate, ahead of review, against the
      working-tree diff vs fork point (the branch's single commit is the
      ticket refine, so a commit-range diff would have audited the ticket
      file and none of the code). One low INJECTION finding: the new
      `bracketed` wrapper collides with the two spec-committed bracketed
      literals. Main-session falsification found the forgery class
      pre-exists the diff — the delta is two characters of attacker effort
      plus a new bracket ambiguity. Both out-of-model items are advisory:
      degraded-surface bracket parity (already filed as M1-766) and the
      absence of a write-boundary length cap on the LLM-authored
      title_en/body_en columns, which this diff is the first to project.
  - date: 2026-08-04
    verdict: FINDINGS
    base: 817bf593f846f22e865bf81594c13f10fce23388
    head: working tree (m1/M1-759-anchor-first-headline-display), round 2
    verdict_file: docs/plan/m1/redteam/M1-759-2026-08-04-r2.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Re-audit of the round-1 remediation (an in-branch fix invalidates the
      audit it answers). One low INJECTION finding, CONFIRMED against the
      code and remediated in-branch the same round: the round-1 collision
      check was whole-string, so a payload supplying its own bracket left
      the committed literal as a SUBSTRING. Main-session falsification
      found the auditor under-reported by one case — the symmetric
      unterminated-suffix payload, completed by the renderer's CLOSING
      bracket — so the fix covers both brackets. Out-of-model items
      advisory and unchanged in disposition: the uncapped title_en/body_en
      write boundary (round-1 repeat) and the collision between the
      subordinate line and the other renderer-authored bracketed tokens
      (`[topic_id=<id>]`, `/saved`'s uid slot), which are display-spoof
      only and in neither the threat model nor any parser.
  - date: 2026-08-05
    verdict: CLEAN
    base: 008edfce434f263b564163b19dba54ffe606dd8e
    head: working tree (m1/M1-759-anchor-first-headline-display), round 3
    verdict_file: docs/plan/m1/redteam/M1-759-2026-08-05-r3.md
    findings_count: 0
    out_of_model_count: 3
    note: |
      Re-audit after the round-2 remediation AND the M1-758 rebase, with the
      prior two findings named in the prompt and a CLEAN verdict explicitly
      authorized so a third round could not manufacture one. Both INJECTION
      findings confirmed closed. `redteam_findings:` deliberately keeps its
      two entries rather than being reset to `[]` — they are real, they were
      remediated in-branch, and erasing them would destroy the audit trail
      this index exists to preserve.
      Three OUT-OF-MODEL advisories, none blocking: (1) the placeholder
      shape's whitespace exclusion leaves `REDACTED:AB CD` uncaught —
      strictly weaker than the already-accepted pre-existing class, since a
      space-bearing id is not the byte-exact marker the spec commits; (2) the
      unbracketed-line invariant has now crossed into security.md while
      DegradedDigestRenderer:78 and SummaryProseGenerator:233 still render
      foreign headlines bare, so the filed M1-766 may be spec-conformance
      rather than polish; (3) `bracketed`/`primaryFor`/`subordinateFor` are
      public statics whose safety argument rests on today's four callers
      rather than on encapsulation, unlike the `prepareTranslatedHeadline`
      composite in the same class.
redteam_findings:
  - date: 2026-08-04
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Ingest pipeline commits the `[REDACTED:<id>]`
      placeholder as byte-identical "so user-facing prose, snapshot bodies,
      and tests recognise the marker by exact-match", with the per-row
      `<id>` randomization named as what stops attackers pre-crafting a
      fake placeholder; §LLM output sanitizer commits `[redacted command]`
      on the group-visible echo surfaces.
    gap: |
      `DisplayHeadline.bracketed` wraps wholly publisher-controlled text in
      square brackets on the same surfaces those two spec-committed
      bracketed literals render on, with no collision check, so the
      renderer now supplies the brackets an attacker previously had to
      supply themselves.
    repro: |
      A post titled exactly `redacted command` survives Stage 1,
      IngestTextNormalizer and the closed-list sanitizer byte-for-byte (no
      leading `/`), then renders as `[redacted command]` on the subordinate
      line for any reader whose language differs from the post's — a
      forged redaction marker with no LLM_OUTPUT_SANITIZED audit row.
    suggested_fix_class: input-sanitization
    note: |
      Falsified in the main session before escalation: the class PRE-EXISTS
      this diff. On main the headline is appended verbatim and nothing
      strips brackets from a feed title, so a post titled
      `[redacted command]` already forges the marker today. The diff's
      delta is a two-character reduction in attacker effort, plus a NEW
      ambiguity — the bracket now also means "publisher's original", so a
      collision reads as two different things. See the verdict file.
  - date: 2026-08-04
    category: INJECTION
    severity: low
    round: 2
    promise: |
      The same two byte-exact literals `docs/spec/security.md` commits for
      exact-match recognition — plus, this round, the property the round-1
      remediation itself asserts in `DisplayHeadline`'s javadoc and in this
      ticket's acceptance item: "neither literal can be forged".
    gap: |
      The round-1 check was WHOLE-STRING (`equals` and `Matcher.matches()`),
      so it closed only a payload that supplies no bracket of its own. A
      title carrying its own `]` pairs with the renderer's opening bracket
      and leaves the literal as a SUBSTRING of the delivered line, taking
      the non-colliding branch: `bracketed("redacted command] x")` returned
      `"[redacted command] x]"`. The two round-1 regression tests pinned
      only the bare payloads, so they passed while the class stayed open.
    repro: |
      A post titled `redacted command] x` (or `REDACTED:<id>] x`) on a
      non-`en` source renders, for an `en` reader, a line containing the
      byte-exact literal for a post that was never flagged and produced no
      LLM_OUTPUT_SANITIZED audit row. Not a new attacker capability over
      `main` — it is the remediation failing to deliver the property it
      claims, which is what a future reader would rely on.
    suggested_fix_class: input-sanitization
    note: |
      CONFIRMED against the code and REMEDIATED in-branch the same round.
      Falsification found the auditor under-reported by one case: a title
      ENDING in an unterminated `x [redacted command` is completed by the
      renderer's CLOSING bracket — same synthesis, unreachable on `main`,
      and a leading-space break does nothing for it. Detection is now by
      SPAN (an occurrence the wrap synthesized must start at index 0 or end
      at the last index — the only positions the renderer's brackets
      occupy), the break puts a space inside BOTH brackets, and the
      placeholder shape excludes whitespace from its `<id>` class so the
      break cannot be re-absorbed into the shape. Acceptance item reworded
      rather than the fix applied silently. See the r2 verdict file.
outline_file: target/m1-tick-outline-M1-759.md
clarity_check:
  date: 2026-08-04
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-759: Anchor-first headline display

## Context

Design session 2026-08-04. Two defects sit behind one decision.

**English readers are the only audience display translation does not
serve.** `runForDisplayHit` short-circuits on `scopeLanguage == "en"`, and
nothing in the provider reads `post.title_en` — verified: the sole
provider reference is a javadoc mention in `LlmOutputSanitizer` (line 39).
So a Turkish post renders as Turkish to the default reader, while the
English translation of that exact headline sits in the row, already
computed, already sanitized. M1-747 excluded the anchor deliberately, but
its stated reason was cost — "a regression here adds an LLM call to every
result set" — and reading a populated column adds no call. The criterion's
PURPOSE survives this ticket; only its letter changes.

**It also makes hits look wrong.** Retrieval matches on the English
anchor in both arms. An English reader searches in English, matches
`title_en`, and is shown a foreign headline with no visible relation to
what they typed — indistinguishable from the search having failed.

The fix is to make the anchor a display artifact as well as a retrieval
one, and to show the original beneath it so the publisher's own words stay
reachable. D29 was amended for this on 2026-08-04.

## Why the bracket, and why not a label

The bracket creates an invariant: an UNBRACKETED line is always in the
reader's language. Without it, a bare line sometimes means "your language"
and sometimes means "foreign, we could not translate", and nothing
distinguishes them. Position carries the second meaning — subordinate line
means "original alongside a translation", primary slot means "original
because no translation exists" — so no second affordance is needed.

A localized `originál:` label reads better and may still be the end
state. It was previously held back because a bundle key would race the
in-flight es/tr/ru sessions; those landed (M1-716/718/719/720), so
nothing blocks a key now. What kept it out at that point was the
observation below — that a bracketed system annotation adjacent to
bracket-wrapped feed text is spoofable, which argued for a labelled
form. **This ticket removes the annotation instead**, so that argument no
longer applies and the plain bracket carries no known wrinkle. Adopting
the label is now a clean, separate design question rather than a
mitigation.

## Why the machine-translation marker goes

`reply.translation.hit_marker` (`[machine translated]` and its four
translations) was introduced by M1-747 as an implementation choice. The
ticket text that called it "the D30 marker" was wrong — D30 governs
plain-text output formatting (backticks, bare URLs) and says nothing
about translation provenance. But the pre-refine claim that it is
"mandated nowhere" was wrong too: D29's own trailing clause names it and
asserts it unchanged, so this ticket amends that clause in the same
commit. A grep for the marker across `docs/spec` and `docs/design`
returns `decisions.md:46` alone — that one clause is the entire spec
surface.

It is redundant under this ticket's layout. Every composition that
renders machine-derived text also renders the bracketed original directly
beneath it, so position already says "derived". The compositions with no
bracket are exactly the ones showing the publisher's own words: a
translation byte-identical to its input, and an anchor byte-identical to
its original. A failed translation is not affected — it carries
`fallbackWithNote`'s `(translation unavailable — showing English)`, which
reports something position cannot.

And it is actively harmful at this layout. Stacked immediately above a
bracket-wrapped, feed-authored line, a bracketed system annotation is a
spoof target: a hostile source titling a post `machine translated`
renders a line indistinguishable from ours. Removing the annotation
deletes the target rather than mitigating it. On a worst-case digest it
also repeats up to 40 times.

Its removal is small — one emission site (`TranslationPipeline
.finishDisplayHit`), one `BundleKeys` constant, five bundle values, and
the assertions that reference it. Note that a key-name grep does NOT find
every site: `DigestRendererTest` holds the Czech value in a `CS_MARKER`
constant and `ClusterBlockRendererTest` asserts the literal twice.

M1-758 landed the key/bundle/assertion half of this on 2026-08-04 while
this ticket was in flight, so only the emission site remains here — and
it now reads `bounded + "\n[" + displayHeadline + "]"` rather than the
marker append, because M1-758 substituted the bracketed original inline
in the pipeline. This ticket moves that composition to the renderer,
which is where the anchor, the note and the collision check all live.

## Sizing: chunks, not a cap

The original sizing gate measured the rendered digest "against the
adapter body cap" and said to escalate on a breach. That is a category
error, and it is why this ticket was rewritten. `SimpleXAdapter.send`
chunks every outbound body unconditionally; `MAX_OUTBOUND_TEXT_BYTES`
(4 000) bounds a CHUNK, not a message, and the adapter's own comment
records that pre-chunking a digest over that size "failed PERMANENT and
the recipient received nothing" — chunking exists precisely because
digests exceed it. Signal declares no outbound cap at all. So the gate as
written fires on `main`'s status quo and can never be satisfied by any
digest.

What is worth measuring is chunk count, because each chunk draws its own
rate-limiter token and a chunked send is not atomic. The layout change
itself is nearly free in bytes (a newline instead of two spaces, plus a
blank separator — roughly 2 B per entry); the bracketed original is the
whole cost, and only on non-English entries.

## Census

Two class-scoped enumerations. Both greps are re-runnable and must be
re-run at `start`; the disposition tables below are the authorized answer
and the reviewer checks them against the diff.

### A. Machine-translation marker sites

A key-name grep alone is INSUFFICIENT — the tests hold the bundle VALUE as
a literal, not the key, and the spec names the marker in prose that
carries neither. Enumerate by invocation AND by value, across code AND
`docs/spec` + `docs/design`:

```
grep -rn "REPLY_TRANSLATION_HIT_MARKER\|hit_marker\|machine translated\|machine-translation marker\|strojový překlad\|traducción automática\|машинный перевод\|makine çevirisi" \
  infochat-provider/src docs/spec docs/design
```

Rows marked LANDED were done by M1-758 (merged 2026-08-04, mid-flight) and
left this ticket's diff on the rebase; the census stays as the record of
what the removal covered.

| Site | Disposition |
|---|---|
| `bundle/BundleKeys.java:2029` | REMOVE the constant — LANDED (M1-758) |
| `translation/TranslationPipeline.java:442` | REMOVE the append in `finishDisplayHit`. M1-758 replaced it with an inline `"\n[" + original + "]"`; this ticket removes THAT too, since the renderer composes the block — and only the renderer's `bracketed` carries the system-marker collision check |
| `resources/bundles/{en,cs,es,ru,tr}.properties` | REMOVE the value from all five together (bilateral keyset parity) — LANDED (M1-758) |
| `test/…/translation/DisplayHitTranslationTest.java:87` | REMOVE the `marker(lang)` helper; assertions that become vacuous are deleted, not weakened |
| `test/…/command/SavedCommandHandlerTest.java:423` | RE-PIN without the marker |
| `test/…/command/ClusterBlockRendererTest.java:180,215` | RE-PIN; `:215`'s absence assertion becomes vacuous — delete it |
| `test/…/digest/DigestRendererTest.java:46` | REMOVE the `CS_MARKER` constant and its uses |
| `docs/spec/decisions.md:46` (D29 trailing clause) | AMEND — the one spec sentence that names the marker; see the D29 acceptance item |
| Javadoc/comment orphans the removal creates, all in `files_scope` main files: `TranslationPipeline` `:247,262,284,308,318,428-431`; `ClusterBlockRenderer` `:42,54,121,124`; `DigestRenderer` `:139,855`; `SavedCommandHandler` `:394,420` | UPDATE — each promises a marker that will not exist, and the `untranslated AND unmarked` ones also describe behaviour the bracketing-rule item changes. Orphans created by this diff, so in scope under §"Surgical changes" |

NOT in this class, despite matching on the word "marker" in prose:
`TranslationPipeline.REJECTED_BY_TARGET_SCRIPT_CHECK` and its
`DisplayHitTranslationTest` cases (M1-761) — including its javadoc at
`TranslationPipeline:66,77`, which the out-of-scope item forbids touching
— `LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT` with
`DisplayHeadline.markerSafeCut` (`DisplayHeadline:240-286` entire),
`DigestRenderer.LEAD_TAG`'s section marker (`:175,246`) and the REFUSAL
marker (`:590`). All stay.

### B. Post projections that can feed a display surface

```
grep -rn "p\.title" --include=*.java infochat-provider/src/main
```

| Site | Disposition |
|---|---|
| `summary/EligiblePostQuery.java:283` | IN — project the anchor |
| `digest/DigestPostCollector.java:158` AND `:178` | IN — TWO separate SQL blocks; both get the anchor, or the two queries render inconsistently |
| `command/RetryCommandHandler.java:90` | IN — project the anchor (the third site the pre-refine ticket omitted) |
| `command/SaveCommandHandler.java:115` | OUT — the `/save` snapshot WRITE; storing the anchor needs `saved_post` columns (M1-765) |
| `summary/SummaryProseGenerator.java:181` | OUT — prompt input; stays untranslated and anchor-free (M1-747) |
| `digest/CategoryRollupGenerator.java:329` | OUT — prompt input via the `(String, String, sanitizer)` overload |
| `chat/tool/GetPostTool.java:62` | OUT — D21 chat-mode |
| `chat/tool/SearchPostsTool.java:147` | OUT — D21 chat-mode |
| `chat/tool/SemanticSearchTool.java:231,246` | OUT — D21 chat-mode |

## Notes

- `complexity: high` triggers the plan-writer sidecar. The sizing gate in
  `acceptance:` must be answered in that sidecar BEFORE code.
- A first plan-writer pass returned `OUTLINE FAILED` on 2026-08-04
  against the pre-refine text; the first rewrite was that escalation's
  resolution. Its findings on `/saved`, `/retry` and the marker are all
  folded in above.
- A second plan-writer pass PASSED on 2026-08-04
  (`target/m1-tick-outline-M1-759.md`, 10 risks) and triggered this
  second refine via a `budget-breach` escalation. Three of its risks are
  now acceptance items — the D29 clause (R1), the `/retry` render form
  (R2), and the bracketing rule (R8) — each independently verified in
  source before being written here, not taken on the subagent's word. Its
  remaining risks are sizing and judgment calls addressed by the two
  notes below; the sidecar stays the authority on implementation order.
- `EligiblePostQuery.Post` gains `titleEn`/`bodyEn` as trailing
  components PLUS a 15-arg compat constructor delegating
  `(…, sourceLanguage, null, null)`. This is not a
  backwards-compatibility shim in the §"No defensive code" sense: 28
  `new Post(...)` sites live across 16 test files, 13 of them OUTSIDE
  `files_scope`, so without it the diff must touch 34 files. The pattern
  is precedented in the same file — `EligiblePostQuery:142-189` already
  carries two such constructors, added by M1-724 and M1-747 for exactly
  this reason.
- `round_cap: 3` (permitted for `complexity: high` / `risk: high`). The
  `runForDisplayHit` signature change is atomic across four call sites
  plus a 31-test file, on top of an eight-file production change; two
  rounds is a thin margin for a diff this wide, and the alternative —
  splitting the discriminated result into its own ticket — would leave
  the double-print defect unfixed in between.
- Two follow-ups are filed and blocked on this ticket: M1-765 (the
  `saved_post` English-anchor snapshot, migration, following V76's
  pattern) and M1-766 (degraded-surface parity for
  `DegradedDigestRenderer` / `SummaryProseGenerator.degradedProseFor`).
  They are independent of each other and share no files, so they can run
  in parallel once this lands.
- Sequenced after the es/tr/ru bundle work by intent, not by dependency:
  `blocked_by` names only M1-756. Those bundles have since landed
  (M1-716/718/719/720). Renderer overlap is with M1-756 and M1-762
  (`DigestRenderer.java`, `DigestRendererSectionsTest.java`) and M1-761
  (`TranslationPipeline.java`, `DisplayHitTranslationTest.java`); all are
  merged. Check `git worktree list` as well as ticket status, since a
  worktree-local branch is invisible to the frontmatter.
