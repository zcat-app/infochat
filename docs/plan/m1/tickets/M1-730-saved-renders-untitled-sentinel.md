---
id: M1-730
title: "/saved renders the ingest \"untitled\" sentinel to a reader"
status: done
created: 2026-07-30
last_updated: 2026-07-31
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The four chat tools that also emit a bare `title`
    (`ListSavesTool`, `SearchPostsTool`, `SemanticSearchTool`,
    `GetReferencesTool`). Their output is JSON reinjected into the chat
    prompt, not text shown to a reader, and `"untitled"` versus `""`
    conveys the same nothing to the model. That they return no body at
    all is a separate content-visibility question, not this ticket's.
  - >-
    The two LLM prompt builders (`SummaryProseGenerator.buildPrompt`,
    `CategoryRollupGenerator.buildPrompt`). `DisplayHeadline`'s javadoc
    states it is display-only and must never feed prompt input — a
    bounded headline would have the model summarize a fragment. Both
    already append the body on the line after the title.
  - >-
    Removing the `IngestTextNormalizer.UNTITLED_TITLE` sentinel from the
    write path. That decision carries a retention-window complication
    (see §Notes) and is not taken here.
  - >-
    Backfilling `saved_post` rows snapshotted with the sentinel, and any
    change to what `/save` snapshots. `saved_post.body` is already
    written by `SaveCommandHandler`; this ticket only reads it.
  - infochat-collector/**
  - infochat-core/**
acceptance:
  - >-
    A `/saved` line for a post saved with no upstream title shows text
    derived from the saved body instead of the word `untitled`.
  - >-
    `SavedCommandHandler` obtains that text from the shared
    `DisplayHeadline` derivation rather than a second inline fallback,
    so `/saved` cannot drift from the three surfaces M1-729 fixed.
  - >-
    CONTROL PRESERVATION (engineering-rules §10). The path being
    replaced is `SavedCommandHandler.java:275`, which today calls
    `llmOutputSanitizer.sanitize` on the title before interpolating it
    into the bundle line template. The replacement must keep that
    redaction, and must keep its UNIT at ONE author's field per call
    (M1-697) — title OR body, never a concatenation. A test asserts a
    command-shaped saved BODY promoted to the `/saved` line is redacted,
    which is the newly reachable leg: before this ticket the body could
    not reach that line at all.
  - >-
    The `{3}` tags placeholder at `SavedCommandHandler.java:278` keeps
    its own separate `sanitize` call. It is a second field and must not
    be folded into the headline derivation.
  - >-
    A saved post with neither title nor body still renders a stable
    line — no blank where the headline was, and no leaked sentinel.
    `DisplayHeadline` returns the empty string for such a post and its
    contract requires the caller to omit the headline token TOGETHER
    with the separator that would have followed it, which the single
    fixed `reply.saved.line` template cannot express. A second bundle
    template (`reply.saved.line.no-headline`) carries the token-less
    form, declared in `BundleKeys` and present in BOTH `en.properties`
    and `cs.properties` (D43 bilateral keyset). No hardcoded English
    stand-in — the line's language is the scope's.
  - >-
    `SavedCommandHandlerTest`'s existing title-rendering assertions are
    updated in step, not deleted.
  - >-
    REDTEAM REMEDIATION (audit 2026-07-30, DOS/medium). `saved_post.body`
    has no write-boundary cap, so promoting it to the headline would let
    an unbounded column cross JDBC for all 20 rows of a page before any
    Java-side bound applies. The SELECT must bound it at the SQL
    boundary, expressed in terms of `DisplayHeadline.BODY_SCAN_LIMIT` so
    the two bounds cannot drift. The rendered headline must be
    byte-identical with and without the cap — Postgres `left()` counts
    code points and Java counts UTF-16 units, so the SQL pre-bound always
    hands the helper at least as many chars as its own cut consumes —
    and a test pins that a body far past the limit still renders the same
    headline.
  - >-
    REDTEAM REMEDIATION (audit 2026-07-30, INFO-LEAK/medium — fixed
    in-ticket after the r2 disposition showed the audit's cost premise
    wrong; see §Notes item 3). A post re-hidden to `QUARANTINED` after
    being saved must not keep rendering through `/saved`: in group
    scope the reply is broadcast to every member, and for a
    titleless-by-design source the promoted body IS the post. Both
    listing SELECTs (rows and count, so the header total stays honest)
    carry a visibility predicate: a saved row appears iff NO `post`
    row carries its uid — the aged-out case the D13/D33 snapshot
    exists for — OR at least one `post` row with its uid has
    `status = 'READY'`. That is the same visibility rule `/summary`,
    search and `getPost` apply, so an admin approve or a BENIGN
    requeue makes the bookmark reappear with nothing destroyed, and a
    multi-window duplicate uid behaves identically across surfaces.
    The predicate probes existence and status only — no `post` content
    column crosses into the reply. Tests pin: a QUARANTINED post's
    save is hidden (listing and count), a READY post's save renders, a
    save whose post has aged out still renders its snapshot (the
    pre-existing tests seed exactly this shape), and a re-hidden post
    that returns to READY reappears.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  preserves:
    - >-
      M1-729's `DisplayHeadline` behaviour and every existing test in
      `DisplayHeadlineTest` — this ticket may ADD an entry point for
      callers holding a title/body pair rather than an
      `EligiblePostQuery.Post`, but must not change the derivation.
    - >-
      The `saved_post` snapshot contract (`schema.md` §Per-user state,
      D13/D33 — NOT Invariant 6, which is TTL by partitioning): `/saved`
      renders only the snapshot content columns and never re-resolves
      CONTENT against `post`. The r2 rework adds one narrow exception:
      a visibility predicate on `post.status` (an existence + status
      probe — no content column) decides whether the row is listed at
      all. Refined here from the clause's original "never re-resolves"
      framing, which was this ticket's own scope constraint, not a spec
      invariant — see §Notes item 3.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 1120
      removed: 50
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-30
    verdict: FINDINGS
    base: 4e5de9f2ff2abe2d9a23fa3cc277ac8b1d0aeae6
    head: working-tree (uncommitted branch m1/M1-730-saved-renders-untitled-sentinel)
    verdict_file: docs/plan/m1/redteam/M1-730-2026-07-30.md
    findings_count: 2
    out_of_model_count: 3
    note: |
      Round 1. Gate audit at /m1-tick run step 4, ahead of review. Both findings are
      widenings this diff introduces by promoting saved_post.body into the
      /saved render path — the sanitize unit grows 10x on a command the spec
      files under the cheap rate-limit bucket, and the frozen-snapshot read
      path carries no post.status predicate so a re-quarantined post's body
      keeps reaching readers. Three out-of-model items: a BODY_SCAN_LIMIT
      pre-cut that lacks the Stage-1-placeholder guard `truncate` has
      (pre-existing M1-729 code, fourth caller); a privileged command split
      across the headline and tag fields of one line (an accepted §"Flag
      position" residual, equally reachable pre-diff via the title); and the
      larger quantity of DM-world content one group `/saved` now reveals.
  - date: 2026-07-30
    verdict: FINDINGS
    base: 4e5de9f2ff2abe2d9a23fa3cc277ac8b1d0aeae6
    head: working-tree-r2 (post-remediation, branch m1/M1-730-saved-renders-untitled-sentinel)
    verdict_file: docs/plan/m1/redteam/M1-730-2026-07-30-r2.md
    findings_count: 2
    out_of_model_count: 4
    note: |
      Round 2, the mandatory re-audit of the remediated diff. The adversary
      verified round 1's JDBC/materialisation leg CLOSED against all four
      failure modes it was asked to attack — the SQL bound cannot change a
      headline (Postgres left() counts code points, Java counts UTF-16 units,
      so the Java cut is always the binding one), left() never splits a code
      point, the interpolated BODY_SCAN_LIMIT is a compile-time int with no
      inbound-derived input, and text_left is TOAST-slice aware so the backend
      partially detoasts. The two residuals are re-reported: the audit-INSERT
      leg (fix class rate-limit — the sanitize UNIT is M1-729's shipped
      contract, but the REACHABILITY from a user-pulled command in the cheap
      bucket is this diff's) and the quarantine interlock. The audit held
      both to be follow-up tickets, the interlock on a cost premise (no
      uid-only index on the partitioned post table → unbounded
      cross-partition lookups) that did not survive measurement — see §Notes
      item 3. The user's disposition (2026-07-31): the interlock is fixed IN
      this ticket (new acceptance item), the audit-INSERT leg is filed as
      M1-737, and a side-discovery from the disposition review (re-hidden
      posts never enter the admin review queue) is filed as M1-738. One
      new out-of-model item over round 1: security.md §"Flag position mirrors
      the parser's own scan" now under-describes the /saved sanitize unit —
      spec-text drift only, the one-field-per-call invariant still holds.
  - date: 2026-07-31
    verdict: CLEAN
    base: 4e5de9f2ff2abe2d9a23fa3cc277ac8b1d0aeae6
    head: working-tree-r3 (post-r2-rework, branch m1/M1-730-saved-renders-untitled-sentinel)
    verdict_file: docs/plan/m1/redteam/M1-730-2026-07-31.md
    out_of_model_count: 3
    note: |
      Round 3, the mandatory re-audit of the r2-rework diff (the visibility
      interlock). CLEAN — the interlock held against every failure mode the
      brief named: count/rows split, tag/window filter and pagination paths,
      precedence, NULL handling, second code paths, post content crossing,
      and multi-window "any READY window" divergence vs /summary,
      searchPosts, semanticSearch and getPost (all status='READY'-gated).
      Round 2's DOS residual stayed dispositioned as M1-737 (not re-reported;
      the interlock cannot aggravate it — hidden rows reduce sanitize work).
      Three out-of-model items: aged-out-QUARANTINED revival via the NOT
      EXISTS TTL branch (spec silent on whether a hostile verdict outlives
      retention); ListSavesTool's pre-existing title-only bypass (the
      chat-tool surface this ticket's out_of_scope excludes); the carried
      --page int overflow from round 2 (pre-existing).
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings:
    - >-
      Self-check: acceptance item 5 was unsatisfiable within the
      original four-path files_scope — resolved by the
      start-gate refine at 4e5de9f2 (no-headline bundle template;
      files_scope 4 → 7, files_budget unchanged at 8).
  blockers: []
escalation_reason:
---

# M1-730: `/saved` renders the ingest "untitled" sentinel to a reader

## Context

M1-729 (merged 2026-07-30) made the three digest/summary render surfaces
recognise `IngestTextNormalizer.UNTITLED_TITLE` as "no title" so
`DisplayHeadline`'s body fallback fires. It deliberately stopped there:
its acceptance named only `/summary`, the periodic digest and the
degraded digest.

`/saved` was left behind. `SavedCommandHandler.java:81` selects
`post_uid, title, url, snapshot_tags, personal_tags, saved_at` — no
`body` — and interpolates the title into the `REPLY_SAVED_LINE` bundle
template at line 275. For a post saved from Bluesky, Nostr, or a Reddit
item with no title, that line reads `untitled` while the post's real
text sits unread in `saved_post.body`, a column V15 declares and
`SaveCommandHandler.java:144` already writes.

This is the only *human-facing* surface still showing the sentinel. It
is what stands between the current state and deleting the sentinel
outright — see §Notes.

## Census

Every provider site that reads a stored `title` column, enumerated
mechanically:

    grep -rn 'p\.title\|p2\.title\|, title,\|"title"\|\.title()' \
      --include='*.java' infochat-provider/src/main/java

| Site | Disposition |
|---|---|
| `command/SavedCommandHandler.java:81,238,275` | **fix** — human-facing list; renders the sentinel with no body available |
| `chat/tool/ListSavesTool.java:89,130` | out-of-scope — JSON to the model; `""` and `"untitled"` are equally uninformative |
| `chat/tool/SearchPostsTool.java:147,183` | out-of-scope — same, and its entries are under a `MAX_RESULT_BYTES` budget |
| `chat/tool/SemanticSearchTool.java:150,231` | out-of-scope — same |
| `chat/tool/GetReferencesTool.java:91` | out-of-scope — same |
| `chat/tool/GetPostTool.java:62,82` | not affected — selects `p.body` alongside |
| `command/SaveCommandHandler.java:115,144,343` | not affected — selects and snapshots both title and body |
| `command/RetryCommandHandler.java:90,445` | not affected — selects `p.title, p.url, p.body` |
| `summary/EligiblePostQuery.java:220,291,302` | not affected — carries body; feeds `DisplayHeadline` (M1-729) |
| `digest/DigestPostCollector.java:112,139,157` | not affected — same |
| `summary/SummaryProseGenerator.java:181` | out-of-scope — LLM prompt input, body appended on the next line |
| `digest/CategoryRollupGenerator.java:199` | out-of-scope — same |
| `help/TopicCorpusBuilder.java:280` | not affected — a help topic's title, not a post's |

## Acceptance

See the YAML `acceptance:` list. In prose: `/saved` stops printing the
storage sentinel and shows saved body text instead, obtained from the
shared `DisplayHeadline` derivation rather than a second inline copy;
the existing per-field `sanitize` control on that line survives the
change with its one-field unit intact; and the no-title-no-body case
still renders a stable line.

## Out-of-scope

The four chat tools and the two prompt builders are listed above with
reasons. The distinction is deliberate and worth restating: those six
sites hand `title` to a *model*, and `DisplayHeadline`'s own javadoc
forbids using it for prompt input because a bounded headline makes the
model summarize a fragment. Only `/saved` renders a stored title
straight to a person.

Removing the sentinel from `PostPersister` is also excluded. It is the
natural next step once this ticket lands, but it is a decision with its
own complication (§Notes) rather than a mechanical follow-through, and
folding it in here would put a collector-side and a core-side file into
a provider-scoped ticket.

## Notes

- Adjacent code: `DisplayHeadline.of(Post, LlmOutputSanitizer)` takes an
  `EligiblePostQuery.Post`, which `/saved` does not have — its rows come
  from `saved_post`. An overload taking the title/body pair keeps one
  derivation without forcing a synthetic `Post`; that shape is a
  suggestion, not a commitment.

- **The open decision this unblocks.** Once no human-facing surface
  renders the sentinel, `PostPersister.normalizeTitle` could store `""`
  instead, and the column would record what the source actually
  reported — the rule M1-723 states for `likes`/`reposts` (NULL means
  "not reported", which is not 0). Two things need deciding first, and
  neither is settled here. (1) `post` retention is 30 days (14 on `pi`),
  so rows already carrying the sentinel outlive the change; the
  reader-side match in `DisplayHeadline` would have to stay until they
  age out, which reads like the backwards-compatibility shim CLAUDE.md
  forbids even though it is really live-data handling. (2)
  `saved_post` rows are snapshots and are NOT retention-bounded, so a
  sentinel snapshotted today is permanent — which is a second reason
  this ticket's fallback reads the body rather than trying to fix the
  stored value.

- M1-729's commit message (`3737adb4`) records the full route analysis
  and the consumer split this census refines.

- **What the 2026-07-30 redteam audit found, and what this ticket can
  and cannot answer.** Both findings survived falsification; the audit
  files are `docs/plan/m1/redteam/M1-730-2026-07-30.md` and `-r2.md`.
  Of the three legs, two are fixed inside this ticket (items 1 and 3);
  the third is filed as a follow-up. All are recorded here rather than
  silently absorbed:

  1. *Fixed here (new acceptance item above).* The unbounded body column
     crossing JDBC. `DisplayHeadline` applies `BODY_SCAN_LIMIT` only
     after the whole column is materialised, so the SQL read is where
     the bound belongs for a 20-row page.

  2. *Not fixable here — shared-derivation property.* The sanitizer's
     per-occurrence `audit_log` INSERT count scales with the 2000-char
     `BODY_SCAN_LIMIT` sanitize unit rather than the write-capped
     200-char title. That unit is M1-729's, and is already live on the
     three digest surfaces; narrowing it for `/saved` alone would make
     one caller audit fewer spans than the others for the same post, and
     narrowing it for all four changes M1-729's shipped contract. What
     M1-730 genuinely adds is that the cost is now reachable from a
     user-pulled command that `security.md` §Rate limiting files under
     "one bucket; high cap; cheap". Deciding whether that bucket still
     fits is a spec question, not a render-path edit. Filed as M1-737:
     aggregate the audit emission per distinct token per call — the
     `match_count` field `emitAuditRows` already writes as the literal
     `1` is the vehicle, and the spec's "per-occurrence" wording plus
     the §"Flag position" enumeration drift ride that ticket's diff.

  3. *Fixed here after all (r2 rework; user disposition 2026-07-31).*
     `saved_post` carries no `post.status`, so a post re-hidden to
     `QUARANTINED` after being saved kept rendering. For a
     titleless-by-design source the escape is diff-INTRODUCED, not
     merely widened: pre-diff that line printed the `untitled` sentinel
     and disclosed no post content at all; post-diff it prints up to
     200 chars of the body, which for a social source IS the post.
     `schema.md` §Per-user state justifies the snapshot by **retention
     TTL** only ("so retention TTL on the underlying post does not break
     the bookmark", D13/D33) and does not extend the carve-out to a
     moderation decision, so this was a real gap rather than a
     documented exemption.

     Both audit rounds held it unfixable in-ticket on cost: "`post` is
     `PARTITION BY RANGE (fetched_at)` with `UNIQUE (uid, fetched_at)`
     and no uid-only index, so a status interlock joining by `uid`
     needs an unbounded cross-partition lookup — 20 of them per
     `/saved` page." That premise does not survive measurement: the
     partitions are MONTHLY (V7__joins_post.sql:175) and post retention
     is ~30 days, so at most ~2 partitions are live at once, each
     carrying the local index its UNIQUE constraint implies — the
     EXISTS probes are bounded index seeks, not an unbounded scan, and
     no migration is needed. The fix is a visibility predicate in the
     two existing SELECTs: a row renders iff no `post` row carries its
     uid (the aged-out TTL case the snapshot exists for) or some row
     does with `status = 'READY'` — the same rule `/summary`, search
     and `getPost` apply, so moderation reversals (admin approve,
     BENIGN requeue) restore the bookmark for free and multi-window
     duplicate uids behave identically across surfaces. The
     `test_plan.preserves` clause was refined to match: the
     never-re-resolve rule was this ticket's own framing, never a spec
     invariant, and it now reads "content never re-resolves; visibility
     consults `post.status`".

     Tracing the re-hide path for this fix surfaced a pre-existing gap
     worse than the finding itself: a post released READY during a
     Stage 2 outage and later re-hidden has NO quarantine row, so it
     never enters `quarantine_review_view` / the admin review queue.
     Filed as M1-738.

- **Spec-text drift the audit surfaced (out-of-model, not a control
  gap).** `security.md` §"Flag position mirrors the parser's own scan"
  enumerates the per-caller sanitize units and names "the `/saved` reply
  one row's title and one row's tags". After this ticket the unit is one
  row's title OR up to `BODY_SCAN_LIMIT` chars of its body, plus one
  row's tags. The load-bearing invariant is unaffected — `DisplayHeadline`
  selects title XOR body, so it is still one author's field per sanitize
  call — only the enumeration is stale. A spec file is outside
  `files_scope`; recorded here for a follow-up.
