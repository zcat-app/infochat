---
id: M1-833
title: "Document reboot resilience in guides and design notes"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probes (RED on main; documentation surface, no mvn coverage): (a)
  `grep -n 'stack.sh' SETUP_GUIDE.md ADMIN_GUIDE.md` prints nothing — the
  full-stack verb M1-832 ships is undiscoverable from either operator
  guide; (b) `grep -ni 'linger' SETUP_GUIDE.md ADMIN_GUIDE.md
  docs/design/07-deployment.md` prints nothing — no doc tells an operator
  that on a rootless-Docker host a user logout kills the whole stack
  unless `loginctl enable-linger` is set (setup-hurdles.md item H:
  invisible until it bites; it bit 2026-08-12 and ate both digest
  windows).
analysis_ref: docs/plan/m1/tick-analysis/lifecycle-reboot-resilience.md
blocked_by: [M1-830, M1-831, M1-832]
files_scope:
  - SETUP_GUIDE.md
  - ADMIN_GUIDE.md
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - docs/spec/** — no spec promise changes (spec-first analysis:
    docker-compose.yml, the wizard, and lifecycle verbs all live in design
    notes per docs/spec/deployment.md §What lives in design notes).
  - Any code, script, or compose change (M1-830/M1-831/M1-832, all landed
    per blocked_by).
  - The §7.7.2 step-0 table row (M1-831 owns it) and the §7.7.1
    ops-scripts table row (M1-832 owns it) — this ticket adds §7.8.7 text
    only, in the same design file.
  - Documenting the 2026-08-13 circuit-breaker addendum (batch E) or
    restore.sh (batch A).
  - Digest-window recovery claims of any kind (analysis P7 — missed
    windows are skip-not-catch-up by design; docs must never imply
    otherwise).
acceptance:
  - "REPRODUCTION (a), now passing: SETUP_GUIDE.md §After setup (:497-552, the section that already documents apps.sh) gains the full-stack verb — when to use stack.sh vs apps.sh (whole-host reboot/shutdown vs quick app restart), in the guide's plain-language register. Probe: `grep -n 'stack.sh' SETUP_GUIDE.md` hits the After-setup section."
  - "REPRODUCTION (b), now passing: the same section (or the Troubleshooting section, :719) tells the operator that on a rootless-Docker host logging out of the desktop session stops the whole bot unless lingering is enabled, names the one host command (`loginctl enable-linger`), and notes the wizard's doctor checks it. Probe: `grep -ni 'linger' SETUP_GUIDE.md` hits."
  - "ADMIN_GUIDE.md gains the operator-facing lifecycle line (in the §Upgrading the bot neighbourhood, :431-452, or Common situations): full-stack stop/start around host maintenance runs through prod/scripts/stack.sh; probe: `grep -n 'stack.sh' ADMIN_GUIDE.md` hits."
  - "docs/design/07-deployment.md §7.8.7 (host resource hardening — the section that already owns the rootless-ACL runbook, :1015-1026) gains the reboot-resilience paragraph: every long-running service carries `restart: unless-stopped` (and why not `always` — operator stop intent), the daemon-restart retry-envelope ordering note (depends_on does not order daemon-driven restarts), and the rootless linger prerequisite with its one-command remedy. Probe: `awk '/^### 7.8.7/,/^## 7.9/' docs/design/07-deployment.md | grep -ni 'linger'` hits."
  - "FAILURE-MODE, message honesty (analysis P7): no new text promises recovery of missed digest windows or implies restart policies/linger catch anything up — the new sections say these measures PREVENT the outage, full stop. Probe: the added lines contain no 'catch-up'/'recover the digest'-shaped claim (verified at review against the diff; the skip-not-catch-up design is spec behavior and is not relitigated)."
  - "Register + link integrity: new guide text matches each file's existing plain-language register (SETUP_GUIDE is non-technical); any internal anchor links resolve. `mvn verify` from repo root is green (no code change; the run proves nothing else drifted)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.8.7 Host resource hardening (swap, container caps, build isolation)
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-833: Document reboot resilience in guides and design notes

## Context

M1-830 (restart policies), M1-831 (doctor linger check) and M1-832 (the
stack.sh full-stack verb) land the mechanisms; this ticket makes them
discoverable. Today neither guide mentions the full-stack verb, and no doc
anywhere mentions linger — the property whose absence killed the prod stack
on 2026-08-12 and was invisible until it bit (setup-hurdles.md item H
follow-up 3 asks for exactly these one-liners, alongside the script fixes
that precede them). Shared analysis: `analysis_ref:`.

## Root cause

Verified: `grep -n 'stack.sh' SETUP_GUIDE.md ADMIN_GUIDE.md` and
`grep -ni 'linger' SETUP_GUIDE.md ADMIN_GUIDE.md docs/design/07-deployment.md`
both return nothing (repo-wide grep confirms `loginctl`/`linger` appear in
no committed doc). The reboot-resilience story — restart policies, the
rootless linger prerequisite, the full-stack verb — exists only in the
scratch evidence file and, after the siblings land, in code. Documentation
gap, not a defect in logic.

## Pitfalls

Numbered consistently with the analysis document.

- P7: message honesty — the docs may say linger + restart policies PREVENT
  the stack-dies-on-logout / half-alive-after-reboot outage; they must
  never promise recovery of a missed digest window (skip-not-catch-up is
  spec design — `/retry --digest` only targets slots with a summary_cache
  row — and is not relitigated here).
- P8: landed-names-only — blocked_by M1-830/831/832 guarantees every name
  this ticket cites (stack.sh, the doctor linger check, the restart
  policies) exists on main when the text lands, so no guide sentence pins
  an unlanded name.
- Register trap (unnamed in the analysis, named here): SETUP_GUIDE.md is
  written for non-technical operators ("The easy path") — the linger
  explanation must be plain-language ("logging out of the desktop session
  stops the bot") with the command as the actionable detail, not a systemd
  essay; the systemd mechanism detail belongs in §7.8.7.

## Approach

- **Files to touch:** `files_scope` (two guides, one design-doc section).
- **Steps, in order:**
  1. SETUP_GUIDE.md §After setup: add the stack.sh vs apps.sh guidance
     (whole-host shutdown/reboot vs quick app restart after a config edit)
     beside the existing apps.sh block (:503-516), and the rootless-logout
     / linger note with the one host command and the doctor-check pointer.
  2. ADMIN_GUIDE.md: the operator lifecycle line near §Upgrading the bot
     (:431-452) — host maintenance runs through stack.sh.
  3. docs/design/07-deployment.md §7.8.7: the reboot-resilience paragraph
     (restart-policy alignment + why `unless-stopped` not `always` +
     daemon-restart retry-envelope ordering + the linger prerequisite with
     remedy), sitting beside the existing rootless runbook (:1015-1026).
  4. Run the acceptance probes, then `mvn verify`.
- **Controls to preserve (§10):** no spec text touched (docs/spec/** is
  out of scope — §12's approval gate is not triggered because no promise
  changes); the sibling-owned rows (§7.7.2 step-0, §7.7.1 ops table) are
  not re-edited here.
- **Pitfall→mitigation:** P7→acceptance item 5's review-verified wording
  constraint; P8→blocked_by ordering; register→step 1's plain-language
  shape with the mechanism deferred to §7.8.7.

## Definition of done

Both guides name stack.sh and the linger prerequisite in their own
register; §7.8.7 carries the reboot-resilience paragraph (policies,
ordering note, linger runbook); no doc promises digest recovery; all probes
hit; suite green.

## Verification

- Reproduction (a) → acceptance items 1 and 3 (guide probes).
- Reproduction (b) → acceptance items 2 and 4 (linger probes, the §7.8.7
  one scoped by awk range so M1-831's step-0 row cannot satisfy it).
- P7 → acceptance item 5 — FAILURE-MODE: a "catch-up" / "recover the
  digest" claim anywhere in the diff fails review; the docs must never
  promise recovery of a missed window.
- P8 → blocked_by (the driver enforces the ordering).
- Register/links → acceptance item 6.

## Out-of-scope

Named in `out_of_scope`: spec text (no promise changes — analysis
§Ground truth "Spec position"); all code/scripts/compose (the three
siblings, landed); the two design-doc table rows the siblings own; batch
E's circuit-breaker addendum and batch A's restore.sh; any digest-recovery
wording. No test is added (documentation-only diff — assertion-adequacy
NOT-APPLICABLE) and no pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-833-lifecycle-reboot-resilience-4.md
```
