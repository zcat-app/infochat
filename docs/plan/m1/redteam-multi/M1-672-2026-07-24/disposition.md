# M1-672 red-team-multi — finding disposition

Auditors: claude (FINDINGS×3), codex (FINDINGS×1 high), kimi (CLEAN).
Cross-examination: 1 corroborated cluster, 2 single-auditor. Falsification
pass run in the main session against the actual code (not the auditors' patch
line numbers). Findings are advisory; the dispositions below were confirmed
with the ticket owner.

## Finding 1 — PERM-ESCAL, `bootstrap_ensure_admin` / `auto_promote_group_admin` ungated (claude medium, codex high — corroborated)

**Mechanism: real.** Ground-truthed in V62 — `bootstrap_ensure_admin` and
`auto_promote_group_admin` carry no actor gate and are granted to
`infochat_provider`; a foothold can call them to mint a bot admin or grant
group-admin.

**Falsified framing:**
- "Regression / new surface" — FALSE. Pre-V62 the same foothold ran a raw
  `UPDATE users SET is_admin=TRUE` with more freedom. V62 strictly narrows
  this; the routine path is at worst equal power for the admin-mint goal.
- "A guard was available (see `claim_simplex_admin`)" — WEAK. That guard fits
  a single-use claim; `bootstrap_ensure_admin` must stay idempotent and
  multi-admin (operator restart, per-adapter admins), and `auto_promote` is a
  pinned 1:1 replacement whose D47 eligibility lives in Java. Adding SQL guards
  = re-deriving authz in SQL = the scope the round-2 planning failure foreclosed.
- Entry condition is hypothetical: the module-6 premise check found NO
  injectable Provider SQL, re-confirmed here — 285 prepared-statement calls, 0
  runtime-value concatenations, the 5 raw `createStatement` uses are constants
  or a numeric. So this is defense-in-depth behind a first layer that holds.

**Disposition:** the design residual is **real, corroborated, ticket-sanctioned**
(acceptance items 4 + 11) and inherent to a bootstrap path the weak Provider
role must reach at every start. ACCEPTED, not closed. The genuine defect was
that the §DB roles spec bullet overstated the control ("cannot mint a bot
admin") and then contradicted itself ("can still mint the first admin").
FIXED — the residual is now recorded honestly (any admin any time, plus the
group-admin and invited-user conduits; the column revocation is a narrowing,
not an elimination). Closing it for real would need admin bootstrap under a
higher-privilege connection — a separate architectural change.

## Finding 2 — AUDIT-EVASION, routines write no audit row (claude low, single-auditor)

**Falsified as actionable.** Not a regression (auditor concedes; pre-V62 raw
writes were equally silent). The implied fix — proc-side audit rows — is
EXPLICITLY FORBIDDEN by acceptance item 6 (would double the rows handler tests
count and bypass the Java `RedactionHook`, leaking unredacted contact ids into
`audit_log`). Neither codex nor kimi flagged it. NOT FIXED — fixing it would
violate an acceptance criterion to solve a non-regression.

## Finding 3 — AUTH-BYPASS, two routines' actor gate unpinned by the suite (claude low, single-auditor)

**Survives as a real in-scope test gap.** Not exploitable as shipped (the
`PERFORM require_bot_admin_actor()` guard IS present — confirmed as the first
statement of both routines). But acceptance item 6 requires EACH admin-gated
routine to be pinned, and the suite covered 12 of 14, skipping
`insert_preban_user` and `mint_invite_code`. The IT comment's excuse ("cannot
be driven to a no-op") was wrong for the refuse legs — the gate raises before
the INSERT. FIXED — refuse-leg probes (unset GUC + non-admin GUC → P0001, not
IC001) added for both, plus a rolled-back real-insert accept leg; the
misleading comment corrected.

## Out-of-model (both auditors) — noted, no action

- 19 SECURITY DEFINER routines run with owner rights, but every body is static
  SQL with `SET search_path = pg_catalog, public` — no dynamic-SQL / search-path
  hijack path. Owner-rights blast radius now attached to 19 entry points vs 3.
- Hostile operator / direct psql is explicitly out of model (security.md §24).
- `ProviderIdentityGrantsIT` leaves one `is_admin=TRUE` row in the collector
  test cluster — verified harmless (no collector test asserts on admin count;
  V40 delete-trigger would block removing the last admin anyway). Test hygiene.

## Re-audit on the edited (remediated) code — 2026-07-24-r2

After the two conformance fixes (honest §DB roles residual wording +
insert_preban_user/mint_invite_code refuse-leg test probes), redteam-multi was
re-run on the edited diff (evidence: `../M1-672-2026-07-24-r2-2026-07-24/`).
Result: **0 findings.**

- **claude: CLEAN.** Read the delivery against the *amended* spec and found no
  promise-vs-delivery gap — the residual is now read as an accepted, documented
  design decision, not an overstatement. Independently re-verified: column
  revocation + REVOKE-before-GRANT ordering; all 14 admin-gated routines open
  with `require_bot_admin_actor()`; the 5 system-actor routines match the exact
  blessed residual set; routines are faithful 1:1 replacements; `search_path`
  pinned; V40 IC001 still propagates while actor-check uses P0001; actor GUC
  bound transaction-locally and fails CLOSED.
- **codex: CLEAN.**
- **kimi: UNAVAILABLE** (exit 124 — hit the 900s cap mid-reasoning, so the
  parser correctly refused to score the truncated file as CLEAN). Its raw reply
  nonetheless stress-tested 7 candidates (A–G: promote race, mint cap race,
  consume/claim param preservation, bootstrap RETURNING, void-fn execute()) and
  concluded verbatim "RED-TEAM VERDICT: CLEAN … zero findings"; it also raised
  the /vouch-has-no-routine question and dismissed it correctly (/vouch writes
  probation_until only, no registration_state transition), matching claude.

Finding 1's design residual (ungated system-actor conduits) remains ACCEPTED
and is now the documented spec posture; both CLEAN auditors examined it and
consciously did not escalate it. Ticket is clean on both gates: review APPROVE
(round 1) + redteam re-audit CLEAN.
