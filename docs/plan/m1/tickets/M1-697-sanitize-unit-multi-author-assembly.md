---
id: M1-697
title: "Scope every feed-bytes sanitize call to one author's field"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
remediates: M1-694
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
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
    A test constructs a cluster whose posts are titled "/list-sources", a
    legitimate headline, and "--all" in that order, renders the default
    /summary form, and asserts the legitimate headline SURVIVES while the
    two command-shaped titles are redacted. This is the exact repro from
    docs/plan/m1/redteam/M1-694-2026-07-25-r3.md and currently fails.
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
    /summary --full and /retry "are covered by neither" is corrected.
  - >-
    Bare URLs still survive every sanitize call (D30 plain-text), and the
    per-occurrence LLM_OUTPUT_SANITIZED audit row is emitted at least as
    often as before — narrowing the unit must not narrow the detector.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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

- The natural shape is to sanitize each post's title where
  `degradedProseFor` composes it, so every consumer of degraded prose
  inherits a per-author unit — but that changes `SummaryProseGenerator`
  output for the digest path too, which is why `DegradedDigestRenderer` and
  M1-691 are in `out_of_scope` and must be coordinated rather than
  pre-empted. Check M1-691's state before choosing.
- Watch the audit-row count: narrowing the unit must not reduce how often
  `LLM_OUTPUT_SANITIZED` fires. Sanitizing three titles separately should
  emit at least as many rows as sanitizing their concatenation, not fewer.
- M1-694's `defaultFormRedactsCommandShapedFeedTitleInDegradedProse` already
  pins that the default form redacts and that the bare URL survives; it is
  the regression guard this ticket must not break.
