---
id: M1-727
title: "Personal and humorous posts from social accounts render under topic headers: add a `personal` label and route those clusters to Other"
status: done
created: 2026-07-30
last_updated: 2026-08-01
blocked_by: []
files_budget: 14
files_scope:
  - infochat-core/src/main/resources/db/migration/V73__classification_personal_label.sql
  - infochat-llm-adapter/src/main/resources/prompts/classifier.md
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategorizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterProminence.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
  - docs/design/05-llm-and-embeddings.md
  - docs/design/03-commands.md
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `/summary`, `/retry` and `ClusterBlockRenderer`. Personal posts stay
    fully visible there and keep rendering their `classification:` line
    — now able to read `personal`. The digest is a push that must earn
    every line; `/summary` is a pull where the reader asked and gets
    what is there. A diff that filters `/summary` has left scope.
  - >-
    M1-724's prominence score. `personal` routes a cluster to a
    different SECTION; it is not a fifth weighted term and must not
    become one — the weights, percentiles and score arithmetic are
    untouched. The one touch point on M1-724's surface is the bottom
    gate in `ClusterProminence.totalOrder()` (acceptance item 6), which
    reads no score component.
  - >-
    Excluding personal clusters from the digest entirely. Rejected in
    favour of Other — see §Why Other and not exclusion. A diff that
    drops them from collection has left scope.
  - >-
    Re-classifying existing posts. The CHECK constraint only WIDENS, so
    no existing row violates it and no backfill is needed or run;
    already-classified posts keep their labels and age out with their
    partition (D33). V57 set this precedent explicitly for its own
    rollout.
  - >-
    `post.tags` and the tagger. A personal post still gets whatever tags
    the tagger assigns — that is M1-726's territory, and the two
    tickets address different populations: M1-726 handles posts where no
    vocabulary tag fits, this one handles posts that legitimately tag
    on-topic but are personal in kind.
  - >-
    The `unknown` mutual-exclusion rule and the 1–3 substantive cap
    (`ClassifierWorker.java:135-155`). `personal` joins the substantive
    set and obeys both unchanged; it is not a fourth kind of value.
  - >-
    The degraded (D17) digest and the zero-posts reply. Neither has
    category structure to route into.
  - any other module
acceptance:
  - >-
    `personal` joins the closed classification set in all three places
    that enforce it: the V57 CHECK constraint (widened by a new
    migration — take the next unused version number at implementation
    time; V67 assumes M1-722's V66 has landed), the prompt's fixed label
    list in `prompts/classifier.md`, and
    `ClassifierWorker.java:142`'s substantive-label `Set.of`. A test
    asserts a reply of `{"classification":["personal"]}` survives the
    membership filter instead of being dropped as out-of-enum.
  - >-
    `prompts/classifier.md` defines the label so it means KIND, not
    topic: a post about the author's own life, a joke, a greeting or a
    social pleasantry — as distinct from `opinion`, which is a view
    ABOUT the subject matter. The rule text names the failure case it
    exists to catch (a birthday photo or a pet picture from an
    otherwise on-topic account) and stays inside the existing
    delimiter-wrapped, treat-as-data prompt shape (D21).
  - >-
    `DigestPostCollector` already PROJECTS `p.classification` in both
    `POSTS_ALL_SQL` and `POSTS_EXPLICIT_SQL`, and `mapPost` reads it —
    M1-724 (done) added the projection for its prominence urgent gate.
    What this ticket adds is the missing pin: a test asserts a
    collected post carries its real classification rather than the
    `unknown` sentinel, in both ALL and EXPLICIT mode.
  - >-
    A cluster is `personal` only when EVERY member post carries the
    label. A mixed cluster — one personal post that clustered with real
    coverage through the `post_reference` graph — is NOT personal and
    stays in its topic section. A test pins both, because the
    all-versus-any choice is the one that decides whether real news can
    be hidden by one stray member.
  - >-
    A personal cluster is assigned to the Other bucket regardless of its
    tags, and is EXCLUDED from the qualifying-tag counting pass — so a
    run of cat pictures tagged `security` can neither create a category
    nor keep an existing one alive past the `category-min-clusters`
    threshold. A test pins that 3 personal clusters sharing a tag do not
    promote that tag to a category, and that a real category with
    exactly `category-min-clusters` real clusters survives when personal
    clusters carrying the same tag are added and removed.
  - >-
    Within the Other section, personal clusters sort AFTER
    non-personal ones. Other is a real section competing for slots under
    the M1-721 budget, so without this a run of personal posts would
    evict genuinely-uncategorizable news from the bucket that exists to
    catch it. Relative order within each of the two groups is unchanged.
    The ordering is a BOTTOM GATE in `ClusterProminence.totalOrder()` —
    personal compares after non-personal ahead of the urgent gate and
    score comparison — symmetric to M1-724's `urgent` top gate and NOT
    a weighted term; M1-724 landed first without it, so this ticket adds
    it. `DigestCategorizer` emits its Other bucket in the same order so
    the non-scored render paths agree. A test pins a budget-constrained
    Other rendering the real clusters and dropping the personal ones,
    and `ClusterProminenceTest` pins the bottom gate.
  - >-
    Section order, the `categoryMinClusters` threshold, the
    fold-into-Other second pass and Other-always-last are otherwise
    untouched (D62). A test asserts a digest containing no personal
    clusters is byte-identical to today.
  - >-
    `docs/spec/commands.md` §Periodic group digests and the D62 row in
    `docs/spec/decisions.md` state that Other additionally receives
    personal-classified clusters and that those sort last within it —
    D62 currently defines Other purely as "a cluster with no qualifying
    tag", which this widens. `docs/design/05-llm-and-embeddings.md`
    §5.4.4 and `docs/design/03-commands.md` record the sixth label.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerTest.java
      — `personal` survives the membership filter; it combines with
      other substantive labels (`["personal","opinion"]`); it obeys the
      1–3 cap; it is still mutually exclusive with `unknown`.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
      — an all-personal cluster routes to Other despite carrying a
      qualifying tag; a mixed cluster stays in its topic section; 3
      personal clusters sharing a tag do not promote it to a category; a
      real category at exactly the threshold is unaffected by personal
      clusters carrying its tag; personal clusters sort last within
      Other; a digest with no personal clusters is byte-identical to the
      pre-change output.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
      — a collected post carries its real classification in both ALL and
      EXPLICIT mode, not the `unknown` sentinel.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
      — the personal bottom gate: a personal cluster sorts after every
      non-personal cluster regardless of urgency or score, and two
      non-personal clusters keep their prior relative order.
  preserves:
    - >-
      Every existing `DigestCategorizerTest` assertion — assignment,
      the qualifying threshold, the fold-into-Other second pass, section
      order and Other-last all hold unchanged for inputs containing no
      personal clusters.
    - >-
      Every existing `ClassifierWorkerTest` and `ClassifierWorkerIT`
      assertion, including the `unknown`-mutual-exclusion rule, the
      out-of-enum drop, the 1–3 substantive cap, and the
      retry-then-`{unknown}` graceful-release path.
    - >-
      `ClusterBlockRendererTest`'s `classification:` line assertions —
      the render side is untouched and simply gains a sixth possible
      value.
    - >-
      `DigestPostCollectorIT` window and D59 world-predicate semantics.
      This ticket adds a projected column, not a predicate; a test
      asserts the row SET returned is unchanged.
    - >-
      `DigestWorkerTest` / `DigestRoundtripIT` per-category delivery
      (D63) message counts for personal-free fixtures.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/design/05-llm-and-embeddings.md §5.4.4
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D62
  - D19
  - D21
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 588
      removed: 72
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-01
    verdict: CLEAN
    base: b8db467923432e4ad49a3eb30441cfd316aba708
    head: working tree (in-progress branch m1/M1-727-personal-classification-routes-to-other)
    verdict_file: docs/plan/m1/redteam/M1-727-2026-08-01.md
    out_of_model_count: 1
    note: |
      Audit at the /m1-tick run gate (security_relevant: true), ahead of
      review. No findings: the all-versus-any rule bounds content
      suppression to self-demotion, the closed set is widened not weakened,
      and no auth/authz/audit/LLM-tool surface is touched. The one
      out-of-model observation (V73 re-validates existing rows under lock
      at boot without NOT VALID) was evaluated and falsified as a
      practical concern: AccessExclusiveLock confirmed, but post is bounded
      by the 30-day partition-drop TTL, the real corpus is ~10^4 rows, and
      a measured DROP+ADD of V73's shape over 1M rows took 1.28 s (real
      corpus: milliseconds) at Flyway boot, before traffic. Accepted
      residual; no remediation opened.
clarity_check:
  date: 2026-08-01
  verdict: WARN
  warnings:
    - >-
      Self-check: stale premise fixed inline — M1-724 (done) already
      projects p.classification in both digest SELECTs and mapPost reads
      it; acceptance item 3 and §Notes rewritten to match (remaining work
      there is the test pin only).
    - >-
      Self-check: files_scope migration path corrected V67 -> V73; V67
      already taken by V67__group_digest_mode.sql, acceptance item 1
      already delegates the version number to implementation time.
escalation_reason:
---

# M1-727: personal posts render under topic headers

## Context

Social sources mix registers. A security researcher's Bluesky or nitter
feed carries CVE analysis, a joke about a haircut, a pet photo and a
friend's birthday — all from one account, all fetched by one source, all
tagged from that source's vocabulary.

M1-726 handles the subset where the tagger finds no vocabulary tag: those
posts get `tags = '{}'` and land in the D62 Other bucket, which is the
right place for them. It does not help the rest. A joke that genuinely
mentions a buffer overflow tags `security` correctly, so it renders under
the SECURITY header between two CVEs. The classification the pipeline
already computes cannot express what is wrong with it: the closed set is
`{factual, opinion, technical, urgent, ongoing, unknown}`, and a birthday
photo is not any of them — `opinion` is a view *about the subject
matter*, not a post about the author's weekend.

So the label set has a gap exactly where the digest needs a signal.

## Why Other and not exclusion

An earlier framing of this ticket excluded personal clusters from the
digest and kept them in `/summary`, with a count line at the end of the
digest to say how many were held back.

Other is better, and the reason is that Other already means this.
Following the retrieval branches (M1-726 §Where an untagged post actually
goes), untagged off-topic posts ALREADY land in Other under the default
`tag_mode='ALL'`. Routing personal-classified clusters to the same place
gives one rule instead of two, needs no exclusion mechanism, needs no new
bundle key, and leaves `/summary` untouched. Other *is* the count line,
and unlike a count line it is inspectable — a reader can see what was set
aside instead of being told a number.

It also fails safe. A mislabelled post is demoted to a section the reader
can still read, not silently removed from a push they cannot audit.

## The all-versus-any choice

A cluster is personal only when **every** member post carries the label.

Clusters are connected components of the `post_reference` graph, so a
personal post that clustered with real coverage was linked to it by
shared entities or embedding similarity — evidence it is part of the
story, not noise beside it. Taking `any` would let one stray member
route a genuine multi-source cluster into Other, which is the failure
mode worth avoiding: hiding real news is a worse error than leaving one
joke under a topic header.

## Two counting details that are easy to miss

**Personal clusters must not vote on categories.** The qualifying-tag
pass counts how many clusters carry each tag. If personal clusters are
counted and then routed away, three cat pictures tagged `security` could
promote `security` to a category that no real cluster joins, or hold a
dying category open past the `category-min-clusters` threshold. They are
excluded from the count, not merely from the assignment.

**Other is a section, and sections compete for budget.** Under M1-721 the
digest has a total cluster budget allocated across sections. Other is one
of them, so a run of personal posts arriving in a quiet window would
evict the genuinely-uncategorizable news that Other exists to carry.
Sorting personal clusters last within Other means the budget cuts cat
pictures before it cuts news. When M1-724 lands it re-expresses this as a
bottom gate on the ordering, symmetric to its `urgent` top gate, with the
same observable behaviour.

## Notes

`DigestPostCollector` already projects `p.classification` in both
digest SELECTs and `mapPost` reads it — M1-724 added the projection for
its prominence urgent gate. The `unknown` sentinel is now only the
fallback for a hand-stubbed `ResultSet`. What remains for this ticket on
that front is the test pin: a collected post carries its real
classification in both ALL and EXPLICIT mode.

Widening a CHECK constraint cannot invalidate an existing row, so the
migration needs no backfill and no re-classification job — the same
argument V57 made for its own rollout.
