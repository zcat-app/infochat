---
id: M1-049
title: "Process fix D: test pyramid — handler/router/IT decoupling"
status: done
created: 2026-05-21
last_updated: 2026-05-22
blocked_by: []
files_budget: 8
files_scope:
  - docs/process/test-pyramid.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceBanCheckOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1WatchdogIT.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
decomposed_from: M1-047
out_of_scope:
  - any change to a production handler (AddSourceCommandHandler, SummaryCommandHandler, HelpCommandHandler) — the refactor changes how the tests construct + invoke the handlers, not the handlers themselves
  - any change to InboundRouter or any intake-step service — router-level behavior is M1-044b territory; this ticket only changes how tests are SHAPED
  - any change to integration-test classes (`*IT.java`, e.g. AddSourceIT, SummaryIT, AddSourceAdapterScopeIT, SummaryAdapterScopeIT, AdapterRouterIT) — ITs already sit at the correct pyramid layer (full chain) per the new convention
  - any change to test infrastructure shared with ITs (MvpProfile, test-time `application-test.properties`, InMemoryAdapter test wiring) — handler-tier tests stop using these; ITs continue to use them unchanged
  - parameter contract annotations (M1-050 territory)
  - the `verified_stays_green:` lint check (M1-048 territory) — though D's refactor reduces the heuristic's surface area, A still ships independently
acceptance:
  - "New doc `docs/process/test-pyramid.md` exists with three sections defining the three layers (handler unit tests / router unit tests / integration tests), each section naming the layer's responsibility, what it MAY use, and what it MUST NOT use. Verify: `test -f docs/process/test-pyramid.md && grep -cE '^## (Handler|Router|Integration)' docs/process/test-pyramid.md` returns 3. The doc also names canonical example classes per layer (e.g. HelpCommandHandlerTest as handler-tier; InboundRouterTest as router-tier; AddSourceIT as integration-tier) so future tests have a copy-from template"
  - "AddSourceCommandHandlerTest.java is plain JUnit (no `@QuarkusTest`), constructs the handler under test directly, and exercises it via `handler.handle(scope, body)` with mocked collaborators (BundleLoader, KindResolver, UrlProbe, SourceUpsertService, DataSource, InboundContext — the six @Inject fields of AddSourceCommandHandler, verified by reading AddSourceCommandHandler.java:83-99). No call to `adapter.deliverDm(...)`. Verify: `grep -cE '@QuarkusTest' AddSourceCommandHandlerTest.java` returns 0 AND `grep -cE 'adapter\\.deliverDm' AddSourceCommandHandlerTest.java` returns 0 AND `grep -cE '\\.handle\\(' AddSourceCommandHandlerTest.java` returns ≥8"
  - "AddSourceCommandHandlerTest.java preserves all 8 pre-refactor @Test scenarios (count verified by `grep -cE '^\\s*@Test\\b' AddSourceCommandHandlerTest.java` on main = 8). Per-scenario verification by name-substring grep (case-insensitive single-method greps, NOT an aggregate count — see [[no-heterogeneous-aggregate-test-counts]]): `grep -iE 'void\\s+\\w*DispatchesAddSourceToHandlerExactlyOnce\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*FreshInsertReply\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*BannedUserRejectsBeforeProbe\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*GroupScopeNonAdminCallerIsRejected\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*AmbiguousUrlWithHtmlContentType\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*RssPathUrlContradicted\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*SubscribedExistingReply\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*TagReplacementReply\\w*\\s*\\(' AddSourceCommandHandlerTest.java` ≥1. (Each substring is a unique fragment of one of the 8 pre-refactor method names; the §Authorized test changes section pins the pre→post rename map per method.)"
  - "AddSourceBanCheckOrderingTest.java is plain JUnit, calls `handler.handle()` direct with mocked collaborators, preserves all 2 pre-refactor @Test scenarios (count verified by `grep -cE '^\\s*@Test\\b' AddSourceBanCheckOrderingTest.java` on main = 2). Note: this class STAYS a handler-tier test because the ban check currently lives in the handler on main (NOT in the router — that move is M1-044b's splice, which this ticket precedes). After M1-044b lands, M1-044b's refine will delete this class because the ban check will move to InboundRouter and the ordering moves to InboundRouterIntakeOrderingTest scenario (f). Verify: `grep -cE '@QuarkusTest' AddSourceBanCheckOrderingTest.java` returns 0 AND `grep -cE '\\.handle\\(' AddSourceBanCheckOrderingTest.java` returns ≥2 AND `grep -iE 'void\\s+\\w*bannedDmUserReceivesFixedBanReply\\w*\\s*\\(' AddSourceBanCheckOrderingTest.java` ≥1 AND `grep -iE 'void\\s+\\w*groupScopeNonAdminReceivesGroupAdminOnly\\w*\\s*\\(' AddSourceBanCheckOrderingTest.java` ≥1"
  - "SummaryCommandHandlerTest.java is plain JUnit, calls `handler.handle()` direct with mocked collaborators (BundleLoader, DataSource, EligiblePostQuery, ClusterTraversal, SummaryProseGenerator, LlmOutputSanitizer, InboundContext — the seven @Inject fields of SummaryCommandHandler, verified by reading SummaryCommandHandler.java:84-103), preserves all 9 pre-refactor @Test scenarios (count verified by `grep -cE '^\\s*@Test\\b' SummaryCommandHandlerTest.java` on main = 9). Verify: `grep -cE '@QuarkusTest' SummaryCommandHandlerTest.java` returns 0 AND `grep -cE 'adapter\\.deliverDm' SummaryCommandHandlerTest.java` returns 0 AND `grep -cE '\\.handle\\(' SummaryCommandHandlerTest.java` returns ≥9. Per-scenario verification by name-substring grep (case-insensitive, NOT an aggregate count — see [[no-heterogeneous-aggregate-test-counts]]): `grep -iE 'void\\s+\\w*handlerNameIsLiteralSummary\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*DispatchesSummaryToHandlerExactlyOnce\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*ZeroSubscriptionsProducesNoPostsYetReply\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*EmptyWindowProducesNoPostsYetReply\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*HappyPathThreeEligiblePostsYieldsThreeClusterBlocks\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*LlmUnreachableYieldsDegradedFallbackReply\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*CapExcessYieldsCapExcessNoticePrefix\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*GroupScopeReturnsNoPostsYet\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1; `grep -iE 'void\\s+\\w*SanitizerStripsPrivilegedCommandFromLlmAuthoredProse\\w*\\s*\\(' SummaryCommandHandlerTest.java` ≥1"
  - "HelpCommandHandlerTest.java drops `@QuarkusTest` and constructs the handler directly. The class already calls `handler.handle()` direct (pre-refactor pattern matches the new convention); the only changes are removing `@QuarkusTest` and `@Inject` and constructing the handler+bundleLoader by hand. All 4 pre-refactor @Test scenarios preserved (per-method name-substring grep, NOT an aggregate count — see [[no-heterogeneous-aggregate-test-counts]]). Verify: `grep -cE '@QuarkusTest' HelpCommandHandlerTest.java` returns 0 AND `grep -cE '@Inject' HelpCommandHandlerTest.java` returns 0 AND `grep -iE 'void\\s+\\w*replyContainsHeaderAndThreeMvpCommandShortHelpLines\\w*\\s*\\(' HelpCommandHandlerTest.java` ≥1 AND `grep -iE 'void\\s+\\w*handlerConsumesExactlyTheFourMvpBundleKeys\\w*\\s*\\(' HelpCommandHandlerTest.java` ≥1 AND `grep -iE 'void\\s+\\w*missingBundleKeyCausesHandlerToFailInsteadOfShippingIncompleteReply\\w*\\s*\\(' HelpCommandHandlerTest.java` ≥1 AND `grep -iE 'void\\s+\\w*replyContainsNoMarkdownLinkSyntaxOrHtmlAnchors\\w*\\s*\\(' HelpCommandHandlerTest.java` ≥1"
  - "AdapterRegistryTest.java stays at the wiring layer but tightens its assertion: the canonical test (`singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`) asserts WIRING (router was invoked with the delivered body) instead of REPLY CONTENT (UNKNOWN_COMMAND_REPLY literal). The reply-content check belongs in InboundRouterTest's `unknownCommandProducesFriendlyUnknownCommandReply` (already there on main). Both pre-refactor @Test scenarios preserved (per-method name-substring grep, NOT an aggregate count — see [[no-heterogeneous-aggregate-test-counts]]). Verify: `grep -cE 'UNKNOWN_COMMAND_REPLY' AdapterRegistryTest.java` returns 0 AND `grep -iE 'void\\s+\\w*singleAdapterHappyPathActivatesInMemoryAndRegistersRouter\\w*\\s*\\(' AdapterRegistryTest.java` ≥1 AND `grep -iE 'void\\s+\\w*multiAdapterHappyPathActivatesBothFakeAdapters\\w*\\s*\\(' AdapterRegistryTest.java` ≥1. (This tightens the wiring test's scope so M1-044b's splice does not collateral-damage it via the unknown-DM contact path — the same defect the handoff describes.)"
  - "Stage1WatchdogIT.java's wall-clock sanity-band upper assertion widens from 5× to 10× the per-host wall to absorb CI-tolerance noise. Reason: this cap has flaked twice on main with the 5× setting (51ms during M1-040 round-2 on 2026-05-19 per memory note `project_stage1watchdogit_flake.md`; 78ms then 52ms during M1-049 round-1 on 2026-05-22). The side-effect assertions (watchdog row INSERTED, post status QUARANTINED, span_start/end set correctly) remain the load-bearing correctness checks; the wall-clock band catches gross drift only. No production code (Stage1Pipeline, Stage1Watchdog) is modified. Verify: `grep -cE 'TEST_CAP_MS \\* 10' Stage1WatchdogIT.java` returns 2 (the assertion expression + the message-string echo of the cap value; matches the original 5×-form's grep shape) AND `grep -cE 'TEST_CAP_MS \\* 5' Stage1WatchdogIT.java` returns 0"
  - "mvn -B clean verify exits 0 — every refactored test passes against the existing production code (no production handler is modified). The `*IT.java` integration tests (AddSourceIT, SummaryIT, AddSourceAdapterScopeIT, SummaryAdapterScopeIT, AdapterRouterIT) also stay green — they continue to provide full-chain coverage at the IT layer per the new pyramid convention"
test_plan:
  adds:
    - docs/process/test-pyramid.md
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceBanCheckOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1WatchdogIT.java
  preserves:
    - all tests currently green on main outside files_scope
    - InboundRouterTest, InboundRouterIntakeOrderingTest (does not exist on main yet — M1-044b creates it), InboundRouterNormalizeTest, InboundRouterContactIdRedactionTest — router-tier tests stay as-is
    - all `*IT.java` integration tests
    - all SPI-tier tests (RateCapBucketTest, InviteCodeConsumerTest, BanCheckTest, AutoRegisterServiceTest, etc.) — these test individual services, already at the right layer
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 1155
      removed: 579
escalations:
  - date: 2026-05-21
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      ACCEPTANCE-VS-DOD-CONSISTENT FAIL: Acceptance item 8 asserts a
      `grep -hcE '^\s*@Test' <5 files> | awk '{s+=$1}END{print s}' returns ≥29`
      HETEROGENEOUS-AGGREGATE-NAMED count across 5 DoD-named classes with
      different individual counts (9, 3, 10, 4, 3). The aggregate masks
      per-class regressions. Fix recommended in clarity verdict: add per-class
      @Test count assertions to items 6 (HelpCommandHandlerTest ≥4) and 7
      (AdapterRegistryTest ≥3), drop item 8.
  - date: 2026-05-21
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The ticket's Definition of Done and acceptance items pin
      pre-refactor @Test counts that do not match the files on main as of
      2026-05-21. The DoD asserts `AddSourceCommandHandlerTest (9 @Test),
      AddSourceBanCheckOrderingTest (3 @Test), SummaryCommandHandlerTest
      (10 @Test), HelpCommandHandlerTest (4 @Test), AdapterRegistryTest
      (3 @Test)` for a total of 29; the `revisions:` block likewise frames
      the per-class counts as `9, 3, 10, 4, 3 → total 29`. Actual
      `grep -cE '^\s*@Test\s*$'` counts on main: `AddSourceCommandHandlerTest=8,
      AddSourceBanCheckOrderingTest=2, SummaryCommandHandlerTest=9,
      HelpCommandHandlerTest=4, AdapterRegistryTest=2` → total 25. Four of
      the five classes have one fewer @Test method than the ticket promises
      to "preserve". Acceptance item 3 ("preserves all 9 pre-refactor @Test
      scenarios") is unsatisfiable by a pure refactor; the same for items
      4, 5, and the DoD line on AdapterRegistryTest.

      Secondary risk: acceptance items 2 and 5 list illustrative collaborator
      class names that do not match production code. Item 2 cites `LlmClient`
      — AddSourceCommandHandler does not depend on it. Item 5 cites
      `SummaryService` and `JoinService` — neither class exists in the
      repository. Actual deps (verified by Read against production handlers):
        - AddSourceCommandHandler: BundleLoader, KindResolver, UrlProbe,
          SourceUpsertService, DataSource, InboundContext
        - SummaryCommandHandler: BundleLoader, DataSource, EligiblePostQuery,
          ClusterTraversal, SummaryProseGenerator, LlmOutputSanitizer,
          InboundContext
      The verifier greps in items 2 and 5 pin `.handle(` counts rather than
      specific mock class names, so this is documentation/clarity drift
      rather than an unrunnable verifier — but a developer following the
      acceptance prose would head for non-existent classes.

      SUGGESTED ESCALATION: refine — fix the @Test counts in DoD and
      acceptance items 3, 4, 5, 7 to match reality (8, 2, 9, 4, 2), and
      replace the wrong collaborator-class names in items 2 and 5 with the
      real field-injected deps listed above. Also a natural place to pin
      the rename map for AddSourceCommandHandlerTest that the prior
      clarity_check WARN flagged as inconsistent ("TBD by implementer" vs
      acceptance item 3 saying the section enumerates it).

      EVIDENCE (verbatim from Plan subagent):

      `grep -cE '^\s*@Test\s*$'` on main:
        HelpCommandHandlerTest.java                       4
        AddSourceBanCheckOrderingTest.java                2
        AdapterRegistryTest.java                          2
        SummaryCommandHandlerTest.java                    9
        AddSourceCommandHandlerTest.java                  8
                                                  total: 25

      ### Audit coverage
      - file accounting — audited (pass): 6 files (1 doc + 5 test modifies)
        ≤ files_budget 8.
      - API-surface — audited (fail): handle(ScopeRef, String) signatures
        align with acceptance, but @Test-count claims do not match reality
        and collaborator names in items 2/5 cite non-existent classes.
      - test-scaffolding — audited (pass): all 5 test files in files_scope
        appear in §Authorized test changes.
      - cross-cutting concerns — audited (pass): refactor changes test
        shape, not production behavior.
      - implementation order — audited (pass): doc → HelpCommandHandlerTest
        → AdapterRegistryTest → AddSourceBanCheckOrderingTest →
        SummaryCommandHandlerTest → AddSourceCommandHandlerTest is the
        suggested order.
      - risks — audited: @Test-count mismatches and illustrative-collaborator-
        naming gap (escalated above). Also rename-map TBD vs acceptance-item-3
        claim inconsistency (already flagged in clarity_check WARN; natural
        to fold into the same refine).
  - date: 2026-05-22
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Implementation surfaced a Stage1WatchdogIT timing flake on main:
      round-1 `mvn -B verify` failed with `Stage1Pipeline.process
      duration was 78ms — expected at most 50ms (5× cap, the M1-029
      CI-tolerance precedent)`; the retry hit 52ms. This is the second
      occurrence on the same code (first was 51ms during M1-040 round-2
      on 2026-05-19 per memory note project_stage1watchdogit_flake.md).
      Per the memory rule "widen to 10× only after a second hit on the
      same code", widen Stage1WatchdogIT.java's wall-clock cap from 5×
      to 10×. Stage1WatchdogIT.java is in infochat-collector, outside
      M1-049's current files_scope (5 paths in infochat-provider + 1
      process doc), so files_scope must be widened via refine before
      the edit lands.

      Provider-module validation: the 5 refactored test files all pass
      against the provider-only `mvn -pl infochat-provider -am verify`
      (Help 4/4, AdapterRegistry 2/2, AddSourceBanCheckOrdering 2/2,
      Summary 9/9, AddSource 8/8; total provider 176 unit + 46 IT, all
      green). The blocking failure is entirely orthogonal to M1-049's
      handler-tier surface.
revisions:
  - date: 2026-05-21
    reason: clarity-fail refine snapshot (acceptance item 8 heterogeneous-aggregate)
    summary: |
      Pre-refine snapshot. The initial draft of acceptance had 9 items.
      Item 8 asserted a single aggregate @Test count across 5 named test
      classes via `grep -hcE '^\s*@Test' <5 files> | awk '{s+=$1}END{print s}'
      returns ≥29`. The DoD enumerates the 5 classes with heterogeneous
      individual counts (AddSourceCommandHandlerTest=9,
      AddSourceBanCheckOrderingTest=3, SummaryCommandHandlerTest=10,
      HelpCommandHandlerTest=4, AdapterRegistryTest=3 → total 29). The
      aggregate masks per-class regressions: dropping 2 from one class
      and adding 2 to another keeps the total at 29 while mutating two
      classes silently.

      The clarity subagent caught this at /m1-tick start as
      ACCEPTANCE-VS-DOD-CONSISTENT FAIL (HETEROGENEOUS-AGGREGATE-NAMED).
      The author-side linter passed it because the original
      AGGREGATE_TEST_RE only matched single-file greps with the literal
      `'@Test'` pattern and a `≥N match`/`matches` suffix — item 8's
      `'^\s*@Test'` anchor variant, multi-file path list, and
      `returns ≥29` phrasing all evaded the regex.

      The linter regex was widened in a separate `process:` commit (the
      same day) to catch the multi-file + awk-sum + anchor-variant
      shape generically as HETEROGENEOUS-AGGREGATE-COUNT. This refine
      then applies the fix the clarity verdict recommended: drop
      item 8, fold per-class @Test count assertions into items 6
      (HelpCommandHandlerTest ≥4) and 7 (AdapterRegistryTest ≥3).
      Items 2, 4, 5 already pin per-class `.handle(` counts for the
      other three classes (≥9, ≥3, ≥10).

      Prior frontmatter values: status=escalated (was pending pre-start);
      clarity_check.verdict=FAIL with 1 blocker, 1 warning.
  - date: 2026-05-21
    reason: outline-fail refine snapshot (fabricated test-method counts + collaborators)
    summary: |
      Pre-refine snapshot. The clarity-fail rework above (commit 1a91794)
      pinned per-class @Test counts as 9, 3, 10, 4, 3 → total 29 — written
      from memory without grepping the actual files. Reality on main
      verified by `grep -cE '^\s*@Test\b'`: AddSourceCommandHandlerTest=8,
      AddSourceBanCheckOrderingTest=2, SummaryCommandHandlerTest=9,
      HelpCommandHandlerTest=4, AdapterRegistryTest=2 → total 25. Four of
      five classes had one fewer @Test than promised.

      Secondary fabrications: acceptance item 2 named `LlmClient` as an
      AddSourceCommandHandler collaborator (no such class exists; real
      deps verified by reading AddSourceCommandHandler.java:83-99 are
      BundleLoader, KindResolver, UrlProbe, SourceUpsertService, DataSource,
      InboundContext). Item 5 named `SummaryService` and `JoinService` as
      SummaryCommandHandler collaborators (neither class exists; real
      deps verified by reading SummaryCommandHandler.java:84-103 are
      BundleLoader, DataSource, EligiblePostQuery, ClusterTraversal,
      SummaryProseGenerator, LlmOutputSanitizer, InboundContext).

      The Plan subagent at /m1-tick start caught the count drift on
      round 1 as OUTLINE FAILED. Clarity round 2 (on the refined ticket
      that introduced the fabrication) passed it as WARN — clarity reads
      only the ticket text and cited spec files, has no filesystem
      grounding, so it could not detect ticket↔reality drift.

      Resolution path chosen (2026-05-21 design discussion): NOT to add
      a `DOD-COUNTS-GROUND-TRUTHED` lint check (whack-a-mole concern over
      adding one mechanical check per LLM-author hallucination leaf).
      Instead encode the trunk discipline in author-memory note
      `feedback_ground_truth_before_claims.md`: every claim about an
      external artifact (count, name, signature, regex match, file path)
      must be preceded by the grounding command, with the output visible
      in the same turn. This refine applies that discipline retroactively
      — every count and class name below was preceded by an explicit
      `grep` against main in the chat that produced this commit.

      Changes applied:
        - DoD per-class counts: 9/3/10/4/3 → 8/2/9/4/2; total 29 → 25.
        - Acceptance item 2: collaborator list replaced with the 6 real
          @Inject fields; `≥9` → `≥8`.
        - Acceptance item 3: "all 9" → "all 8"; enumerated all 8
          per-method substring greps (resolves prior clarity WARN that
          flagged 6-of-9 enumeration + "TBD by implementer" inconsistency).
        - Acceptance item 4: "all 3" → "all 2"; `≥3` → `≥2`; added
          second per-method name grep for the second @Test method.
        - Acceptance item 5: collaborator list replaced with the 7 real
          @Inject fields; "all 10" → "all 9"; `≥10` → `≥9`; added per-
          method substring greps for all 9 methods (resolves prior clarity
          WARN that flagged HETEROGENEOUS-AGGREGATE-UN-ENUMERATED).
        - §Authorized test changes: counts updated to match reality;
          AddSourceCommandHandlerTest rename map enumerated (resolves
          prior clarity WARN that flagged "TBD by implementer" vs the
          acceptance-item-3 claim that the section enumerates it).

      Prior frontmatter values: status=escalated;
      clarity_check.verdict=WARN with 0 blockers, 2 warnings.
  - date: 2026-05-22
    reason: budget-breach refine snapshot (Stage1WatchdogIT cap widening)
    summary: |
      Pre-refine snapshot. The 5-test-file provider-tier refactor
      (HelpCommandHandlerTest, AdapterRegistryTest,
      AddSourceBanCheckOrderingTest, SummaryCommandHandlerTest,
      AddSourceCommandHandlerTest) plus the new process doc landed on
      the branch in `/m1-tick start` round-1 implementation; all 5
      tests pass in provider-only `mvn -pl infochat-provider -am
      verify`. Full reactor `mvn -B verify` failed twice consecutively
      in infochat-collector at
      Stage1WatchdogIT.watchdogFiresAndPostIsSealedAtQuarantined: 78ms
      then 52ms vs the existing 50ms cap (TEST_CAP_MS × 5). Per memory
      note `project_stage1watchdogit_flake.md`, this is the second hit
      on the same code (first was 51ms during M1-040 round-2 on
      2026-05-19); the codified rule triggers widening to 10× wall.

      This refine adds Stage1WatchdogIT.java to files_scope (1→7 of 8
      budget; doc + 5 provider tests + 1 collector test) and one new
      acceptance item (#9) pinning the 5×→10× widening. The widening
      is test-scope only: no production code (Stage1Pipeline,
      Stage1Watchdog) is touched; the side-effect assertions (watchdog
      row INSERTED, post QUARANTINED, span_start/end set correctly)
      remain the load-bearing correctness checks; the wall-clock band
      catches gross drift only (e.g. watchdog never fired, process
      took 100× the cap because something other than the matcher was
      the bottleneck).

      Prior frontmatter values: status=in-progress; files_scope had 6
      entries (1 doc + 5 provider tests); acceptance had 8 items.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-21
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-049.md
---

# M1-049: Process fix D — test pyramid (handler/router/IT decoupling)

## Context

Subticket of [[M1-047]]. The structural fix for the defect class M1-044b's premise-fail #2 surfaced.

The defect class: handler tests exercise the full `adapter → router → handler` chain via `@QuarkusTest + adapter.deliverDm()`. When the router changes (e.g. M1-044b's splice adds invite gate / ban check before dispatch), handler test outcomes change too — even though the handler under test is unmodified. The fix: handler tests call `handler.handle(scope, body)` directly with mocked collaborators. Router tests exercise `router.onMessage()` with mocked handlers. Integration tests cover the full chain at the IT layer.

After D lands, M1-044b's "M1-035c/M1-036/M1-037/M1-039/M1-040 tests stay green unchanged" claim DISSOLVES — those handler tests no longer exercise the router, so router changes can't break them. The 7 failures M1-044b surfaced (5 unknown-DM + 2 banned-DM) all happened in `AddSourceCommandHandlerTest` + `AddSourceBanCheckOrderingTest`; under D those tests don't reach the router at all.

[[M1-048]] (A) is the procedural backstop; this is the structural fix. [[M1-050]] (E) is the API-contract complement.

## Definition of Done

- New `docs/process/test-pyramid.md` convention doc with three layer sections.
- 5 handler-tier test classes refactored (per-class @Test counts verified by `grep -cE '^\s*@Test\b'` against main as of 2026-05-21):
  - **Full refactor**: AddSourceCommandHandlerTest (8 @Test), AddSourceBanCheckOrderingTest (2 @Test), SummaryCommandHandlerTest (9 @Test) — drop `@QuarkusTest`, drop `adapter.deliverDm`, call `handler.handle()` direct with mocks.
  - **Light touch**: HelpCommandHandlerTest (4 @Test) — already calls `handler.handle()` direct; only drop `@QuarkusTest` + `@Inject`.
  - **Tighten assertions**: AdapterRegistryTest (2 @Test) — stays a wiring test; assertion narrows from "router dispatches AND emits UNKNOWN_COMMAND_REPLY" to "router was invoked with the body." The reply-content check is already in InboundRouterTest.
- Total @Test count ≥ 25 (no scenario silently dropped).
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Mock library.** Project already uses Mockito for InboundRouterTest. Reuse the same pattern: `Mockito.mock(...)`, `when(...).thenReturn(...)`, `verify(...)`. No new dependency.
- **Handler construction.** Each handler is a CDI bean today; construct directly via `new AddSourceCommandHandler()` and assign fields (`handler.urlProbe = mockUrlProbe`; etc.). The current package-private field assignment pattern (already used in InboundRouterTest) carries over. If a handler has a constructor that wires fields, use it; if it uses field injection, assign directly.
- **`bundleLoader` mocking.** The production `BundleLoader` is class, not interface. Use `Mockito.mock(BundleLoader.class)` or extend it with a recording subclass (HelpCommandHandlerTest already has `RecordingBundleLoader` — copy the pattern).
- **`DataSource` mocking.** Tests that exercise SQL paths today via real Postgres (through `@QuarkusTest`) must mock `DataSource`, `Connection`, `PreparedStatement`, `ResultSet` — verbose but mechanical. Consider extracting a small `H2DataSource` test fixture if the boilerplate is heavy (out of scope here unless boilerplate dominates).
- **Helper extraction.** If three handler tests need similar collaborator stubs (mock DataSource, mock BundleLoader), a `HandlerTestFixture` helper may be useful (not in this ticket's `files_scope`; extracting it would require a refine to widen files_budget and add the helper path to `files_scope`). If the boilerplate stays bounded per test class, skip the helper entirely.
- **AdapterRegistryTest's specific change.** The pre-refactor test asserts the round-trip produces UNKNOWN_COMMAND_REPLY. Post-refactor: assert the router's `onMessage` was invoked with the delivered body (via Mockito `verify(router).onMessage(...)`). The reply-content assertion stays in InboundRouterTest's `unknownCommandProducesFriendlyUnknownCommandReply` (already there). This tightening is what makes the test M1-044b-collateral-resistant.
- **The convention doc shape.** Three top-level sections — `## Handler unit tests`, `## Router unit tests`, `## Integration tests` — each ~10-20 lines naming the layer's collaborators, examples, and forbidden patterns (e.g. handler layer MUST NOT call `adapter.deliverDm` or `router.onMessage`). Add a brief preamble citing the M1-044b premise-fail #2 incident as the motivating example. Length target: ~80-120 lines total.
- **Rename map for each refactored class.** §Authorized test changes enumerates the pre→post test-method-name map. The acceptance items 3, 5 cite specific name-substrings; the rename map confirms each rename respects the substring preservation. Test-integrity check passes because the renames are authorized + behavioral coverage is preserved.

## Big-picture notes

- **Why the full refactor (not selective).** User chose option (a) — selective would leave the rest of the handler-via-router test pattern in place as a future trap. Once D's pattern is set, future handler tests follow it; the old pattern atrophies.
- **Why HelpCommandHandlerTest is light touch.** It already follows the pattern (constructs handler, calls `.handle()` direct, mocks bundleLoader). The only un-pyramid thing is `@QuarkusTest`. Dropping that + `@Inject` is mechanical.
- **Why AddSourceBanCheckOrderingTest stays (for now).** On current main, the ban check lives in `AddSourceCommandHandler` (per M1-039), NOT in InboundRouter. The "ban before probe" ordering is a handler-internal concern. After M1-044b lands the splice, the ban check moves to InboundRouter and this class becomes redundant; M1-044b's eventual refine will delete it (the ordering moves to InboundRouterIntakeOrderingTest scenario (f) — see M1-044b's existing acceptance item 12).
- **Why AdapterRegistryTest stays at the wiring layer.** Its purpose is to prove the SPI wiring (AdapterRegistry → InMemoryAdapter → InboundRouter round-trip), not to verify command behavior. Tightening the assertion to "router was invoked" preserves the wiring claim without coupling the test to any particular command's reply text.
- **After D + M1-044b refine: clean state.** The handler tests no longer break when the router changes; future router changes affect only InboundRouterTest + InboundRouterIntakeOrderingTest + the ITs. The "stays green" claim becomes self-evidently true rather than load-bearing.

## Out-of-scope expansion

- **No production handler changes.** AddSourceCommandHandler, SummaryCommandHandler, HelpCommandHandler stay byte-for-byte unchanged.
- **No router changes.** InboundRouter is not in files_scope. M1-044b's splice still needs to land separately.
- **No IT changes.** AddSourceIT, SummaryIT, AddSourceAdapterScopeIT, SummaryAdapterScopeIT, AdapterRouterIT continue to do the full-chain assertion at the IT layer. They serve as the spec-conformance backstop the handler-tier tests can no longer provide.
- **No SPI-tier test changes.** RateCapBucketTest, InviteCodeConsumerTest, BanCheckTest, AutoRegisterServiceTest already exercise individual services in isolation — already at the correct pyramid layer.
- **No new fixture extraction unless boilerplate demands it.** A `HandlerTestFixture` helper is permissible but adds a file; defer unless the per-class boilerplate is dominating.

## Authorized test changes

- `AddSourceCommandHandlerTest.java` (M1-036): full refactor per §Implementation notes. All 8 @Test methods preserved. Authorized rename map (pre → post) — each post-rename name must retain the substring cited in the per-method grep in acceptance item 3:
  - `inboundRouterDispatchesAddSourceToHandlerExactlyOnce` → keep verbatim OR rename containing `DispatchesAddSourceToHandlerExactlyOnce`
  - `dmNonBannedNonAdminProceedsAndProducesFreshInsertReply` → keep verbatim OR rename containing `FreshInsertReply`
  - `dmBannedUserRejectsBeforeProbe` → keep verbatim OR rename containing `BannedUserRejectsBeforeProbe`
  - `groupScopeNonAdminCallerIsRejected` → keep verbatim OR rename containing `GroupScopeNonAdminCallerIsRejected`
  - `ambiguousUrlWithHtmlContentTypeSurfacesAmbiguousFriendlyError` → keep verbatim OR rename containing `AmbiguousUrlWithHtmlContentType`
  - `rssPathUrlContradictedByHtmlContentTypeSurfacesAmbiguous` → keep verbatim OR rename containing `RssPathUrlContradicted`
  - `branchBSubscribedExistingReplyOmitsUrlVisibilityDisclosure` → keep verbatim OR rename containing `SubscribedExistingReply`
  - `branchCBotAdminTagReplacementReplyOmitsUrlVisibilityDisclosure` → keep verbatim OR rename containing `TagReplacementReply`
- `AddSourceBanCheckOrderingTest.java` (M1-039): full refactor per §Implementation notes. All 2 @Test methods preserved; `bannedDmUserReceivesFixedBanReply` and `groupScopeNonAdminReceivesGroupAdminOnly` substrings preserved per acceptance item 4.
- `SummaryCommandHandlerTest.java` (M1-037): full refactor per §Implementation notes. All 9 @Test methods preserved with name-substring continuity (see acceptance item 5).
- `HelpCommandHandlerTest.java` (M1-035c): light touch — drop `@QuarkusTest` + `@Inject`. All 4 @Test methods preserved with original names per acceptance item 6.
- `AdapterRegistryTest.java` (M1-008b): tighten assertion in `singleAdapterHappyPathActivatesInMemoryAndRegistersRouter` from reply-content check to wiring check (router was invoked). Both @Test method names (`singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`, `multiAdapterHappyPathActivatesBothFakeAdapters`) are preserved per acceptance item 7.

## Alternatives considered

- **Additive D (keep old tests, add new direct-handler tests alongside).** Rejected by user 2026-05-21 — the old tests stay a tripwire for future router changes; defect class survives.
- **Selective D (refactor only the 7 currently-failing tests).** Rejected by user 2026-05-21 — cheapest but leaves rest of pattern in place; future handler tests likely copy the @QuarkusTest pattern from the un-refactored examples.
- **Extract `HandlerTestFixture` as part of this ticket.** Considered; deferred unless the per-class boilerplate dominates. If extracted, file added via refine (budget bump 8 → 9).
- **Split into D1 (convention doc + AddSourceCommandHandlerTest as proof) + D2 (apply to remaining 4).** Considered; rejected to land the full pattern atomically. The risk is that D1 lands with a half-converted suite and the second half drifts. files_budget: 8 fits all 6 files (5 tests + 1 doc).
