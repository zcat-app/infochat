---
id: M1-658
title: "Guard: no inbound-text reflection in outbound templates"
status: pending
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
    the build when a source change introduces a template-interpolation site
    that passes inbound-derived text into outbound text without a recorded
    justification, and passes on the current tree. "Interpolation site" =
    any production code in infochat-provider that supplies arguments to a
    bundle template placeholder, across ALL THREE forms enumerated by the
    2026-07-18 handoff §6: (1) inline MessageFormat.format over a
    bundleLoader.get value, (2) the Failure/interpolationArgs pattern
    (AddSourceArgs, SummaryArgs), (3) the format(KEY, ...) helper form. The
    guard's census enumerates sites mechanically (never a hand-listed
    subset) and fails on any site it cannot classify — new or changed
    sites are unclassified until consciously recorded.
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
    property.
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
