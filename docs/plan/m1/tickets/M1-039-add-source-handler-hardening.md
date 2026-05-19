---
id: M1-039
title: /add-source handler hardening — ban-check ordering + contact-ID redaction in exceptions
status: pending
created: 2026-05-19
last_updated: 2026-05-19
blocked_by:
  - M1-038
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceBanCheckOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceContactIdRedactionTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-036
out_of_scope:
  - any change to the spec — §User ban and §Secrets handling already commit to the behavior; this ticket is pure code remediation
  - any T2-A intake-gate work — the upstream ban check, invite gate, probation gate, rate-limit cap stay deferred to T2-A; this ticket fixes the in-handler ban-check ordering only, which remains load-bearing as defense-in-depth even after T2-A lands
  - any new bundle keys beyond the existing `error.add_source.banned` (re-use it; do NOT add `error.add_source.group_admin_banned` or equivalent variants — the spec's "one fixed reply regardless of input" precludes per-scope ban-reply variants)
  - any change to ContactIds.redact's API or implementation — that helper comes from M1-038 and is consumed unchanged
  - any change to /add-source business logic (URL probe, kind resolver, upsert flow, audit row) beyond the reorder + redaction
  - any change to AutoRegisterService or AdapterRegistry
acceptance:
  - "AddSourceCommandHandler.handle reorders the intake gates so the actor lookup AND the ban check run BEFORE the scope discriminator (the `if (scope instanceof ScopeRef.Group)` branch). After the reorder: parse args → lookupActor → if `is_banned` return fixed ban reply → then scope discriminator → then group_admin_only / kind / probe / upsert. grep -E 'is_banned|isBanned' AddSourceCommandHandler.java returns at least one match BEFORE the line containing `instanceof ScopeRef.Group` (verify by reading: the ban check precedes the scope-discriminator branch in source order)"
  - "AddSourceBanCheckOrderingTest covers: (a) banned user in DM scope receives the fixed ban reply (regression — already covered before this ticket, the test pins the new ordering); (b) banned user in group scope receives the fixed ban reply (NOT `error.add_source.group_admin_only`) — this is the M1-036 finding 2 fix; (c) non-banned non-group-admin user in group scope receives `error.add_source.group_admin_only`; (d) non-banned group-admin user in group scope proceeds past the discriminator into the URL probe / upsert path"
  - "AddSourceCommandHandler's IllegalStateException messages (the `lookupActor failed for contact_id=...` shape at the current line 178-181 region) interpolate the contact id via ContactIds.redact (consumed from M1-038). grep -E 'ContactIds\\.redact' AddSourceCommandHandler.java returns at least one match in the IllegalStateException construction path"
  - "SourceUpsertService's IllegalStateException messages (the equivalent shape with the source identifier / URL at the current line 138-141 region) interpolate the identifier through a redaction or truncation helper. The source URL is bot-admin-visible per docs/spec/security.md §Source URL visibility so full redaction is not required, but the raw URL in an exception message is still a defense-in-depth concern — implement EITHER ContactIds.redact (treats the URL like any string) OR a deliberate truncate-to-prefix helper, documented in Implementation notes. grep -E 'redact|truncateUrl|UrlRedactor' SourceUpsertService.java returns at least one match"
  - "AddSourceContactIdRedactionTest forces both IllegalStateException paths (a lookupActor SQL failure via a stubbed DataSource, and a SourceUpsertService SQL failure via the same mechanism), captures the exception message, and asserts the raw contact-id literal does NOT appear in the message. The exception's underlying SQL cause is preserved (the cause still carries the SQLException stack); only the user-derived string in the IllegalStateException message is redacted"
  - "mvn -B clean verify from the repo root exits 0; M1-036's existing AddSourceCommandHandlerIT (or whatever the M1-036 IT classes are named) continues to pass — only the new ban-check-ordering tests add behavior"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceBanCheckOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceContactIdRedactionTest.java
  preserves:
    - all existing M1-036 tests
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D11
---

# M1-039: /add-source handler hardening — ban-check ordering + contact-ID redaction in exceptions

## Context

M1-036's red-team audit returned two net-new findings against
`AddSourceCommandHandler.java` and `SourceUpsertService.java` that
are NOT covered by T2-A's intake-gate umbrella:

1. **Finding 2 (medium INFO-LEAK)** — Banned-user group-reply leak.
   The handler's `if (scope instanceof ScopeRef.Group) { return
   error.add_source.group_admin_only; }` short-circuit runs BEFORE
   the `is_banned` check at lines 126-127, so a banned user invoking
   `/add-source` in a group scope receives the group-admin error
   rather than the spec-required ban literal. The DM branch checks
   `is_banned`; the group branch doesn't. This violates §User ban's
   "Banned user receives one fixed reply per inbound message,
   regardless of input."

2. **Finding 3 (low INFO-LEAK)** — Contact-id and source URL appear
   verbatim in `IllegalStateException` messages constructed in
   `AddSourceCommandHandler.lookupActor` and
   `SourceUpsertService.upsert`. A transient SQL failure causes
   Quarkus's default exception logger to write the unredacted values
   to stdout / structured logs, violating §Secrets handling
   ("Contact IDs are logged in redacted form (prefix + ellipsis +
   suffix) outside the audit log").

T2-A will eventually wire the ban check UPSTREAM of the handler
(at the InboundRouter / intake-gate seam). When that lands, the
in-handler `is_banned` check becomes defense-in-depth rather than
the primary gate — but the spec's promise that no command surface
short-circuits the ban path means the in-handler ordering MUST
still be correct. Leaving it broken would mean T2-B (`/save`,
`/follow-tag` etc., which will be authored from this handler as
the canonical template) copies the same defect three more times.

## Definition of Done

- `AddSourceCommandHandler.handle` performs the actor lookup AND
  the ban check BEFORE the scope discriminator. A banned user in
  any scope (DM or group) receives the fixed ban reply, never the
  group-admin error.
- `AddSourceCommandHandler.lookupActor` interpolates the contact id
  via `ContactIds.redact` when constructing the
  `IllegalStateException` message. The exception's SQL cause stays
  intact for ops debugging.
- `SourceUpsertService.upsert` interpolates the source identifier
  through a redaction or truncation helper when constructing its
  `IllegalStateException` message.
- New tests pin the four ban-check-ordering scenarios (banned in
  DM, banned in group, non-banned non-admin in group, non-banned
  group-admin in group) and the absence of unredacted contact-id
  literals in captured exception messages.
- `mvn -B clean verify` exits 0; M1-036's existing tests continue
  to pass.

## Implementation notes

- **Reorder is mechanical.** Move the `lookupActor(scope)` call
  upstream of the `if (scope instanceof ScopeRef.Group)` branch,
  add the `if (actor.isBanned()) return banReply` immediately
  after, then leave the scope discriminator in its current position.
  The args-parse step can stay first (parse failures short-circuit
  with the parse-error reply; the spec does NOT require a banned-
  user check for unparseable input — parsing fails fast and the
  reply is identical to the non-banned parse-fail path, so no
  observable difference).
- **`ContactIds.redact` consumption.** M1-038 lands the helper at
  `infochat-core/.../log/ContactIds.java`. Import and use; do NOT
  reimplement.
- **`SourceUpsertService` URL redaction.** The source URL is
  visible to bot admins via `/list-sources --all` per §Source URL
  visibility, so a full prefix+ellipsis+suffix redaction is
  arguably stricter than needed. Two acceptable shapes:
  (a) `ContactIds.redact(identifier)` (treats the URL as an opaque
  string; slightly over-redacts but reuses the helper);
  (b) a small `truncateUrl(String)` helper that keeps the scheme +
  host + a truncated path (e.g. `https://example.com/path/...`).
  Either is acceptable per spec; document the choice. Option (a)
  is shorter; option (b) preserves more debugging context. The
  spec promise is "no full URL in an exception message that
  reaches stdout"; the redaction shape is implementer's call.
- **Exception-cause preservation.** The redacted message is the
  `IllegalStateException`'s message string. The `cause` argument
  (`SQLException e`) is unchanged — ops still see the full SQL
  diagnostic in the cause's stack trace. Only the user-derived
  string in the wrapping message is redacted.
- **Bundle key reuse.** Use the existing `error.add_source.banned`
  literal for both DM and group ban replies. The spec's "one fixed
  reply regardless of input" precludes per-scope variants.

## Big-picture notes

- **T2-B is the next author of a CommandHandler.** `/save`,
  `/follow-tag`, `/unfollow-tag`, `/list-sources`, `/remove-source`,
  `/tag-mode` will all be authored from `AddSourceCommandHandler`
  as the canonical pattern. Fixing the ban-check-ordering here
  means T2-B's authors copy the corrected template, not the
  defective one. The reviewer's "match existing style" rule
  reinforces this — get the style right at the first instance.
- **The in-handler ban check survives T2-A.** Even after T2-A
  wires the upstream ban gate, the per-handler `is_banned` check
  stays as defense-in-depth: a banned user who somehow bypasses
  the upstream gate (a regression, a misconfigured deployment,
  a future code path that calls the handler directly) MUST still
  hit a ban barrier inside the handler. The reorder is permanent,
  not transitional.
- **The contact-id redaction pattern is the seam for every
  future handler.** M1-038 builds the helper; this ticket is the
  first call-site fix; T2-B handlers will repeat the pattern.
  M1-019 and M1-020 (deferred) cover orthogonal log surfaces —
  stdout console filter (API keys) and exception messages
  (SafeLog wrapper) — that don't overlap with this ticket's
  per-call-site fix.

## Out-of-scope expansion

- **Upstream intake gates (T2-A).** The probation gate, rate
  limit, invite gate, transport-level rate cap are all T2-A
  territory and stay deferred here. The in-handler ordering fix
  is independent of them.
- **Per-scope ban-reply variants.** Do NOT introduce
  `error.add_source.group_admin_banned` or similar; the spec's
  "one fixed reply regardless of input" means the same literal
  for every banned-user input.
- **`AutoRegisterService` / `AdapterRegistry` changes.** This
  ticket touches only the handler and the upsert service.
- **Other handlers.** `/help` and the umbrella router are
  untouched. M1-038 covers the router-level concerns.
- **New bundle keys.** Re-use `error.add_source.banned`; the
  ban-check ordering fix needs no new resource entries.

## Authorized test changes

- (none — this ticket adds two new test files and does not modify
  any pre-existing test. M1-036's existing tests pin the
  non-ban-related happy paths and remain green.)

## Alternatives considered

- **Leave the in-handler ban check broken and rely on T2-A.**
  Rejected — the spec's promise that ban runs "regardless of
  input" is a per-handler invariant per §User ban, not a
  router-level convenience. Even when T2-A lands, an in-handler
  bypass is a defect; the reviewer would flag it on a future
  audit. Fix at the lowest cost surface.
- **Add a single `assertNotBanned(actor)` helper to abstract the
  pattern.** Rejected — three lines of code at the top of
  `handle()` is below the abstraction threshold. T2-B can extract
  the helper if the same shape appears in 3+ handlers.
- **Redact the source URL using the existing `UrlRedactor` from
  M1-023 / M1-028.** Considered — that redactor strips userinfo
  segments from URLs. It MAY suffice for SourceUpsertService;
  the implementer should check whether `UrlRedactor` exists as
  a callable utility and reuse it if so, otherwise the
  `truncateUrl` shape described above. The acceptance grep
  accepts either path.
