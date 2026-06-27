---
id: M1-492
title: "Production javadoc/contract drift: stale or wrong SPI/handler contracts"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any behavioral change beyond the StreamSource.stop drain obligation (15#F2/08#F1/09#F2 are documentation/contract corrections only)."
acceptance:
  - >-
    StreamSource.stop()'s SPI contract states the drain-to-outbox obligation the
    architecture spec and the consumer rely on (StreamSource.java:40-45;
    StreamSourceRegistration.java:68-78 assumes stop() flushes in-flight events).
    The contract is made explicit and a test asserts stop() drains in-flight
    events to the outbox rather than dropping them.
  - >-
    ScopeRef.Group's javadoc no longer claims group-scope dispatch is deferred —
    it is live in v1 (SignalGroupHandler builds ScopeRef.Group and dispatches)
    (ScopeRef.java:18-21).
  - >-
    EligiblePostQuery.readVocabulary()'s javadoc no longer promises graceful
    degradation the code does not implement — either the javadoc is corrected to
    match the throwing behavior, or the code is made to degrade as documented
    (EligiblePostQuery.java:345-365). Whichever is chosen, javadoc and behavior
    agree.
  - >-
    SimpleXConfig's @Startup validation is either wired so it actually runs (the
    messaging-adapter jar is currently not CDI-indexed by the provider, so the
    @PostConstruct never fires) or its dead inert path is removed/documented as
    intentionally inert (SimpleXConfig.java:32-34,114-129).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/StreamSourceStopDrainIT.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-492: Production javadoc/contract drift: stale or wrong SPI/handler contracts

## Context

From `/deep-code-review full` (2026-06-27), four production contract/javadoc
drifts sharing one topic (all verified at source):

- **01#F1** (`StreamSource.stop()`): the SPI under-specifies the drain-to-outbox
  obligation that `docs/spec/architecture.md` mandates ("MUST aggressively flush
  in-flight events to the outbox") and that `StreamSourceRegistration` assumes —
  the only finding here with a behavioral/test component.
- **08#F1** (`ScopeRef.Group`): javadoc says group dispatch is "deferred," but it
  is live in v1.
- **15#F2** (`EligiblePostQuery.readVocabulary`): javadoc promises empty-set-on-
  failure graceful degradation; the code throws `IllegalStateException`.
- **09#F2** (`SimpleXConfig` `@Startup`): the eager validation path is inert
  because the messaging-adapter jar is not CDI-indexed by the provider.

## Acceptance

See frontmatter. Make the `StreamSource.stop()` drain contract explicit and
tested; correct the three stale/wrong javadoc-vs-code drifts so doc and behavior
agree.

## Out-of-scope

See frontmatter. Only `stop()` carries a behavioral change; the rest are
contract/doc corrections.

## Notes

- Source: `/deep-code-review full` (2026-06-27), reports 01#F1, 08#F1, 15#F2, 09#F2.
- For 15#F2 and 09#F2 the implementer chooses doc-to-match-code or
  code-to-match-doc, justified in the commit; the bar is internal consistency.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-492-*.md
```
