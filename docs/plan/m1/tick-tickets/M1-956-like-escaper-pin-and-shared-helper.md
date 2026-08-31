---
id: M1-956
title: "Pin the /topic LIKE escaper; share one escapeLike helper"
status: done
created: 2026-08-30
last_updated: 2026-08-31
flow: tick
reproduction: >-
  Mutation probe (executed 2026-08-30 on clean main f9a76203; full log
  .scratch/falsify-topic-mutation-20260830.log): mutate
  EligiblePostQuery.java:497 from `params.add(escapeLike(topicPrefix));`
  to `params.add(topicPrefix);` — deleting the LIKE-metacharacter
  escaping from the query /topic binds its drill-down argument into —
  then run `./mvnw -B -pl infochat-provider verify
  -Dtest=TopicCommandHandlerTest -Dit.test=TopicCommandHandlerIT
  -Dfailsafe.failIfNoSpecifiedTests=false`. OBSERVED (wrong): BUILD
  SUCCESS — TopicCommandHandlerTest 9/9 and TopicCommandHandlerIT 2/2
  GREEN with the security-relevant escaping deleted; the identical
  mutation on the searchPosts twin IS caught
  (SearchPostsToolTest.likeMetacharacterValuesNeverWiden, M1-935), so
  the protection is asymmetric across two byte-identical private
  implementations of one rule. The wrong behavior is the suite's
  silence, not a failing test, so the closing test is
  EligiblePostQueryIT.topicPrefixMetacharacterValuesNeverWiden
  (to-be-written; the class exists in-tree and the method is added by
  this ticket): at /tick start, write the test, RE-APPLY the probe
  mutation, run the test RED
  (workflow §0's conversion for a mutation-probe reproduction; the
  M1-896/M1-897 mutation-log precedent), revert the mutation, and
  proceed to the fix with the test green.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/util/LikeEscaper.java
  - infochat-core/src/test/java/app/zcat/infochat/core/util/LikeEscaperTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY change to /topic tag ADMISSION — TopicArgs.java and both
    TagNormalizer classes stay byte-identical. TopicArgs.parse gates the
    positional tag through provider.command.TagNormalizer.isValid
    (`^[a-z0-9][a-z0-9-]{0,47}$`, TopicArgs.java else-branch), which is
    what keeps %, _ and \ off the command leg TODAY; commands.md §Content
    calls the /topic `<tag>` a free tag with "no bounded-vocabulary
    admission" while TopicArgs applies the tag-rule class — that tension
    is a user decision for its own ticket, and this ticket's pin is
    deliberately sited at the query tier so it stays valid whichever way
    that decision goes (loosening the class, as M1-935 r1 did for the
    twin surface, is exactly the change that would re-arm live
    metacharacter exposure on this leg).
  - >-
    UNIFYING the two SQL shapes — SearchPostsTool's explicit
    `LIKE ? ESCAPE '\'` with the Java-appended wildcard vs
    EligiblePostQuery's `LIKE ? || '%'` with the SQL-appended wildcard
    and implicit default escape: behaviorally equivalent in stock
    Postgres (the default LIKE escape character is the backslash and is
    not server-configurable), so unification is stylistic churn
    (engineering-rules §1) and rejected.
  - >-
    THE TAGNORMALIZER DUPLICATION — provider.command.TagNormalizer
    (M1-489, trims) vs core.util.TagNormalizer (M1-934, no trim) is the
    named drift precedent motivating this ticket, but consolidating THAT
    pair changes controlled-vocabulary admission behavior across
    /summary, /follow-tag and /unfollow-tag and is NOT folded in here
    (engineering-rules §1, §3).
  - >-
    Any new search_tags reader, GIN index (V87 chartered prefix-LIKE
    with no GIN by design), ranking change, digest-footer or
    TopicRanking work, and any change to SearchPostsToolTest or
    TopicCommandHandler suites — the existing tests are the controls
    this reroute must preserve, not edit.
acceptance:
  - "REPRODUCTION closed: EligiblePostQueryIT.topicPrefixMetacharacterValuesNeverWiden — on a fixture seeding two in-world, in-window READY posts carrying canonical search_tags ['qwen3'] and ['axb'] (the SearchPostsToolTest.likeMetacharacterValuesNeverWiden seeds, mirrored), query.fetchByTopicPrefix('dm', userId, 'qw%n', <window>) and ('a_b') each return an EMPTY Result (posts empty AND totalBeforeCap 0), while the companion arm ('qwen') on the SAME fixture returns exactly the qwen3 post — the pair discriminates: the companion catches a broken/over-narrow fixture, the empty arms catch an escaping-skipping mutation (the re-applied probe mutation must run this test RED at start, workflow §0)."
  - "FAILURE-MODE at the helper tier: new infochat-core LikeEscaperTest (plain JUnit, no container — engineering-rules §8 stack-specific rule) pins the shared contract — escapeLike(\"a%b_c\\\\d\") equals \"a\\\\%b\\\\\\\\_c\\\\\\\\\\\\\\\\d\" (i.e. every %, _ and \\ gains a preceding backslash), each metacharacter branch singly, and metacharacter-free text passes through unchanged; deleting or weakening ANY branch of the helper fails it. The backslash branch is unpinned today on BOTH legs (SearchPostsToolTest feeds only qw%n / a_b) even though it is reachable on the searchPosts leg (TOPIC_VALUE_PATTERN `^[\\x21-\\x7E]{1,48}$` admits backslash since the M1-935 r1 fix)."
  - "Consolidation: one public static helper app.zcat.infochat.core.util.LikeEscaper.escapeLike(String) in the exact JsonEscaper/TagNormalizer/Sha256 family shape (public final class, private constructor, public static method, bare String parameter = non-null per engineering-rules §7a package default); both call sites switched (SearchPostsTool.java:235, EligiblePostQuery.java:497) and BOTH private copies deleted — probe: `grep -rn 'String escapeLike' --include='*.java' infochat-core/src/main infochat-collector/src/main infochat-provider/src/main infochat-llm-adapter/src/main infochat-messaging-adapter/src/main infochat-ssrf/src/main` returns exactly the core class, nothing else."
  - "SQL shapes unchanged (spec_refs: the security.md §Prompt-injection defenses searchPosts mechanism sentence — code-escaped metacharacters, code-appended wildcard, raw values never interpolated): SearchPostsTool keeps `LIKE ? ESCAPE '\\'` with the Java-appended wildcard (`escapeLike(prefix) + \"%\"`), EligiblePostQuery keeps `LIKE ? || '%'` with the SQL-appended wildcard — probe: `git diff` over the two production files shows no changed line matching `grep -n 'LIKE\\|ESCAPE'` (every LIKE/ESCAPE-bearing line byte-identical to main)."
  - "Existing tests UNCHANGED (engineering-rules §10 — the searchPosts pin is a control this reroute must not drop, and §8 test-modification authorization is deliberately NOT invoked): git diff names no pre-existing test method; SearchPostsToolTest (including likeMetacharacterValuesNeverWiden and punctuationCarryingTopicValuesFilterRatherThanDegrade), TopicCommandHandlerTest (9/9), TopicCommandHandlerIT (2/2) all green and unmodified."
  - "The /topic admission gate is untouched: `git diff --name-only` names neither TopicArgs.java nor any TagNormalizer — the parse gate stays the first widening control on the command leg, the escaper the defense-in-depth control at the public fetchByTopicPrefix boundary (grep probe)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - >-
      infochat-core/src/test/java/app/zcat/infochat/core/util/LikeEscaperTest.java
      — the helper-contract tests (all three metacharacter branches,
      single-branch cases, passthrough).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
      — topicPrefixMetacharacterValuesNeverWiden (new @Test method in the
      EXISTING class: DB-tier, @QuarkusTest with the pinned
      MvpProfile/fixed Clock, *IT naming already conformant with the
      IntegrationTestNamingGuardTest ratchet) plus one private additive
      seeding helper that writes post.search_tags (the existing
      insertPost helpers do not seed the column; V87 default '{}').
  modifies: []
  preserves:
    - >-
      all tests currently green on main — explicitly SearchPostsToolTest,
      TopicCommandHandlerTest, TopicCommandHandlerIT,
      EligiblePostQueryTopExpansionIT, EligiblePostQueryClockIT,
      EligiblePostQueryStatementTimeoutIT, DigestTopicsFooterIT and the
      rest of the EligiblePostQueryIT methods, unmodified.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Content
decision_refs: []
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-31
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS — 4 falsification candidates dropped with citations (acceptance-2 example literal defeated by the same item's normative text + byte-identical consolidation premise + security.md:328 mechanism sentence; DB-tier backslash arm defeated by P5's own mapping + LikeEscaperTest.backslashGainsAPrecedingBackslash; post-verify-log javadoc mtimes defeated by comment-only diff + both modules test-compile green + flow's comment-edit rule; acceptance-4 LIKE-grep probe defeated by the census row disposing the javadoc mentions + all SQL literals byte-identical). Verdict: .scratch/tick-review-M1-956-r1.txt"
    diff_stats: "7 files, +139/-39 (LikeEscaper.java +22 new, LikeEscaperTest.java +37 new, SearchPostsTool.java -16 net, EligiblePostQuery.java -15 net, EligiblePostQueryIT.java +56 additive, ticket frontmatter bookkeeping, board regen)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-31
  result: >-
    Self-check clean, no blocking question. Lint 0 findings. All file:line
    citations spot-checked true: SearchPostsTool.java:228 LIKE ? ESCAPE '\',
    :235 bind, :341-351 private escapeLike; EligiblePostQuery.java:496
    LIKE ? || '%', :497 bind, :399-409 private escapeLike; sole production
    caller TopicCommandHandler.java:206; TopicArgs else-branch gate
    (normalize+isValid -> BUNDLE_TAG_MALFORMED) confirmed; provider pom
    depends on infochat-core; core.util family shape (JsonEscaper) confirmed;
    EligiblePostQueryIT insertPost helpers seed tags but NOT search_tags
    (new additive helper needed, as planned); SearchPostsToolTest mirror
    seeds qwen3/axb confirmed. Census re-ran clean: exactly two LIKE sites
    in all src/main, SIMILAR TO/~~ grep empty. analysis_ref: self — no
    cross-read. blocked_by empty, replaces empty — nothing to trace.
escalation_reason:
---

# M1-956: Pin the /topic LIKE escaper; share one escapeLike helper

## Context

One security-relevant escaping rule — "prefix every `%`, `_` and `\` with
a backslash before binding a value into a prefix `LIKE`" — lives in TWO
byte-identical private implementations: `SearchPostsTool.escapeLike`
(SearchPostsTool.java:341-351, M1-935) and `EligiblePostQuery.escapeLike`
(EligiblePostQuery.java:399-409, M1-936). The M1-935 r1 review's
RECOMMENDED-NEW-TICKET (.scratch/tick-review-M1-935-r1.txt:152-163)
flagged the duplication: a third prefix reader would copy it again, and
the copies can drift (one gains a metacharacter fix the other misses).
The drift precedent already exists one namespace over:
`provider.command.TagNormalizer` (M1-489, trims) vs
`core.util.TagNormalizer` (M1-934, no trim) — one rule, two classes,
already semantically divergent.

The M1-935 copy is pinned by a mutation-catching failure-mode test
(`SearchPostsToolTest.likeMetacharacterValuesNeverWiden`,
SearchPostsToolTest.java:989-1012). The M1-936 copy is pinned by NOTHING:
the §0 mutation probe (reproduction above) deleted the escaping call at
EligiblePostQuery.java:497 and the entire /topic surface stayed green.
This ticket closes both halves: pins the /topic leg at the tier where the
mutation actually bites, and consolidates the two private copies into one
public helper beside the existing `core.util` util family.

**Premise census (coverage-ticket discipline, M1-896's lesson).** The
spec-required properties of the /topic drill-down retrieval were
enumerated against the code, end to end:

| Property (spec citation) | Code | State |
|---|---|---|
| Prefix-tolerant match over stored canonical search_tags (commands.md §Content /topic) | EligiblePostQuery.java:492-498, `LIKE ? \|\| '%'` over `unnest(p.search_tags)` | present |
| Bounded by caller's world + window (commands.md §Content /topic) | :461-468, READY + `ready_at >= ?` + D59 world predicate | present |
| Deterministic SQL-decided set and order (architecture.md §Architectural principles 1) | :518, `COALESCE(published_at, p.fetched_at) DESC, p.id DESC` | present |
| Stored canonical tags can never forge a command token (commands.md §Content /topic) | write side `core.util.TagNormalizer` class + V87; read side the TopicArgs gate | present |
| LIKE widening control mirrored from the searchPosts discipline (security.md §Prompt-injection defenses: "a parameter-bound LIKE whose metacharacters (`%`, `_`, the escape character) are code-escaped and whose trailing wildcard is code-appended, so raw values are never interpolated and a literal metacharacter can never widen the match") | escaping at :497, wildcard SQL-appended at :496 | present in code, UNPINNED by any test |

No spec-required property is missing — this is a coverage-plus-refactor
ticket, not a defect fix. But the census corrected the brief's premise in
one load-bearing way (see Root cause): metacharacter-bearing input cannot
reach the escaper THROUGH THE COMMAND today, so the pin must sit at the
query tier, not the command tier.

## Root cause

Verified, three facts:

1. **The /topic copy is unpinned.** Grep over the whole test tree finds
   no `%`/`_`-bearing /topic request fixture (greps over
   TopicCommandHandlerTest.java and TopicCommandHandlerIT.java for
   `qw%n`, `a_b`, `[%_]`-bearing topic arguments return nothing); the
   unit suite stubs the query entirely (TopicCommandHandlerTest.java:302
   overrides `fetchByTopicPrefix` on a fake), and the IT drives only
   clean tags. The mutation probe proves the gap is real, not apparent:
   with the escaping deleted, BUILD SUCCESS.

2. **The pin cannot be sited at the command surface — the brief's
   candidate shape is wrong here.** The drill-down argument is
   parse-gated before the query:
   `TopicArgs.parse` → `TagNormalizer.normalize` + `TagNormalizer.isValid`
   (`^[a-z0-9][a-z0-9-]{0,47}$`) — a token carrying `%`, `_` or `\`
   returns `Failure(BUNDLE_TAG_MALFORMED)` and `handle()` replies without
   any SQL (TopicArgs.java else-branch; TopicCommandHandler.java:132-134,
   :206 is the sole production caller of `fetchByTopicPrefix` — verified
   by grep). So a test "request the metacharacter-bearing topic; assert
   the honest narrow/empty result" THROUGH THE COMMAND would assert the
   parse gate, stay green under the §0 mutation, and be vacuous for the
   escaping (engineering-rules §8 non-vacuity; the M1-785 discriminator
   lesson). The escaper's exposure today is defense-in-depth at the
   PUBLIC `fetchByTopicPrefix` boundary: any new caller — or a future
   loosening of the /topic admission class (exactly what M1-935 r1
   FINDING 1 did to the twin surface, on spec-text grounds) — re-arms
   live metacharacter exposure on a leg with zero pin. The brief's
   "input is user-typed chat text" is true of the input's ORIGIN but
   stops at the parse gate; recorded here as the premise correction.
   Minor brief discrepancy also corrected: EligiblePostQuery.java
   imports NOTHING from `core.util` today (its imports are
   `messaging.ScopeRef` + `provider.chat.CancellationService`); the
   JsonEscaper import the brief cited lives in the OTHER call-site file,
   SearchPostsTool.java:4. The switch therefore adds EligiblePostQuery's
   first `core.util` import — no pom change needed (provider already
   depends on infochat-core, infochat-provider/pom.xml:19-20).

3. **The duplication is the drift risk.** The two bodies are
   byte-identical (`if (c == '%' || c == '_' || c == '\\')` …), but the
   call sites differ in SQL shape — behaviorally equivalently in stock
   Postgres: SearchPostsTool binds `escapeLike(prefix) + "%"` into
   `LIKE ? ESCAPE '\'` (SearchPostsTool.java:227-228, :235);
   EligiblePostQuery binds `escapeLike(topicPrefix)` into
   `LIKE ? || '%'` relying on the default escape character
   (EligiblePostQuery.java:495-497). Repo-wide census closes the class
   at exactly these two parameter-bound LIKE sites (see Census).

## Pitfalls

- P1 (vacuous-pin trap): a command-surface metacharacter test cannot
  exist — TopicArgs' `isValid` gate rejects `%`/`_`/`\` with
  TAG_MALFORMED before `fetchByTopicPrefix` runs; the pin must call the
  query method directly (EligiblePostQueryIT). Why it bites: the wrong
  siting pins the parse gate instead of the escaper and stays green
  under the §0 mutation (§8 non-vacuity).
- P2 (drift is the defect class): two byte-identical private copies —
  one can gain a fix the other misses; the same neighborhood already
  realized the drift once (provider.command.TagNormalizer trims,
  core.util.TagNormalizer does not). Single shared helper in
  `core.util` beside JsonEscaper/TagNormalizer/Sha256 (the established
  public-static-util family; provider pom already depends on core).
- P3 (SQL-shape churn): unifying the explicit `ESCAPE '\'` form with the
  implicit default-escape form, or the Java- vs SQL-appended wildcard,
  is behaviorally equivalent in stock Postgres and therefore stylistic
  churn (§1) — the helper must do ONLY the escaping; each call site
  keeps its own wildcard placement and ESCAPE form.
- P4 (relocated controls, §10): switching the call sites reroutes the
  escaping path. Enumerated controls to carry across: the escaping call
  itself (unit: one bound LIKE parameter per value — unchanged at both
  sites), the searchPosts pin
  (`SearchPostsToolTest.likeMetacharacterValuesNeverWiden`) green and
  UNMODIFIED — the brief and this ticket explicitly authorize NO
  pre-existing-test edits, so §8 modification authorization is never
  invoked — plus the per-file incidental controls (one pooled
  connection + statement_timeout in both classes, dispatcher caps) which
  the diff does not touch.
- P5 (backslash branch unpinned on both legs):
  `likeMetacharacterValuesNeverWiden` feeds only `qw%n`/`a_b`; the `\`
  branch is live on the searchPosts leg (TOPIC_VALUE_PATTERN
  `^[\x21-\x7E]{1,48}$` admits backslash) and pinned nowhere; the shared
  helper gets its own contract test covering all three branches.
- P6 (naming/tier guard): a NEW DB-backed test class must be named `*IT`
  (failsafe phase) — a new integration-shaped `*Test` fails
  `IntegrationTestNamingGuardTest`'s baseline ratchet. Siting the new
  method in the EXISTING `EligiblePostQueryIT` (already `*IT`,
  `@QuarkusTest` + `@SeedDataSource`, pinned `MvpProfile` clock/cap)
  avoids the guard entirely and follows the class's own charter
  ("DB-tier IT for EligiblePostQuery").
- P7 (fixture END-state + discriminating pair): the metacharacter-empty
  assertion needs the plain-prefix companion arm on the SAME fixture
  (`qwen` → the qwen3 post) so a broken or over-narrow query cannot fake
  a pass (§8 assertion adequacy; M1-896's census lesson: evidence must
  cover the same property set it claims). The fixture seeds search_tags
  directly on the posts (post-V87 END state — no later sibling in this
  ticket changes it; there is no sibling).
- P8 (comment discipline, §11): the shared helper's javadoc states the
  contract with ONE stable anchor (docs/spec/security.md
  §Prompt-injection defenses) — no ticket chronicle, no provenance tag
  (git blame carries it via the `M1-956:` commit prefix); the deleted
  private methods take their comments with them (cleanup of what the
  change made unused), and no adjacent comment is "improved" (§1).

## Approach

- **Files to touch:** new
  `infochat-core/src/main/java/app/zcat/infochat/core/util/LikeEscaper.java`
  + `LikeEscaperTest.java`; `SearchPostsTool.java` (switch :235, delete
  :341-351, add nothing else); `EligiblePostQuery.java` (switch :497,
  delete :399-409, add the `core.util` import);
  `EligiblePostQueryIT.java` (new test method + one private additive
  search_tags seeding helper). Five files, no spec/design edit — the
  behavior promised by security.md:328 and commands.md §Content does not
  change, so no amendment rides the diff (engineering-rules §12: spec
  states promises, not code layout).
- **Steps in order (reason):**
  1. RED ceremony first (workflow §0): add
     `EligiblePostQueryIT.topicPrefixMetacharacterValuesNeverWiden` +
     seeding helper; RE-APPLY the §0 mutation (one line,
     EligiblePostQuery.java:497); run the test — it must be RED (the
     unescaped `qw%n` widens onto the seeded `qwen3` post); revert the
     mutation; test GREEN on unmodified main. The pin exists BEFORE the
     refactor, so the consolidation is guarded from its first commit.
  2. New `core.util.LikeEscaper` (family shape per JsonEscaper) with
     contract javadoc anchored at security.md §Prompt-injection
     defenses; new `LikeEscaperTest` (plain JUnit) pinning all three
     branches + passthrough. Core compiles before provider switches.
  3. Switch SearchPostsTool (:235 → `LikeEscaper.escapeLike(prefix) +
     "%"`), delete its private escapeLike (:341-351). Existing
     SearchPostsToolTest stays green unmodified — byte-identical
     behavior.
  4. Switch EligiblePostQuery (:497 → `params.add(LikeEscaper.escapeLike(topicPrefix))`),
     delete its private escapeLike (:399-409), add the import.
  5. Full `mvn verify` from repo root.
- **Controls to preserve (engineering-rules §10):** both escaping calls
  (unit: one bound LIKE parameter per value, at both sites); the
  searchPosts pin + punctuation-degradation pin green UNMODIFIED; the
  one-pooled-connection + statement_timeout discipline in both files;
  the dispatcher's input caps; TopicArgs' admission gate byte-identical;
  no SQL string literal changes in either production file.
- **Pitfall→mitigation:** P1→query-tier siting (step 1); P2→single
  helper (steps 2-4) + the acceptance-3 grep probe; P3→wildcard/ESCAPE
  ownership stays per call site (steps 3-4) + acceptance 4; P4→controls
  list above + acceptance 5's git-diff fence; P5→LikeEscaperTest
  (step 2); P6→existing *IT class (step 1); P7→companion arm in the
  test; P8→reviewer diff check over comments.

## Definition of done

The mutation probe now bites: re-applying the one-line §0 mutation turns
`EligiblePostQueryIT.topicPrefixMetacharacterValuesNeverWiden` RED (empty
arms return the qwen3/axb posts), and unmutated it is green with the
companion `qwen` arm returning the qwen3 post. `LikeEscaperTest` pins the
shared contract on all three metacharacters plus passthrough. Exactly one
`escapeLike` implementation exists repo-wide (the grep probe returns only
`core/util/LikeEscaper.java`); both call sites use it; both private
copies are gone; no SQL string literal changed; no pre-existing test
method touched (SearchPostsToolTest, TopicCommandHandlerTest 9/9,
TopicCommandHandlerIT 2/2 green unmodified); TopicArgs and both
TagNormalizers byte-identical; `mvn verify` from repo root green.

## Verification

- Reproduction / P1 → `EligiblePostQueryIT.topicPrefixMetacharacterValuesNeverWiden`
  (acceptance 1): feeds the real `fetchByTopicPrefix` the hostile
  prefixes `qw%n` and `a_b` against seeded canonical `qwen3`/`axb`
  posts; asserts empty Result (posts + totalBeforeCap) while the
  companion `qwen` arm returns the qwen3 post; the start-time RED
  ceremony re-applies the probe mutation and must observe RED.
- P2 → acceptance 3's grep probe (exactly one `String escapeLike`
  declaration repo-wide, in core) + acceptance 7.
- P3 → acceptance 4 (reviewer diff check: no SQL string literal in
  SearchPostsTool.java or EligiblePostQuery.java changes) + the
  companion arm still matching `qwen` (the implicit default-escape form
  keeps working as before).
- P4 → acceptance 5 (git-diff fence over test files;
  likeMetacharacterValuesNeverWiden and
  punctuationCarryingTopicValuesFilterRatherThanDegrade green
  unmodified) and acceptance 6 (TopicArgs/TagNormalizers untouched).
- P5 → acceptance 2 (`LikeEscaperTest`: a helper mutation deleting the
  `%`, `_` or `\` branch each fail a named case; passthrough case fails
  an over-escaping mutation).
- P6 → the test lives in the existing `EligiblePostQueryIT` (failsafe
  phase, naming-guard-conformant); no new test class name introduced.
- P7 → the discriminating pair inside acceptance 1 (both arms on one
  fixture).
- P8 → reviewer diff check: the helper javadoc carries one spec anchor,
  no ticket-ID chronicle in code comments.
- acceptance 7 → `mvn verify` exit 0 from repo root.

## Out-of-scope

See `out_of_scope:` — /topic tag admission (TopicArgs' class and both
TagNormalizers) untouched: the parse gate is the first widening control
on the command leg and changing it is a user decision, recorded with the
free-tag-vs-class tension; SQL-shape unification rejected as churn;
the TagNormalizer duplication named as motivating context, not folded in;
no new search_tags reader, no GIN, no ranking/digest work; no edit to
any pre-existing test (this ticket deliberately invokes NO §8
test-modification authorization — every change is an add).

## Census

Class-scoped: "a user-influenced value bound into a LIKE predicate".
Mechanical enumeration over every module's `src/main`:
`grep -rn 'LIKE' --include='*.java' <each module>/src/main` and
`grep -rn 'SIMILAR TO\|~~' --include='*.java' <each module>/src/main`
(the second returns nothing):

| Site | Disposition |
|---|---|
| SearchPostsTool.java:228 `WHERE t LIKE ? ESCAPE '\'` (bound :235) | **Switch to shared helper** — pinned today, stays pinned unmodified |
| EligiblePostQuery.java:496 `WHERE t LIKE ? \|\| '%'` (bound :497) | **Switch to shared helper** — gains the query-tier pin (acceptance 1) |
| Both files' escaper javadoc/comment mentions | Ride the deletion of the private copies |
| No other production LIKE / SIMILAR TO site exists | Closed — nothing to defer |

Re-runnable after the change: `grep -rn 'String escapeLike' --include='*.java'`
over all modules' `src/main` returns exactly
`infochat-core/.../core/util/LikeEscaper.java`.

## Review observations

- Round 1 RECOMMENDED-NEW-TICKET (TOUCHED-BY-THIS-DIFF: no, no DECIDE-BEFORE):
  acceptance item 2's example expected-literal is the escape applied twice
  (decoded: a\%b\\_c\\\\d) and contradicts the same item's normative text
  ("every %, _ and \ gains a preceding backslash"). The diff implements the
  normative single-pass contract, which LikeEscaperTest pins
  (everyMetacharacterInOneValueIsEscaped asserts escapeLike("a%b_c\d") =
  "a\%b\_c\\d"); the reviewer confirmed this is the only reading consistent
  with the byte-identical consolidation and security.md's mechanism
  sentence. A frontmatter-text correction to the example literal is the
  driver/user's discretion; no code change involved, no ticket filed.
