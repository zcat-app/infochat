---
id: M1-156
title: "Misc security-low hardening (Redactor separator, invite per-code counter, AddSource userinfo)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 13
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core
  - infochat-core/src/main/resources/db/migration
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/main/resources/bundles
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the SSRF bundle (M1-135) and the typed-signal work (M1-151)
  - inventing a containment root for bootstrap paths (operator-supplied config is not a privilege boundary)
  - a new Flyway migration version other than V33__redact_secrets_widen_separator.sql (redteam F3 reversed the in-place-V31 precedent for this ticket — V31 reverts to its original content and the widened function lands as V33)
  - re-keying the brute-force counter per-code (closed as NON-ISSUE by acceptance item 3's entropy argument)
acceptance:
  - "Redactor.CATALOGUE's generic key/value separator quantifier is widened from {0,5} to {0,20} so a key followed by a 6-20-char separator run no longer evades the catch-all; a long-separator test is added to RedactingLogFilterTest"
  - "V31's redact_secrets_jsonb generic pattern is widened identically — the regex string stays textually identical to the Java side (plain {0,20}; PostgreSQL's ARE engine has no possessive quantifiers) via in-place edit of V31__service_role_login_and_audit_redaction.sql; RedactorSqlParityIT's generic-family sample carries a long separator run so the parity guard exercises the widened quantifier on both engines"
  - "Per-code attempt counter is closed as NON-ISSUE: invite codes are minted via gen_random_uuid() (CSPRNG UUIDv4, 122 random bits; InviteCommandHandler SELECT_NEW_CODE_SQL) and docs/spec/security.md §Invite-code registration mandates per-(adapter, contact_id) keying ('prevents a patient brute-force search of the UUID space'); a comment at the InviteCodeConsumer counter site documents this entropy argument"
  - "InviteCodeConsumer.breachAudited gains stale-entry eviction: entries whose last breach observation is older than the brute-force window are swept opportunistically during consume, so the in-memory set cannot grow without bound; eviction preserves the existing re-audit semantics (window expired = breach event ended); a test is added to InviteCodeConsumerTest"
  - "AddSourceArgs.parseUri rejects URIs with getRawUserInfo() != null at parse time; parse surfaces a dedicated Failure bundle key (error.add_source.userinfo_rejected) added to bundles/en.properties and cs.properties; a test is added to AddSourceArgsTest"
  - "Redteam F1 (supersedes item 1's {0,20} figure): the generic pattern's separator class is widened identically on both engines from [\"'\\s:=] to also accept ',', '|', '<', '>', '(', ')' and '-', and the bound rises to {0,64} so column-aligned formats (the finding's repro) no longer evade; the pattern-site comment documents why the bound stays finite (keeps the spec's 'adjacent' meaningful; caps backtracking retries per position) and where the deliberate cliff sits; in-bound tests for the new separator characters land in RedactingLogFilterTest, and a 65-separator negative test pins the cliff on both engines"
  - "Redteam F2: the generic pattern's \\s shorthand is eliminated on both engines — whitespace is spelled as an explicit character class that is a strict superset of Java's ASCII \\s ([ \\t\\n\\x0B\\f\\r]) plus U+00A0 (NBSP) via the \\u00A0 escape valid in both Java and PostgreSQL ARE, textually identical in Redactor.java and the SQL function; RedactorSqlParityIT gains generic-pattern edge samples (new separator chars in-bound, NBSP-separated, over-the-bound negative) in a dedicated list exercised on both engines WITHOUT disturbing the one-sample-per-family size tripwire; the textual-identity contract comment stays accurate"
  - "Redteam F3 (supersedes item 2's 'via in-place edit of V31' clause): V31__service_role_login_and_audit_redaction.sql reverts to its pre-ticket content (original {0,5} pattern) so already-applied checksums stay valid, and the final post-F1/F2 pattern lands via new migration V33__redact_secrets_widen_separator.sql containing the complete CREATE OR REPLACE of redact_secrets_jsonb; Redactor.java's and RedactorSqlParityIT's mirror-site comments point at V33; the migration-cursor canary rises to >= 33 (test renamed migrationCursorReachesV33) so the parity guard proves the V33 function is the one under test"
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
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 272
      removed: 52
  - round: 2
    date: 2026-06-05
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 639
      removed: 72
  - round: 2
    date: 2026-06-05
    verdict: OVERRIDE-APPROVE
    checks:
      # carried through from the overridden MANUAL verdict; the FAIL
      # remains as the reviewer reported it — the verdict alone carries
      # the override.
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
escalations:
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer-triggered before implementation. Acceptance item 1
      (widen Redactor's generic separator quantifier) requires mirroring
      the same change in V31's redact_secrets_jsonb
      (infochat-core/src/main/resources/db/migration/V31__service_role_login_and_audit_redaction.sql:103
      hand-copies the {0,5} pattern; M1-169's RedactorSqlParityIT guards
      the pair). That path is outside files_scope and the ticket declares
      migration_touch: false. Secondary: item 3's "clear error" needs a
      new bundle key in bundles/en.properties + cs.properties, also
      outside files_scope.
  - date: 2026-06-05
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL. SCOPE-DRIFT-CHECK: FAIL — "Must-shrink violation,
      mechanical: round 2 grew along ALL THREE dimensions vs round 1
      (files 13 > 12, lines added 639 > 272, lines removed 72 > 52), and
      the codified exception cannot apply — round 1's verdict was
      APPROVE, so there is no round-1 REWORK item authorizing a growing
      refactor." UNCERTAINTY: "the growth traces entirely to the
      ticket's own post-APPROVE redteam revision (acceptance items 6-8
      mandate a net-new V33 migration file, new parity edge samples, new
      negative tests, plus the redteam audit file) ... any diff that
      shrinks below round 1's stats necessarily fails ACCEPTANCE-CHECK
      ... Every other check passed, including all nine acceptance items
      and a green full-suite mvn verify; no rework item exists that is
      addressable in the existing diff without violating acceptance."
  - date: 2026-06-05
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS — 3 findings (all INFO-LEAK, low), 2
      out-of-model advisories. Audit ran post-APPROVE pre-commit on the
      working tree (base 05159f1). Verbatim record:
      docs/plan/m1/redteam/M1-156-2026-06-05.md; findings mirrored in
      redteam_findings below. (1) finite separator bound keeps a cliff at
      21+ chars and the separator class excludes common punctuation;
      (2) Java vs PostgreSQL \s / case-folding semantics diverge on
      non-ASCII input despite textual regex identity, parity corpus is
      ASCII-only; (3) in-place V31 edit + `flyway repair` on a
      pre-existing DB silently retains the old {0,5} SQL function.
revisions:
  - date: 2026-06-05
    reason: redteam-finding-refine (3 low INFO-LEAK findings — separator-class gaps + bound raised to {0,64} with documented cliff; explicit whitespace class for cross-engine \s parity + NBSP sample; V31 revert + V33 migration to close the flyway-repair drift)
    snapshot: |
      files_budget: 11
      out_of_scope: 4 items (SSRF/typed-signal; no bootstrap containment root;
        no new Flyway migration version — V31 edited in place per M1-116
        precedent; per-code counter closed as NON-ISSUE)
      acceptance: 6 items — Redactor {0,5}->{0,20} widening + long-separator
        test; V31 redact_secrets_jsonb widened identically via in-place edit,
        parity sample carries long separator; per-code counter closed as
        NON-ISSUE with entropy comment; breachAudited stale-entry eviction +
        test; AddSourceArgs.parseUri rejects userinfo with dedicated bundle
        key + test; mvn -B clean verify exits 0
  - date: 2026-06-05
    reason: budget-breach-refine (V31 redact_secrets_jsonb mirror + bundle keys outside files_scope; invite-code entropy confirmed high; clarity warnings folded in)
    snapshot: |
      files_budget: 8
      files_scope:
        - infochat-core/src/main/java/app/zcat/infochat/core
        - infochat-provider/src/main/java/app/zcat/infochat/provider
        - infochat-collector/src/main/java/app/zcat/infochat/collector
        - infochat-core/src/test/java/app/zcat/infochat/core
        - infochat-provider/src/test/java/app/zcat/infochat/provider
      risk: low
      migration_touch: false
      out_of_scope: 2 items (SSRF bundle / typed-signal; no bootstrap containment root)
      acceptance: 4 items —
        - "Redactor's generic key/value separator pattern is widened (e.g. {0,20} or
          possessive) so a key with a long separator run does not evade redaction; a
          long-separator test is added"
        - "InviteCodeConsumer adds a per-code attempt counter (not only per (adapter,
          contact_id)) and periodically evicts stale breachAudited entries — gated on
          confirming invite-code entropy first (if codes are high-entropy random,
          document why per-contact keying is sufficient and close)"
        - "AddSourceArgs.parseUri rejects getRawUserInfo() != null at parse time with a
          clear error (credentials are otherwise stored but un-fetchable)"
        - "mvn -B clean verify from the repo root exits 0"
overrides:
  - date: 2026-06-05
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — "Must-shrink violation, mechanical:
      round 2 grew along ALL THREE dimensions vs round 1 (files 13 > 12,
      lines added 639 > 272, lines removed 72 > 52), and the codified
      exception cannot apply — round 1's verdict was APPROVE, so there
      is no round-1 REWORK item authorizing a growing refactor."
    user_justification: |
      The growth is authorized by the redteam-finding refine (acceptance
      items 6-8): fixing the three audit findings mandates a net-new V33
      migration, parity edge samples, negative tests, and the audit
      record itself, so shrinking below round-1 stats would violate
      acceptance. M1-131 precedent: redteam-mandated growth on an
      already-APPROVEd ticket resolves by override, not shrinking. All
      substantive checks passed (acceptance 9/9, test-integrity,
      out-of-scope, negative-space, spec-conformance, green suite).
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-05
    category: INFO-LEAK
    severity: low
    promise: |
      "Audit-log writes pass through a redaction hook that masks values
      matching a closed catalogue of API-key shapes … Generic 32+-character
      hex / base64 strings adjacent to the case-insensitive substrings
      api[_-]?key, secret, token, password, bearer." (§Secrets handling) and
      "Stdout console logs pass through the closed API-key catalogue
      redactor."
    gap: |
      The {0,5}→{0,20} widening (Redactor.java generic pattern + V31 SQL
      mirror) moves the evasion cliff but keeps two finite escape hatches:
      (a) a 21+-char separator run still defeats the catch-all on both
      engines; (b) the separator class ["'\s:=] excludes common punctuation
      (`,`, `|`, `->`, parens), so `password -> <secret>` never matches at
      any quantifier. New tests only exercise inside-the-bound shapes.
    repro: |
      A secret reaching a log message or audit_log.details_json formatted as
      `api_key:` + 21+ separator chars + 40-char key (column-aligned config
      dumps, pretty-printed YAML) or `token -> <key>` passes both redactors
      unredacted; a bot admin reading /audit or anyone with log access sees
      the raw key. Attacker rarely controls the operator key's surrounding
      formatting — hence low.
    suggested_fix_class: input-sanitization
  - date: 2026-06-05
    category: INFO-LEAK
    severity: low
    promise: |
      "The audit_log writer consumes the same Redactor utility so the two
      cannot drift." (§Secrets handling) — Java-side and read-side redaction
      surfaces stay equivalent (M1-169 parity guard).
    gap: |
      Textual regex identity does not give semantic identity across engines:
      Java \s without UNICODE_CHARACTER_CLASS is ASCII-only, PostgreSQL ARE
      \s is [[:space:]] (can include U+00A0 under ICU/UTF-8); (?i) folding
      scope differs too. RedactorSqlParityIT's corpus is ASCII-only, so the
      divergence is invisible to the suite.
    repro: |
      `password<U+00A0><32-char-secret>` (NBSP, common in copy-pasted rich
      text) is left raw by the Java console Redactor and audit-write hook
      while redact_secrets_jsonb may mask it on read — console log stream
      leaks what the audit view hides. mvn verify stays green.
    suggested_fix_class: other
  - date: 2026-06-05
    category: INFO-LEAK
    severity: low
    promise: |
      "audit_log_view is a Postgres view that exposes the same columns as
      audit_log minus any redacted fields … this is the path /audit uses."
      (§DB roles) — the audit read path must reflect the current catalogue.
    gap: |
      The widened SQL pattern lands via in-place edit of already-numbered
      V31. A DB that already executed the original V31 keeps the old {0,5}
      redact_secrets_jsonb; Flyway flags a checksum mismatch, but the
      standard remedy (`flyway repair`) updates the checksum WITHOUT
      re-running the migration — the narrow function stays permanently.
      RedactorSqlParityIT runs against a fresh schema and cannot detect a
      stale deployed function.
    repro: |
      Operator with a pre-existing dev/staging DB pulls the change, hits the
      V31 checksum-mismatch startup failure, runs `flyway repair`, restarts.
      Console Redactor masks 6-20-char-separator secrets while
      audit_log_view renders them raw — silent permanent drift. Greenfield
      M1 keeps this low.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-05
    verdict: FINDINGS
    base: "05159f1 (merge-base of branch and main)"
    head: "working tree of m1/M1-156-misc-security-low-hardening-re (post-APPROVE, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-156-2026-06-05.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      All three findings are low-severity INFO-LEAK residuals of the
      {0,5}→{0,20} widening, not regressions introduced by the diff: the
      finite-bound cliff + separator-class gaps, Java-vs-PostgreSQL \s
      semantic divergence on non-ASCII input, and the flyway-repair path
      retaining the old {0,5} SQL function on pre-existing DBs. Out-of-model
      advisories: Sybil-driven O(N) sweep cost on breachAudited (bounded by
      eviction, Sybil resistance out of v1 scope) and the pre-existing
      distinct "too many attempts" reply acting as a brute-force oracle
      (spec-conformance follow-up candidate, outside this diff).
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 2: the 'close as NON-ISSUE' branch resolves to a documentation action with no verifiable artifact; if taken, produce a concrete artifact (e.g. a comment in InviteCodeConsumer.java citing the entropy value and why per-contact keying is sufficient)"
    - "COMPLEXITY-RISK-CALIBRATED: risk: low slightly under-represents that acceptance item 2 is a security-model decision about a brute-force counter; consider risk: medium"
---

# M1-156: Misc security-low hardening

## Context

Three small defense-in-depth items, refined 2026-06-05 after a pre-implementation
budget-breach escalation:

- (C-REDACTOR-SEP) `Redactor.java:52-54`'s generic separator `[\"'\\s:=]{0,5}`
  lets a key with >5 separators evade the catch-all. The same pattern is
  hand-copied into V31's `redact_secrets_jsonb` (the `audit_log_view` read-side
  mirror, guarded by M1-169's `RedactorSqlParityIT`), so the widening must land
  on both engines or the console filter and the audit view drift.
- (B-INVITE-COUNTER) the entropy gate resolved: codes are
  `gen_random_uuid()` (CSPRNG UUIDv4, 122 bits), so the per-code counter
  closes as NON-ISSUE with a documenting comment; the entropy-independent
  half — the unbounded in-memory `breachAudited` set
  (`InviteCodeConsumer.java:122`) — stays in scope and gains stale-entry
  eviction.
- (C-USERINFO-SRC) `AddSourceArgs.parseUri` accepts userinfo in the source
  URI, storing un-fetchable credentials; rejected at parse time with a
  dedicated bundle key (reusing `malformed_url` would mislead — the URL is
  syntactically valid).

Re-refined 2026-06-05 after a post-APPROVE pre-commit `/redteam` pass (3 low
INFO-LEAK findings: separator-class/bound gaps, `\s` cross-engine divergence,
V31-in-place flyway-repair drift — verbatim record in
`docs/plan/m1/redteam/M1-156-2026-06-05.md`). Fixes are acceptance items 6-8;
V33 becomes the SQL mirror site (V32 was taken by M1-134 on main, absorbed via
rebase).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-REDACTOR-SEP, §B-INVITE-COUNTER,
  §C-USERINFO-SRC; `opus-47-full-handout.md` §F-SEC-25/22/19.
- Entropy confirmation (2026-06-05): `InviteCommandHandler.SELECT_NEW_CODE_SQL`
  = `SELECT gen_random_uuid()`; spec §Invite-code registration mandates the
  per-(adapter, contact_id) keying.
- The Java and SQL generic patterns stay textually identical ({0,64}, no
  possessive form, explicit whitespace class — no \s shorthand) —
  PostgreSQL's ARE engine lacks possessive quantifiers, and textual identity
  is what the comment-based sync with V33's redact_secrets_jsonb relies on.
  ReDoS is not a concern: the separator quantifier is bounded (≤65
  backtracking retries per position, no nested quantifiers — the separator
  and value classes overlap on '=' and '-', so disjointness is NOT the
  argument), the Java side has the InterruptibleCharSequence watchdog, and
  PostgreSQL's ARE engine is automaton-based.
