---
id: M1-533
title: "6b SimpleX provisioning: tighten the stdout error-marker so operator-controlled output can't false-trigger a failure"
status: done
created: 2026-06-30
last_updated: 2026-07-01
blocked_by: []
files_budget: 2
files_scope:
  - prod/scripts/6b-simplex-provision.sh
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The decision to parse stdout rather than the exit code — that is correct (simplex-chat exits 0 even on a bad command, per the spike) and stays."
  - "The provisioning command sequence (profile-create / /ad / /auto_accept on / /show_address), the idempotency behavior, or the D37 transient contact-link print."
  - "The data-dir / db-prefix handling."
acceptance:
  - >-
    The failure-marker grep in 6b-simplex-provision.sh is tightened from the
    broad `bad chat command|(^|[^a-z])error` to match only simplex-chat's actual
    error forms (e.g. anchored `bad chat command` and the known
    `simplex-chat: ... : <haskell-exception>` line shape), so that
    operator-controlled content appearing in stdout (a display name containing
    the substring "error") can no longer false-trigger a provisioning failure.
  - >-
    SimpleXProvisioningWiringTest gains a @Test: a provisioning run whose display
    name contains the word "error" (e.g. "Error Corp"), with a fake docker that
    emits a NORMAL (success) simplex-chat output echoing that name, completes with
    exit 0 and no "FAIL" in the output — proving the marker no longer keys off
    operator content.
  - >-
    The existing bad-command detection still works: the test that feeds a
    `bad chat command` marker on stdout still fails provisioning (exit non-zero,
    output contains FAIL) — regression-protected.
  - >-
    `mvn -B -pl infochat-messaging-adapter -am verify` exits 0; SimpleXProvisioningWiringTest
    passes (existing 4 tests plus the new false-positive guard); repo-root
    `mvn verify` reports no regressions.
test_plan:
  modifies:
    - "infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java — add the display-name-with-error false-positive guard @Test."
  preserves:
    - all wizard wiring tests currently green on main
spec_refs:
  - "docs/spec/messaging.md §Per-adapter trust level and identity"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 59
      removed: 21
escalations: []
revisions:
  - date: 2026-07-01
    reason: >-
      clarity-fail rework — fix unresolvable spec_refs anchor and move the
      investigation-only acceptance item 1 (verified-by-reading) into Notes.
    snapshot:
      status: pending
      escalation_reason: clarity-fail
      clarity_check:
        date: 2026-07-01
        verdict: FAIL
        blockers:
          - "SPEC-REFS-VALID: `docs/spec/messaging.md §SimpleX adapter` matches no heading in that file; the SimpleX-specific content is under `## Per-adapter trust level and identity` (line 214). Fix by changing the spec_ref to that heading."
          - "ACCEPTANCE-RUNNABLE: acceptance item 1 is an investigation task whose only artifact is text in the commit/notes (verify-by-reading, a weak form). Move it into Context/Notes and let items 2–5 stand as the acceptance criteria."
        warnings: []
      spec_refs_at_snapshot:
        - "docs/spec/messaging.md §SimpleX adapter"
      acceptance_item_1_removed: >-
        First confirm the trigger: determine (from .scratch/simplex-spike-findings.md
        and/or a fake-docker probe) whether simplex-chat echoes the operator-supplied
        --create-bot-display-name back on stdout. Record the finding in the ticket
        notes / commit message.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-01
  verdict: PASS
  warnings: []
  blockers: []
---

<!--
  Revision 2026-07-01 (clarity-fail rework, /m1-tick run bounded self-refine):
  the former acceptance item 1 (an investigation-only "confirm whether simplex-chat
  echoes the display name, record in notes" step, verified only by reading) was moved
  out of the acceptance list into the Notes section below, where the confirmed finding
  now lives. spec_refs anchor corrected from the nonexistent §SimpleX adapter to
  §Per-adapter trust level and identity. See revisions: in frontmatter for the snapshot.
-->


# M1-533: Harden 6b's stdout error-marker against operator-controlled content

## Context

Verified-as-plausible during the setup-wizard review (flaws.md F18). `6b`
correctly decides provisioning success/failure by parsing simplex-chat stdout
(it exits 0 even on a bad command), but the marker is broad:
`grep -qiE 'bad chat command|(^|[^a-z])error'`. The bot DISPLAY NAME is operator
input that flows into `--create-bot-display-name "$display_name"` and may be
echoed back, so a name containing the word "error" (e.g. "Error Corp") could
false-trigger a provisioning failure.

CONFIRMED: simplex-chat echoes the profile's display name on stdout as
`Current user: <display_name>` (spike `.scratch/simplex-spike-findings.md`, the
`Current user: infobot` lines under items 3/5/6). So a display name like
"Error Corp" surfaces as `Current user: Error Corp`, which the broad
`(^|[^a-z])error` marker matches (the space before "Error" satisfies `[^a-z]`) and
false-triggers a provisioning failure. This is a real false-positive vector, not
merely a smell — keying the failure detector off a broad "error" substring that
operator content can populate must be removed.

## Acceptance

See the YAML `acceptance:` list. In prose: tighten the marker to simplex-chat's
real error forms, and add a test proving a benign display name containing "error"
no longer fails provisioning while a real `bad chat command` still does. (The echo
behavior that motivates the fix is already confirmed — see Context above.)

## Out-of-scope

See the YAML `out_of_scope:` list. The stdout-parsing approach itself is correct
and stays; only the marker pattern tightens.

## Notes

- Trigger confirmed (formerly acceptance item 1): simplex-chat echoes the display
  name as `Current user: <name>` on stdout (spike, items 3/5/6), so the broad
  marker is a confirmed false-positive vector — see Context.
- Spike reference: `.scratch/simplex-spike-findings.md` (items 3, 5) documents the
  real error strings — `bad chat command` for a rejected command, and a Haskell
  `hGetLine: end of file` shape on the fresh-DB interactive-prompt path.
- Keep the matched-line echo (currently line ~135) consistent with the tightened
  marker so the diagnostic still prints the real error.
