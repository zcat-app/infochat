# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-672/docs/plan/m1/redteam-multi/M1-672-2026-07-24`
Auditors: claude, codex, kimi

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 1 corroborated (flagged by >=2 auditors).
- 2 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 3, 'codex': 1}.

## Per-auditor verdicts

- **claude**: FINDINGS (3 finding(s))
- **codex**: FINDINGS (1 finding(s))
- **kimi**: CLEAN (0 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | codex | kimi | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | PERM-ESCAL | `V62__provider_identity_grants.sql:117-134` | medium | high | -- | high | claude, codex |
| 2 | AUDIT-EVASION | `V62:245-249` | low | -- | -- | low | claude-only -- needs review |
| 3 | AUTH-BYPASS | `ProviderIdentityGrantsIT.java:452-455` | low | -- | -- | low | claude-only -- needs review |

## Per-cluster detail

### Cluster 1: PERM-ESCAL @ `V62__provider_identity_grants.sql:117-134`

**claude** (severity: medium, fix-class: trust-boundary-tightening)

- PROMISE: docs/spec/security.md:1512-1519 (§DB roles, Provider role — the
              bullet this diff itself writes): "`users.is_admin`,
              `users.is_banned` and its ban metadata,
              `users.registration_state`, `groups.approval_status` and
              `group_membership.is_group_admin` are not directly writable by
              the role, so a SQL-injection foothold in the Provider ...
- GAP (first 400 chars): Three of the four ungated "system-actor" routines are granted to
         infochat_provider and each re-opens, unconditionally, a transition
         the sentence above enumerates as closed:
         - V62__provider_identity_grants.sql:117-134 `bootstrap_ensure_admin
           (TEXT, TEXT)` — no actor gate AND no precondition of any kind. The
           `ON CONFLICT (adapter, contact_id) DO UPDAT...

**codex** (severity: high, fix-class: trust-boundary-tightening)

- PROMISE: "the privilege columns ... are not directly writable by the role, so a SQL-injection foothold in the Provider cannot mint a bot admin"
- GAP (first 400 chars): V62__provider_identity_grants.sql:117-134 exposes bootstrap_ensure_admin as SECURITY DEFINER with wholly caller-controlled adapter and contact arguments; it inserts or promotes that contact to is_admin=TRUE without an actor or bootstrap-context check. V62__provider_identity_grants.sql:534 grants EXECUTE on that function to infochat_provider, the very role an in-model Provider SQL-injection foothol...


### Cluster 2: AUDIT-EVASION @ `V62:245-249`

**claude** (severity: low, fix-class: audit-log-coverage)

- PROMISE: docs/spec/security.md:1517-1519: "Every legitimate transition on
              those columns runs through a narrow, single-purpose `SECURITY
              DEFINER` routine the Provider holds `EXECUTE` on (V62)."
              Paired with §Authorization model step 8 (security.md:812,
              "Audit-log the intent"), §Per-adapter admin threat profile
              (security.md:881-883, "the au...
- GAP (first 400 chars): V62:245-249 records the decision — "No routine writes an audit row.
         Every affected caller already pre-writes its row through
         AuditLogWriter + RedactionHook (Invariant 7)". V62 simultaneously
         makes those routines the ONLY write path to the privilege columns
         (V62:566-569 REVOKE INSERT, UPDATE on all four tables). The result:
         the newly-created chokepoint —...


### Cluster 3: AUTH-BYPASS @ `ProviderIdentityGrantsIT.java:452-455`

**claude** (severity: low, fix-class: other)

- PROMISE: docs/spec/security.md:1526-1532: "the **admin-gated** routines
              resolve their actor from the `infochat.actor_id` GUC that the
              calling role sets itself, and check `is_admin` only, so against
              an attacker who already controls Provider SQL the gate raises
              the bar — they must name some admin's id".
- GAP (first 400 chars): The diff's own regression suite pins that gate for 12 of the 14
         admin-gated routines and deliberately skips the two that matter most
         to credential issuance. ProviderIdentityGrantsIT.java:452-455 states
         it: "{@code insert_preban_user} and {@code mint_invite_code} are
         absent by necessity — they INSERT unconditionally, so they cannot be
         driven to a no-op; ...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **claude-only**: AUDIT-EVASION @ `V62:245-249` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: AUTH-BYPASS @ `ProviderIdentityGrantsIT.java:452-455` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.

