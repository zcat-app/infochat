---
id: M1-759
title: "Anchor-first headline display: reader-language line, bracketed original beneath"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by:
  - M1-756
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
complexity: high
risk: high
round_cap: 2
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
    NEW BUNDLE KEYS. The bracket is punctuation, not localized text,
    precisely so this ticket adds no key while es/tr/ru are in flight. The
    `originál:`-style LABEL form is the better long-term affordance and is
    deliberately deferred until those bundles have settled; do not add it
    here.
  - >-
    THE D30 MARKER's text, placement or suppression rule. It stays
    appended after truncation, and a self-identical translation stays
    unmarked (M1-747). This ticket adds a line BELOW it; it does not touch
    it.
  - >-
    Digest selection, ordering, category caps, or the per-render
    translator budget landed by M1-756. If the taller block breaches the
    per-message cap, that is an escalation, not a licence to re-tune the
    caps inside this ticket.
  - >-
    Chat-mode / D21 `UNTRUSTED_CONTENT` paths and prompt builders. Prompt
    inputs stay untranslated and anchor-free (M1-747).
acceptance:
  - >-
    DESIGN GATE, before any code: state the per-message worst case for a
    digest at `category-headline-count=5` across the maximum section
    count, in lines and bytes, against the adapter body cap. Today a
    five-headline category is 5 lines; the block is 2 lines per entry
    without a translation and 3 with, plus a blank between entries. If the
    worst case breaches the cap, ESCALATE — do not silently shrink the
    headline count.
  - >-
    ANCHOR READ. `EligiblePostQuery` and `DigestPostCollector` project the
    English anchor alongside the original, and the reader-language line
    derives from `coalesce(title_en, title)` / `coalesce(body_en, body)` —
    the same coalesce shape `EmbeddingWorker` already uses, so a NULL
    anchor degrades to the original rather than to an empty headline.
    `DisplayHeadline`'s bound -> flatten -> sanitize -> truncate ORDER is
    preserved exactly; M1-747 documents that order as load-bearing and the
    anchor must enter at the same point the original does, not around it.
  - >-
    NO NEW SANITIZER PATH IS NEEDED, and the ticket must show why rather
    than assume it. `IngestTranslationWorker` runs its output through the
    shared `LlmOutputSanitizerCore` — the same transform the provider's
    `LlmOutputSanitizer` delegates to, with `CLOSED_LIST` a literal alias
    of the core's — so the anchor arrives already sanitized to display
    grade. Verify this still holds at implementation time; if it has
    drifted, that is a blocker, not a fix-inline.
  - >-
    ENGLISH READER, NON-ENGLISH SOURCE renders the anchor plus the D30
    marker plus the bracketed original, and makes ZERO translator calls —
    asserted with a provider spy. This is the case the amendment exists
    for: it is a column read. A regression that turns it into a model call
    puts generative cost on every English result set in the deployment.
  - >-
    NON-ENGLISH READER translates FROM THE ANCHOR, not from the raw
    original: `runForDisplayHit`'s source language becomes `en` whenever
    an anchor is present. This is the collapse the D29 amendment names —
    one direction per reader language instead of one per (source, reader)
    pair — and it is what keeps the translator on the only direction that
    has ever been measured.
  - >-
    LAYOUT. Reader-language line, then the bracketed original, then the
    URL on its own line, with a blank line between entries. The bracketed
    line is SUPPRESSED when it would equal the reader-language line, so a
    post already in the reader's language renders as today's single line
    and the all-English corpus stays byte-identical. Bracket wrapping
    happens AFTER truncation, mirroring the marker rule, so a cut can
    never drop the closing bracket.
  - >-
    ANCHOR-ABSENT CASE. A non-English source with a NULL anchor promotes
    the bracketed original to the primary slot. The invariant to pin: an
    UNBRACKETED line always means "already in the reader's language", so
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
    CONSISTENT ACROSS SURFACES. Digest, `/summary`, `/retry` and `/saved`
    render the same post identically. `/saved` renders through a
    `MessageFormat` line template and needs the block shape threaded
    through it.
  - >-
    PERSISTED-BYTE PINS. `digest_section.content` stores rendered bytes
    and `DigestRetryService.replayMissing` replays them verbatim, so
    sections written before this ticket replay in the old shape after it —
    acceptable, but state it. Every byte assertion that changes is
    re-pinned knowingly. Per CLAUDE.md §"Preserve the controls of a path
    you replace", enumerate what each changed assertion INCIDENTALLY
    pinned (truncation bounds, sanitizer output, marker placement) and
    carry each across; a byte assertion that pins a security property is
    load-bearing beyond its name.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  preserves:
    - >-
      M1-747's zero-translator-call assertions for `en` scopes — the
      property survives the amendment; only the bytes change, and only for
      non-English sources.
    - >-
      M1-755's `/saved` per-user cache partitioning and rate-cap metering.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D30
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-759: Anchor-first headline display

## Context

Design session 2026-08-04. Two defects sit behind one decision.

**English readers are the only audience display translation does not
serve.** `runForDisplayHit` short-circuits on `scopeLanguage == "en"`, and
nothing in the provider reads `post.title_en` — verified: the sole
provider reference is a javadoc mention in `LlmOutputSanitizer`. So a
Turkish post renders as Turkish to the default reader, while the English
translation of that exact headline sits in the row, already computed,
already sanitized. M1-747 excluded the anchor deliberately, but its stated
reason was cost — "a regression here adds an LLM call to every result
set" — and reading a populated column adds no call. The criterion's
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

A localized `originál:` label reads better and is the intended end state.
It is NOT in this ticket: it needs a bundle key, `BundleLoaderTest`
enforces bilateral keyset parity, and three concurrent sessions are adding
es/tr/ru bundles. A key added here breaks whichever side merges second.

## Known wrinkle, to be carried into the redteam

Bracketed system markers plus bracket-wrapped untrusted text are
spoofable: the D30 marker is literally `[machine translated]`, the bracket
content is feed-authored, so a hostile source can title a post
`machine translated` and its bracketed line renders identically to a
system annotation. Severity is cosmetic — the closed-list sanitizer still
strips command shapes and no injection follows — but it is inherent to the
chosen affordance and should be adjudicated deliberately rather than
discovered. It is one more reason the label form is the better end state.

## Notes

- `complexity: high` triggers the plan-writer sidecar. The design gate in
  `acceptance:` (per-message worst case) must be answered in that sidecar
  BEFORE code, because a cap breach invalidates the layout.
- Sequenced after the es/tr/ru bundle work by intent, not by dependency:
  `blocked_by` names only M1-756. If those sessions are still open when
  this is picked up, check `git worktree list` for renderer overlap first.
