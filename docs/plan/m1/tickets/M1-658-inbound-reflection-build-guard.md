---
id: M1-658
title: "Guard: no inbound-text reflection in outbound templates"
status: done
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-656
  - M1-657
files_budget: 8
complexity: high
risk: low
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Production code changes. The guard observes; it never refactors. No
    handler, Args class, bundle template, or BundleLoader/BundleKeys change
    is authorized. If enforcement genuinely cannot be built without a
    production-side change (e.g. a wrapper type, a helper chokepoint),
    escalate — that is the §3a provenance-discipline project the 2026-07-18
    handoff explicitly deferred, not this ticket.
  - >-
    The bot-admin-only raw-echo surfaces (error.audit.unknown_action,
    error.quarantine.invalid_id, error.invite.unknown_adapter,
    error.group_not_found, error.recover_pool.not_found, the two
    error.invite.bot_contact_* keys, and any sibling the census finds).
    They stay as spec-documented known-unfixed; the guard records them in
    its baseline with their gate citation, it does not fix them.
  - >-
    LlmOutputSanitizer runtime behaviour, CLOSED_LIST content, and where
    the sanitizer runs. Extending the sanitizer over deterministic output
    was evaluated and rejected in the 2026-07-18 handoff §3.1 (it breaks
    /help and the registration welcome — six CLOSED_LIST commands are
    legitimate USER-tier /help content). Only the exemption's WORDING in
    security.md changes, per acceptance.
  - >-
    cs.properties template-by-template analysis. D43 bilateral-keyset
    parity means the en/cs keysets match and BundleLoaderTest already
    enforces it; the guard's census may read en.properties as the single
    source of placeholder shapes.
  - >-
    The M1-654 tool-allowlist spec-parity guard (separate concern, in
    flight in a parallel worktree). Do not touch its files.
acceptance:
  - >-
    A new test class InboundReflectionGuardTest (infochat-provider) fails
    the build when a source change introduces a FRIENDLY-ERROR
    template-interpolation site that passes inbound-derived text into
    outbound text without a recorded justification, and passes on the
    current tree. SCOPE (narrowed by user decision 2026-07-18, see the
    revision entry): the census covers only interpolation sites whose
    bundle key is an error key — the BundleKeys constant matches ^ERROR_ or
    the string-literal key matches ^"error\\. — because "friendly errors
    must not reflect inbound text" is the exact threat class (all six
    historical M1-647/M1-656 regressions were error templates) and the
    error/reply key split is the cheap proxy that keeps the baseline small
    and trustworthy. "Interpolation site" = any production code in
    infochat-provider that supplies arguments to an error-keyed bundle
    template placeholder, across ALL THREE forms enumerated by the
    2026-07-18 handoff §6: (1) inline MessageFormat.format over a
    bundleLoader.get value, (2) the Failure/interpolationArgs pattern
    (AddSourceArgs, SummaryArgs), (3) the format(KEY, ...) helper form. The
    guard's census enumerates error-keyed sites mechanically (never a
    hand-listed subset) and fails on any it cannot classify — new or
    changed sites are unclassified until consciously recorded.
  - >-
    The guard STATES ITS OWN BOUNDARY in the test-class Javadoc: it proves
    only that below-bot-admin friendly-ERROR templates do not reflect
    inbound text; it does NOT prove success/confirmation/reply templates
    safe (a future reflection introduced on a non-error-keyed template is
    out of its view), and the complete provenance-tracking fix (a taint
    wrapper type for inbound strings — the handoff §3a project) remains
    deferred. This is required so a green guard is never mistaken for
    "reflection is now impossible" — the exact false confidence the
    original security.md exemption carried.
  - >-
    InboundReflectionGuardTest includes a self-check test method proving
    non-vacuity: a synthetic fixture site carrying inbound-derived text
    must be flagged by the classifier; the method fails if the detector
    goes blind. (This is the enumeration-not-inspection lesson of M1-647 /
    M1-656: five consecutive wrong estimates came from unverified sweeps.)
  - >-
    Sites judged acceptable are recorded in an explicit in-repo baseline,
    one entry per site, each carrying a one-line justification of one of
    three kinds: provably-constrained-at-parse (SummaryArgs model),
    bot-authored value (catalogue / vocabulary / registry / typed
    non-String), or bot-admin-only reachable (with the gate's file:line).
    The guard fails on a baseline entry that matches no current site (no
    dead entries), so the baseline cannot rot into an unchecked allowlist.
  - >-
    A baseline entry pins the site tightly enough that changing WHAT is
    interpolated at an existing site (the AddSourceArgs regression shape —
    same template key, new raw-token argument) invalidates the entry and
    fails the guard, forcing a conscious re-record.
  - >-
    docs/spec/security.md §LLM output sanitizer: the deterministic-output
    exemption states the real condition — deterministic command output is
    exempt because and only while it interpolates no inbound-derived text
    (parse-validated echoes per commands.md §Discovery included) — instead
    of the incidental "that text never passes through an LLM" rationale.
    Sanitizer behaviour is unchanged; this is wording only.
  - >-
    docs/spec/commands.md §Discovery gains one sentence pointing at the
    guard as the mechanical enforcement of the existing "any friendly
    error reachable below bot admin must not reflect inbound text"
    property, and naming its boundary (error templates only; success/reply
    paths and the taint-type fix are out of its scope).
  - >-
    No production source file changes. The diff is the guard test (plus
    its baseline resource if stored separately) and the two spec files.
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/InboundReflectionGuardTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/commands.md §Discovery
decision_refs:
  - D43
outline_file: target/m1-tick-outline-M1-658.md
revisions:
  - date: 2026-07-18
    reason: >-
      Scope narrowed by user decision after the census was built and run
      (mid-implementation, in-branch). The census over the whole provider
      (223 interpolation sites) mechanically CONFIRMED no below-admin error
      reflects a raw inbound token — M1-656/M1-657 closed the real holes.
      That surfaced a design reality: a syntactic guard cannot judge
      provenance mechanically (a DB accessor and a raw inbound token are
      syntactically identical), so an all-223-sites guard needs a ~150-entry
      hand-annotated baseline that developers rubber-stamp on every routine
      reply-copy edit — reviving the exact hand-maintained-allowlist failure
      the handoff §3.1 warned against. Falsification of the error-scope
      recommendation (shared with the user in chat) found error-scope leaves
      a real blind spot (a future reflection on a non-error-keyed template),
      but that class has never occurred, is structurally less likely
      (success paths echo post-validation values), and error-scope covers
      6/6 of the historical regressions — all of which were error templates.
      Resolution: scope the census to error-keyed sites (^ERROR_ constants /
      ^"error." literals) and REQUIRE the guard to document its own boundary
      in the class Javadoc, so a green guard is never mistaken for a
      completeness proof. Acceptance item 1 narrowed; one acceptance item
      (self-documented boundary) added; the commands.md pointer item extended
      to name the boundary. files_budget, files_scope, out_of_scope,
      complexity, round_cap UNCHANGED.
    prior_values: |
      acceptance item 1 covered "any template-interpolation site" (all 223
      reply+error sites); no self-documented-boundary acceptance item
      existed; the commands.md pointer item did not mention the boundary.
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
      added: 747
      removed: 23
  - round: 2
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
      added: 826
      removed: 25
    note: >-
      Round-2 rework addresses the round-1 redteam finding (classifier
      prefix-match hole). Must-shrink: files held equal at 6; the
      added/removed growth is the user-accepted in-branch redteam
      remediation recorded in redteam_findings, a citable mandate.
  - round: 3
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 1025
      removed: 21
    note: >-
      Round-3 rework addresses the round-2 redteam finding (medium): the
      security.md/commands.md spec honesty fix (deterministic-output
      exemption reframed as a residual risk naming the live reply instance)
      plus filing M1-659 for the live /add-source --name reflection. Docs-only
      delta; testable surface byte-identical to the r2 green log (M1-272 inert
      diff). Must-shrink: removed-lines shrank 25->21, so growth is not along
      all three dimensions (convergent); the added M1-659 file is the
      user-accepted redteam-remediation disposition (citable mandate).
overrides: []
aborted_attempts: []
reopens: []
redteam_audits:
  - date: 2026-07-18
    verdict: FINDINGS
    base: 605920f8
    head: working tree on m1/M1-658 (r1)
    verdict_file: docs/plan/m1/redteam/M1-658-2026-07-18-r1.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      r1: one LOW classifier prefix-match hole (String.valueOf(rawToken)).
      Fixed in round 2.
  - date: 2026-07-18
    verdict: FINDINGS
    base: 605920f8
    head: working tree on m1/M1-658 (r2 re-audit)
    verdict_file: docs/plan/m1/redteam/M1-658-2026-07-18-r2.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      r2: r1 hole confirmed CLOSED, all 50 baseline justifications honest.
      One MEDIUM: spec overclaim + a live reply-template reflection
      (/add-source --name). Disposed: spec honesty fixed round 3; live fix
      filed as M1-659.
  - date: 2026-07-18
    verdict: CLEAN
    base: 605920f8
    head: working tree on m1/M1-658 (r3 re-audit)
    verdict_file: docs/plan/m1/redteam/M1-658-2026-07-18-r3.md
    out_of_model_count: 2
    note: |
      r3: spec wording now honest (residual-risk framing, live instance
      named + tracked as M1-659); reply-surface fix correctly dispositioned
      to M1-659 per the error-only scope. CLEAN.
redteam_findings:
  - date: 2026-07-18
    category: INJECTION
    severity: low
    promise: |
      commands.md §Discovery / security.md §LLM output sanitizer: the guard
      mechanically enforces "any friendly error reachable below bot admin must
      not reflect inbound text".
    gap: |
      The auto-classifier's NUMERIC_CONV / JOIN_CALL shapes used .find() on
      ^-anchored patterns, degrading to a prefix match: String.valueOf(rawToken)
      (String.valueOf returns the argument's toString unchanged) and
      concatenations like Integer.toString(x) + rawToken were classified
      trivially-safe and dropped before the baseline check. A future
      below-admin error echoing String.valueOf(rawUserToken()) would pass green.
      The live tree was clean (all baseline residue verified honest); this was a
      future-regression blind spot, not a live reflection.
    repro: |
      A new handler returns MessageFormat.format(bundleLoader.get(ERROR_FOO,
      lang), String.valueOf(args.rawUserToken())); mvn verify stays green.
    suggested_fix_class: trust-boundary-tightening
    resolution: |
      Fixed in-branch (round 2). Tightened isTriviallySafe to only the shapes
      safe regardless of argument value/type: single string/int literal,
      .size(), and local static-final-String constants. Removed String.valueOf
      / String.join / sortedJoin / commaList from the auto-classifier (and with
      them the .find() prefix hazard); the 16 error-keyed sites using those
      shapes moved into the baseline with explicit bot-authored justifications
      (34 -> 50 entries). Class-Javadoc boundary extended to disclose the
      out-of-model concatenation / String.format forms as outside the census.
  - date: 2026-07-18
    category: INJECTION
    severity: medium
    promise: |
      security.md §LLM output sanitizer (as reworded by this diff) claimed
      deterministic command output "interpolates no inbound-derived text" and
      that "M1-647/M1-656 removed those echoes" — an affirmative property over
      ALL deterministic output, not just errors.
    gap: |
      A LIVE reflection exists in a reply.* (success) template — the surface
      the guard's error-only scope discloses as out of view.
      AddSourceCommandHandler:210-211 interpolates result.displayName() into
      REPLY_ADD_SOURCE_FRESH_INSERT; SourceUpsertService.defaultDisplayName
      returns the --name override verbatim (no charset/length constraint); and
      CommandTokenizer unwraps double-quotes, so `--name "/grant-admin <uuid>
      approved"` carries a parameterized admin command string. Reachable by a
      GROUP admin (below bot admin) and broadcast to the group. The security.md
      wording this diff added is contradicted by this shipping echo.
    repro: |
      In an approved group a group admin sends: /add-source <url> --tags news
      --name "/grant-admin <uuid> approved"; the FRESH_INSERT success reply is
      broadcast to every member with the verbatim string.
    suggested_fix_class: input-sanitization
    resolution: |
      Two parts, both handled without widening the error-only guard.
      (1) Spec honesty (fixed in-branch, round 3): the security.md reword no
      longer claims deterministic output interpolates no inbound text; it now
      states the exemption is a RESIDUAL RISK on non-error output, names the
      error surface as guarded and the reply surface as not-yet-guaranteed, and
      cites the live instance as tracked by M1-659. commands.md §Discovery
      likewise names the live reply instance instead of glossing the blind
      spot. (2) The live vulnerability itself is a reply.* template outside
      this ticket's deliberate error-only scope; filed as M1-659
      (remediates: M1-658) to constrain the echoed display name at its source,
      exactly as the M1-656 r2 finding was dispositioned to M1-657. The guard
      code is unchanged; the r2 audit CONFIRMED the r1 hole closed, all 50
      baseline justifications honest, and no new gap from the tightening.
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings:
    - >-
      The Context/Notes cite the uncommitted 2026-07-18 handoff
      (HANDOFF-output-reflection-20260718.md) for section-numbered
      rationale (§3a/§3b/§3c/§3.1/§6). Non-blocking: the acceptance
      criteria already inline every load-bearing fact (the three
      interpolation forms, the CLOSED_LIST-vs-/help measurement), so the
      implementer needs no out-of-repo lookup.
  blockers: []
---

# M1-658: Guard: no inbound-text reflection in outbound templates

## Context

M1-647 and M1-656 fixed eight reflection surfaces across six audit rounds,
and every audit round found another instance the previous estimate missed —
five wrong estimates in a row, each produced by inspection rather than
enumeration. The 2026-07-18 handoff's conclusion: the defect is not in any
one handler, it is the absence of a rule that holds automatically; per-site
fixing cannot converge because nothing prevents the next handler from
reintroducing it (the r2 audit then proved the point by finding
/approve-group, fixed as M1-657). This ticket is the handoff's recommended
global fix — §3c as the rule (parse-boundary validation, already stated in
commands.md §Discovery by M1-656) with §3b as its enforcement: a build-time
guard that mechanically enumerates every template-interpolation site and
fails on any site without a recorded provenance judgment. It also rewords
the security.md sanitizer exemption to state the condition the guard
enforces, closing the spec question three audits raised as out-of-model.

## Acceptance

Mirrors the YAML: a mechanically-enumerating guard test
(`InboundReflectionGuardTest`) covering all three interpolation forms,
failing on unclassified or changed sites; a non-vacuity self-check; a
no-dead-entries baseline with per-site justifications of exactly three
kinds; the security.md exemption reworded to the real condition; a
commands.md pointer sentence; test+docs-only diff; `mvn verify` green.

## Out-of-scope

No production code. No fixing the bot-admin-only echo sites (they enter the
baseline with gate citations). No sanitizer-behaviour change. No cs-side
template analysis beyond what D43 parity already guarantees. No contact
with M1-654's files. The complexity:high plan-writer owns the mechanism
design (static census of the three call forms vs. other approaches) inside
these bounds; if the chosen mechanism cannot satisfy the
changed-site-invalidates-entry acceptance item without production
refactoring, that is an escalate, not a scope expansion.

## Notes

- The three interpolation forms and their counts (110 MessageFormat.format
  sites in the provider as of 2026-07-18) are in the handoff §6; AddSourceArgs
  escaped three sweeps because two forms were unswept. The census MUST be
  derived from the sources at test runtime, not a frozen list.
- Candidate mechanism (non-binding): flatten each production source file,
  locate the three call forms with paren-balanced extraction, key each site
  as (file, template-key or BundleKeys constant, arg-index,
  normalized-argument-expression); classify by expression form (typed
  non-String locals like UUID/int, *.commaList(), String.join over
  constants, BundleKeys constants) and fall back to the baseline for
  everything else. The normalized-argument-expression component is what
  makes a changed site invalidate its entry.
- The tier judgment ("bot-admin-only reachable") is a human judgment the
  baseline RECORDS with a gate citation; the guard pins the site, not the
  reachability analysis. The r2 audit (docs/plan/m1/redteam/
  M1-656-2026-07-18-r2.md) verified each current admin-tier site's gate
  ordering — those citations seed the baseline.
- /summary's parse-validated echo (SummaryArgs:94-100) is the
  provably-constrained precedent; its baseline entry cites the validation
  site.
- Why not extend LlmOutputSanitizer instead: measured in the handoff §3.1 —
  CLOSED_LIST ∩ USER-tier /help = 6 commands, so whole-message sanitization
  deletes six lines from every ordinary user's /help and damages
  REPLY_WELCOME_DM_FRESH via CommandPermissions.ALLOWED interpolation.
  Argument-vs-template also fails (M1-647's suggestion list IS an argument
  and legitimately contains CLOSED_LIST names). Provenance is the only
  distinction that works, and the baseline is where provenance is recorded.
