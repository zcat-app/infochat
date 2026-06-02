---
id: M1-145
title: "/save personal-tag length + count caps"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the read-side caps (already enforced via ChatToolDispatcher.validateInputLengths)
  - all command handlers under infochat-provider/src/main/java/app/zcat/infochat/provider/command/ other than SaveCommandHandler.java
  - application.properties (the caps use @ConfigProperty defaultValue, matching the read-side comparator ChatToolDispatcher; no profile-pinned entries are added)
acceptance:
  - "/save -t enforces a per-tag length cap and a per-call tag-count cap, read from profile-driven config, at the parser boundary"
  - "Over-cap input is rejected with friendly-error bundle keys rather than stored"
  - "A test asserts an over-length tag and an over-count tag list are both rejected"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Rate limiting
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 138
      removed: 9
revisions:
  - date: 2026-06-02
    reason: budget-breach refine (BundleKeys.java added to files_scope; clarity WARN on out_of_scope folded in)
    snapshot:
      files_budget: 5
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
        - infochat-provider/src/main/resources
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
      complexity: low
      risk: low
      round_cap: 2
      out_of_scope_summary: "read-side caps (ChatToolDispatcher); other command handlers"
      acceptance_count: 4
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation files_scope breach. The idiomatic
      implementation must add two new error-key constants to
      infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java,
      which is NOT listed in files_scope (scope covers only
      SaveCommandHandler.java, src/main/resources, and the test/command dir).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-02
    verdict: CLEAN
    base: c4c1997
    head: 287d60b
    verdict_file: docs/plan/m1/redteam/M1-145-2026-06-02.md
    out_of_model_count: 1
    note: |
      CLEAN — parser-boundary length + count caps on /save -t personal
      tags, ordered before any DB write, on the sole saved_post.personal_tags
      write path. One OUT-OF-MODEL advisory: the comment at
      SaveCommandHandler.java:128-131 overstates the read-side mirror (length
      defaultValue=64 vs ChatToolDispatcher input-max-length=500; the count
      cap 20 does match list-max-size=20). 64 is stricter than 500, so no
      security gap — comment-accuracy nit only. Audit ran post-commit /
      pre-merge on branch m1/M1-145-save-tag-caps.
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "OUT-OF-SCOPE-SPECIFIC: the second out_of_scope entry (\"other command handlers\") is vague and circular. Consider replacing with a named set or glob (e.g. \"all handlers under infochat-provider/src/main/java/.../command/ other than SaveCommandHandler.java\")."
  blockers: []
---

# M1-145: /save personal-tag length + count caps

## Context

`SaveCommandHandler.java:265-321` accepts unbounded personal-tag strings and
counts (bounded only by the 64 KB body cap). `/saved` interpolates them into
outbound (bypassing the chat body cap) and `listSaves` reads them into the
prompt. The read side is capped; the write side is the symmetric obligation.

## Acceptance

See frontmatter. Profile-driven per-tag length + per-call count caps at the
parser; friendly-error bundle keys.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-SAVE-UNBOUNDED;
  `opus-47-full-handout.md` §F-SEC-13; `opus-47-only-handout.md` §S5.
- Suggested defaults: `infochat.save.personal-tag-max-length=64`,
  `infochat.save.personal-tag-max-count=20`.
