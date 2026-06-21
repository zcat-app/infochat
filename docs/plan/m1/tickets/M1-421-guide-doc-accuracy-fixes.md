---
id: M1-421
title: Fix minor guide-doc inaccuracies (confirm list, source types, xref)
status: pending
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
files_budget: 4
files_scope:
  - ADMIN_GUIDE.md
  - USER_GUIDE.md
  - SETUP_GUIDE.md
complexity: low
risk: low
security_relevant: false
migration_touch: false
out_of_scope:
  # The /unfollow-source guide entries (USER_GUIDE worked example + id format) are
  # owned by M1-419 — do not touch them here to avoid a same-file conflict.
  - infochat-provider/**
  - infochat-collector/**
  - docs/spec/**
  - docs/design/**
acceptance:
  - ADMIN_GUIDE.md Commands-that-require-confirmation section (around line 272) lists
    /ban (and /remove-source, and the soft-delete-revival path of /source-enable),
    all of which are confirm-gated in code (BanCommandHandler / RemoveSourceCommandHandler /
    SourceEnableCommandHandler via ConfirmStateService). The generalization that
    targeted constructive actions are unconfirmed is softened so it no longer implies
    /ban is unconfirmed.
  - USER_GUIDE.md source-type sentence (around line 213) lists only the real
    auto-detected kinds — RSS, Bluesky, Reddit, YouTube, Odysee, Nostr (the
    `SourceKind` set) — and no longer presents "X-via-Nitter" as a distinct detected
    type; instead it notes Nitter/X feeds are added as ordinary RSS (a `NitterFetcher`
    handles them under the RSS kind).
  - SETUP_GUIDE.md (around line 70) points to docs/design/06-messaging.md §6.5.1 for
    the detailed SimpleX/Signal account-creation walkthrough (07-deployment.md §7.7.2/
    §7.9 only name registration as a step; the detailed steps live in 06-messaging.md).
  - The diff touches only the three guide files (git diff --stat); no behavior/code change.
test_plan:
  adds:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
decision_refs:
reviews: []
revisions: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-421: Fix minor guide-doc inaccuracies (confirm list, source types, xref)

## Context

Three low-severity factual inaccuracies surfaced in the guide-doc audit
(2026-06-21). All are doc-only wording fixes; none change behavior.

1. **ADMIN_GUIDE.md:272-274 — confirmation list incomplete.** The section lists
   only `/invite create --open`, `/invite revoke`, `/reject-group` as confirm-gated
   and states "Targeted, constructive actions ... don't [confirm]." But `/ban` is
   confirm-gated (`BanCommandHandler` prompt → `/ban confirm` via
   `ConfirmStateService`), as are `/remove-source` and the soft-delete-revival path
   of `/source-enable`. `/ban` is targeted, so the generalization reads as if it is
   unconfirmed. Add the missing commands; soften the generalization.

2. **USER_GUIDE.md:213-214 — "X-via-Nitter" is not a detected source type.** The
   closed `SourceKind` set is RSS / NOSTR / BLUESKY / REDDIT / YOUTUBE / ODYSEE
   (`KindResolver`), with host auto-detection only for bsky/reddit/youtube/odysee.
   Nitter/X feeds ARE supported, but ingested as ordinary RSS (a `NitterFetcher`
   exists under the RSS kind) — the bot does not detect a distinct "X-via-Nitter"
   type. Align the listed types with reality.

3. **SETUP_GUIDE.md:70-71 — cross-reference imprecision.** It points to
   `07-deployment.md §7.7.2/§7.9` for "detailed account-creation steps" for SimpleX/
   Signal, but those anchors only NAME registration as a wizard/bootstrap step; the
   detailed walkthrough is `06-messaging.md §6.5.1` (which 07-deployment.md itself
   points to). Add/redirect the reference.

## Out-of-scope

The `/unfollow-source` USER_GUIDE worked example + id-format correction are owned by
M1-419 (which makes the command real) — leave them alone here to avoid a same-file
edit conflict. No code, spec, or design changes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-421-*.md
```
