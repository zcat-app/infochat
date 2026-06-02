---
id: M1-156
title: "Misc security-low hardening (Redactor separator, invite per-code counter, AddSource userinfo)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the SSRF bundle (M1-135) and the typed-signal work (M1-151)
  - inventing a containment root for bootstrap paths (operator-supplied config is not a privilege boundary)
acceptance:
  - "Redactor's generic key/value separator pattern is widened (e.g. {0,20} or possessive) so a key with a long separator run does not evade redaction; a long-separator test is added"
  - "InviteCodeConsumer adds a per-code attempt counter (not only per (adapter, contact_id)) and periodically evicts stale breachAudited entries — gated on confirming invite-code entropy first (if codes are high-entropy random, document why per-contact keying is sufficient and close)"
  - "AddSourceArgs.parseUri rejects getRawUserInfo() != null at parse time with a clear error (credentials are otherwise stored but un-fetchable)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §Invite-code registration
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-156: Misc security-low hardening

## Context

Three small defense-in-depth items: (C-REDACTOR-SEP) `Redactor.java:52-54`'s
generic separator `[\"'\\s:=]{0,5}` lets a key with >5 separators evade the
catch-all; (B-INVITE-COUNTER) `InviteCodeConsumer.java:74-76` keys the
brute-force counter per `(adapter, contact_id)`, so N contact ids get N× the
budget against one code, and the in-memory `breachAudited` set is unbounded —
**gate priority on confirming invite-code entropy** (high-entropy random codes
make per-contact keying acceptable → may close as NON-ISSUE);
(C-USERINFO-SRC) `AddSourceArgs.parseUri` accepts userinfo in the source URI,
storing un-fetchable credentials.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-REDACTOR-SEP, §B-INVITE-COUNTER,
  §C-USERINFO-SRC; `opus-47-full-handout.md` §F-SEC-25/22/19.
- Confirm invite-code format/entropy before scoping the per-code counter (the
  finding is conditional).
