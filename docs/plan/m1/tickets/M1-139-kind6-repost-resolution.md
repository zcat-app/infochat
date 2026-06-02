---
id: M1-139
title: "Kind-6 repost edge resolution"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - the broader Nostr ingest pipeline beyond the repost-edge resolution path
  - changing post.id semantics for non-Nostr posts
acceptance:
  - "A kind-6 repost edge resolves to a real post when the original event is also seen — the GetReferencesTool join returns the linked post (currently never, because to_post is a deterministic UUID-v3 of the event id while persisted posts use random UUIDs)"
  - "A test seeds a repost referencing a later-seen original and asserts the edge resolves"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/schema.md §Posts and derivatives
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-139: Kind-6 repost edge resolution

## Context

`Kind6Handler.java:142-167` stores `to_post = nameUUIDFromBytes(eventId)`
(deterministic UUID-v3); `PostPersister.java:108-119` persists posts with
`id = gen_random_uuid()`. `GetReferencesTool.java:67-80` joins
`pr.to_post = post.id` — which can never match. Every kind-6 repost edge is
structurally unresolvable; M1-100's user-visible payoff is absent.
`architecture.md` §Source identity commits to resolving the link "if and when
the original event is also seen."

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Migration version assigned at start (do not hardcode).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A19 (KIND6-REPOST, High);
  `opus-47-full-handout.md` §F-MAINT-48; `opus-47-only-handout.md` §M4.
- Option A (spec-closest): store `to_upstream_identifier`, leave `to_post` NULL
  until a resolver job fills it. Option B: deterministic `post.id` for Nostr
  posts (smaller diff, changes id semantics). Plan-writer pass — decide A vs B
  with the architecture lens; Option B redefines `post.id` module-wide.
