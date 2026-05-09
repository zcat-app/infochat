---
name: redteam
description: Run an adversarial security review on pending changes. Spawns a fresh-context threat-actor subagent that reads the project's threat model (docs/spec/security.md) and the diff, then flags gaps between what the threat model promises and what the diff actually defends against. Use when the user asks for a "red-team", "security review", "threat-model audit", "adversarial review", or "vulnerability check"; runs naturally at milestone boundaries, on tickets flagged security_relevant: true, and before tagging a release. Findings are bucketed by category (AUTH-BYPASS, INFO-LEAK, INJECTION, DOS, PERM-ESCAL, AUDIT-EVASION) and severity (critical|high|medium|low). Invoke as `/redteam <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`. Distinct from the engineering-rules code reviewer — adversarial framing only, no implementation context, no APPROVE/REWORK verdict.
---

# /redteam — adversarial security review

This skill is the procedure. The prompt template is `docs/process/redteam-prompt.md`. The threat model the adversary reads is `docs/spec/security.md`. If either of those is absent, the skill refuses and explains.

This skill is milestone-agnostic — it accepts any ticket-ID matching the pattern `[A-Z]+\d+-\d+` (e.g. `M1-007`, `M2-012`) and infers the active milestone from the target argument. It is intentionally separate from `/m1-tick` because (a) its cadence is different (milestone-end / flagged-ticket / pre-release, not per-ticket), (b) its inputs and outputs are different (multi-ticket diff ranges; bucketed findings rather than APPROVE/REWORK), and (c) the adversary persona benefits from a focused skill description rather than being one of ten subcommands under a generic "drive the workflow" umbrella.

## Invocation forms

```
/redteam <ticket-id>              # single ticket; usually one with security_relevant: true
/redteam milestone <name>         # all `done` tickets in a named milestone (e.g. "milestone m1")
/redteam id-range <from>..<to>    # inclusive ticket-ID range (e.g. "id-range M1-005..M1-012")
/redteam release <tag>            # diff between previous release tag and main
```

If the args don't match, print the table above and stop.

## Preconditions

- `docs/spec/security.md` exists. Refuse if not (the adversary needs the threat model to compare against).
- `docs/process/redteam-prompt.md` exists. Refuse if not.
- For `<ticket-id>` form: the ticket exists and `status: done`, OR the user explicitly opts in to running the audit on an in-progress ticket via `/redteam <id> --in-progress` (rare; surfaces findings before APPROVE).
- For `milestone <name>` form: the milestone directory `docs/plan/<name>/` exists and contains tickets.
- For `id-range <a..b>` form: every ticket in the range exists; all share a milestone prefix (refuse cross-milestone ranges with a clear error).
- For `release <tag>` form: `<tag>` matches the repo's tag pattern AND a previous release tag exists.

## Steps

### 1. Resolve the diff range

| Target form | Base | Head |
|---|---|---|
| `<ticket-id>` | `main^` (commit before the ticket landed) | the ticket's merge commit on `main`, or the branch tip if `--in-progress` |
| `milestone <name>` | merge-base of `main` and the first ticket commit of the milestone | `main` (or the latest done-ticket commit in that milestone) |
| `id-range <a..b>` | commit before `a` landed | commit when `b` landed |
| `release <tag>` | previous release tag | `<tag>` (or `main` if not yet tagged) |

Capture: `git diff <base>...<head>`.

### 2. Identify the active milestone (for report-write path)

- `<ticket-id>` → parse the milestone prefix from the ID (e.g. `M1-007` → `m1`).
- `milestone <name>` → `<name>`.
- `id-range <a..b>` → the shared prefix from `a` (already enforced in preconditions).
- `release <tag>` → `release/<tag>`.

The active-milestone token determines where reports get written: `docs/plan/<active-milestone>/redteam/`. If the directory doesn't exist, create it.

### 3. Build the sensitive-surface inventory

Grep the diff for code paths matching:

| Surface | Pattern |
|---|---|
| Authentication / invite / first-mention registration | files under `**/security/auth/**`; methods named `*authenticate*`, `*invite*`; annotations `@PermitAll`, `@RolesAllowed` |
| Authorization / admin-tier gates / per-(user, scope) isolation | `is_admin`, `is_group_admin`, `@RolesAllowed`; files under `**/security/authz/**` |
| Input validation / message intake / LLM tool-call argument parsing | adapter inbound classes; `@Path`/`@POST`/`@GET` handlers; JSON deserialization sites; LLM tool-method signatures |
| Ban handling / fixed-response-only paths | anything matching `*ban*` (case-insensitive) outside comments |
| Audit log writes | writes to `audit_log` table; `AuditLogger.*` calls |

Collect file:line tuples per surface. Substitute into the prompt placeholders (`{{AUTH_PATHS}}`, `{{AUTHZ_PATHS}}`, `{{INPUT_PATHS}}`, `{{BAN_PATHS}}`, `{{AUDIT_PATHS}}`).

### 4. Substitute prompt placeholders

Read `docs/process/redteam-prompt.md`. Substitute:

- `{{TARGET}}` — the literal target arg
- `{{BASE_REF}}` / `{{HEAD_REF}}` — the resolved git refs
- `{{SECURITY_SPEC_CONTENT}}` — the verbatim contents of `docs/spec/security.md`
- `{{DIFF_OUTPUT}}` — the captured diff
- `{{AUTH_PATHS}}` / `{{AUTHZ_PATHS}}` / `{{INPUT_PATHS}}` / `{{BAN_PATHS}}` / `{{AUDIT_PATHS}}` — the inventory from step 3 (one path-or-tuple per line, or `(none touched)`)

### 5. Spawn the adversary subagent

```
Agent(
  subagent_type: "code-reviewer",
  prompt: <substituted>,
  description: "Red-team <target>"
)
```

Foreground. The verdict gates the next steps.

### 6. Parse the structured verdict

Expected format:

```
RED-TEAM VERDICT: <CLEAN | FINDINGS>

FINDINGS: (omit on CLEAN)
  - CATEGORY: <AUTH-BYPASS | INFO-LEAK | INJECTION | DOS | PERM-ESCAL | AUDIT-EVASION>
    SEVERITY: <critical | high | medium | low>
    PROMISE: <quote from threat model>
    GAP: <file:line evidence>
    REPRO: <attack sequence>
    SUGGESTED-FIX-CLASS: <input-sanitization | trust-boundary-tightening | missing-auth-check | rate-limit | audit-log-coverage | other>

OUT-OF-MODEL: (optional)
  - <attacks outside the documented threat model>
```

If the output doesn't parse, treat as MANUAL: print verbatim, ask the user how to proceed, do not write findings to frontmatter.

### 7. Persist findings

- **Single-ticket target.** Append each finding to the ticket's `redteam_findings:` list:
  ```yaml
  redteam_findings:
    - date: <YYYY-MM-DD>
      category: <CATEGORY>
      severity: <severity>
      promise: |
        <verbatim>
      gap: |
        <verbatim>
      repro: |
        <verbatim>
      suggested_fix_class: <fix-class>
  ```
- **Other targets.** Write the verbatim verdict to `docs/plan/<active-milestone>/redteam/<target-slug>-<YYYY-MM-DD>.md`. Slug rule: target form's args, lowercased, hyphenated (e.g. `milestone-m1`, `id-range-m1-005-to-m1-012`, `release-v0-1-0`).

### 8. Escalate findings to the lifecycle workflow

Findings DO NOT auto-rewrite tickets or auto-fire `/m1-tick escalate`. Cross-skill auto-invocation creates state-machine coupling that is hard to reason about. Instead:

- Print a one-screen summary in chat (count by severity, count by category).
- For each finding that maps to an existing ticket (single-ticket targets always; multi-target findings the user can opt in to mapping), recommend the lifecycle escalation:
  ```
  M1-NNN has 2 findings (1 critical, 1 medium). To open the lifecycle
  escalation, run:
    /m1-tick escalate M1-NNN redteam-finding
  ```
- For findings without a clear owning ticket (architectural gaps, missing audit coverage spanning many files), recommend the user file a new ticket OR raise a spec amendment via `/m1-tick escalate <some-related-ticket> spec-amend`.

The adversary subagent never edits files, runs commands, or fires escalations on its own.

## Cross-cutting rules this skill must obey

- **Read-only.** This skill never edits code, commits, or pushes. It only reads, spawns the subagent, and writes audit reports under `docs/plan/<active-milestone>/redteam/` plus `redteam_findings:` frontmatter on single-ticket targets.
- **Fresh context for the adversary.** The subagent must NOT be given conversation history, design notes (`docs/design/**`), or ticket bodies. It sees only the threat model and the diff. Anchoring it on implementer rationale defeats the point.
- **No auto-escalation.** Recommend, don't execute. The user (or Claude in the next turn) decides which findings become tickets.
- **If `docs/spec/security.md` does not exist or is empty, REFUSE.** The threat model is the system's commitments; without it the audit has nothing to compare against.
- **Severity language is canonical.** `critical | high | medium | low`, no synonyms. The redteam subagent's prompt enforces this; if it returns "blocker" or "info" or similar, treat as parse failure.
- **Out-of-model findings are advisory only.** They flag potential threat-model gaps but are not failures. The user decides whether to extend the model.
