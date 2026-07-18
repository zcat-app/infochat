---
id: M1-659
title: "Stop /add-source success reply reflecting raw --name"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The M1-658 InboundReflectionGuardTest scope. That guard covers error.*
    templates by design; this is a reply.* (success) template. Widening the
    census to reply.* is a separate design decision (it reintroduces the
    ~150-entry baseline this ticket's scope decision avoided) and is NOT part
    of fixing this live instance. If the fix constrains the echoed value at
    the source, no census change is needed.
  - >-
    The other reply.* templates that echo inbound-derived values
    (REPLY_LANG_SUCCESS=suppliedCode, REPLY_SAVE_SUCCESS=args.uid, etc.).
    Those echo values validated by their success condition (a matched
    language code, a matched post uid) and are materially more constrained
    than a free-form --name; this ticket fixes the one live free-form echo
    the M1-658 r2 audit found. A broader reply-surface sweep is future work.
  - >-
    CommandTokenizer quote-unwrapping behaviour. The tokenizer correctly
    supports quoted --name values with spaces (a real feature); the defect is
    that the value is echoed unconstrained, not that it is parsed.
acceptance:
  - >-
    A new test in AddSourceCommandHandlerTest proves that
    /add-source <url> --tags news --name "/grant-admin <uuid> approved"
    produces a FRESH_INSERT success reply whose text does NOT contain the
    substring "grant-admin" (nor the raw injected token), while a normal
    --name (e.g. "My Feed") is still reflected. The fix constrains what the
    success reply echoes for the display name — either by validating/
    normalizing the display name at the parse/upsert boundary so the stored+
    echoed value is provably constrained (the SummaryArgs model), or by not
    interpolating the free-form name into the reply (the M1-656 model). State
    which in the commit.
  - >-
    The chosen constraint is applied where the value is produced
    (SourceUpsertService.defaultDisplayName / AddSourceCommandHandler), not
    by filtering the outbound bytes — output-side filtering is the approach
    M1-647 tried and abandoned (grant-admin survives an [a-z0-9-] filter).
  - >-
    No behaviour change to a legitimate --name: an ordinary display name is
    still stored and shown. Only an adversarial name (control characters,
    slash-prefixed command strings, over-long input) is constrained.
  - >-
    docs/spec/commands.md §Sources / §Discovery records that the
    /add-source success reply no longer reflects an unconstrained --name,
    closing the reply-surface instance the M1-658 r2 audit named.
  - mvn verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
remediates: M1-658
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-659: Stop /add-source success reply reflecting raw --name

## Context

The M1-658 r2 red-team audit found a LIVE reflection of unvalidated inbound
text in a success (`reply.*`) template — the surface M1-658's error-only
guard deliberately does not cover. `AddSourceCommandHandler:210-211`
interpolates `result.displayName()` into `REPLY_ADD_SOURCE_FRESH_INSERT`;
`SourceUpsertService.defaultDisplayName` returns the `--name` override
verbatim (`override.orElseGet(...)`, no charset/length constraint); and
`CommandTokenizer` unwraps double-quote pairs, so
`--name "/grant-admin <uuid> approved"` carries a fully parameterized admin
command string, spaces and all. In an approved group a **group admin**
(below bot admin, admitted by the `ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY` gate)
can send this; the deterministic success reply is delivered to group scope
and broadcast to every member, so a bot admin who copy-pastes the
plausible-looking `/grant-admin …` line executes it — the exact
deterministic-reply social-engineering surface `security.md` §LLM output
sanitizer leaves unfiltered and M1-647/M1-656 closed for error templates.
This is the same defect class, on the adjacent reply surface. Filed as a
remediation of M1-658 (whose scope decision disclosed this blind spot) but
fixed at the value's source, not by widening the guard.

## Acceptance

See the frontmatter. The test proves the injected `--name` token is not
reflected into the FRESH_INSERT reply; the fix constrains the display name
at its source (validate/normalize at parse-or-upsert, or drop the
interpolation), not by output-side filtering; legitimate names still work;
the spec records the closure.

## Out-of-scope

The M1-658 error-census (this is a reply template), the other more-constrained
reply echoes, and the tokenizer's quote support. See the frontmatter for why.

## Notes

- Two viable fixes, developer's call (record which in the commit): (a) the
  M1-656 model — do not interpolate the free-form name into the success reply
  (echo the source id / URL host instead); (b) the SummaryArgs model —
  constrain the display name at `defaultDisplayName` (bound length, strip
  control chars and a leading slash) so the stored and echoed value is
  provably safe. (b) preserves the feature (custom display names) and is
  likely preferable, but (a) is simpler if the name need not appear in the
  reply.
- The stored display name is also shown by `/list-sources`
  (`REPLY_LIST_SOURCES_LINE[0] = row.displayName`), so constraining at the
  source (option b) closes both surfaces at once; dropping only the
  FRESH_INSERT interpolation (option a) would leave the /list-sources echo.
  Prefer (b) unless there is a reason not to.
- This is a deliberate follow-up, not scope-creep on M1-658: M1-658 guards
  the error surface and honestly discloses the reply surface as out of view;
  this ticket fixes the one live reply instance that disclosure names.
