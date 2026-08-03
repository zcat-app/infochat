---
id: M1-756
title: "Display-time hit translation for digest headlines and the degraded renderers"
status: pending
created: 2026-08-03
last_updated: 2026-08-04
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE HEADLINE LAYOUT. This ticket keeps the CURRENT single-line form
    `· <headline>  <url>` byte-for-byte. The three-line
    reader-language/bracketed-original/url block, and the anchor read that
    feeds it, are M1-759 — deliberately sequenced after the es/tr/ru
    bundle work rather than merged here, so this ticket races no one.
    The one method M1-759 rewrites is `appendHeadlines`; that churn is
    accepted and recorded, not avoided by deferring this ticket.
  - >-
    READING `post.title_en` / `post.body_en`. The anchor stays a
    retrieval artifact for the duration of this ticket (M1-747
    out_of_scope, unchanged until M1-759 lands the D29 display-leg
    amendment in code). Translation here runs source -> reader on the
    ORIGINAL headline, exactly as `/summary` does today.
  - >-
    The /summary, /retry (M1-747) and /saved (M1-755) legs. All three are
    merged and their behaviour is not revisited; this ticket brings the
    digest to parity with them, it does not change them.
  - >-
    Translating bundle-sourced prose. D43's bundle-not-translator
    invariant stands: only the EMBEDDED source-authored fragment (the post
    headline) may enter the translator, never the bundle template text
    around it.
  - >-
    Persisting translated text, changing digest selection/ordering, or
    changing the section-cap and category-cap policy.
  - >-
    NEW BUNDLE KEYS. Three parallel sessions are adding es/tr/ru bundles;
    `BundleLoaderTest` enforces bilateral keyset parity, so a key added
    here breaks whichever side merges second. Nothing in this ticket needs
    one.
acceptance:
  - >-
    `DigestPostCollector` projects the source's declared language.
    `POSTS_ALL_SQL` and `POSTS_TAGGED_SQL` already `JOIN source s`, so the
    change is `s.language` in the select list plus the full
    `EligiblePostQuery.Post` constructor in `mapPost` — which today ends
    at `source_window_posts`, i.e. the pre-M1-747 compat overload that
    hard-codes `sourceLanguage = null`. Without this the digest leg is
    dead on arrival: `runForDisplayHit`'s null-source-language no-op leg
    returns the input unchanged for every digest row, forever, with no
    error anywhere.
  - >-
    `DigestRenderer.appendHeadlines` routes each headline through
    `translationPipeline.runForDisplayHit(headline, p.sourceLanguage(),
    scopeKind, scopeId, scopeLanguage)` — the same entry point, no-op
    legs, §10 controls, fallback and cache as `/summary`. The rendered
    line shape is unchanged: the translated string simply replaces the
    untranslated one in the existing `· <headline>  <url>` template.
  - >-
    LLM-COST METERING, mandatory and stronger than `/saved`'s. M1-755 drew
    a high/DOS red-team finding for an on-demand, user-initiated page; the
    digest is a SCHEDULED BROADCAST to every group with no user in the
    loop, up to `infochat.digest.category-headline-count` (default 5)
    headlines per category across every category and every group, so the
    generative surface is strictly larger. Follow M1-755's shape: a
    per-render translator budget
    (`infochat.digest.translation-max-per-render`, default 5) bounds the
    per-invocation generative count, cache hits cost no budget because
    they make no provider call, and rows beyond the budget render
    untranslated and unmarked. State the per-group-per-slot worst case in
    the ticket's own terms before implementing; if it exceeds what the
    `/saved` precedent justifies, escalate rather than widen the budget.
  - >-
    DEGRADED PATHS ARE EXCLUDED, and the recorded reason is the SPEC PIN,
    not the cost story. `DegradedDigestRenderer` and
    `SummaryProseGenerator.degradedProseFor` add no translator call.
    Rationale to record in the code comment: `docs/spec/security.md`
    §Failure handling PINS degraded output to headlines + URLs + UIDs with
    no LLM calls — the same pin `ClusterBlockRenderer.java` already cites
    for its own degraded skip. Do NOT record it as "the LLM path failed so
    the translator is down": the translator is a different
    `ModelTask.TRANSLATOR` route than the summarizer whose failure
    triggered the degraded branch, and a cache hit makes no provider call
    at all, so the cost argument alone does not survive review.
  - >-
    `en` scope stays byte-identical with zero translator calls, asserted
    with a provider spy — the M1-747 acceptance property, extended to the
    digest. Persisted `digest_section.content` byte pins are preserved;
    any that must change are re-pinned KNOWINGLY with the reason stated,
    never by updating expected strings until green.
  - >-
    `DigestRetryService.replayMissing` is untouched and still delivers
    persisted bytes verbatim with no cache consult and no re-translation.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  preserves:
    - >-
      `DegradedDigestRendererTest` — its no-translator-call expectation is
      now load-bearing, not incidental. It must stay green WITHOUT
      modification.
    - >-
      Every persisted-section byte assertion in `DigestRendererSectionsTest`
      and `DigestRoundtripIT`.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/security.md §Failure handling
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

# M1-756: Display-time hit translation for digest headlines and the degraded renderers

## Context

Filed from M1-747's surface-binding rework (2026-08-03); scope settled
2026-08-04 after a design session that also produced the D29 display-leg
amendment and M1-759/760/761.

M1-747 landed display-hit translation and wired `/summary` and `/retry`;
M1-755 wired `/saved`. The digest broadcast — the highest-volume headline
surface in the deployment — is still untranslated, and would REMAIN
untranslated even if the pipeline call were added, because
`DigestPostCollector.mapPost` builds its rows through the pre-M1-747
compat constructor. That overload documents `sourceLanguage` NULL as
"unknown — never translate", which is correct for a hand-built fixture
and wrong for a production projection that has `source.language` sitting
in an already-joined table.

So the digest needs two changes that only work together: project the
language, and call the pipeline. Either alone is inert — a projection with
no reader is dead code, and a pipeline call over null source languages is
a permanent no-op.

## Why the layout is NOT here

The design session settled a three-line block — reader-language headline,
bracketed original beneath it, URL — plus reading the English anchor so
that English readers stop being the one audience that gets no help. That
is M1-759. It is separated from this ticket for one reason: it touches
renderers that three concurrent es/tr/ru sessions may also be in, and it
depends on a spec amendment landing first. This ticket touches neither,
so it can run now.

The cost is that M1-759 rewrites `appendHeadlines` again. That is one
method, and the projection, the metering and the degraded decision all
survive M1-759 unchanged.

## Census

Surfaces that render a post headline, and whether each translates:

```
grep -rn "DisplayHeadline.of\|runForDisplayHit" --include=*.java \
  infochat-provider/src/main/java
```

| Site | Translates today | Disposition |
|---|---|---|
| `ClusterBlockRenderer` (`/summary`, `/retry`) | yes (M1-747) | out-of-scope |
| `SavedCommandHandler` (`/saved`) | yes (M1-755) | out-of-scope |
| `DigestRenderer.appendHeadlines` | **no** | **fix** |
| `DegradedDigestRenderer` | no | documented why-not |
| `SummaryProseGenerator.degradedProseFor` | no | documented why-not |
| `SummaryProseGenerator.buildPrompt` | no — prompt input | out-of-scope (M1-747) |
| `CategoryRollupGenerator` | no — prompt input | out-of-scope (M1-747) |

If the grep returns a site absent from this table, it needs a row before
this ticket starts.

## Notes

- `security_relevant: true`: the change adds a metered LLM call to a
  scheduled broadcast path, and the `/saved` equivalent drew a high/DOS
  finding. Expect `/redteam` to probe the per-group worst case first.
- The degraded exclusion is the item most likely to be challenged in
  review. The pin is `docs/spec/security.md` §Failure handling; cite it
  in the code, not the cost reasoning, and the precedent is the comment
  already in `ClusterBlockRenderer`.
