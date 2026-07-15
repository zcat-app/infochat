---
id: M1-632
title: "Default bare /invite create to --open (D60)"
status: done
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/spec/security.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
decomposed_from: M1-631
decision_refs:
  - D44
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/security.md §Invite-code registration
spec_amend_for:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/security.md §Invite-code registration
out_of_scope:
  - >-
    The --contact disposition and the in-band contactId-sourcing surface —
    those are M1-633 (blocked on this ticket). This ticket keeps a VALID
    `--contact <id>` working exactly as it is today; it only changes what a
    truly-bare `/invite create` does and adds explicit rejection of malformed
    create input.
  - >-
    The adapter-resolution default and the empty-backtick error copy (M1-626,
    merged) — do not re-touch the --adapter inference block. The --open confirm
    gate and confirm-fork (M1-051), the per-adapter open cap, and the
    audit-before-effect transaction shape are UNCHANGED. This ticket redirects
    the truly-bare branch into the existing --open path and adds a
    bare-vs-malformed discrimination at the CreateArgs.parse layer (tracking
    unconsumed tokens) plus a new malformed-input error reply — it does not
    alter the --open machinery itself or the confirm fork.
acceptance:
  - >-
    D60 is recorded in docs/spec/decisions.md capturing BOTH operator sub-decisions
    (2026-07-15): (1) a bare `/invite create` — meaning NO recognized flag (empty
    remainder, or only `--adapter <name>`) — defaults to --open; the confirm gate
    and per-adapter open cap remain the backstop for the broader blast radius.
    Malformed create input (an unrecognized token, a value-less `--contact`, or an
    unexpected bare argument) is NOT treated as bare: it returns an explicit error
    and creates/arms nothing (redteam M1-632 medium finding, 2026-07-15). (2)
    --contact is KEPT (not retired) and gains an in-band contactId-sourcing
    surface, delivered separately by M1-633.
  - >-
    docs/spec/commands.md §Admin (bot admin) is amended so the line currently at
    commands.md:1103 ("`--contact` and `--open` are mutually exclusive. Providing
    neither returns a hint listing both flags and their trade-offs; no invite is
    created.") instead states that providing neither *recognized flag* defaults to
    --open (still confirm-gated); malformed/unrecognized create input returns an
    explicit error and creates nothing; --contact and --open remain mutually
    exclusive.
  - >-
    InviteCommandHandler routes a TRULY-bare `/invite create` (no --contact, no
    --open, no unrecognized token) through the same --open path as an explicit
    `--open`: adapter resolution (single-adapter inference / multi-adapter friendly
    error — M1-626) and the M1-051 confirm gate both fire exactly as they do for
    `--open`. A create whose remainder carries an unrecognized token, a value-less
    `--contact`, or an unexpected bare argument instead returns a new explicit
    error (`error.invite.create_malformed`, en + cs, plus the
    `ERROR_INVITE_CREATE_MALFORMED` BundleKeys constant) and writes NO audit row
    and arms NO pending confirm — restoring the fail-safe the retired hint gave
    (redteam medium finding). To distinguish bare from malformed, `CreateArgs.parse`
    tracks whether any token went unconsumed. The now-unreachable
    error.invite.missing_flag branch and its bundle keys (en + cs) and the
    ERROR_INVITE_MISSING_FLAG BundleKeys constant are removed as orphans this
    change creates.
  - >-
    docs/spec/security.md §Invite-code registration (the authoritative document for
    the invite trust path) and the parallel docs/design/03-commands.md are amended
    to match the shipped behavior: a bare `/invite create` defaults to --open
    (confirm-gated) and malformed create input returns an explicit error and mints
    nothing. This closes the threat-model-vs-code divergence the redteam low finding
    flagged (the retired "providing neither returns a hint; no code created"
    promise).
  - >-
    InviteCommandHandlerTest pins: a truly-bare `/invite create` on a single-adapter
    deployment returns the --open confirm prompt (not a hint) and matches the
    explicit-`--open` first-call reply; a malformed create (e.g. a value-less
    `--contact`, or a typo'd `--contcat <id>`) returns error.invite.create_malformed,
    writes NO INVITE_CREATE_INTENT audit row, and arms NO pending confirm; an
    explicit `--contact <id>` still creates a CONTACT_BOUND invite with no confirm.
    mvn verify is green.
test_plan:
  adds:
    - >-
      InviteCommandHandlerTest malformed-create scenario — a create with an
      unrecognized token / value-less `--contact` returns
      error.invite.create_malformed, writes no INVITE_CREATE_INTENT audit row, and
      arms no pending confirm (redteam medium-finding regression guard).
  preserves:
    - all tests currently green on main
reviews:
  - round: 1
    date: 2026-07-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 45
      removed: 25
  - round: 2
    date: 2026-07-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 557
      removed: 96
    note: >-
      Round-2 remediation of the two redteam AUTH-BYPASS findings. Must-shrink
      growth vs round 1 (8->12 files, +45->+557, -25->-96) reviewer-validated as
      authorized by the user-accepted in-branch redteam remediation (cited in
      escalations/revisions + docs/plan/m1/redteam/M1-632-2026-07-15.md); a large
      share of the added lines is in the lifecycle-exempt ticket/redteam files.
escalations:
  - date: 2026-07-15
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (pre-commit --in-progress audit, after
      round-1 review APPROVE). critical=0 high=0 medium=1 low=1;
      out-of-model=0.
      - AUTH-BYPASS / medium: "The D60 normalization keys on the PARSE
        RESULT, not on the remainder actually being bare ... every
        malformed attempt at a STRICT invite — `--contcat <id>` (flag
        typo), `--adapter simplex --contact` (id lost to a paste failure)
        ... is defaulted into the OPEN flow" — full verbatim entry at
        redteam_findings[0].
      - AUTH-BYPASS / low: "does NOT amend docs/spec/security.md
        §Invite-code registration. The threat model therefore still
        promises a fail-safe ... that the shipped code no longer
        delivers" — full verbatim entry at redteam_findings[1].
      Durable record: docs/plan/m1/redteam/M1-632-2026-07-15.md.
revisions:
  - date: 2026-07-15
    reason: >-
      redteam-finding refine (round 1 rework). Restrict the D60 default to a
      TRULY-bare remainder and reject malformed create input with an explicit
      error (medium finding); amend docs/spec/security.md + docs/design/03-commands.md
      to match the shipped behavior (low finding). Pre-refine frontmatter snapshot
      below; the full verbatim pre-refine ticket is the parent of the refine commit
      on the branch.
    snapshot:
      files_budget: 8
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/main/resources/bundles/cs.properties
        - docs/spec/commands.md
        - docs/spec/decisions.md
      acceptance_count: 4
      out_of_scope_count: 2
      note: >-
        Round-1 review APPROVEd this pre-refine scope (8 files, +45/-25) before the
        redteam gate surfaced the two AUTH-BYPASS findings.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-15
    category: AUTH-BYPASS
    severity: medium
    promise: |
      security.md §Invite-code registration: "Providing neither flag returns
      a hint listing both options; no code is created." (security.md:757-758),
      and "`--contact <id>` — strict invite, bound to a specific (contact_id,
      adapter) pair. No confirmation required; risk is bounded to one
      identity." (security.md:752-753), versus "`--open` ... Requires confirm
      (broader blast radius)" (security.md:754-756) and "Open codes have the
      broadest blast radius (any unknown contact on the adapter can consume
      them)" (security.md:822-824). The hint was the documented fail-safe that
      kept a malformed strict-invite attempt from producing anything.
    gap: |
      The D60 normalization keys on the PARSE RESULT, not on the remainder
      actually being bare: InviteCommandHandler.java:326-328 rewrites
      `args.contact == null && !args.open` into `--open`. CreateArgs.parse
      (InviteCommandHandler.java:830-850) silently skips unknown tokens (the
      `else { i++; }` branch at :847-849) and silently drops a value-less
      trailing `--contact` (the `i + 1 < tokens.size()` guard at :838 fails
      and the token falls into the skip branch). Therefore every malformed
      attempt at a STRICT invite — `--contcat <id>` (flag typo), `--adapter
      simplex --contact` (id lost to a paste failure), or a bare pasted
      contact id with no flag — now parses to contact=null/open=false and is
      defaulted into the OPEN flow: an INVITE_CREATE_INTENT audit row is
      written (:374-381), a pending open-confirm is armed (:382-383), and the
      OPEN prompt is returned. Pre-diff, all of these inputs returned the
      retired error.invite.missing_flag hint with no state change (removed
      hunk; the bundle values are deleted from en.properties and
      cs.properties, so the fail-safe reply no longer exists anywhere). This
      exceeds even the new commands.md wording ("Providing neither defaults
      to --open", commands.md:1103-1106) — here a flag WAS provided,
      malformed, and the system converts the admin's explicitly-signalled
      bounded-risk intent into the unbounded-identity invite class.
      Aggravator (pre-existing M1-051 code, interaction widened by this
      diff): the confirm fork accepts ANY create body ending in " confirm"
      (InviteCommandHandler.java:299), so once a malformed create has armed
      the pending open intent, a follow-up such as `/invite create --contact
      <id> confirm` ignores its own flags, pops the pending, and mints the
      OPEN code (:304-310 → createOpen). Compensating controls verified
      intact: admin gate (:204-207), the confirm prompt names OPEN and the
      adapter, per-adapter open cap at effect time (:471-475),
      audit-before-effect (:481), TTL, single-use.
    repro: |
      1) Single-adapter deployment (simplex). A bot admin intends a strict
      invite and sends `/invite create --adapter simplex --contact` with the
      id lost to a paste/line-wrap failure (or `--contcat <id>`). 2) Instead
      of the pre-diff fail-safe hint, the handler arms a pending OPEN intent
      and prompts "About to mint an OPEN invite for adapter `simplex`...".
      3) The admin — whose intended --contact flow is documented as
      confirm-free, so they are mid-task and prompt-habituated — replies
      `/invite create confirm`; createOpen mints a single-use OPEN_ADAPTER
      code shown once. 4) The admin transmits the code to the intended
      recipient over an out-of-band channel; any adversary who observes it
      first (channel interceptor, over-paste into a group) presents it as an
      unknown contact on that adapter and passes the DM registration gate
      (authorization step 2), consuming the code — registration the strict
      class would have bound to one identity. Pre-diff the same inbound at
      step 1 produced the hint and created nothing, so the chain was
      unreachable. The system shouldn't allow it because the threat model
      promises malformed/flagless issuance input is inert and strict-invite
      risk stays bounded to one identity.
    suggested_fix_class: input-sanitization
  - date: 2026-07-15
    category: AUTH-BYPASS
    severity: low
    promise: |
      security.md §Invite-code registration: "A bot admin issues `/invite
      create --adapter <name>` with exactly one of two mutually exclusive
      flags" (security.md:750) and "Providing neither flag returns a hint
      listing both options; no code is created." (security.md:757-758).
      security.md declares itself authoritative for this surface:
      "security.md is the source of truth for the trust path. When
      architecture.md or commands.md mention authorization, ban handling, or
      quarantine, this file is the document that constrains them."
      (security.md:7-10).
    gap: |
      The diff retires the hint behavior and defaults bare create to --open
      (InviteCommandHandler.java:326-328; ERROR_INVITE_MISSING_FLAG removed
      from BundleKeys.java and both bundles), amending
      docs/spec/commands.md:1103-1106 and docs/spec/decisions.md:123 (D60
      explicitly "supersedes the D44 clause 'Providing neither flag returns
      a hint; ... no code is created'") — but does NOT amend
      docs/spec/security.md §Invite-code registration. The threat model
      therefore still promises a fail-safe (flagless input → hint, no code,
      zero flags never mint) that the shipped code no longer delivers: a
      bare `/invite create` now leads to a mintable OPEN invite after
      confirm. commands.md now contradicts the document that by its own
      terms constrains it.
    repro: |
      No direct adversary trigger; the gap is the divergence itself. An
      operator, auditor, or later implementer reading security.md
      §Invite-code registration models ambiguous `/invite create` input as
      inert and calibrates admin training, monitoring, or a future
      reimplementation to that promise; the live system instead writes an
      INVITE_CREATE_INTENT row, arms a confirmable OPEN mint, and — one
      reflexive confirm later — issues the broadest-blast-radius credential
      class. Threat-model text and delivered trust path must not diverge on
      the DM authentication gate's issuance rules.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-15
    verdict: FINDINGS
    base: main (87e49737)
    head: m1/M1-632-default-bare-invite-create-to working tree (uncommitted; branch tip == main)
    verdict_file: docs/plan/m1/redteam/M1-632-2026-07-15.md
    findings_count: 2
    out_of_model_count: 0
    note: |
      Pre-commit --in-progress audit after round-1 review APPROVE. Medium:
      D60 defaulting keys on the parse result, so malformed --contact
      attempts (flag typo, value-less --contact) are normalized into the
      OPEN flow instead of failing safe. Low: security.md §Invite-code
      registration (authoritative for this surface, outside files_scope)
      still promises the retired missing-flag hint — same divergence the
      developer flagged at the implement stop (also stale:
      docs/design/03-commands.md:1267). Resolved: user chose refine
      (2026-07-15); both findings remediated in-branch (round-2 impl) and
      re-audited CLEAN — see the second redteam_audits entry.
  - date: 2026-07-15
    verdict: CLEAN
    base: main (87e49737) — fork point
    head: m1/M1-632 working tree (round-2 remediation; refine commit 5cd62220 + uncommitted impl)
    verdict_file: docs/plan/m1/redteam/M1-632-2026-07-15-remediation.md
    findings_count: 0
    out_of_model_count: 1
    note: |
      Re-audit of the REMEDIATED code (the round-1 audit ran on the pre-fix
      diff, so the shipped malformed-gate + parser change had never faced
      adversarial review). CLEAN — both prior AUTH-BYPASS findings closed, no
      new adversary-reachable gap. One out-of-model item: empty-equals
      `--contact=` mints a dead, un-consumable CONTACT_BOUND invite bound to ""
      (non-exploitable, safe-direction, pre-existing parse behavior; surfaced
      only because this diff's new spec wording can be read to cover it).
      Advisory follow-up recommended (extend the malformed gate to
      `--contact=`/`--adapter=` empty-equals + reject empty expected_contact_id
      at issuance); not filed, not blocking.
clarity_check:
  date: 2026-07-15
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-632: Default bare /invite create to --open (D60)

## Context

Decomposed from M1-631 (operator decision-gate resolved 2026-07-15, isolated
live test). `--open` is the only practically-usable `/invite create` path for
onboarding a brand-new person — who has no `contactId` yet — yet a bare
`/invite create` currently returns a two-flag trade-off hint and creates
nothing (`commands.md:1103`). The operator decided a bare `/invite create`
should default to `--open`, with the existing confirm gate and per-adapter
open cap as the backstop for the broader blast radius. This ticket carries
that half of the resolution (default-flag change + the D60 record); the
`--contact` usability half is M1-633, which blocks on this ticket landing D60.

## Acceptance

**Decision record.** D60 is added to `docs/spec/decisions.md` documenting both
sub-decisions (default-to-open; keep-and-fix `--contact`), since the operator
settled them together and M1-633 references D60. The default-to-open sub-decision
applies to a *truly bare* create only; malformed create input is rejected (see
Behavior).

**Spec.** `docs/spec/commands.md` §Admin (bot admin) is amended so the
"providing neither" line (`commands.md:1103`) reflects the default-to-open
behavior and the malformed-input rejection. `docs/spec/security.md` §Invite-code
registration — the authoritative document for the invite trust path — and the
parallel `docs/design/03-commands.md` are amended to match (they still promise
the retired "providing neither returns a hint; no code created" fail-safe; the
redteam low finding flagged the divergence). `--contact` and `--open` stay
mutually exclusive.

**Behavior.** A *truly bare* `/invite create` — no `--contact`, no `--open`, and
no unrecognized token (empty remainder, or only `--adapter <name>`) — is treated
as `--open`: it runs the same adapter-resolution (single-adapter inference,
multi-adapter friendly error — M1-626) and the same M1-051 confirm gate as an
explicit `--open`. A create whose remainder carries an unrecognized token, a
value-less `--contact`, or an unexpected bare argument is **malformed**: it
returns a new `error.invite.create_malformed` reply (en + cs, plus the
`ERROR_INVITE_CREATE_MALFORMED` `BundleKeys` constant), writes no audit row, and
arms no pending confirm — restoring the fail-safe the retired hint provided
(redteam medium finding). `--contact <id>` alone is unchanged (immediate,
confirm-free, theft-proof binding). The `error.invite.missing_flag` copy (en +
cs) and the `ERROR_INVITE_MISSING_FLAG` `BundleKeys` constant become unreachable
and are removed as orphans this change creates (bilateral en/cs keyset per D43).

**Tests.** `InviteCommandHandlerTest` pins that a truly-bare `/invite create`
returns the `--open` confirm prompt (not a hint) and matches the
explicit-`--open` first-call reply; a malformed create returns
`error.invite.create_malformed` and arms nothing (no intent audit row, no pending
confirm); `--contact` still mints a CONTACT_BOUND invite without a confirm.
`mvn verify` is green.

## Out-of-scope

- The `--contact` disposition and the in-band contactId-sourcing surface are
  **M1-633** (blocked on this ticket). This ticket does not add, remove, or
  reframe a *valid* `--contact` beyond leaving it working as-is; it only adds
  explicit rejection of *malformed* create input.
- The `--adapter` inference block (M1-626, merged), the `--open` confirm-gate
  and confirm-fork mechanics (M1-051), the per-adapter open cap, and the
  audit-before-effect transaction shape. The truly-bare branch is redirected
  into the existing `--open` path; the bare-vs-malformed discrimination lives at
  the `CreateArgs.parse` layer (unconsumed-token tracking); none of the `--open`
  machinery or the confirm fork itself changes. Rejecting malformed input at the
  first call is what closes the confirm-fork aggravator (a follow-up
  `... confirm` then finds no pending → `error.confirm.no_pending`), so the fork
  needs no edit.
- `InviteCommandHandlerTest`: existing tests are preserved. Any test that
  asserted the old missing-flag hint for a bare create is updated to the new
  default-to-open expectation and named as an intentional edit.

## Notes

- **Redteam remediation / must-shrink.** This round-2 scope grows vs the round-1
  APPROVE (8 files, +45/-25): two spec files added (`security.md`,
  `docs/design/03-commands.md`), a new `error.invite.create_malformed` key, a
  `CreateArgs.parse` change, and a new test. The growth is authorized as a
  user-accepted in-branch redteam remediation (M1-632 redteam findings,
  2026-07-15, `docs/plan/m1/redteam/M1-632-2026-07-15.md`) — the implementation
  commit message MUST cite it so the round-N must-shrink SCOPE-DRIFT check passes.
- Security posture: making `--open` (broader blast radius) the implicit default
  for a *truly bare* create is a deliberate change — the confirm gate
  (`commands.md:1099-1102`) and the per-adapter open cap are the backstops. The
  fail-safe against *ambiguous/malformed* issuance input is preserved by the new
  `create_malformed` rejection. `security_relevant: true`.
- Implementation shape: the no-flag case must distinguish TRULY bare (empty
  remainder, or only `--adapter <name>`) from MALFORMED (any unconsumed token — a
  typo'd flag, a value-less `--contact`, a stray bare arg). Only truly-bare
  defaults into the `--open` path (adapter resolution + confirm gate run
  unchanged); malformed returns `error.invite.create_malformed` and arms no
  pending. `CreateArgs.parse` tracks whether any token went unconsumed. Keep the
  mutually-exclusive check (`args.contact != null && args.open`).
- Removing/adding a bundle key requires deleting/adding the en AND cs entries
  plus the `BundleKeys` constant, or `BundleLoaderTest` fails the D43
  bilateral-keyset check (see the project convention on bundle-key twins). This
  ticket removes `error.invite.missing_flag` and adds
  `error.invite.create_malformed`.
- Adjacent code: `InviteCommandHandler.handleCreate` and `CreateArgs.parse`
  (`infochat-provider/.../command/InviteCommandHandler.java`), the no-flag branch
  at the `args.contact == null && !args.open` guard.
- Decision family: D44 (per-adapter invite). D60 is created here.
