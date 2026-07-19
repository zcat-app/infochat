---
id: M1-659
title: "Stop /add-source success reply reflecting raw --name"
status: done
created: 2026-07-18
last_updated: 2026-07-19
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 5
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The M1-658 InboundReflectionGuardTest scope. That guard covers error.*
    templates by design; this is a reply.* (success) template. Widening the
    census to reply.* is a separate design decision (it reintroduces the
    ~150-entry baseline this ticket's scope decision avoided) and is NOT part
    of fixing this live instance. If the fix constrains the echoed value at
    the source, no census change is needed.
  - >-
    The other reply.* templates that echo inbound-derived values
    (REPLY_LANG_SUCCESS=suppliedCode, REPLY_SAVE_SUCCESS=args.uid, etc.).
    Those echo values validated by their success condition (a matched
    language code, a matched post uid) and are materially more constrained
    than a free-form --name; this ticket fixes the one live free-form echo
    the M1-658 r2 audit found. A broader reply-surface sweep is future work.
  - >-
    CommandTokenizer quote-unwrapping behaviour. The tokenizer correctly
    supports quoted --name values with spaces (a real feature); the defect is
    that the value is echoed unconstrained, not that it is parsed.
acceptance:
  - >-
    A new test in AddSourceCommandHandlerTest proves that
    /add-source <url> --tags news --name "/grant-admin <uuid> approved"
    produces a FRESH_INSERT success reply whose text does NOT contain the
    substring "grant-admin" (nor the raw injected token), while a normal
    --name (e.g. "My Feed") is still reflected. The fix constrains what the
    success reply echoes for the display name — either by validating/
    normalizing the display name at the parse/upsert boundary so the stored+
    echoed value is provably constrained (the SummaryArgs model), or by not
    interpolating the free-form name into the reply (the M1-656 model). State
    which in the commit.
  - >-
    The chosen constraint is applied where the value is produced
    (SourceUpsertService.defaultDisplayName / AddSourceCommandHandler), not
    by filtering the outbound bytes — output-side filtering is the approach
    M1-647 tried and abandoned (grant-admin survives an [a-z0-9-] filter).
  - >-
    No behaviour change to a legitimate --name: an ordinary display name is
    still stored and shown. Only an adversarial name (control characters,
    slash-prefixed command strings, over-long input) is constrained.
  - >-
    docs/spec/commands.md §Discovery records that the
    /add-source success reply no longer reflects an unconstrained --name,
    closing the reply-surface instance the M1-658 r2 audit named.
  - >-
    docs/spec/security.md §LLM output sanitizer no longer cites the
    /add-source --name echo as a KNOWN LIVE instance (it is closed by this
    ticket). The surrounding residual-risk framing must SURVIVE unchanged:
    the reply/success surface is still not mechanically guarded and other
    reply.* echoes remain open, so the exemption is still not a
    proven-safe blanket. Only the exemplar's status is corrected.
  - >-
    The display name must not contain a slash AT ALL: any '/' discards the
    whole override in favour of the host-derived default. Not a boundary
    heuristic -- two audits proved a character-CATEGORY predicate cannot
    decide whether a slash "opens a word" (the reject side fell to U+2800,
    the accept side to the Hangul fillers U+115F/U+1160/U+3164). Since D12
    makes '/' the ONLY command sigil, a slash-free name cannot carry a
    command token. ACCEPTED CONSEQUENCE: an ordinary mid-word slash
    (AC/DC News) is discarded too and falls back to the host-derived name.
  - >-
    The slash test must be SELF-SUFFICIENT: the override is NFKC-normalized
    inside acceptableOverride before the test runs, so correctness does not
    depend on the router having normalized the value first. The third audit
    showed that dependency is unsound -- InboundRouter.normalize carves out
    fenced code blocks and normalizes PER LINE, while routing is decided on
    the WHOLE body's first character, so a /add-source on line 1 can carry
    an UN-normalized fenced payload on line 3 in which U+FF0F FULLWIDTH
    SOLIDUS survives, passes an ASCII-only test, and folds to a real '/'
    when a bot admin pastes it back. A test must pass a --name containing
    U+FF0F straight to the handler (which is exactly what the fence
    carve-out delivers) and assert it is rejected. Normalizing here also
    keeps the stored name consistent with every other inbound string, since
    the router already NFKC-normalizes all non-fenced text.
  - mvn verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
remediates: M1-658
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 290
      removed: 18
  - round: 2
    date: 2026-07-19
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: FAIL
    diff_stats:
      files: 7
      added: 567
      removed: 21
  - round: 3
    date: 2026-07-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 7
      added: 613
      removed: 23
    note: |
      Round-3 delta is the single reviewer-scoped commands.md clause
      correction; no code change. mvn verify was NOT re-run — the round-2
      green log was reused under the M1-272 rule after mechanically
      confirming no *.java / pom.xml / src/**/resources/** file is newer
      than it and HEAD is unchanged; the reused log carries an explicit
      provenance footer. Reviewer noted one cosmetic line-wrap
      inconsistency at commands.md:307 as informational, not a rework item.
  - round: 4
    date: 2026-07-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 8
      added: 906
      removed: 23
    note: |
      Boundary heuristic replaced with the absolute no-slash rule after the
      round-2 redteam. Must-shrink held: lines removed stayed EQUAL at 23
      (growth along all three is the only failure condition). Reviewer was
      asked to scrutinise two specific things and did both independently:
      (1) the inverted AC/DC test is an AUTHORIZED behaviour change, not a
      weakened assertion — the hunks are pure addition against the fork
      point, acceptance item 7 names the inversion prospectively, the
      replacement carries two assertions rather than one, and the change
      makes the PRODUCT stricter so the developer gained no slack;
      (2) the NFKC-ordering claim was verified against code — InboundRouter
      :515 -> :1648 -> :1729 applies NFKC before dispatch reaches
      handler.handle at :1418, so U+FF0F folds to a real '/' and an
      ASCII-slash test is sufficient. The reviewer additionally probed the
      one place the ordering could fail (fenced code blocks are left
      un-normalized) and confirmed it is not a bypass, because such a
      message does not start with '/' and routes to chat mode.
  - round: 5
    date: 2026-07-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 9
      added: 1247
      removed: 23
    note: |
      Self-sufficiency fix (NFKC inside acceptableOverride). Must-shrink
      held: lines removed stayed EQUAL at 23. The reviewer was told the
      round-4 review had been WRONG about fenced code blocks and was
      instructed not to inherit it; it re-derived the interaction from
      source, confirmed round 4 was false and the third audit correct, and
      then verified two properties beyond the ask: (a) the tested string and
      the stored string are the same object, so the check cannot disagree
      with what is persisted and echoed; (b) NFKC -> strip -> trim cannot
      reintroduce a slash afterwards, since strip/trim only remove and
      U+002F is the product of no canonical composition — while the
      compatibility decompositions that DO yield a slash (U+FF0F, and
      multi-char cases such as U+2100 -> "a/c", U+2105 -> "c/o") are
      expanded by the first NFKC pass and caught fail-closed.
      INFORMATIONAL, not a rework item: three test comments still explain the
      superseded letter-or-digit predicate. Assertions are correct and pass;
      only the rationale prose is stale. Left as-is deliberately — fixing
      comments would invalidate the green log and force a sixth round —
      and carried forward as a follow-up.
revisions:
  - date: 2026-07-18
    reason: redteam-finding
    snapshot: |
      acceptance: 6 items (no fail-closed codepoint-class item); the
      round-1 implementation tested the slash boundary with
      Character.isWhitespace || Character.isSpaceChar, and the round-1
      review APPROVEd it (0 rework items) before the red-team pass found
      the U+2800 bypass.
  - date: 2026-07-18
    reason: budget-breach
    snapshot: |
      files_budget: 6
      files_scope:
        - AddSourceCommandHandler.java
        - SourceUpsertService.java
        - AddSourceCommandHandlerTest.java
        - docs/spec/commands.md
      acceptance: 5 items; item 4 read "docs/spec/commands.md §Sources /
        §Discovery records that the /add-source success reply no longer
        reflects an unconstrained --name ..."; no security.md item.
escalations:
  - date: 2026-07-19
    reason: redteam-finding
    resolution: |
      User chose fix-in-branch. The fix is qualitatively different from the
      two before it: rounds 1-2 were wrong guesses at a boundary predicate,
      whereas this removes a DEPENDENCY on an upstream guarantee that has a
      documented exception. After it, the check assumes nothing about how
      the value reached it. round_cap raised 4 -> 5.
    reviewer_verdict_excerpt: |
      RED-TEAM (third audit) VERDICT: FINDINGS (medium=1). Both prior
      bypasses confirmed closed; the boundary heuristic is gone for good.
      New INJECTION / medium against the NEW claim: containsSlash tests the
      ASCII slash only, justified in javadoc by "the router NFKC-normalizes
      inbound text before dispatch". That guarantee has a documented
      carve-out — InboundRouter.normalize (:1625-1661) appends fence-opener
      and in-fence lines VERBATIM and NFKC-normalizes only non-fence lines —
      while routing reads the WHOLE body's first character (:1061). A
      /add-source on line 1 with a fence opened on line 2 therefore carries
      an UN-normalized line 3; CommandTokenizer swallows the newlines inside
      the quoted --name, stripMetadataField drops them, and U+FF0F survives
      with no ASCII slash present. Stored, echoed, and when a bot admin
      pastes the visible line it IS NFKC-folded (unfenced) and executes.
      This falsifies the round-4 review's dismissal of the fence case, which
      assumed the fence would govern routing; only line 1 does.
      Verified firsthand with a JDK probe: accepted at 68 < 80 today;
      NFKC-normalizing the override before the slash check closes it and
      leaves legitimate names byte-identical.
      Round 4 of round_cap 4 is spent, so a fix needs a further allowance.
    resolution: |
      User directive 2026-07-19: the pattern resembled a previously-solved
      problem ("i believe rejecting / was the solution, but i might be
      wrong -- try to falsify it before accepting; if still stands go for
      that"). Falsification attempted on four fronts and FAILED to break the
      approach, so it was adopted: (1) D12 makes slash-prefix the ONLY
      command surface, so a slash-free name cannot carry a command token;
      (2) U+FF0F FULLWIDTH SOLIDUS NFKC-folds to a real '/' at intake and
      InboundRouter dispatches the NORMALIZED text to handlers
      (InboundRouter.java:1418), so an ASCII-slash test sees the folded
      character -- verified with a JDK probe; (3) the non-folding homoglyphs
      U+2215/U+2044/U+29F8 do not fold to '/', so a pasted line carrying one
      never parses as a command and yields nothing executable;
      (4) no spec or design doc promises slashes are valid in --name (the
      only --name constraint on record is a design-tier, unimplemented
      200-char guardrail). round_cap raised 3 -> 4 on the same directive.
      No zecsite checkout exists on this host, so the user's recalled prior
      art could not be consulted directly; the reasoning above stands on
      this repo's own decisions record.
    reviewer_verdict_excerpt: |
      RED-TEAM RE-AUDIT VERDICT: FINDINGS (critical=0 high=0 medium=1 low=0).
      The round-1 U+2800 bypass is confirmed CLOSED. New INJECTION / medium in
      the same class, on the other side of the predicate: containsCommandToken
      accepts a '/' when preceded by Character.isLetterOrDigit, and the Hangul
      fillers U+115F / U+1160 / U+3164 are category Lo (OTHER_LETTER) while
      rendering as a blank gap. Verified firsthand: all three report
      isLetterOrDigit=true (getType=5), U+3164 NFKC-folds to U+1160, and
      "Reuters<U+3164>/grant-admin <uuid> approved" is accepted at length
      66 < 80. Nothing upstream strips them (InboundRouter.appendNormalized,
      stripMetadataField and trim() all leave them intact).
      The javadoc's claim that inverting the test "makes every such codepoint —
      present and future — a rejection by default" is therefore FALSE; the
      inversion moved the hole from the reject side to the accept side.
      SECOND failure of the same boundary heuristic, which is the signal that
      a character-category predicate is the wrong shape for this decision:
      both sides of any such partition contain blank-rendering codepoints.
      Round 3 of round_cap 3 already APPROVEd this code, so the fix needs both
      a refine (acceptance item 7 pins the isLetterOrDigit rule by name) and a
      further round allowance.
  - date: 2026-07-19
    reason: round-cap
    resolution: |
      User directive 2026-07-19: "this was just spec edit, just run code
      review again, there is no need to do more. if it pass we can continue,
      if not we can escalate." Resolved as refine: round_cap raised 2 -> 3
      on the user's explicit authorization (the ticket is complexity/risk
      medium, so the documented "high only" guidance for round_cap: 3 was
      deviated from deliberately, not by oversight). The round-3 delta is the
      single reviewer-scoped clause correction in docs/spec/commands.md and
      no code change; the round-2 mvn verify log stays valid because no
      Java/config/DB file is touched after it (M1-272 reuse).
    reviewer_verdict_excerpt: |
      VERDICT: REWORK (round 2 of round_cap 2). SPEC-CONFORMANCE-CHECK: FAIL,
      1 rework item; every other check PASS (scope_drift, test_integrity,
      out_of_scope, negative_space, acceptance), and the round-2 must-shrink
      growth was accepted on the cited redteam-remediation mandate.
      Rework item: docs/spec/commands.md:302-306 claims a name carrying
      "a slash command token ..., control characters, or over-long input is
      discarded in favour of the host-derived default". Control characters
      are STRIPPED by IngestTextNormalizer.stripMetadataField and the rest of
      the name is kept — the diff's own passing test
      displayNameControlCharactersAreStrippedBeforeTheReplyEchoesIt asserts
      the reply still shows "My FeedSource added.", contradicting the spec
      sentence this same diff adds. Reviewer scoped the fix explicitly:
      correct the one clause, change no code (stripping is the shipped,
      tested behaviour that acceptance item 3 requires).
      Round cap reached mechanically; the outstanding work is a one-clause
      doc correction with zero code change.
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (critical=0 high=0 medium=1 low=0).
      INJECTION / medium: containsCommandToken's isWhitespace||isSpaceChar
      boundary test is an enumeration of blank codepoints and is defeated by
      U+2800 BRAILLE PATTERN BLANK, which renders as a blank word gap but is
      category OTHER_SYMBOL — so `<word><U+2800>/grant-admin ...` is stored
      and echoed verbatim, the exact payload the new test asserts can never
      reach the reply. Verified firsthand with a JDK predicate probe before
      escalating. User chose fix-in-branch over defer, because this same diff
      amends security.md/commands.md to declare the instance closed; merging
      as-is would land a spec that is false on the day it merges.
      Resolution: refine to pin a FAIL-CLOSED rule (accept '/' only after a
      letter or digit) as an acceptance item, then re-implement, re-verify,
      review round 2, and run a FRESH red-team pass on the new diff.
  - date: 2026-07-18
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer-raised mid-implementation, no reviewer involved.
      docs/spec/security.md:335-338 cites the /add-source --name echo as a
      KNOWN LIVE instance "tracked as M1-659". This ticket closes that
      instance, so the clause reads as present-tense-open the moment the
      ticket lands, but docs/spec/security.md was outside files_scope.
      User chose refine (option 1) — add security.md to files_scope and
      correct the clause here — over deferring it to a follow-up ticket,
      which would have added a fourth entry to the V3 doc-drift backlog.
      Also folded in: acceptance item 4's "§Sources" anchor pointed at no
      real heading (clarity-pass note); narrowed to "§Discovery".
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-18
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer — deterministic output is
      safe only to the extent it is bot-authored and "interpolates no
      inbound-derived text ... a property the handlers must maintain". This
      same diff amends that section to declare the /add-source --name echo
      "closed by M1-659", and amends commands.md §Discovery to claim a name
      carrying a slash command token is discarded "(tested at a token
      boundary, not merely at the start)".
    gap: |
      containsCommandToken tests the character preceding a '/' with only
      Character.isWhitespace || Character.isSpaceChar, which cover ASCII/
      Unicode whitespace and the Zs/Zl/Zp separators. A codepoint that
      RENDERS as a blank word gap but sits in another category passes both
      predicates and is also absent from IngestTextNormalizer
      .stripMetadataField's removal set. Verified firsthand with a JDK
      probe: U+2800 BRAILLE PATTERN BLANK reports isWhitespace=false,
      isSpaceChar=false, isISOControl=false (getType=28 OTHER_SYMBOL) and
      survives String.trim(); U+180E behaves identically (getType=16
      FORMAT). So `<word><U+2800>/grant-admin ...` is accepted, stored, and
      echoed verbatim — the exact payload the new test
      slashPrefixedDisplayNameIsNotReflectedIntoFreshInsertReply asserts can
      never reach the reply.
    repro: |
      A registered non-probation DM user (or a group admin in an approved
      group, making the reply a broadcast) sends:
        /add-source https://feeds.example.com/f.xml --tags news
          --name "Reuters<U+2800>/grant-admin <attacker-contact-id> approved"
      CommandTokenizer keeps the quoted spaces; acceptableOverride accepts
      (length 39 < 80); the value is stored as source.display_name and
      interpolated into REPLY_ADD_SOURCE_FRESH_INSERT and every later echo
      (/list-sources, /unfollow-source). A bot admin who copy-pastes the
      apparent command line — selecting from the '/', leaving the U+2800
      behind — executes a genuine /grant-admin under is_admin=true.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-07-19
    verdict: CLEAN
    base: 6d99686e803d31e27d9abc8f1325a832b886511e
    head: working tree (round-5 implementation, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-659-2026-07-19-r4.md
    out_of_model_count: 5
    note: |
      Fourth and final audit: CLEAN, 0 findings at every severity. All three
      prior medium INJECTION findings remediated, none recurred. The
      auditor was explicitly told CLEAN was acceptable, so the verdict is
      not a manufactured-finding artifact. redteam_findings below is
      retained rather than reset to [] — the three findings were real, were
      fixed in-branch, and the history is the ticket's audit trail.
  - date: 2026-07-19
    verdict: FINDINGS
    base: 6d99686e803d31e27d9abc8f1325a832b886511e
    head: working tree (round-4 approved implementation, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-659-2026-07-19-r3.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      Third audit. Both prior bypasses confirmed CLOSED. New medium finding
      against the absolute rule's own load-bearing claim (ASCII-'/' suffices
      because the router NFKC-normalizes first): InboundRouter.normalize
      carves out fenced code blocks and normalizes PER LINE, while routing is
      decided on the WHOLE body's first character, so a command on line 1 can
      carry an un-normalized fenced payload on line 3. This FALSIFIES the
      round-4 review's explicit dismissal of the fence case. Verified
      firsthand: the payload is accepted at 68 < 80 chars today, and the
      pasted line folds to a real '/' and dispatches.
  - date: 2026-07-19
    verdict: FINDINGS
    base: 6d99686e803d31e27d9abc8f1325a832b886511e
    head: working tree (round-3 approved implementation, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-659-2026-07-19.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Re-audit of the remediated diff (the round-1 audit covered code the
      isLetterOrDigit fix replaced). Confirms the U+2800 bypass is closed,
      and finds a NEW instance of the same class on the accept side:
      Character.isLetterOrDigit is true for the Hangul fillers U+115F /
      U+1160 / U+3164, which render as a blank gap, so
      "Reuters<U+3164>/grant-admin ..." is accepted. Verified firsthand
      with a JDK probe (all three getType=5 OTHER_LETTER; U+3164 NFKC-folds
      to U+1160; payload length 66 < 80). Two out-of-model items are
      advisory (human copy-paste residual; homoglyph slashes yield nothing
      executable); the third flags that defaultDisplayName's host-fallback
      path does not share the override's constraint.
  - date: 2026-07-18
    verdict: FINDINGS
    base: 6d99686e803d31e27d9abc8f1325a832b886511e
    head: working tree (uncommitted implementation on branch)
    verdict_file: docs/plan/m1/redteam/M1-659-2026-07-18.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Audited the working-tree-vs-fork-point diff rather than main...branch:
      at the redteam gate the branch carried only the ticket-refine commit,
      so the documented branch form would have handed the adversary a diff
      with no implementation in it (the known /redteam --in-progress gap).
      The finding was verified firsthand with a JDK character-predicate
      probe before escalation. Both out-of-model items are advisory: the
      human copy-paste step is explicitly in-model per security.md, and
      homoglyph slashes (U+2215, U+2044) survive the check but do not parse
      as commands at intake, so they yield nothing executable.
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-659: Stop /add-source success reply reflecting raw --name

## Context

The M1-658 r2 red-team audit found a LIVE reflection of unvalidated inbound
text in a success (`reply.*`) template — the surface M1-658's error-only
guard deliberately does not cover. `AddSourceCommandHandler:210-211`
interpolates `result.displayName()` into `REPLY_ADD_SOURCE_FRESH_INSERT`;
`SourceUpsertService.defaultDisplayName` returns the `--name` override
verbatim (`override.orElseGet(...)`, no charset/length constraint); and
`CommandTokenizer` unwraps double-quote pairs, so
`--name "/grant-admin <uuid> approved"` carries a fully parameterized admin
command string, spaces and all. In an approved group a **group admin**
(below bot admin, admitted by the `ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY` gate)
can send this; the deterministic success reply is delivered to group scope
and broadcast to every member, so a bot admin who copy-pastes the
plausible-looking `/grant-admin …` line executes it — the exact
deterministic-reply social-engineering surface `security.md` §LLM output
sanitizer leaves unfiltered and M1-647/M1-656 closed for error templates.
This is the same defect class, on the adjacent reply surface. Filed as a
remediation of M1-658 (whose scope decision disclosed this blind spot) but
fixed at the value's source, not by widening the guard.

## Acceptance

See the frontmatter. The test proves the injected `--name` token is not
reflected into the FRESH_INSERT reply; the fix constrains the display name
at its source (validate/normalize at parse-or-upsert, or drop the
interpolation), not by output-side filtering; legitimate names still work;
the spec records the closure.

## Out-of-scope

The M1-658 error-census (this is a reply template), the other more-constrained
reply echoes, and the tokenizer's quote support. See the frontmatter for why.

## Notes

- Two viable fixes, developer's call (record which in the commit): (a) the
  M1-656 model — do not interpolate the free-form name into the success reply
  (echo the source id / URL host instead); (b) the SummaryArgs model —
  constrain the display name at `defaultDisplayName` (bound length, strip
  control chars and a leading slash) so the stored and echoed value is
  provably safe. (b) preserves the feature (custom display names) and is
  likely preferable, but (a) is simpler if the name need not appear in the
  reply.
- The stored display name is also shown by `/list-sources`
  (`REPLY_LIST_SOURCES_LINE[0] = row.displayName`), so constraining at the
  source (option b) closes both surfaces at once; dropping only the
  FRESH_INSERT interpolation (option a) would leave the /list-sources echo.
  Prefer (b) unless there is a reason not to.
- This is a deliberate follow-up, not scope-creep on M1-658: M1-658 guards
  the error surface and honestly discloses the reply surface as out of view;
  this ticket fixes the one live reply instance that disclosure names.
