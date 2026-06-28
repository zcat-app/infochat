---
id: M1-509
title: "Operator onboarding: simple (comic) + advanced admin guides"
status: pending
created: 2026-06-28
last_updated: 2026-06-28
blocked_by: []
files_budget: 8
files_scope:
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any code/test/migration change — this is docs only. The SimpleX inbound codec bug is M1-508; the wizard/compose contract fixes are M1-507 / hotfix a381aedf."
  - "docs/spec/** — the spec is the authority and was aligned by M1-506; this ticket rewrites operator-facing GUIDES (SETUP_GUIDE + design runbooks), not the spec."
  - "Changing runtime behavior or config keys — docs must describe what IS, not introduce new contracts."
acceptance:
  - >-
    A SIMPLE guide (comic-book style — short numbered steps, one action per
    step, plain language, zero Java/Quarkus jargon, copy-paste commands, a
    'what you should see' after each step) takes a non-expert from clone to a
    working bot for the DEFAULT path (SimpleX-only, local LLM). It states the
    happy path end-to-end: run setup.sh, choose simplex, set a strong
    admin-token, start, DM the token to claim admin, then unset the token.
  - >-
    An ADVANCED guide covers the full surface: profiles, multi-adapter, Signal,
    recovery/runbooks, and the gotchas. It is clearly separated from the simple
    guide (split file or clearly delineated sections) so a beginner never has to
    read it to get running.
  - >-
    Signal onboarding is documented CORRECTLY end-to-end and the current wrong
    claim is removed: signal-cli is BUNDLED in the provider image (do NOT tell
    operators to install it); registration is run via `docker compose exec
    provider signal-cli …` against the bind-mounted data-dir; the captcha +
    SMS-verify steps are shown; the admin value is the ACI (UUID) NOT a phone
    number, with how to obtain the ACI (DM the bot, read sourceUuid via `-o json
    receive`); and the order-of-operations is explicit (register + get ACI
    BEFORE the wizard's admin prompt, since the image must exist first).
  - >-
    The known gotchas are documented as runbook entries: (a) bootstrap-sources
    tag rules (lowercase/^[a-z0-9-]$, no spaces) so a bad tag does not crash the
    Collector; (b) for deployments that bootstrapped SimpleX by address before
    M1-506, the phantom (simplex, is_admin=true) row blocks the token claim —
    clear it (with the last-admin-trigger break-glass for SimpleX-only); (c)
    'bot does not receive on SimpleX' points at M1-508 until that ships.
  - >-
    Every operator step is verifiable: each major step ends with an explicit
    success check (a log line, a container health state, or a bot reply) so the
    operator is never guessing whether it worked.
  - "No code/config files changed (docs-only); mvn verify N/A by the inert-diff rule — baseline is the fork point."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main (docs-only ticket; no Java/test/migration files)
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
decision_refs:
  - D46
  - D50
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
---

# M1-509: Operator onboarding — simple (comic) + advanced admin guides

## Context

Operator onboarding is hard to follow and, in places, wrong. Real defects hit
while bringing up a deployment: SETUP_GUIDE.md tells operators to "install
signal-cli" when it is BUNDLED in the provider image; it shows Signal
register/verify as host commands when they must run via `docker compose exec`
inside the container; it never explains the admin ACI is a UUID (not a phone)
or how to obtain it; the order-of-operations (register before the wizard) is
unstated; and several failure modes we hit (bad bootstrap-sources tag crashing
the Collector; the M1-506 phantom-admin row blocking the token claim; SimpleX
"bot does not receive" = M1-508) have no runbook entry.

The goal is to make setup SUPER simple: a SIMPLE guide a non-expert can follow
like a comic book for the default SimpleX-only path, and a separate ADVANCED
guide for multi-adapter / Signal / recovery. M1-507 corrected the SimpleX
token wording; this ticket restructures and completes the operator guides.

## Acceptance

See the YAML `acceptance:` list. In prose: split into a beginner SIMPLE guide
(comic-book steps, each with a success check) and an ADVANCED guide; document
Signal correctly (bundled binary, docker-exec registration, captcha, ACI not
phone, order-of-operations); and add runbook entries for the gotchas
(bootstrap tag rules, phantom-admin clear, SimpleX-not-receiving → M1-508).

## Notes

- **Simple = comic book.** Optimize for a tired non-expert: one action per
  step, exact copy-paste, an explicit "you should now see …" after each, and no
  jargon. Default path only (SimpleX-only, local LLM). Push everything else to
  the advanced guide.
- **Don't repeat M1-507/M1-508.** This ticket is docs only. Where behavior is
  still broken (SimpleX inbound, M1-508), the guide says so rather than pretending
  it works.
- **Source the gotchas from real incidents** (2026-06-28 bring-up): GLM-AI tag
  crash, phantom-admin claim block, compose token-env omission (a381aedf),
  Signal binary-is-bundled confusion.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-509-*.md
```
