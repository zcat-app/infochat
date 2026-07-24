---
id: M1-686
title: Fix wizard invite guidance and stale 2-secrets comment refs
status: done
created: 2026-07-24
last_updated: 2026-07-24
blocked_by: []
files_budget: 2
files_scope:
  - prod/setup.sh
  - prod/scripts/6-adapter.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-provider/**          # no handler behavior changes; D60 already shipped in M1-632
  - README.md                     # already corrected (commit e1d906ce)
  - ADMIN_GUIDE.md                # already corrected (commit e1d906ce)
  - SETUP_GUIDE.md                # guide-side wording is a separate doc commit
  - prod/scripts/[0-57-9]*.sh     # other wizard steps carry no equivalent defect
  - prod/switch-llm.sh
acceptance:
  - prod/setup.sh's closing handoff no longer names `/invite create --adapter <app> --contact <their id>` as the first-invite step; it names the bare/`--open` form and states that the code needs a `confirm` resend
  - prod/setup.sh's closing handoff names `/invite bot-contact` as the way to give the invitee the bot's own contact, and `/invite pending-contacts` as the way to obtain a contact id when a targeted `--contact` invite is wanted
  - Neither prod/scripts/6-adapter.sh:109 nor :134 claims the remote LLM key or its prompt lives in 2-secrets.sh
  - mvn verify is green from the repo root
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
decision_refs:
  - D60
reviews:
  - round: 1
    date: 2026-07-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 23
      removed: 12
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-24
  verdict: PASS
  warnings:
    - "self-check: Context cited 4-llm.sh:190 as the INFOCHAT_LLM_API_KEY write site; :190 deletes it (local-backend cleanup), the write is :566 — citation corrected inline, substance unchanged"
  blockers: []
escalation_reason:
---

# M1-686: Fix wizard invite guidance and stale 2-secrets comment refs

## Context

`prod/setup.sh`'s `print_handoff` block is the last thing the wizard prints and
the first thing a brand-new bootstrap admin acts on. Its "First moves once
connected" step 2 instructs:

    /invite create --adapter <app> --contact <their id>

That is the one invite form a brand-new admin cannot use. D60
(`docs/spec/decisions.md:77`) settled that a bare `/invite create` defaults to
`--open` precisely because "`--open` is the only practically-usable path for
onboarding a brand-new person, who has no contactId yet"; the same reasoning is
restated at the code site,
`infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java:454`.
An invitee's contact id does not exist until they have connected to the bot, so
the wizard's closing instruction sends the new admin down a dead end on their
very first action. The same defect was corrected on the documentation side in
commit `e1d906ce` (README, ADMIN_GUIDE); the wizard script was outside that
doc-only commit's scope and still carries it.

Folded in, per the §Census below: two code comments in
`prod/scripts/6-adapter.sh` assert the remote LLM key and its prompt live in
`2-secrets.sh`. They do not — `prod/scripts/2-secrets.sh:11-12` states outright
that the key "is NOT collected here: it is captured in step 4 (4-llm.sh)", and
`4-llm.sh:566` is what writes `INFOCHAT_LLM_API_KEY`.

## Census

The class is "operator-facing text in `prod/` shell scripts that misstates
current behavior". Two mechanical enumerations, both re-runnable:

    grep -rn 'invite create\|--contact' prod/ --include='*.sh'
    grep -rn '2-secrets' prod/scripts/*.sh | grep -i 'prompt\|key'

| Site | Disposition |
|---|---|
| `prod/setup.sh:120` | fix — the `--contact` first-invite instruction |
| `prod/scripts/6-adapter.sh:109` | fix — "like the LLM key in 2-secrets.sh" |
| `prod/scripts/6-adapter.sh:134` | fix — "mirroring the LLM-key prompt in 2-secrets.sh" |

The first enumeration returns exactly one site; the `--contact` guidance is not
duplicated anywhere else under `prod/`. The second returns two, both in the same
file — the second (`:109`) was missed on first inspection and surfaced only by
running the enumeration, which is why it is listed rather than hand-recalled.

## Acceptance

- `prod/setup.sh`'s closing handoff no longer names
  `/invite create --adapter <app> --contact <their id>` as the first-invite
  step. It names the bare / `--open` form, and states that the code needs a
  `confirm` resend (the M1-051 confirm gate applies to `--open`).
- The handoff names `/invite bot-contact` as the way to hand the invitee the
  bot's own contact — they cannot reach the bot without it — and
  `/invite pending-contacts` as the way to obtain a contact id when the admin
  specifically wants a targeted `--contact` invite.
- Neither `prod/scripts/6-adapter.sh:109` nor `:134` claims the remote LLM key
  or its prompt lives in `2-secrets.sh`.
- `mvn verify` is green from the repo root.

## Out-of-scope

No handler, permission, or bundle behavior changes: D60 shipped in M1-632 and
`/invite bot-contact` / `/invite pending-contacts` shipped in M1-620 / M1-633.
This ticket only corrects what the wizard *says*. The README and ADMIN_GUIDE
carry the same corrected guidance already (commit `e1d906ce`) and must not be
re-edited here. `SETUP_GUIDE.md` wording is deliberately excluded — its
remaining onboarding-clarity gaps are a separate doc change, not this ticket.
The other numbered wizard steps were enumerated (§Census) and carry no
equivalent defect; do not sweep them.

The two `6-adapter.sh` lines are comments only. Do not "improve" the
surrounding secret-handling logic while in that file — the comment text is the
entire change there.

## Notes

The handoff's SimpleX branch is already correct and should be left alone: it
tells the operator to DM the claim-token, notes it "is NOT an invite code", and
tells them to blank `INFOCHAT_SIMPLEX_ADMIN_TOKEN` and restart. That block was
in fact *more* accurate than the README was before `e1d906ce` — the README has
now been aligned to it, not the reverse. Keep the same voice when rewriting the
invite step.

Suggested shape for the replacement step 2 (non-binding — §Acceptance is the
contract):

    2. Invite someone:
         /invite create --adapter <app> --open       (then resend with 'confirm')
       The bot replies with a one-time code. Send them that code AND the bot's
       own contact — /invite bot-contact prints it — then they connect and send
       the code on its own as their first DM.
       Want the code locked to one person? They must connect first; then
       /invite pending-contacts shows their contact id for --contact.

- Decision: `docs/spec/decisions.md` D60 (both sub-decisions).
- Code anchor for the "no contactId yet" reasoning — **read-only citation, not a
  file this ticket edits** (it is excluded by the `infochat-provider/**`
  `out_of_scope` entry): the `handleCreate` bare-form normalization comment in
  `InviteCommandHandler.java:454`.
- Adjacent pattern: `print_handoff` in `prod/setup.sh` — per-adapter `case`
  arms, plain `echo` lines, no colour codes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-686-wizard-handoff-invite-guidance.md
```
