---
target: tier1-summary
target_form: milestone-phase
date: 2026-05-19
phase: end-of-tier-1
sources:
  - docs/plan/m1/redteam/M1-028-2026-05-16.md
  - docs/plan/m1/redteam/M1-030-2026-05-16.md
  - docs/plan/m1/redteam/M1-032-2026-05-16.md
  - docs/plan/m1/redteam/M1-033-2026-05-16.md
  - docs/plan/m1/redteam/M1-035-2026-05-18.md
  - docs/plan/m1/redteam/M1-035a-2026-05-17.md
  - docs/plan/m1/redteam/M1-035b-2026-05-17.md
  - docs/plan/m1/redteam/M1-036-2026-05-18.md
  - docs/plan/m1/redteam/M1-037-2026-05-19.md
  - docs/plan/m1/redteam/id-range-m1-032-to-m1-034b-2026-05-17.md
deferred_tickets_reviewed:
  - M1-019
  - M1-020
  - M1-021
  - M1-031
  - M1-034 (closed — decomposed into M1-034a/b, both done)
new_tickets_created:
  - M1-038 (pending) — InboundRouter hardening
  - M1-039 (pending, blocked_by M1-038) — /add-source handler hardening
  - M1-040 (pending, blocked_by M1-039) — /summary prompt-injection wrapper + cross-handler adapter-scoped users lookup
  - M1-041 (deferred, post-mvp-audit-writer-consolidation) — Audit-log writer + RedactionHook + LlmOutputSanitizer audit row
  - M1-042 (deferred, post-mvp-hardening) — Operator-config + startup-guard hardening
disposition: |
  Consolidation pass run at end of Tier 1 (M1-037 closed the
  last T1-F MVP-slice ticket). Reviewed every red-team report
  filed during Tier 1 plus the four existing deferred tickets
  (M1-019, M1-020, M1-021, M1-031), classified every finding /
  advisory against three buckets:

    (a) FIXED / SUPERSEDED — finding was addressed in the same
        branch's follow-up commit OR superseded by a later
        ticket. No action.
    (b) ABSORBED BY T2-A — finding describes a gap that T2-A
        (the onboarding / auth umbrella, 3 tickets per
        session-grouping-plan) will close as part of its
        normal scope. No new remediation ticket; T2-A will
        re-audit the same surface and surface any residual.
    (c) STILL OPEN, NET-NEW DEFECTS — finding is a real defect
        in already-merged code that won't be closed by T2-A's
        intake-gate work and needs its own remediation ticket.

  Bucket (c) produced 5 new tickets. Three are PENDING (fix
  before T2-A starts) because they set patterns T2-A / T2-B /
  T2-D will copy: M1-038 (InboundRouter hardening — fenced-code
  carve-out + body-size cap + contact-ID redaction in logs);
  M1-039 (/add-source handler ban-check ordering + contact-ID
  redaction in exceptions); M1-040 (/summary prompt-injection
  wrapper + cross-handler adapter-scoped users lookup).

  Two are DEFERRED with explicit `deferred_reason` plumbing
  surfaced in STATUS.md: M1-041 (audit-log writer +
  RedactionHook + LlmOutputSanitizer audit row) waits for
  T2-A/B/E to land all audit-write call sites so the writer's
  API can be designed against the full picture; M1-042
  (operator-config + startup-guard hardening) bundles four
  OOM advisories — none bite in steady state, all defense-in-
  depth, picked up if M1 has slack before tag or carried into
  M2.

  **Verify-at-end-of-tier-1 commitment.** Before tagging v1
  or starting T3-A (production adapters), re-audit the five
  new tickets against the then-current spec and codebase.
  Defects may have moved (T2-A may have rewritten
  InboundRouter intake; T2-D may have authored new
  LLM-triggering surfaces with the same pattern as
  /summary; T3-A may make M1-040 finding 5 exploitable).
  This report is the durable signal for that re-audit pass —
  refresh disposition on each ticket at that time.
---

# End-of-Tier-1 red-team consolidation (2026-05-19)

## Why this report exists

Tier 1 closed with M1-037 (the /summary command). Ten red-team
reports filed during Tier 1 carried 15 findings of varying
severity plus 16 OUT-OF-MODEL advisories. Four tickets were
authored as deferred (`M1-019`, `M1-020`, `M1-021`, `M1-031`)
to capture work that did not block Tier 1 critical-path. This
report consolidates the entire surface, takes one decision per
finding, and either creates a remediation ticket or records the
deferral with an explicit rationale.

This is the durable signal for the **end-of-tier-1 re-audit pass**
(scheduled before T3-A or v1 tag, whichever comes first). When
that pass runs, walk this report's tables top-to-bottom and
update the disposition column against the then-current spec and
codebase.

## Input inventory

**Red-team reports (10):**

| Report | Verdict | Findings | OOM |
|---|---|---|---|
| M1-028 | CLEAN | 0 | 3 |
| M1-030 | CLEAN | 0 | 3 |
| M1-032 | FINDINGS | 1 high + 1 medium + 1 low | 2 |
| M1-033 | CLEAN | 0 | 4 |
| M1-035 | CLEAN | 0 | 2 |
| M1-035a | CLEAN | 0 | 3 |
| M1-035b | FINDINGS | 2 high + 3 medium + 1 low | 2 |
| M1-036 | FINDINGS | 1 high + 1 medium + 1 low | 2 |
| M1-037 | FINDINGS | 3 high + 2 medium | 2 |
| id-range M1-032..M1-034b | CLEAN | 0 | 3 |
| **TOTAL** | | **6 high + 7 medium + 3 low = 16** | **26** |

**Deferred tickets reviewed (4 active + 1 historical):**

| ID | Theme | Disposition |
|---|---|---|
| M1-019 | API-key redaction on stdout | Unchanged — stays deferred, sibling pattern to M1-038 |
| M1-020 | Exception message sanitization (SafeLog) | Unchanged — stays deferred |
| M1-021 | V6 identity/audit remediation (M1-008a findings) | Unchanged — runnable when ready |
| M1-031 | Provider catch-up hardening (M1-030 OOM advisories) | Unchanged — stays deferred |
| M1-034 | (decomposed into 034a/b, both done) | Historical |

## Per-finding disposition

### Bucket (a) — FIXED / SUPERSEDED (no action)

| Finding | Origin | Why no action |
|---|---|---|
| Stage 1 entity-decode bypass (high INJECTION) | M1-032 #1 | Addressed in follow-up commit on M1-032 branch before merge |
| Stage 1 OWASP exception fail-closed (medium DOS) | M1-032 #2 | Addressed in follow-up commit on M1-032 branch before merge |
| M1-030 OOM advisories (3) | M1-030 | Captured in M1-031 (deferred) |
| `delete_preban_user` actor-existence / actor-admin / search_path | M1-008a redteam (covered in M1-021) | M1-021 (deferred) is the remediation |
| Ban-self trigger gap (high PERM-ESCAL) | M1-008a redteam | M1-021 (deferred) |
| audit_log actor poisoning (medium AUDIT-EVASION) | M1-008a redteam | M1-021 (deferred) |
| LLM response body preview logs API-key | M1-033 OOM #2 | Captured in M1-019 (deferred) |

### Bucket (b) — ABSORBED BY T2-A (no new remediation ticket)

T2-A is the onboarding / auth umbrella per `docs/plan/m1/drafts/session-grouping-plan.md`:
3 tickets covering invite-gating + /ban + /unban + slow-start
probation + /grant-admin + /revoke-admin, all upstream of
InboundRouter. Every finding in this bucket describes a gap that
T2-A will close as part of its normal scope.

| Finding | Severity | Origin | T2-A surface that closes it |
|---|---|---|---|
| No per-(adapter, contact_id) rate-limit cap | high DOS | M1-035b #1 | Transport-level rate-limit step 1.5 |
| No ban / invite / probation gate upstream of InboundRouter | high AUTH-BYPASS | M1-035b #2 | Steps 4 (ban), 2 (invite), 7 (probation) per §Authorization model |
| Unknown-DM gets CHAT_MODE_REPLY instead of "access requires invitation" | medium AUTH-BYPASS | M1-035b #4 | Step 2 fixed-reply path |
| Probation bypass for /add-source | high AUTH-BYPASS | M1-036 #1 | Step 7 permission-matrix probation enforcement |
| No rate-limit on /add-source | OOM | M1-036 OOM #1 | /add-source bucket per §Rate limiting |
| Group-only DM gate missing | OOM | M1-036 OOM #2 | Step 2 + §Invite-code registration |
| No rate-limit on /summary | high DOS | M1-037 #3 | LLM-triggering bucket per §Rate limiting |
| No ban check before /summary | high INFO-LEAK | M1-037 #4 | Step 4 ban check |
| Auto-register state for first-DM-without-invite is 'invited' | OOM | M1-035 OOM #1 | T2-A replaces M1-035d's auto-register path with invite-gated flow |
| `Stage1Worker` `@Incoming` lacks `@Blocking` | OOM | M1-033 OOM #4 | Out-of-model per threat-model scope; can land opportunistically with T2-A worker-pool review |

### Bucket (c) — STILL OPEN, NET-NEW DEFECTS (new tickets created)

| Finding | Severity | Ticket | Status |
|---|---|---|---|
| `InboundRouter.normalize()` no fenced-code carve-out | medium INFO-LEAK | **M1-038** | pending |
| `InboundRouter` no body-size cap pre-NFKC | medium DOS | **M1-038** | pending |
| Contact-ID unredacted in InboundRouter error logs | low INFO-LEAK | **M1-038** | pending |
| `/add-source` ban check runs AFTER scope discriminator | medium INFO-LEAK | **M1-039** | pending |
| Contact-ID unredacted in /add-source exception messages | low INFO-LEAK | **M1-039** | pending |
| `SummaryProseGenerator` missing delimiter wrapper + random marker + refusal instructions | **high INJECTION** | **M1-040** | pending |
| `SummaryCommandHandler` users SELECT drops adapter predicate | medium INFO-LEAK | **M1-040** | pending |
| `AddSourceCommandHandler` users SELECT drops adapter predicate (parallel defect) | medium INFO-LEAK | **M1-040** | pending |
| `LlmOutputSanitizer` hits log WARN, not audit_log row | medium AUDIT-EVASION | **M1-041** | deferred |
| Stage 2 audit row bypasses redaction-hook | OOM | **M1-041** | deferred |
| `LlmRouter.forTask` silent fallback on unknown default-provider | OOM | **M1-042** | deferred |
| `local-only` posture only inspects security base-URL | OOM | **M1-042** | deferred |
| `OutboxRehydrator` unbounded in-memory List | OOM | **M1-042** | deferred |
| `FetchScheduler.tickOnce` logs unredacted URL via exception chain | OOM | **M1-042** | deferred |

### Excluded (deliberately) with reasons

| Finding | Origin | Why excluded |
|---|---|---|
| `InMemoryAdapter` `maxInboundMessageBytes` not enforced | M1-035a OOM #1 | Test-classpath-only; production gate is M1-035b's AdapterRegistry (done). |
| `InMemoryAdapter.assertIdentity` returns sender verbatim | M1-035a OOM #2 | LOW trust level is the gate; production exclusion in AdapterRegistry. |
| `InMemoryAdapter` public HIGH-trust constructor unrestricted | M1-035a OOM #3 | Same as above; AdapterRegistry gate suffices. |
| `%test.allow-low-trust=true` could leak to prod | M1-035 OOM #2, M1-035b OOM #1 | Operator-config concern; T3-A deployment-review territory. |
| `TestRssFetcher` SSRF allowlist bypass | M1-028 OOM #2 | Test-only; refactoring hazard not a defect. |
| Stage 1 admin-notifier wiring for watchdog | M1-032 #3 | Deferred to T2-G (admin-notifier umbrella) per the M1-032 disposition. |
| Stage 1 pre-watchdog string allocation cost | id-range M1-032..M1-034b OOM #2 | Defended by fetcher-side body-cap. |
| LlmOutputSanitizer CLOSED_LIST hand-maintained vs. spec-derived | M1-037 OOM #2 | CI parity test mitigates; spec-interpretation question, not a defect. |
| SummaryProseGenerator continues after first cluster failure | M1-037 OOM #1 | Latency / availability concern; not a spec violation. |
| LLM endpoint HTTP bypasses SSRF allowlist | M1-033 OOM #1 | In-spec per current commitments; operator-trusted config. |

## Recommended remediation order

1. **M1-038** (InboundRouter hardening) — runnable now, sets up `ContactIds.redact` helper for M1-039.
2. **M1-039** (/add-source handler hardening) — depends on M1-038's helper.
3. **M1-040** (/summary prompt-injection wrapper + adapter-scope) — depends on M1-039 to keep merge ordering clean (both touch AddSourceCommandHandler).
4. **(T2-A authoring window opens here.)**
5. **M1-041** (audit-log writer) — runs AFTER T2-A/B/E so the writer's API is informed by all call sites.
6. **M1-042** (operator-config hardening) — picked up if M1 has slack, otherwise M2-open.

## Rationale for the deferral choices

### Why M1-041 is deferred and not pending

Today's audit-log call sites are 2 (bootstrap admin, Stage 2
release-on-failure); both are system-actor and carry no
user-derived data. The spec's redaction-hook layer has nothing
to actually redact in those rows.

T2-A will add ~6 audit-write call sites (`/grant-admin`,
`/revoke-admin`, `/ban`, `/unban`, `/promote`, `/demote`,
`/vouch`). T2-B will add ~2 more (`/save`, paths adjacent to
`/forget`). T2-E will add 2 more (`/forget`, `/export`).

Building the writer + redaction hook NOW means:

- Designing the API against 2 call sites instead of the
  ~10-site reality
- Retrofitting each T2 ticket as it lands
- Reopening to add the LlmOutputSanitizer audit row anyway

Building it AFTER T2-A/B/E means:

- One consolidation ticket migrates all ~10 call sites
  atomically
- The API is informed by every actual consumer
- The LlmOutputSanitizer audit row + RedactionHook layer +
  V12 migration land together as a coherent unit

Residual risk meanwhile: LlmOutputSanitizer hits stay at
WARN with `error_class=llm.output.sanitized`. Operators
needing the signal can grep the structured log. The spec
promise ("every match is audit-logged per-occurrence") is
silently downgraded — bounded gap, durable signal in this
report, fix scheduled.

### Why M1-042 is deferred

All four items are OUT-OF-MODEL. None are exploitable by
the in-model adversary. The fixes are low-cost but
low-urgency. Same posture as M1-031.

### Why M1-038/039/040 are pending (not deferred)

These set patterns that T2-A / T2-B / T2-D will inherit:

- **M1-038's fenced-code carve-out** is the template for T2-D
  chat-mode normalization. Shipping `normalize()` without the
  carve-out means T2-D copies the broken behavior.
- **M1-038's `ContactIds.redact` helper** is the seam for T2-A
  / T2-B contact-id logging.
- **M1-039's ban-check ordering** is the template for T2-B
  command handlers (`/save`, `/follow-tag`, etc.) which will
  be authored from `AddSourceCommandHandler`. Fix once at the
  template, not three times at copies.
- **M1-040's prompt-injection wrapper** is the template for
  T2-D chat-mode prompts. Same template-setting argument.
- **M1-040's adapter-scoped users lookup** must land before
  T3-A (production adapters) makes the defect exploitable.

## Verify-at-end-of-tier-1 checklist

When this report's re-audit pass runs (before v1 tag or T3-A,
whichever comes first):

- [ ] Re-read each ticket M1-038..M1-042; check that its
  acceptance criteria still match the codebase. Surface any
  drift via `/m1-tick refine` if needed.
- [ ] Verify T2-A actually closes every Bucket (b) finding in
  the table above. If T2-A leaves residuals, file remediation
  tickets at that time.
- [ ] Re-audit Bucket (c) findings against the merged code —
  some may have been silently fixed by T2-A's intake rewrite
  (e.g. if T2-A rewrites `InboundRouter.onMessage` from
  scratch, M1-038's error-log redaction sites might land
  there instead).
- [ ] Re-check the Excluded table — `%test.allow-low-trust`
  posture and `TestRssFetcher` SSRF bypass become live
  concerns at T3-A deployment-review time.
- [ ] Run `/redteam milestone m1` once before tag to catch
  anything this consolidation missed; this report's tables
  are the priors for that audit's classification.
