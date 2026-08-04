---
id: M1-759
title: "Anchor-first headline display: reader-language line, bracketed original beneath"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by:
  - M1-756
files_budget: 21
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
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
    but needs a migration this ticket excludes. Follow-up ticket.
  - >-
    DEGRADED-SURFACE PARITY. `DegradedDigestRenderer` and
    `SummaryProseGenerator.degradedProseFor` call
    `DisplayHeadline.of(Post, …)` and are NOT in `files_scope`; they keep
    rendering the original. That is why the anchor must enter through a
    NEW entry point rather than by changing `of(Post, …)`. Giving the
    degraded path the anchor — where a column read still works with no
    LLM — is a follow-up ticket.
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
    `--full` through the same `ClusterBlockRenderer` as `/summary`, so
    omitting its projection would render a different primary line than
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
    THE MACHINE-TRANSLATION MARKER IS REMOVED. `reply.translation
    .hit_marker` is deleted from `BundleKeys` and from all five bundles
    (en/cs/es/ru/tr — removing all five together keeps `BundleLoaderTest`
    bilateral parity satisfied), and `finishDisplayHit` stops appending
    it. Rationale: it was an M1-747 implementation choice with no mandate
    in `docs/spec/` or `docs/design/` (it is NOT decision D30, which
    governs plain-text formatting), it is redundant in every composition
    this ticket produces — position already carries "derived", and the
    only compositions with no bracket are the ones showing the
    publisher's own words — and removing it deletes the spoof target
    named in §"Known wrinkle" outright instead of mitigating it. Its
    absence is asserted on the fallback path too; assertions that become
    vacuous are DELETED, never weakened. `DisplayHeadline.truncate`'s
    `markerSafeCut` protects `LlmOutputSanitizer
    .REDACTED_COMMAND_REPLACEMENT`, a different marker, and is untouched.
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
    render the full block; `/retry --full` renders byte-identically to
    the `/summary --full` it replays; `/saved` renders the anchor-absent
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
translations) was introduced by M1-747 as an implementation choice. It is
mandated nowhere: `docs/spec/` and `docs/design/` contain no reference to
it, and the ticket text that called it "the D30 marker" was wrong — D30
governs plain-text output formatting (backticks, bare URLs) and says
nothing about translation provenance.

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
a literal, not the key. Enumerate by invocation AND by value:

```
grep -rn "REPLY_TRANSLATION_HIT_MARKER\|hit_marker\|machine translated\|strojový překlad\|traducción automática\|машинный перевод\|makine çevirisi" \
  --include=*.java --include=*.properties infochat-provider/src
```

| Site | Disposition |
|---|---|
| `bundle/BundleKeys.java:2029` | REMOVE the constant |
| `translation/TranslationPipeline.java:442` | REMOVE the append in `finishDisplayHit` |
| `resources/bundles/{en,cs,es,ru,tr}.properties` | REMOVE the value from all five together (bilateral keyset parity) |
| `test/…/translation/DisplayHitTranslationTest.java:87` | REMOVE the `marker(lang)` helper; assertions that become vacuous are deleted, not weakened |
| `test/…/command/SavedCommandHandlerTest.java:423` | RE-PIN without the marker |
| `test/…/command/ClusterBlockRendererTest.java:180,215` | RE-PIN; `:215`'s absence assertion becomes vacuous — delete it |
| `test/…/digest/DigestRendererTest.java:46` | REMOVE the `CS_MARKER` constant and its uses |

NOT in this class, despite matching on the word "marker" in prose:
`TranslationPipeline.REJECTED_BY_TARGET_SCRIPT_CHECK` and its
`DisplayHitTranslationTest` cases (M1-761), and
`LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT` with
`DisplayHeadline.markerSafeCut`. Both stay.

### B. Post projections that can feed a display surface

```
grep -rn "p\.title" --include=*.java infochat-provider/src/main
```

| Site | Disposition |
|---|---|
| `summary/EligiblePostQuery.java:283` | IN — project the anchor |
| `digest/DigestPostCollector.java:158` AND `:178` | IN — TWO separate SQL blocks; both get the anchor, or the two queries render inconsistently |
| `command/RetryCommandHandler.java:90` | IN — project the anchor (the third site the pre-refine ticket omitted) |
| `command/SaveCommandHandler.java:115` | OUT — the `/save` snapshot WRITE; storing the anchor needs `saved_post` columns (follow-up ticket) |
| `summary/SummaryProseGenerator.java:181` | OUT — prompt input; stays untranslated and anchor-free (M1-747) |
| `digest/CategoryRollupGenerator.java:329` | OUT — prompt input via the `(String, String, sanitizer)` overload |
| `chat/tool/GetPostTool.java:62` | OUT — D21 chat-mode |
| `chat/tool/SearchPostsTool.java:147` | OUT — D21 chat-mode |
| `chat/tool/SemanticSearchTool.java:231,246` | OUT — D21 chat-mode |

## Notes

- `complexity: high` triggers the plan-writer sidecar. The sizing gate in
  `acceptance:` must be answered in that sidecar BEFORE code.
- A first plan-writer pass returned `OUTLINE FAILED` on 2026-08-04
  against the pre-refine text; this rewrite is that escalation's
  resolution. Its findings on `/saved`, `/retry` and the marker are all
  folded in above. A fresh pass is still required and may surface
  blockers the first did not audit.
- Two follow-up tickets are owed and are named in `out_of_scope`: a
  `saved_post` English-anchor snapshot (migration, following V76's
  pattern), and degraded-surface parity for `DegradedDigestRenderer` /
  `SummaryProseGenerator.degradedProseFor`.
- Sequenced after the es/tr/ru bundle work by intent, not by dependency:
  `blocked_by` names only M1-756. Those bundles have since landed
  (M1-716/718/719/720). Renderer overlap is with M1-756 and M1-762
  (`DigestRenderer.java`, `DigestRendererSectionsTest.java`) and M1-761
  (`TranslationPipeline.java`, `DisplayHitTranslationTest.java`); all are
  merged. Check `git worktree list` as well as ticket status, since a
  worktree-local branch is invisible to the frontmatter.
