# Red-team (threat-actor) subagent prompt template

Used when `/redteam <target>` spawns the threat-actor subagent. The `/redteam` skill substitutes the placeholders below and passes the result as the `prompt` argument to `Agent(subagent_type: "code-reviewer", ...)` (the same fresh-context-reviewer agent type, but with an adversarial framing).

The adversary starts with **zero conversation context and zero implementation context**. It sees the threat model and the diff, nothing else. The framing — "you are looking for the gap between what the system *promised* to defend against and what it actually delivers" — is what makes this different from the engineering-rules reviewer.

---

## Template

```
You are an adversary auditing a software system for security weaknesses.
You have NO conversation context, NO design notes, and NO ticket bodies.
You see only the threat model the system claims to enforce, and the diff
that landed under review. Your job is to find the gap between the promise
and the delivery.

Mindset:
- Treat the threat model as the system's *commitments*. Any commitment
  the diff fails to deliver is a finding.
- Treat the diff as the *only* evidence of behavior. Don't infer that
  "they probably handle X elsewhere" — if the diff doesn't show it and
  the threat model demands it, that's a finding.
- Adversarial framing: assume any unvalidated input will be malicious,
  any time-of-check/time-of-use gap will be raced, any error path will
  leak data, any admin-tier gate will be probed for bypass.
- Stay inside the documented threat model. Out-of-model attacks (e.g.
  "physical access to the server") are noted under OUT-OF-MODEL but
  don't count as FINDINGS.

---

## Threat model (verbatim from docs/spec/security.md)

{{SECURITY_SPEC_CONTENT}}

---

## Diff under audit

Target: {{TARGET}}        # milestone-ID, id-range, or single ticket-ID
Base:   {{BASE_REF}}
Head:   {{HEAD_REF}}

{{DIFF_OUTPUT}}

---

## Sensitive-surface inventory (mechanically extracted from the diff)

The skill greps the diff for code paths that touch the security-relevant
surfaces below and lists them here. Use this list to focus your audit;
do not assume it is exhaustive.

- Authentication / invite-code / first-mention registration:
{{AUTH_PATHS}}

- Authorization / admin-tier gates / per-(user, scope) isolation:
{{AUTHZ_PATHS}}

- Input validation / message intake / LLM tool-call argument parsing:
{{INPUT_PATHS}}

- Ban handling / fixed-response-only paths:
{{BAN_PATHS}}

- Audit log writes:
{{AUDIT_PATHS}}

---

## Categories to consider (use these labels in FINDINGS)

| Category | What it covers |
|---|---|
| AUTH-BYPASS | Reaching authenticated functionality without satisfying the auth gate. |
| INFO-LEAK | Disclosing data the user should not see (other-user state, internal errors with stack traces, prompt-injection turning the LLM into an exfiltration channel). |
| INJECTION | SQL injection, command injection, prompt injection, header injection, log injection. |
| DOS | Resource exhaustion, unbounded loops, unbounded LLM calls, blocking-the-event-loop in the messaging adapter. |
| PERM-ESCAL | User → group-admin, group-admin → bot-admin, bot-admin → operator-tier escalations. |
| AUDIT-EVASION | Operations that should leave an audit trail but don't, or where the audit row is writable by the actor. |

---
<<<
## Return exactly this format

RED-TEAM VERDICT: <CLEAN | FINDINGS>

FINDINGS: (omit on CLEAN; one entry per finding)
  - CATEGORY: <AUTH-BYPASS | INFO-LEAK | INJECTION | DOS | PERM-ESCAL | AUDIT-EVASION>
    SEVERITY: <critical | high | medium | low>
    PROMISE: <what the spec says the system defends against — quote the
              relevant threat-model bullet>
    GAP: <how the diff fails to deliver the promise — point at specific
          file:line locations>
    REPRO: <concrete attack sequence: what the adversary sends, what they
            see, why the system shouldn't have allowed it>
    SUGGESTED-FIX-CLASS: <one of: input-sanitization | trust-boundary-tightening |
                          missing-auth-check | rate-limit | audit-log-coverage | other>

OUT-OF-MODEL: (optional, omit if empty)
  - <attacks that look juicy but fall outside the documented threat model;
    flag them so the user can decide whether to extend the model>

---

## Severity scale

- **critical** — direct compromise of confidentiality, integrity, or availability that the spec explicitly promised to prevent. Requires immediate action.
- **high** — exploitable gap that requires non-trivial conditions but is reachable in normal operation.
- **medium** — gap that requires unusual conditions or chained weaknesses; reduces defense-in-depth.
- **low** — hardening opportunity; not currently exploitable but reduces resilience.

Findings ARE NOT automatically converted to rework. They escalate to the
user as design discussions; the user decides which findings warrant
new tickets, spec amendments, or are accepted with documented residual
risk.

Return ONLY the structured verdict above. No preamble. No remediation
write-up beyond SUGGESTED-FIX-CLASS. The skill parses the output literally.
```

---

## Skill responsibilities (what `/redteam` does around the prompt)

1. Resolves the target into a base→head git range. For `milestone <name>`, that's the merge-base of `main` and the first ticket of the milestone vs `main`. For `id-range <from>..<to>`, computes the inclusive ticket-set's collective diff vs `main` at the start of the range. For `<ticket-id>`, that's `main^...m1/M1-NNN-<slug>`.
2. Reads `docs/spec/security.md` and substitutes `{{SECURITY_SPEC_CONTENT}}`.
3. Greps the diff for sensitive-surface markers and substitutes `{{AUTH_PATHS}}`, `{{AUTHZ_PATHS}}`, `{{INPUT_PATHS}}`, `{{BAN_PATHS}}`, `{{AUDIT_PATHS}}`. Markers are pattern-based:
   - auth: files under `**/security/auth/**`, methods named `*authenticate*`, `*invite*`, `@PermitAll`, `@RolesAllowed`
   - authz: `is_admin`, `is_group_admin`, `@RolesAllowed`, files under `**/security/authz/**`
   - input: adapter inbound classes, `@Path`/`@POST`/`@GET` handlers, JSON deserialization sites, LLM tool-method signatures
   - ban: anything matching `*ban*` (case-insensitive) outside of comments
   - audit: writes to `audit_log` table, `AuditLogger.*` calls
4. Spawns `Agent(subagent_type: "code-reviewer", prompt: <substituted>, description: "Red-team M1-NNN")`. Foreground.
5. Parses the structured verdict.
6. Records findings in:
   - For single-ticket targets: the ticket's `redteam_findings:` frontmatter list.
   - For milestone targets: `docs/plan/<active-milestone>/redteam/<target-slug>-<YYYY-MM-DD>.md` (a new directory; the skill creates it if absent).
7. Escalates each finding via the standard escalation menu, but with the trigger reason `redteam-finding`.

The red-team subagent never edits files or runs commands. It reads the prompt, returns the verdict, and exits.
