---
id: M1-756
title: "Display-time hit translation for digest headlines and the degraded renderers"
status: done
created: 2026-08-03
last_updated: 2026-08-04
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
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
    `DigestRenderer.renderSections` GAINS THE GROUP SCOPE. The renderer is
    `@ApplicationScoped` and its signature is `renderSections(posts,
    langCode, mode)`, so neither the `scopeKind` nor the non-null `UUID
    scopeId` that `runForDisplayHit` requires is reachable from inside it —
    the pipeline call cannot be made without threading the group in. Add a
    `UUID groupId` parameter; the sole production caller `DigestWorker`
    already holds `slot.groupId()` at its call site, and `scopeKind` is the
    literal `"group"` supplied by the renderer (the digest broadcasts to
    groups only). The two test callers the signature reaches —
    `RecordingDigestRenderer`'s `@Override` and every
    `DigestRendererSectionsTest` call site — are updated MECHANICALLY, with
    no assertion touched: all of them render an `en` scope, so their
    persisted-byte pins hold through the no-op leg.
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
    at all, so the cost argument alone does not survive review. TWO COMMENT
    HOMES, not three files: `DegradedDigestRenderer` carries its own (it is
    a separate renderer this diff does not otherwise represent, and it is
    where a reader asking "why is the degraded digest untranslated?"
    lands), and the `degradedProseFor` rationale goes inline at
    `DigestRenderer.appendClusterProse`, the site that calls it — the
    `ClusterBlockRenderer` precedent records its degraded skip at the
    TRANSLATING site, not inside the degraded class.
    `SummaryProseGenerator.java` is NOT touched.
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
    THE METERING RATIONALE MUST BE TRUE ON EVERY ROUTE THAT REACHES
    `renderSections` (redteam 2026-08-04, low/DOS). "Scheduled broadcast
    with no user in the loop" is right for the scheduler and WRONG for
    `/retry --digest`: `RetryCommandHandler` draws the per-user LLM token
    and the D47 per-group sub-bucket (refunding the former on a group
    rejection) and then reaches this same render through
    `DigestRetryService.fallbackRerun` -> `digestWorker.execute(slot)`.
    The comment the previous acceptance item requires is not satisfied by
    a rationale that is false on a reachable route — state both routes and
    what meters each.
  - >-
    §SECRETS HANDLING NAMES THE DIGEST AS A TRANSLATOR SURFACE (redteam
    2026-08-04, low/INFO-LEAK). `docs/spec/security.md` §Secrets handling
    enumerates what routing `ModelTask.TRANSLATOR` to a remote provider
    exposes — the M1-746 chat-query leg and the M1-755 saved-post
    headlines — and the operator consents on that enumeration. This ticket
    adds the first UNATTENDED, SCHEDULED translator consumer: two slots
    per day per non-English group, forever, with no user action. Add the
    bullet in the M1-755 shape (state the surface and the new trigger
    property; hand the `prod/switch-llm.sh` disclosure TEXT to M1-758,
    which already owns it and whose `files_scope` is the script + its
    wiring test). Scope note: the omission is wider than this ticket — the
    `/summary` display-hit leg (M1-747) is absent from the enumeration
    too — but widening the bullet to cover a merged ticket's leg is not
    this ticket's job; name the digest only.
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
reviews:
  - round: 1
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1235
      removed: 49
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-04
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling — the post-setup
      `prod/switch-llm.sh` backend switcher "prints a per-task privacy
      disclosure naming exactly which generative tasks now call a remote
      provider and what each exposes", most recently extended with
      "`translator` also carries the user's saved-post headlines
      (M1-755)".
    gap: |
      The diff adds a THIRD ModelTask.TRANSLATOR display-hit consumer —
      the periodic group digest's normal-mode headlines — and amends
      neither §Secrets handling nor the M1-758 disclosure scope. What is
      new is not the data class but the TRIGGER: every previously
      disclosed translator surface is user-initiated (/summary M1-747,
      /saved M1-755, the D28 pre-fetch M1-746); this one is unattended
      and scheduled, two slots per day per non-English group, forever.
    repro: |
      1. Operator routes `translator` remotely via prod/switch-llm.sh and
         accepts the disclosure as printed.
      2. A group sets /lang cs.
      3. With no further user action, each slot POSTs up to
         infochat.digest.translation-max-per-render (5) of that group's
         feed headlines to the remote endpoint, twice a day, indefinitely.
         Nothing printed at switch time told the operator this stream
         would exist.
    suggested_fix_class: other
  - date: 2026-08-04
    category: DOS
    severity: low
    promise: |
      docs/spec/security.md §Rate limiting — "Periodic digests do NOT
      count against user-initiated per-group LLM budget (they are
      system-initiated; the aggregate system LLM budget is the backstop
      for digest cost)."
    gap: |
      The named backstop has no implementation — only per-task
      max-concurrency semaphores, which bound concurrency, not volume,
      and which the Provider does not declare for `translator` at all. So
      the new per-render, per-group budget does not compose: with N
      non-English groups the scheduler issues up to 5N translator calls
      per slot with no ceiling above it. Separately, the renderer's "no
      user in the loop" rationale is inaccurate on one route:
      `/retry --digest` reaches renderSections via
      DigestRetryService.fallbackRerun -> digestWorker.execute(slot).
      That route IS metered (per-user LLM token + per-group sub-bucket at
      RetryCommandHandler, plus a 2-minute per-group cooldown), which is
      why the finding is low.
    repro: |
      1. Deployment with M non-English groups, translator routed to a
         metered provider.
      2. The morning slot fires; each group's render spends up to 5
         translator calls. No counter sums them; no cap stops the Mth.
      3. A feed adversary publishing fresh distinct titles guarantees a
         cache miss on every render, so the budget is spent every slot.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling — `prod/switch-llm.sh`
      "prints a per-task privacy disclosure naming exactly which
      generative tasks now call a remote provider and what each exposes",
      extended by the translator-specific bullets (M1-746, M1-755) that
      the round-1 remediation adds a third to.
    gap: |
      Round 2, against the ROUND-1 REMEDIATION ITSELF. The new bullet
      asserted "every other translator caller is a user action
      (/summary, /saved, a chat turn), whereas this one recurs twice a
      day per group with no user in the loop". False, and two of the
      three counter-examples sit inside the very render the bullet
      describes: DigestRenderer.appendClusterProse (:793) translates lead
      prose in every non-brief mode and every cluster in FULL, and
      CategoryRollupGenerator (:213) translates one roll-up per section —
      both on the SAME scheduled render, both uncapped. The Collector's
      IngestTranslationWorker (:232 @Scheduled -> :318) is a third,
      continuously polling. The harm is an UNDERSTATED enumeration, not
      just a wrong sentence: the bullet quantifies the unattended stream
      as "up to translation-max-per-render feed headlines" and hands that
      text to M1-758, so an operator sizes remote-routing consent from a
      number that omits the larger legs.
    repro: |
      1. Operator reads §Secrets handling and sizes unattended exposure
         at "<=5 short feed headlines per non-English group per slot".
      2. A cs group with digest_mode=full takes its first slot: one prose
         translation per cluster of every surviving section plus one
         roll-up per section — tens of prose bodies, no user acting.
      3. The Collector has independently been POSTing every ingested cs
         post's title and body to the same endpoint all along.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-04
    verdict: FINDINGS
    base: d479f740a3b02ae1b35fb6b19afd1f0358d14afb
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-756-2026-08-04.md
    findings_count: 2
    out_of_model_count: 3
    note: |
      Round 1 at the /m1-tick run gate, ahead of review. Both findings are
      low and neither disputes what the diff does — each is a gap between
      the diff's new translator consumer and a promise made elsewhere (the
      switch-llm disclosure, which M1-758 already owns; and the
      "aggregate system LLM budget" the threat model names but no code
      implements). Three out-of-model items: feed-adversary budget
      monopolization degrades translation quality only; DigestWorker's
      renderFuture.cancel(true) does not actually interrupt an orphaned
      render (pre-existing, candidate follow-up); and a list of controls
      the auditor verified as delivered on the new path (re-sanitize with
      bound->flatten->sanitize order, one-author-field sanitize unit,
      per-scope cache partition, untrusted-content delimiter, degraded
      arms silent, ISO-639 gate, unchanged D59 world predicate).
      FALSIFICATION PASS (2026-08-04, before refine): both PROMISE quotes
      verified real (security.md §Secrets handling bullets; :1727-1729).
      [1] SURVIVES — but the enumeration also omits the /summary leg
      (M1-747), so the gap is wider than this ticket; fixed here for the
      digest only, in the M1-755 bullet shape. [2] SURVIVES as a fact (no
      aggregate LLM budget exists in code) but does NOT attribute to this
      diff: the M1-732 audit leaned on the same backstop and returned
      CLEAN, and every pre-existing digest LLM consumer is LESS bounded
      than this one — FULL prose is one call per cluster with the item cap
      lifted to Integer.MAX_VALUE (DigestRenderer:329), plus lead prose
      (:341) and one roll-up per section (:389), none per-render capped.
      Deferred to a follow-up ticket. Its /retry --digest sub-claim
      SURVIVES and is in this diff (verified RetryCommandHandler:552-557
      -> fallbackRerun -> digestWorker.execute -> renderSections); fixed
      in-band.
  - date: 2026-08-04
    verdict: FINDINGS
    base: 6a524cd9
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-756-2026-08-04-r2.md
    findings_count: 1
    out_of_model_count: 5
    note: |
      Round 2, re-audit of the round-1 remediation (the prior audit is
      superseded — its diff no longer exists). The one low finding is
      against the remediation itself and SURVIVED falsification: all
      three counter-examples verified by grep, so the "first unattended
      translator caller" claim was simply false. Remediated by rewriting
      the bullet to name the new DATA CLASS (source-authored feed
      headlines) rather than a novel schedule, and to state outright that
      the headline budget is NOT the size of the digest's unattended
      translator exposure. Two out-of-model items also actioned: the
      "only bound on the render's generative surface" phrasing (true only
      as "only rate-limiting control") in both the javadoc and
      application.properties, and the cache-probe TOCTOU now recorded as
      an accepted residual at the probe site. The auditor independently
      re-checked round-1 finding 2's main claim and DECLINED to re-file
      it, confirming the attribution argument that sent it to a follow-up
      ticket.
  - date: 2026-08-04
    verdict: CLEAN
    base: 6a524cd9
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-756-2026-08-04-r3.md
    out_of_model_count: 5
    note: |
      Round 3, re-audit of the round-2 remediation. CLEAN. The auditor
      re-verified the rewritten §Secrets handling bullet SENTENCE BY
      SENTENCE against source rather than accepting the remediation
      notes, and confirmed both prior findings closed; it also confirmed
      "TWO ROUTES" is an exhaustive enumeration of the callers reaching
      renderSections. NOTE ON redteam_findings: the /redteam skill says a
      CLEAN verdict sets that list to [], which is written for a
      single-audit flow. The three entries above are rounds 1-2 findings
      that were REAL and were remediated in-branch; erasing them would
      destroy the record of what this diff had to fix and why. They are
      retained, and this CLEAN entry is what marks them closed.
      Out-of-model, none actioned here: (a) §Secrets handling still does
      not name the /summary display-hit leg (M1-747) — pre-existing, and
      worth M1-758 naming four legs rather than three; (b) the bullet's
      "prose and roll-up legs dominate it" is not universal (a NORMAL
      digest with one section and fewer than leadMinimum clusters spends
      one roll-up against up to five headlines) but the auditor ruled it
      conservative and not a finding, and rewriting it would invalidate
      this CLEAN verdict for no security gain; (c) the new budget key has
      no §Rate limiting entry, unlike the M1-746 and M1-755 legs, so
      removing it later would need no spec amendment.
clarity_check:
  date: 2026-08-04
  verdict: PASS
  warnings:
    - >-
      Round 1 of the self-check found a wrong premise: acceptance required
      a runForDisplayHit call the renderer could not make, because
      renderSections carries no scopeKind/scopeId and DigestRenderer is
      @ApplicationScoped. Escalated budget-breach; resolved by refine
      d479f740 (files_budget 8 -> 10, four files added to files_scope, new
      signature-thread acceptance item, degraded comment homes named).
      Re-checked clean against the refined text.
    - >-
      Census re-run live at start; the seven sites the grep returns match
      the disposition table exactly, with the one nuance that
      SummaryProseGenerator has a single DisplayHeadline.of call site
      (degradedProseFor, line 233) rather than the two rows the table
      splits it into — buildPrompt composes its prompt input elsewhere.
      Neither is a translating site, so both dispositions hold.
  blockers: []
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
