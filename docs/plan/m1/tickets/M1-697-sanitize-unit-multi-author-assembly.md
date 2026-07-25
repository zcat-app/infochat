---
id: M1-697
title: "Scope every feed-bytes sanitize call to one author's field"
status: done
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
remediates: M1-694
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    LlmOutputSanitizer itself — its CLOSED_LIST, its flatten pass, its "](" 
    adjacency-breaking mechanism, and the whole-message span rule of
    redactFlagEntry. This ticket decides WHAT UNIT is fed to the sanitizer,
    not how the sanitizer behaves on the unit it is given. Changing the span
    rule would regress the /list-sources --all dispatch cases security.md
    §"Flag position mirrors the parser's own scan" enumerates.
  - >-
    DegradedDigestRenderer and the periodic digest's own degraded branch.
    That is M1-691's ticket and it is running the same decision for the
    broadcast digest; do not pre-empt it. If M1-691 lands first, adopt its
    chosen shape here rather than inventing a second one.
  - >-
    The render-form split itself (categorized default vs --full). M1-694
    shipped it and it is not reopened here; this ticket changes only the
    granularity of the sanitize call on the paths that already make one.
  - >-
    Ingest-side title normalization. Constraining what a feed may put in a
    title is M1-693 and is a different boundary (write-side, not render-side).
  - >-
    /retry's render-form persistence (M1-696) and per-section delivery
    (M1-695). This ticket may touch what /retry RENDERS through
    ClusterBlockRenderer, but must not change which form /retry selects or
    how any reply is delivered.
acceptance:
  - >-
    Every sanitize call over feed-derived text takes ONE author's field as
    its input. Concretely: no call site passes a string that concatenates
    titles (or URLs, or display names) from more than one post. The
    invariant is stated in a comment at each call site so a future edit that
    widens the unit is visibly wrong.
  - >-
    DigestRenderer.renderSummarySections no longer passes a whole cluster's
    assembled degraded prose to one sanitize call. Redaction of a
    command-shaped title in post A can no longer delete post B's headline,
    URL or uid from the rendered output.
  - >-
    Degraded prose is DERIVED at render, never trusted from the record
    (redteam 2026-07-25 low). DigestRenderer.renderSummarySections,
    DigestRenderer.renderSections and ClusterBlockRenderer obtain degraded
    prose via SummaryProseGenerator.degradedProseFor(cp.cluster(),
    sanitizer) — not by reading cp.prose() — so a hand-assembled degraded
    ClusterProse cannot smuggle unsanitized bytes onto any render path:
    for degraded clusters the prose bytes in the record are IGNORED, and
    the trust is structural rather than a producer convention. A test
    constructs a degraded ClusterProse whose prose string carries a raw
    command-shaped title unrelated to the cluster's posts and asserts the
    rendered output shows the cluster's own sanitized titles instead.
    Audit accounting: LLM_OUTPUT_SANITIZED fires per sanitize call, so a
    degraded title now produces one row at producer composition AND one at
    renderer derivation — duplicate emission is truthful per-occurrence
    accounting (two calls happened), not detector widening.
  - >-
    A test constructs a cluster whose posts are titled "/list-sources", a
    legitimate headline, and "--all" in that order, renders the default
    /summary form, and asserts the legitimate headline SURVIVES — headline,
    bare URL and uid all present. The redaction span no longer crosses post
    boundaries: the two crafted titles render VERBATIM, because neither is
    a closed-list token within its own field (bare `/list-sources` and bare
    `--all` are not CLOSED_LIST entries; a flag-bearing entry redacts only
    when command word and flag appear in ONE sanitize input,
    LlmOutputSanitizer.java:294-333). This is the exact repro from
    docs/plan/m1/redteam/M1-694-2026-07-25-r3.md; today it fails on the
    first clause (the whole-cluster span deletes the innocent post). The
    verbatim pair is the accepted residual named in the detector item
    below, NOT a redaction the test should expect — an earlier draft of
    this item demanded both titles be redacted, which is unreachable once
    the unit is one author's field (premise-fail refine, 2026-07-25).
  - >-
    /summary --full no longer delivers a raw command-shaped feed title. The
    flat form's `summary:` field carries degraded prose verbatim today
    (ClusterBlockRenderer.java:112-113), so a title whose canonical form is a
    privileged command ships unredacted there even though the headline line
    above it reads [redacted command]. A test pins the redaction on the
    --full form.
  - >-
    The headline redaction no longer depends on cluster position.
    ClusterBlockRenderer.java:80 sanitizes posts.get(0).title() only, so a
    multi-post cluster whose command-shaped post is not first emits no
    LLM_OUTPUT_SANITIZED row at all. A test pins that a command-shaped title
    on a NON-first post is redacted and audited.
  - >-
    /retry inherits the fix. It always replays the flat form
    (RetryCommandHandler.java:265-268 constructs ClusterBlockRenderer
    unconditionally), so a user who never types --full still reaches the
    unsanitized path via the spec-sanctioned /retry after a degraded run.
    No RetryCommandHandler change should be needed — verify that and say so.
  - >-
    docs/spec/security.md is updated: the accepted-residual paragraph added
    by M1-694 (§LLM output sanitizer, "The span's justification depends on
    the caller's unit of input") is replaced by a statement of the restored
    invariant, and the §"Sanitizer output never contains `](`" note that
    /summary --full and /retry "are covered by neither" is corrected. The
    restated invariant explicitly SCOPES the whole-message span guarantee
    to within one author's field — a deliberate weakening, stated as such —
    and names the cross-field split-token residual from the detector item
    below as accepted.
  - >-
    Bare URLs still survive every sanitize call (D30 plain-text), and the
    per-occurrence LLM_OUTPUT_SANITIZED audit row is emitted at least as
    often as before for every closed-list token that appears WITHIN one
    author's field — narrowing the unit must not narrow the detector on
    the unit it now scans. A privileged command split ACROSS two posts'
    fields (command word in one title, flag in another) is the accepted
    residual: it is neither redacted nor audited, which is the pre-M1-694
    posture on the flat form, bounded by dispatch still requiring
    is_admin=true. This detector narrowing versus the M1-694 whole-cluster
    unit is a deliberate accept, stated here and in security.md — an audit-
    only cross-post scan was considered and rejected (audit rows would
    lose their visible-redaction counterpart, and an unthrottled match the
    attacker can trigger for free is an audit-spam vector).
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  preserves:
    - >-
      All tests green on main. In particular
      defaultFormRedactsCommandShapedFeedTitleInDegradedProse and
      defaultFormOverflowLineUsesDmWordingNotTheGroupDigestWording (M1-694)
      must stay green unmodified — this ticket narrows the sanitize unit, it
      does not remove any redaction.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D19
  - D30
  - D43
reviews:
  - round: 1
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 781
      removed: 107
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-25
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer (post-diff lines 399-409): "A command-shaped title is redacted and audited no matter where its post sits in the cluster — the detector no longer depends on cluster position … The surface is therefore **closed** on all render forms." And the same section's own M1-675 lesson (lines 380-382): the `/save -t` echo "needed **both** a write-side reject **and** render-side redaction", because the bot-authored exemption "is a property the handlers must **maintain**, not one the exemption may assume" (lines 366-368).
    gap: |
      The diff deletes the last render-side redaction on the /summary default form and replaces it with an unenforced producer convention. In DigestRenderer.java (diff hunk at file lines ~228-245) the degraded arm changes from `llmOutputSanitizer.sanitize(cp.prose())` to appending `cp.prose()` raw, with the new comment "must NOT re-sanitize" — the pre-diff javadoc it replaces stated this call was "the only place a feed-controlled title can be redacted on the /summary path". ClusterBlockRenderer.java (diff lines 325-334) likewise trusts degraded prose "AS COMPOSED". The trust has no mechanical backing: `ClusterProse` is a public record whose `degraded=true` flag is the ONLY gate both renderers consult, and any caller can construct it with an arbitrary, unsanitized string — the diff's own test double demonstrates the seam (SummaryCommandHandlerTest.java hunk at ~line 1060 builds ClusterProse from a caller-supplied field). The composition-time sanitize in `degradedProseFor` covers every producer shown in the diff, so this is not currently exploitable through in-diff code — but the diff converts a render-time chokepoint guarantee into a provenance assumption, exactly the shape the spec's own M1-675 lesson warns against. The naive fix (re-sanitizing the assembled string) is NOT available: that was the M1-694-r3 content-suppression bug this ticket correctly removes. The fix must make the trust structural, not restore the chokepoint.
    repro: |
      Not reachable through the producers in this diff (all degraded ClusterProse instances route through the signature-changed `degradedProseFor(cluster, sanitizer)`, so a missed recomposition path fails to compile). The attack shape it re-opens the moment any present-or-future path hand-assembles a degraded ClusterProse (a helper, a /retry anchor replay building ClusterProse from stored fields, a new caller): a feed publisher sets a post title to `/grant-admin p-attacker`; a victim runs `/summary` on the default or flat form, or `/retry` replays it; the title renders VERBATIM and — unlike every other redaction path — emits no WARN and no per-occurrence LLM_OUTPUT_SANITIZED audit row, because neither renderer inspects degraded prose any more. Pre-diff, the DigestRenderer render-side call caught this regardless of where the prose came from; post-diff the system delivers an absolute spec claim ("closed on all render forms") that rests entirely on caller discipline.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-07-25
    verdict: FINDINGS
    base: 3a655d5681b26de2214fda232a2ca6a3d778a9fe
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-697-2026-07-25.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      In-progress gate audit ahead of review round 1. One low INJECTION
      finding (render-time redaction replaced by a provenance assumption
      on ClusterProse.degraded; not exploitable in-diff) surfaced to the
      user via the redteam-finding escalation. Out-of-model: chat-client
      auto-linking of bare non-http(s) feed URLs is outside the
      documented threat model.
  - date: 2026-07-25
    verdict: CLEAN
    base: 3a655d5681b26de2214fda232a2ca6a3d778a9fe
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-697-2026-07-25-r2.md
    out_of_model_count: 0
    note: |
      Round-2 re-audit after the redteam-finding refine: the round-1 low
      is CLOSED by derive-at-render (all three render arms obtain degraded
      prose via degradedProseFor(cp.cluster(), sanitizer) and ignore the
      record's prose bytes). Re-audit ran with the prior finding quoted
      and an explicit CLEAN authorization.
clarity_check:
  date: 2026-07-25
  verdict: PASS
  warnings:
    - "census line drift only: DegradedDigestRenderer sanitize now at :59 (was :38), ChatAgent at :545 (was :507) — dispositions unchanged; M1-691 is done and its per-title shape is the one this ticket adopts"
  blockers: []
escalation_reason:
---

# M1-697: Scope every feed-bytes sanitize call to one author's field

## Context

Filed from M1-694's redteam round 3
(`docs/plan/m1/redteam/M1-694-2026-07-25-r3.md`), which accepted the finding
as residual rather than fixing it in-scope. This ticket owns the class.

**The invariant that was never written down.** Every pre-existing sanitize
call over feed-derived text is scoped to a *single* author's field:

| Call site | Unit |
|---|---|
| `ClusterBlockRenderer.java:87` | one post title (`first.title()`) |
| `DegradedDigestRenderer.java:38` | one title per post |
| `SavedCommandHandler.java:275,277` | one row's title / one row's tags |

`LlmOutputSanitizer.redactFlagEntry` deletes the whole span from a command
word to a matching flag token (`appended = flagEnd`, `LlmOutputSanitizer
.java:324-327`), and the token search crosses newlines (`isTokenSeparator`
includes `\n`). That span rule is deliberate and correct — `security.md`
§"Flag position mirrors the parser's own scan" derives it from what
`ListSourcesArgs.parse` actually dispatches, and a line- or sentence-scoped
bound would let real dispatching forms evade the match. Its justification,
stated in that same section, is that the bytes swallowed between the two
endpoints are **bot-authored**.

That justification holds only while the sanitize unit is one author's field.
M1-694 broke it for the `/summary` default path: `DigestRenderer
.renderSummarySections` calls `sanitize(cp.prose())` once per cluster, and
`SummaryProseGenerator.degradedProseFor:193-203` composes `title — url (uid)`
for *every* post in that cluster. One call now spans several mutually
untrusted publishers.

## Census

**Required — this is a class-scoped ticket.** The class is "every
`LlmOutputSanitizer.sanitize` call whose input can carry feed-derived bytes".
Enumerate mechanically:

```
grep -rn "\.sanitize(" --include=*.java \
  infochat-provider/src/main infochat-collector/src/main
```

Returned 12 sites on 2026-07-25. Every one needs a row; `out-of-scope:
<reason>` is a valid disposition, "not listed" is not. The discriminator is
**what the input string is**, not which class calls it.

| Site | Input unit | Disposition |
|---|---|---|
| `ClusterBlockRenderer.java:87` | one post title (`first.title()`) | **in scope** — correct unit, but only ever `posts.get(0)`; gap 3 |
| `ClusterBlockRenderer.java:115` | one cluster's LLM prose, non-degraded arm only | **in scope** — the degraded arm at :112-113 bypasses sanitize entirely; gap 2 |
| `DigestRenderer.java:241` (`renderSummarySections`) | one cluster's WHOLE assembled prose | **in scope** — this is gap 1, the multi-author unit M1-694 introduced |
| `SummaryProseGenerator.java:193-203` (`degradedProseFor`) | — (composes, never sanitizes) | **in scope** — the assembly point; the natural place to narrow the unit |
| `DigestRenderer.java:134` (`renderSections`) | one cluster's LLM prose | out of scope — LLM-authored, single generated value, not multi-author feed bytes. Untouched by M1-694 |
| `DegradedDigestRenderer.java:38` | one post title | out of scope — already the correct unit, and it is M1-691's file |
| `SavedCommandHandler.java:275` | one row's title | out of scope — already correct unit |
| `SavedCommandHandler.java:277` | one row's joined tags | out of scope — already correct unit; tags are `TagNormalizer`-constrained |
| `SavedCommandHandler.java:254` | the caller's own `--tag` arg | out of scope — user-supplied, not feed-derived, single value |
| `TranslationPipeline.java:114` | translator output (sanitizer-2) | out of scope — LLM-authored single value |
| `CategoryRollupGenerator.java:148` | roll-up LLM prose | out of scope — LLM-authored single value |
| `ChatAgent.java:507` | chat reply text | out of scope — LLM-authored single value |
| `Stage1Pipeline.java:543` | OWASP HTML policy, not `LlmOutputSanitizer` | out of scope — different sanitizer, ingest-side, no closed-list span rule |

The pattern the table makes visible: **every out-of-scope row is either a
single LLM-generated value or a single author's field.** The four in-scope
rows are the only places where feed bytes from more than one publisher can
reach one call, or where a correct unit is applied to the wrong subset of
posts.

## The three gaps

1. **Cross-post redaction span (introduced by M1-694, low).** Posts titled
   `/list-sources` and `--all` co-clustered around a legitimate post collapse
   the whole span — including the innocent post's headline, URL and uid — into
   one `[redacted command]`. Fails safe (over-redaction, visible marker, audit
   row still fires), which is why M1-694 accepted it, but it is a
   content-suppression vector: the attacker chooses whose story disappears.
2. **`--full` ships the raw title (pre-existing).** `ClusterBlockRenderer
   .java:112-113` writes degraded prose verbatim into the `summary:` field, so
   a command-shaped title is delivered unredacted two lines below a
   `[redacted command]` headline. Strictly narrower than pre-M1-694 (where the
   flat form was the only form), but not closed.
3. **Position-dependent redaction (pre-existing).** The headline sanitize sees
   `posts.get(0)` only, so a command-shaped title on a non-first post of a
   multi-post cluster is neither redacted nor audited — the operator's
   detector reports "no attack" while the token ships.

Gaps 2 and 3 reach users who never type `--full`, because `/retry` always
replays the flat form.

## Why not the cheap fix

Sanitizing the assembled degraded prose **per line** was considered and
rejected in M1-694: `/list-sources` is not a bare `CLOSED_LIST` entry (only
the two flag-bearing forms are, `LlmOutputSanitizer.java:130-131`), so a feed
title containing a newline would split across the line boundary and
**under**-redact — the wrong direction. Titles may contain newlines until
M1-693 lands. The fix must narrow the unit at the point of *assembly*, not
re-cut the assembled string.

## Notes

- **Scope widened again 2026-07-25 (second budget-breach).**
  `ClusterBlockRendererTest.java` joins `files_scope` and
  `test_plan.modifies`: its degraded `ClusterProse` fixtures carry a
  marker prose string that pinned the record-trust behavior the redteam
  remediation removed. The ticket changes rendering to derive-at-render,
  which requires updating that test's two byte-for-byte expectations to
  the derived composition (`title — url (uid)` per post) and refreshing
  two stale comments; assertions on labels, plurals, classification and
  headline redaction are unchanged (§8 authorization for the
  modification). Touched files: 10, at `files_budget: 10`.
- **Scope widened 2026-07-25 (escalate → refine, budget-breach).**
  Sanitizing per title inside `degradedProseFor` changes that method's
  signature (it needs an `LlmOutputSanitizer`), which forces edits to three
  files the original `files_scope` omitted: `SummaryCommandHandler.java`
  (caller at :455 — the over-cap branch the r3 repro drives, so the call
  change is not optional), `SummaryProseGeneratorTest.java` (:110) and
  `SummaryProseRefusalDegradeTest.java` (:58). The two test files get ONLY
  the mechanical signature update at their static `degradedProseFor(...)`
  call sites (plus whatever sanitizer wiring the generator's constructor/
  field needs); their assertions are unchanged. Touched files: 9, within
  `files_budget: 10`.
- The natural shape is to sanitize each post's title where
  `degradedProseFor` composes it, so every consumer of degraded prose
  inherits a per-author unit — but that changes `SummaryProseGenerator`
  output for the digest path too, which is why `DegradedDigestRenderer` and
  M1-691 are in `out_of_scope` and must be coordinated rather than
  pre-empted. Check M1-691's state before choosing.
- **Premise-fail refine 2026-07-25 (second escalation).** The original
  acceptance demanded the r3 repro's two crafted titles be redacted AND the
  audit rate never drop — both unreachable once the sanitize unit is one
  author's field: `redactFlagEntry` fires only when command word and flag
  share ONE input, so a `/list-sources` title and an `--all` title on
  different posts neither redact nor audit. The refined items make the
  honest end state explicit: the innocent post survives, the split pair
  ships verbatim (pre-M1-694 posture), the span guarantee is scoped to
  per-field in the spec, and the detector narrowing is a named accept.
- Watch the audit-row count: narrowing the unit must not reduce how often
  `LLM_OUTPUT_SANITIZED` fires for tokens WITHIN one field. Sanitizing
  three titles separately emits at least as many rows as sanitizing their
  concatenation for self-contained tokens (each field is scanned
  independently); the cross-field split case is the accepted exception the
  detector item above names.
- M1-694's `defaultFormRedactsCommandShapedFeedTitleInDegradedProse` already
  pins that the default form redacts and that the bare URL survives; it is
  the regression guard this ticket must not break.
