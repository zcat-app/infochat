# Session handoff — Tier 1 Group E: messaging adapter + Provider router (umbrella + InMemoryAdapter + router + /help)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author four ticket files and stop. Do NOT
include this preamble paragraph when pasting — only the fenced block
that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets are done and merged on main (M1-001..M1-007 +
  M1-009 + the 9 process tickets M1-010..M1-018).
- Tier 1 Group A (T1-A schema) is done and merged on main:
    M1-008 (umbrella per-(user, scope) isolation IT)
    M1-008a (identity + audit + last-admin trigger, V5 migration)
    M1-008b (sources + tags catalogues, V6 migration)
    M1-008c (joins + scope_preferences + post, V7 migration)
- Tier 1 Group B (T1-B ingest sources) is done and merged on main:
    M1-022 (Bootstrap-sources loader + bootstrap_meta, V8 migration)
    M1-023 (RSS Fetcher impl of the Fetcher SPI, kind='rss')
    M1-024 (infochat-ssrf module + RssFetcher hardening)
    M1-025 (infochat-ssrf hardening — M1-024 redteam remediation)
    M1-026 (infochat-ssrf hardening followup — M1-025 remediation)
- Tier 1 Group C (T1-C outbox/NOTIFY) is done and merged on main:
    M1-027 (Provider catch-up: provider_state V9 + NewPostReconciler +
            new_post LISTEN listener + NewPostHandler stub)
    M1-028 (Collector outbox: PostPersister + EvalQueueProducer +
            OutboxRehydrator + FetchScheduler)
- Tier 1 Group D (T1-D eval pipeline) is done and merged on main:
    M1-032 (Stage 1 deterministic security + quarantine V10 migration)
    M1-033 (Stage 2 LLM judge + first OpenAI-compatible LlmProvider +
            (ModelTask, scope_language) router)
    M1-034a (Tagger pipeline + V11 = post_embedding + embedding_metadata)
    M1-034b (Embedding pipeline + ReadyPromoter + first new_post NOTIFY)
- M1-019 (stdout API-key redaction) is `status: deferred` with
  `deferred_reason: post-mvp-hardening` and `deferred_on: M1-033`
  (the Stage 2 LLM call site). Do NOT touch in this session.
- M1-020 (exception-message sanitization) is `status: deferred`,
  `deferred_reason: post-mvp-hardening`, `deferred_on:` EMPTY. M1-020
  protects messaging-adapter intake paths in
  `infochat-collector/src/main/java/**/messaging/**` and
  `infochat-provider/src/main/java/**/messaging/**`. T1-E's router
  subticket (see "ID allocation" below) is the first ticket that
  lands Provider-side `messaging/` code carrying inbound-handler
  exception sites. Updating M1-020's `deferred_on` to that subticket
  is one of the after-authoring steps (see "After authoring all
  tickets" step 6 below).
- M1-021 (identity/audit redteam remediation) is `status: deferred`,
  `deferred_reason: end-of-tier-1-redteam`. T1-E does not block on
  it and does not unblock it.
- M1-031 (Provider catch-up hardening followup) is `status: deferred`
  and not in T1-E's path.
- M1-029 (RSS body-read timeout test tolerance) is done.
- M1-030 (Provider catch-up hardening backlog) is done.
- Flyway migrations on disk under
  infochat-core/src/main/resources/db/migration/:
    V1..V11 (init, roles, heartbeat, nologin, identity_audit,
    sources_tags, joins_post, bootstrap_meta, provider_state,
    quarantine, post_embedding+embedding_metadata).
  **T1-E is migration-free.** The MVP shape of the messaging surface
  (auto-register-on-first-DM, /help, InMemoryAdapter dispatch) reads
  and writes only columns the V5/V7 schema already provides
  (`users.adapter`, `users.contact_id`, `users.display_name`,
  `users.is_admin`, scope-keyed reads from joins). No new tables, no
  new columns, no new audit-log verb. If you find yourself reaching
  for V12, STOP and escalate — the spec says auto-register touches
  no new schema in MVP. See "Locked decisions" below for the
  audit-log carve-out.
- Branch is main, otherwise clean.

## Already-on-disk SPI scaffolding (do not redo, but DO extend)

The messaging SPI surfaces landed in M1-007c are intentionally a
**minimum-viable stub**. The Javadoc on
`infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java`
explicitly defers Identity assertion, typing, membership events,
inbound-message records, and the transport-layer size cap to the
first concrete-adapter ticket "so the parameter shapes can be
informed by a real transport rather than guessed." T1-E's
InMemoryAdapter subticket (see "ID allocation" below) is that ticket
and DOES fill those in.

Currently on disk under
`infochat-messaging-adapter/src/main/java/io/infochat/messaging/`:

    MessagingAdapter.java     — send/update/finalize + setInboundHandler
                                + InboundHandler nested interface
                                (the InboundHandler.onMessage signature
                                today takes (String scope, String body);
                                T1-E's M1-035a evolves this to ScopeRef +
                                Identity + InboundMessage per design §6.2)
    CapabilityFlags.java      — minimal flag record (the design uses the
                                name AdapterCapabilities — see
                                "Naming-drift carve-out" in Locked
                                decisions below)
    MessageHandle.java        — opaque-handle marker
    ProgressNotifier.java     — stub (used by /summary in T1-F, NOT by
                                /help in T1-E; the stub stays untouched)
    ProgressStage.java        — stub (same)
    TranslationProvider.java  — stub (deferred to T2-C; do NOT extend)

Currently under `infochat-messaging-adapter/src/test/java/io/infochat/messaging/`:

    MessagingSpisLoadTest.java — SPI-load smoke test from M1-007c.
                                 T1-E's InMemoryAdapter subticket
                                 adds the impl tests beside it.

There is NO `impl/inmemory/` directory yet, no `routing/`
AdapterRegistry, no `ScopeRef.java`, no `InboundMessage.java`, no
`OutboundMessage.java`, no `Identity.java`, no `AdapterTrustLevel.java`,
no `FailureCategory.java`, no `MessagingException.java`. T1-E lands
all of those.

There is no Provider-side `messaging/` package today. The router
subticket (M1-035b) opens
`infochat-provider/src/main/java/io/infochat/provider/messaging/`
and lands the InboundHandler implementation, the AdapterRegistry
bean, and the startup gates. M1-035c lands `/help` and
auto-register in a sibling package.

## What you do this session

Author exactly four ticket files in docs/plan/m1/tickets/:
  M1-035  — Adapter + router umbrella (cross-cutting topic IT:
            MVP exit criterion §3 — first DM via InMemoryAdapter
            auto-registers + receives /help)
  M1-035a — InMemoryAdapter + SPI fill-in (ScopeRef, Identity,
            InboundMessage, OutboundMessage, AdapterTrustLevel,
            FailureCategory, MessagingException, the
            AdapterCapabilities record's full field set; concrete
            InMemoryAdapter under impl/inmemory/)
  M1-035b — Provider-side AdapterRegistry + InboundHandler router +
            multi-adapter selection (D46 — single `inmemory` subset
            for MVP) + startup gates (supportsMarkdownLinks=false
            fail-fast, production-exclusion gate, per-adapter
            allow-low-trust opt-in)
  M1-035c — /help command (MVP static text, MUST be composed from
            per-command localization-bundle keys per spec
            §Discovery `/help` bundle composition) +
            auto-register-on-first-DM (MVP legacy onboarding path
            from design/00-mvp.md §4 — first DM creates the user
            and replies with /help; NO invite-gating, NO probation)

These four share heavy context — docs/spec/messaging.md (all of it)
+ docs/design/06-messaging.md §6.1, §6.2, §6.2.1, §6.2.2, §6.6,
§6.7, §6.8, §6.11 + docs/spec/commands.md §Surface conventions +
§Discovery (/help paragraph) + docs/design/03-commands.md §3.1 +
§3.4 (/help paragraph + bundle-key naming) + docs/spec/security.md
§Authorization model (steps 1.x — the intake normalization pass,
applied at adapter intake before dispatch) + docs/design/00-mvp.md
§4 (the MVP scope contract). The subtickets all add Java code
under either `infochat-messaging-adapter/` (a) or
`infochat-provider/src/main/java/.../messaging/` (b, c). The
umbrella adds ONE @QuarkusTest IT in the Provider module exercising
the full inbound→register→dispatch→/help→outbound roundtrip via
InMemoryAdapter's test helpers.

Once you've authored M1-035a, the SPI evolution + InMemory shape is
locked; M1-035b inherits the records and adds the Provider-side
registry/router; M1-035c adds the first command handler that
consumes the router. Author the umbrella M1-035 last so the IT
path matches what each subticket lists in `out_of_scope`.

When you finish, leave the four new files UNTRACKED on main
(workflow rule: drafts ride untracked through /m1-tick start). Do
NOT commit the ticket files. (The M1-020 metadata edit in step 6 of
"After authoring all tickets" IS committed — see that step.)

## Where you are in the milestone

Tier 1 (MVP vertical slice) is in flight.
  T1-A schema (done)
  T1-B ingest sources (done — 5 tickets including SSRF chain)
  T1-C outbox/NOTIFY (done — 2 tickets, M1-027 + M1-028)
  T1-D eval pipeline (done — 4 tickets, M1-032/033/034a/034b)
  T1-E adapter + router (THIS SESSION — 4 tickets, umbrella + 3 subs)
  T1-F first commands (/add-source, /summary)

After T1-E, the next session authors T1-F's detailed handoff JIT.
T1-F is the final Tier-1 group; once it lands, MVP exit criteria
§1..§7 from docs/design/00-mvp.md §6 can run end-to-end on a fresh
`docker-compose up`.

See docs/plan/m1/drafts/session-grouping-plan.md for the full plan.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation" + the umbrella+subticket
pattern from M1-007 / M1-008 / M1-034 (a/b/c on the same digit
slot): T1-E gets fresh IDs at the tail. The next free integer at
this session's start is **M1-035**. M1-034a/b are decomposed
subtickets of M1-034 and consumed the only post-M1-034 slot;
M1-019/020/021/031 are deferred and consume no new slots.

  M1-035  — Adapter + router umbrella (topic IT)
  M1-035a — InMemoryAdapter + SPI fill-in
  M1-035b — Provider-side AdapterRegistry + router + startup gates
  M1-035c — /help command + auto-register-on-first-DM

Re-grep `^id: M1-` across docs/plan/m1/tickets/ at the top of the
authoring session to confirm M1-035 is still the next free slot. If
a new ticket has been authored in the interim, take the next free
slot (M1-036, M1-037, ...) and shift the four IDs together
(`-036/-036a/-036b/-036c`, etc.). The slug → file-name mapping is
the only invariant; the numeric ID is allocated mechanically.

## Out-of-scope for T1-E entirely (regardless of ticket split)

These belong to later groups and MUST appear in every T1-E ticket's
`out_of_scope` list (or in the most-relevant subticket's
`out_of_scope`, with a brief note explaining the boundary):

- **Group `@mention` dispatch.** docs/design/00-mvp.md §4 says "No
  group onboarding (groups are deferred)." Group scope, the
  `@mention` recognition rule from docs/spec/messaging.md §Required
  SPI surface — Receive, the `supportsMentionByContactId` capability
  check, and the §6.2.3 / §6.10 mention-by-id rule are all deferred
  to T2-F (groups). T1-E's InMemoryAdapter delivers DM messages
  only; the SPI surface MAY define ScopeRef.Group(adapterGroupId)
  for type completeness but MUST NOT wire the Group dispatch path.
- **Invite-gating (D44) and slow-start probation (D45).** Deferred
  to T2-A. T1-E uses the MVP-legacy auto-register-on-first-DM path
  per docs/design/00-mvp.md §4 + §5. No `invite_code` table
  consumption (the table exists from V5 but is read-only-ignored in
  MVP), no `users.probation_until` checks (the column is set to
  NULL/sentinel by the auto-register insert; see "Locked decisions"
  for the column-default contract). Do NOT add `/invite create`,
  `/invite list`, `/invite revoke`, `/vouch`, or the slow-start
  permission-filter machinery — they live in T2-A.
- **/ban / /unban (D11).** Deferred to T2-A. T1-E does NOT add a
  ban check before the parser; the MVP intake path is
  "normalize → dispatch", not "ban-check → normalize → dispatch".
  The T2-A ticket that adds /ban will insert the ban gate as the
  first intake step per docs/spec/security.md §Authorization model
  step 1. T1-E leaves the seam visible (a one-line comment in the
  InboundHandler entry point) but does not implement the gate.
- **TranslationProvider integration.** docs/spec/messaging.md §9 +
  docs/design/05-llm-and-embeddings.md §5.6 require Provider to
  translate `OutboundMessage.text` to the per-scope language before
  the adapter sees it. /lang is deferred to T2-C; T1-E ships English
  only. /help's bundle keys ship in `en` (per docs/spec/llm.md
  §Translation flow — `cs` is the second v1 language but bundle
  authoring for `cs` is T2-C). The bundle infrastructure (per-key
  lookup, fallback to `en`) lands in T1-E for /help; the second
  language is added by T2-C without a new bundle pattern.
- **ProgressNotifier integration.** /help is short, deterministic,
  and bypasses the notifier per docs/spec/messaging.md §Progress
  notifications — "Short, deterministic SQL commands bypass the
  notifier entirely." T1-E does NOT extend `ProgressNotifier.java`
  or `ProgressStage.java`; those stubs stay as M1-007c left them.
  T1-F's /summary is the first notifier consumer.
- **SimpleX and Signal adapters.** docs/design/00-mvp.md §5
  Adapters/providers: deferred past MVP. T1-E ships InMemoryAdapter
  ONLY. The AdapterRegistry in M1-035b is shaped to accept the
  multi-adapter D46 case (CDI bean discovery + `infochat.adapters`
  property parsing), but the SimpleX + Signal beans themselves are
  T3-A. The production-exclusion gate from design §6.6
  (refuse `inmemory` together with `simplex|signal`) IS implemented
  in M1-035b — it costs three lines and prevents a future
  misconfiguration from ever firing in production. (See "Locked
  decisions" — startup gates.)
- **Bootstrap admin from docs/spec/deployment.md §Operator inputs.**
  The bot-admin bootstrap @Startup bean that ensures each enabled
  adapter's configured admin contact exists with `is_admin=true` is
  deferred. The MVP path is: auto-register-on-first-DM creates a
  non-admin user; the admin contact, if any, is created the same
  way and is hand-promoted via SQL in MVP. T1-E does NOT add the
  bootstrap-admin @Startup bean. The MVP exit criterion §2 in
  docs/design/00-mvp.md §6 ("bot admin from `infochat.admin.contact-
  id` exists in `user` with `is_admin=true`") is satisfied by a
  one-time SQL grant in `docker-compose` bootstrap or by a future
  spec-compliant ticket — note this gap in the umbrella's "Big-
  picture notes" so the gap is visible.
- **Permanent-delivery-failure cleanup + bot-removed-from-group +
  group-deleted-upstream + user-left-group handlers** from
  docs/spec/messaging.md §Failure handling and design §6.3.6. All
  group-keyed; deferred to T2-F. T1-E's MessagingException MAY
  declare a `FailureCategory category()` accessor for SPI
  completeness (so the type doesn't have to be widened later), but
  the Provider-side cleanup branches are NOT wired.
- **Inbound back-pressure / per-user-fair scheduler** from design
  §6.3.7. InMemoryAdapter's tests do NOT exercise the bounded queue
  + drop-newest + synchronous-throttle-reply path. The
  `maxInboundMessageBytes` field on `AdapterCapabilities` is
  populated (with a generous test value, e.g. 100_000) so the field
  exists on the record, but the inbound-queue enforcement
  machinery itself is deferred to T2-G or whenever SimpleX/Signal
  land. The MVP InMemoryAdapter delivers synchronously via a direct
  call-through to the handler.
- **Transport-layer inbound size cap enforcement** from
  docs/spec/messaging.md §Required SPI surface and design §6.2.2.
  The field exists; the synchronous-drop-and-friendly-reply
  enforcement is deferred (same rationale as inbound back-pressure).
- **LLM output sanitizer** from docs/spec/security.md §LLM output
  sanitizer. /help's text is NOT LLM-authored — it is a
  deterministic concatenation of localization-bundle strings. The
  sanitizer lands in T1-F's /summary (first user-visible LLM prose).
- **Audit-log writes from auto-register.** docs/design/00-mvp.md
  §5 Operations: "Audit-log entries beyond bot-admin bootstrap and
  /add-source" are deferred. MVP auto-register does NOT insert an
  `audit_log` row. (See "Locked decisions" — audit-log carve-out.)
- **Confirmation-pending state machine** from docs/spec/commands.md
  §Surface conventions ("Confirmation for destructive commands").
  /help is not destructive; MVP has no destructive commands. The
  in-memory confirmation map is T2-A or later.
- **Input length caps** from docs/spec/commands.md §Surface
  conventions ("Input length caps — Command body cap / Chat-mode
  body cap"). Profile-driven values land with the configuration
  surface. T1-E MAY apply a hardcoded sensible cap (e.g.,
  4096 bytes) at the InboundHandler entry point with a one-line
  comment naming the profile-driven follow-up, OR may defer the cap
  entirely with the same comment — pick whichever is cleaner. The
  cap value itself is NOT a spec-load-bearing decision for MVP.
- **Mention-recognition rule + bot identity material derivation**
  from docs/spec/messaging.md §Required SPI surface and design
  §6.2.3 / §6.10. Group-scoped; deferred to T2-F. InMemoryAdapter
  declares `supportsMentionByContactId = true` (per design §6.6's
  test rationale: tests assert mention-by-id paths) but the
  Provider-side group dispatch that consumes it is NOT wired.

## Locked decisions (apply to every subticket)

If any of these conflicts with what you'd otherwise pick at
authoring time, escalate — don't silently override. The locks
exist because they were resolved in this handoff session against
verified spec anchors.

### MVP-vs-v1 capability conflict for InMemoryAdapter

docs/design/00-mvp.md §4 says: "the `InMemoryAdapter` reports
`supportsCodeFormatting=false` so the test transcripts stay
readable." docs/design/06-messaging.md §6.6 says: "InMemoryAdapter
declares `supportsCodeFormatting = true` so tests exercise the
code-formatting render path; the SimpleX adapter declares it false
so tests of the plain-text fallback also run." These conflict
because design §6.6 was written for the v1 build where SimpleX
exists; MVP doesn't have SimpleX, so InMemoryAdapter is the only
adapter and the MVP §4 readability argument wins.

**LOCK: T1-E ships InMemoryAdapter with
`supportsCodeFormatting = false`** (per MVP §4). T3-A
SimpleX adapter (with `supportsCodeFormatting = false` per design
§6.4.2) will land at the same time as a one-line flip of
InMemoryAdapter to `true` (per design §6.6's two-adapter test-
coverage argument). That flip is T3-A's responsibility, not T1-E's.
Document the conflict + the resolution in M1-035a's
"Alternatives considered" so the reader at T3-A time finds the
breadcrumb.

### Naming-drift carve-out for CapabilityFlags vs AdapterCapabilities

M1-007c shipped `CapabilityFlags.java` (the existing stub) with a
minimal field set. design/06-messaging.md §6.2 uses the name
`AdapterCapabilities` for the full record (with the additional
fields `supportsMentionByContactId`, `supportsMembershipEvents`,
`supportsMarkdownLinks`, `supportsMultilineCode`,
`supportsAttachments`, `supportsThreading`, `maxMessageBytes`,
`maxInboundMessageBytes`, `maxInflightSends`, `maxSendsPerSecond`,
`supportsMessageEdit`, `supportsTypingIndicator`,
`minEditInterval`). Per the §Engineering rules surgical-changes
rule, M1-035a EXTENDS `CapabilityFlags` with the missing fields
rather than renaming the type. The design doc's `AdapterCapabilities`
name is updated by a separate `spec:` commit at the end of the T1-E
ticket chain (NOT in this authoring session, NOT inside any T1-E
ticket — the design rename is a follow-up `spec:` commit after the
T1-E tickets merge so the design doc stays in sync with the impl
name). Surface this as one of M1-035a's "Alternatives considered"
entries.

### Audit-log carve-out for MVP auto-register

The closed `audit_log.action` verb catalogue (from V5, see
infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
lines 272..298) does NOT include an `AUTO_REGISTER` verb. Per
docs/design/00-mvp.md §5 Operations ("Audit-log entries beyond bot-
admin bootstrap and /add-source" are deferred), MVP auto-register
SKIPS the audit_log insert entirely. T2-A's invite-gating ticket
will add the `INVITE_CONSUME` audit row (the verb already exists in
the closed set) at the moment registration happens, replacing the
MVP-legacy "register-and-skip-audit" path. T1-E does NOT add a new
verb to the closed set; doing so would be a spec amendment
(`spec:` commit) and a separate ticket. The Provider-side
auto-register insert into `users` is the only DB side effect; no
audit row, no trigger fire beyond the existing
`trg_audit_log_append_only` (which doesn't fire on `users` inserts
in the first place).

### Startup gates that DO ship in M1-035b

Per docs/design/06-messaging.md §6.7 registration flow + §6.6
production-exclusion + §6.2.1 markdown-links fail-fast + §6.8
per-adapter LOW-trust opt-in, the AdapterRegistry's startup checks
are spec-load-bearing and ALL ship in M1-035b. The MVP only has
InMemoryAdapter, but the gates are cheap and prevent silent
configuration drift the day SimpleX lands. Specifically:

1. `infochat.adapters` MUST be non-empty (no fall-through to "no
   adapters" — Provider with zero adapters cannot serve traffic).
2. Every name in `infochat.adapters` MUST resolve to a registered
   CDI bean; an unknown name is a fatal startup error naming the
   offending entry.
3. The `supportsMarkdownLinks = false` gate (§6.2.1) is applied to
   every activated adapter's capabilities; a `true` declaration is
   a fatal startup error naming the adapter.
4. The production-exclusion gate (§6.6) rejects any configuration
   that lists `inmemory` together with `simplex` or `signal`. The
   SimpleX/Signal bean names are not yet defined in T1-E, so the
   check is shaped as "if `inmemory` is in the activated set AND
   the activated set has size > 1, reject" — this generalizes
   cleanly when T3-A adds the SimpleX/Signal beans.
5. Per-adapter LOW-trust opt-in (§6.8): InMemoryAdapter reports
   `trustLevel = LOW` by default. The MVP test deployment sets
   `infochat.adapters.inmemory.allow-low-trust=true` in
   `application.properties` (or in the test profile). The registry
   rejects activation if any LOW-trust adapter's matching
   `allow-low-trust=true` property is missing, naming the adapter.
6. `supportsMentionByContactId = false` + group-SPI-wired refuses
   to register (§6.3.3 / §6.7). For MVP this is vacuously true
   because no adapter has group SPI wired; the check costs nothing
   and lets T2-F + T3-A inherit it for free.

### `users.probation_until` default

V5's `users` table includes a `probation_until` column (D45,
slow-start tier). T2-A wires the check; MVP doesn't read or write
the column. The auto-register insert from M1-035c MUST set
`probation_until` per the spec-promised default — read the V5
migration to confirm the column default (likely NULL or a sentinel
timestamp) and pass through without overriding. Adding an MVP-
specific override is scope drift.

### `users.adapter` value

InMemoryAdapter's `MessagingAdapter.name()` is `"inmemory"` per
design §6.6's stub. Auto-register inserts `users.adapter='inmemory'`
verbatim. Do NOT invent a longer or shorter name; the cross-adapter
isolation invariant from docs/spec/messaging.md §Per-adapter trust
level and identity uses `(adapter, contact_id)` as the join key, so
the literal string MUST match what AdapterRegistry registers under.

### M1-035 — Adapter + router umbrella (cross-cutting topic IT)
- blocked_by: [M1-035a, M1-035b, M1-035c]
- complexity: low, risk: low
- security_relevant: TRUE   (MVP exit criterion §3 is the full
  inbound→dispatch→outbound smoke; a leak between the
  auto-register path and the /help dispatch path would let an
  unregistered DM trigger a register-and-reply round trip without
  the dispatch ever happening — a silent NOOP from the user's
  perspective, but a row insert from the DB's perspective. The IT
  asserts both halves happen exactly once.)
- migration_touch: FALSE
- round_cap: 2
- files_budget: 2  (the IT class + at most one test-resources fixture)
- files_scope:
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
- Scope:
  * A @QuarkusTest IT that, against the TestContainers Postgres
    provisioned by the existing Provider test setup, wires an
    InMemoryAdapter bean (test-only HIGH trust if the test exercises
    admin-adjacent paths — for /help it's LOW-default, sufficient).
    Activates `infochat.adapters=inmemory` plus
    `infochat.adapters.inmemory.allow-low-trust=true` in the test
    profile.
  * Asserts the MVP exit criterion §3 contract verbatim:
      - A first-time DM from a new `contact_id` triggers exactly
        ONE `users` insert with `adapter='inmemory'`,
        `is_admin=false`, and the spec-default `probation_until`.
      - The same inbound message produces exactly ONE outbound
        message on the InMemoryAdapter's `sentMessages()` list,
        whose body is the /help text composed from the
        per-command bundle keys for the new user's permitted set
        (MVP: just the three MVP commands per
        docs/design/00-mvp.md §4).
      - A SECOND DM from the same `contact_id` does NOT insert a
        second `users` row (auto-register is idempotent) and
        responds with the same /help text.
      - A DM from a registered user with body `/unknown-command`
        responds with a friendly unknown-command error (NOT an
        empty reply, NOT a silent drop).
  * The IT does NOT add or modify any Flyway migration. It depends
    on M1-035a + M1-035b + M1-035c having shipped the adapter +
    router + /help + auto-register.
  * Body explains WHY this is a separate commit: the umbrella idiom
    (workflow.md §Ticket-ID placeholder convention) — whole-topic
    verification of MVP exit criterion §3 is meaningfully different
    from any single subticket's unit-level assertions (M1-035a's
    InMemoryAdapter unit tests; M1-035b's per-gate startup tests;
    M1-035c's /help bundle-composition test + auto-register
    idempotency test), so it ships as its own reviewable unit.
  * Body's "Big-picture notes" calls out the bootstrap-admin gap
    (the @Startup bean from docs/spec/deployment.md §Operator
    inputs that ensures `infochat.admin.contact-id` exists with
    `is_admin=true` is NOT yet implemented; MVP relies on a manual
    SQL grant in docker-compose bootstrap or a future ticket — see
    the T1-E out-of-scope list above).
- Spec_refs (all verified to exist):
  * docs/spec/messaging.md §Required SPI surface
  * docs/spec/messaging.md §Goals (item 4 — adapter is a thin transport)
  * docs/spec/commands.md §Surface conventions (slash-prefix only)
  * docs/spec/commands.md §Discovery (/help)
  * docs/design/00-mvp.md §4 Messaging adapter and commands
  * docs/design/00-mvp.md §6 MVP exit criteria (criterion 3)
- decision_refs: D10, D30, D46

### M1-035a — InMemoryAdapter + SPI fill-in
- blocked_by: [M1-007c]
- complexity: medium, risk: medium
- security_relevant: TRUE   (the SPI's Identity record is the
  trust anchor for the authorization model per D10; getting the
  contact-id-vs-display-name distinction wrong here propagates into
  every command's permission check. The InMemoryAdapter default-LOW
  trust level is the safety net against accidental privilege
  escalation in a test harness.)
- migration_touch: FALSE
- round_cap: 2
- files_budget: 12   (SPI records: ScopeRef + Identity +
  InboundMessage + OutboundMessage + AdapterTrustLevel +
  FailureCategory + MessagingException + the extended
  CapabilityFlags fields; InMemoryAdapter concrete impl under
  impl/inmemory/; corresponding unit tests; package-info / module
  layout adjustments. NO migration, NO Provider-side code.)
- files_scope:
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/ScopeRef.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/Identity.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/InboundMessage.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/OutboundMessage.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/AdapterTrustLevel.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/FailureCategory.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingException.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/CapabilityFlags.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryAdapter.java
    - infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryMessageHandle.java
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java
- Scope:
  * SPI fill-in (added under
    `infochat-messaging-adapter/src/main/java/io/infochat/messaging/`):
      - `ScopeRef` sealed interface with `Dm(String contactId)` and
        `Group(String adapterGroupId)` permitted records. Group is
        type-complete for T2-F but never dispatched-to in MVP.
      - `Identity(String contactId, String displayName,
        Instant lastSeen)` record per design §6.2.
      - `InboundMessage(Identity sender, ScopeRef scope, String text,
        Instant receivedAt, String adapterMessageId)` record.
      - `OutboundMessage(ScopeRef scope, String text,
        Instant requestedAt, String correlationId)` record.
      - `AdapterTrustLevel` enum: `HIGH`, `LOW`.
      - `FailureCategory` enum: `TRANSIENT`, `PERMANENT`.
      - `MessagingException` extends Exception, carries a
        `FailureCategory category()` accessor (set at throw site;
        adapters that cannot tell default to PERMANENT per spec
        §Failure handling).
      - `CapabilityFlags` EXTENDED with the design §6.2 field set
        (see the "Naming-drift carve-out" lock above —
        `CapabilityFlags` is retained as the type name; the design's
        `AdapterCapabilities` name is a separate `spec:` follow-up).
      - `MessagingAdapter` interface extended to evolve the
        `InboundHandler.onMessage` signature to take `InboundMessage`
        instead of `(String scope, String body)`. Also add
        `AdapterTrustLevel trustLevel()`, `Identity
        assertIdentity(InboundMessage msg)`, and `String name()` per
        design §6.2 (the latter is the adapter-selection key for
        §6.7's AdapterRegistry). Update `MessagingSpisLoadTest` if
        the existing signature breaks.
  * `InMemoryAdapter` concrete impl under
    `infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/`:
      - Default constructor → `trustLevel = LOW` (the test harness
        default per design §6.6).
      - Test-only HIGH-trust opt-in constructor for admin-path
        tests (the M1-035 umbrella IT does NOT exercise admin paths,
        so HIGH-trust is not needed there).
      - `capabilities()` returns the MVP-shape (per "Locked
        decisions — MVP-vs-v1 capability conflict" above:
        `supportsCodeFormatting = false`; `supportsMarkdownLinks =
        false`; `supportsMentionByContactId = true`;
        `supportsMembershipEvents = true`; other flags per design
        §6.6 with the generous test-bound numeric values).
      - `send()` records the OutboundMessage on a `sentMessages()`
        list and returns an `InMemoryMessageHandle`. `update()` /
        `finalize()` append to a per-handle history. `setTyping()`
        records a typing-event list.
      - Test helpers: `deliverDm(contactId, text)`,
        `sentMessages()`, `updateHistory(handle)`, `reset()`. The
        helpers are NOT on the SPI; they're accessed by casting to
        the concrete type in tests.
  * Unit tests under
    `infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/`:
      - Identity stability: same `contactId` across multiple inbound
        messages resolves to the same Identity.
      - `send` → `update` → `finalize` sequence produces the
        expected history.
      - `finalize` exclusivity: any `update` after `finalize` on the
        same handle throws `MessagingException`.
      - `setTyping` toggles are recorded in order.
      - Default trust level is LOW; the HIGH-trust constructor flips
        it.
- Out-of-scope MUST list:
    infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
    (the umbrella's cross-cutting IT — that ticket asserts the
    full inbound→register→/help roundtrip; this ticket asserts
    SPI shape + InMemoryAdapter unit behavior only)
- Spec_refs (all verified):
  * docs/spec/messaging.md §Required SPI surface
  * docs/spec/messaging.md §Capability flags (minimum set)
  * docs/spec/messaging.md §Per-adapter trust level and identity
    (InMemory paragraph)
  * docs/spec/messaging.md §Identity and groups
  * docs/spec/messaging.md §Failure handling (FailureCategory enum
    and the "cannot tell apart MUST default to permanent" rule)
  * docs/design/06-messaging.md §6.1 Module layout
  * docs/design/06-messaging.md §6.2 The SPI
  * docs/design/06-messaging.md §6.6 InMemoryAdapter
- decision_refs: D10, D30, D46

### M1-035b — Provider-side AdapterRegistry + router + startup gates
- blocked_by: [M1-035a]
- complexity: high, risk: high
- security_relevant: TRUE   (the startup gates are spec-load-bearing
  security guarantees: supportsMarkdownLinks=false prevents an
  LLM-authored clickable-link injection vector; production-
  exclusion prevents in-memory identity assertions from being
  trusted in a production deployment; LOW-trust opt-in forces a
  conscious operator choice; supportsMentionByContactId guards
  against display-name spoofing in groups. Getting any one of these
  wrong is a real attack surface even though MVP has only the
  in-memory adapter today.)
- migration_touch: FALSE
- round_cap: 3              (high-complexity / high-risk per
  CLAUDE.md §M1 workflow allows round_cap: 3; the startup-gate
  test matrix and the InboundHandler dispatch correctness argument
  justify the third round)
- files_budget: 10  (AdapterRegistry + InboundRouter +
  per-gate startup-check helpers under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`;
  per-gate unit tests; one IT exercising the
  multi-adapter-selection happy path via test-only second
  InMemoryAdapter bean)
- files_scope:
    - infochat-provider/src/main/java/io/infochat/provider/messaging/AdapterRegistry.java
    - infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/io/infochat/provider/messaging/MessagingStartup.java
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java
    - infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java
    - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java
- Scope:
  * `AdapterRegistry` CDI bean:
      - Discovers every CDI bean implementing `MessagingAdapter` at
        startup (`@Inject Instance<MessagingAdapter>`).
      - Reads `infochat.adapters` (comma-separated names) and
        activates the subset whose `name()` appears in the property.
      - Applies the six startup gates from "Locked decisions —
        Startup gates that DO ship in M1-035b" above, in the
        documented order. Each gate failure throws an
        `IllegalStateException` whose message names the offending
        adapter (so the operator gets actionable feedback).
      - Per-adapter `start(InboundRouter)` invocation: each
        activated adapter calls `setInboundHandler(router)` (or
        whatever name the M1-035a SPI evolution settles on) so its
        inbound deliveries reach the Provider-side router.
      - Logs one INFO line per activated adapter per design §6.8
        format: `INFO AdapterRegistry – activating adapter:
        <name> (trust=<HIGH|LOW>[; allow-low-trust=true])`.
  * `InboundRouter` CDI bean implementing the M1-035a
    `InboundHandler` interface:
      - Entry point applies the spec-required normalization pass
        FIRST (docs/spec/security.md §Authorization model step 1.7 +
        docs/spec/commands.md §Surface conventions — bidi-strip,
        zero-width-strip, leading-whitespace-trim, empty-drop).
        Empty / whitespace-only messages are dropped here, before
        slash-prefix check.
      - Branch: slash-prefix → command dispatch (T1-E ships /help
        only — see M1-035c); chat-mode → stub that returns a
        deterministic "chat-mode is not in MVP" reply (NOT a silent
        drop, NOT an exception). The chat-mode handler proper lands
        in T2-D.
      - Comment in the entry-point method body names the missing
        intake steps that T2-A wires (ban check; invite gate;
        probation filter) so the seam is visible.
      - Dispatch errors (parse failure, command-not-found, internal
        exception) result in a friendly one-line outbound reply via
        the same adapter the inbound came from. The MVP shape: the
        friendly error string is a single localization-bundle key
        (`error.unknown_command`, `error.internal`) — DO NOT
        interpolate exception text into the user-visible reply
        (that's the M1-020 sanitization concern; the bundle-key
        approach sidesteps it entirely for MVP).
      - The exception-logging code path uses raw SLF4J for now
        (M1-020 will retrofit SafeLog when un-deferred).
  * `MessagingStartup` @Startup bean:
      - Drives the AdapterRegistry's `start()` lifecycle once
        Quarkus is up. Per-adapter `start()` failure is logged at
        ERROR and the registry retries on a profile-driven backoff
        per design §6.7 "Per-adapter resilience". MVP has one
        adapter so this is essentially a no-op for the InMemory
        case, but the shape is in place for SimpleX/Signal later.
        (The readiness probe wiring from §6.7 is NOT in scope; the
        bean log lines are sufficient for MVP.)
  * Startup-gate tests (`StartupGatesTest`):
      - Empty `infochat.adapters` → IllegalStateException naming
        "no adapters configured".
      - Unknown name in `infochat.adapters` → IllegalStateException
        naming the unknown entry.
      - A test-only fake adapter declaring
        `supportsMarkdownLinks = true` → IllegalStateException
        naming the adapter.
      - A test-only fake adapter list with `inmemory` plus a
        second prod-flagged adapter → IllegalStateException naming
        the conflicting pair.
      - InMemoryAdapter at default LOW trust + missing
        `allow-low-trust=true` → IllegalStateException naming the
        adapter.
      - A test-only fake adapter declaring
        `supportsMentionByContactId = false` AND wiring the group
        SPI → IllegalStateException (MVP has no group SPI wired so
        this test uses a fake group-SPI flag on the adapter).
  * Registry tests (`AdapterRegistryTest`):
      - With `infochat.adapters=inmemory` and the LOW-trust opt-in
        set, the single InMemoryAdapter activates and its
        `setInboundHandler` is called exactly once.
      - With two test-only InMemoryAdapter beans bearing distinct
        `name()` returns (e.g., `inmemory` and `inmemory2`), and
        `infochat.adapters=inmemory,inmemory2`, both activate. This
        proves the multi-adapter D46 path without depending on
        SimpleX/Signal beans yet to exist.
  * Router tests (`InboundRouterTest`):
      - Empty / whitespace-only / bidi-only / zero-width-only inbound
        text is dropped (no dispatch attempt, no outbound reply).
      - Leading whitespace + `/help` parses as `/help`.
      - Chat-mode (non-slash) inbound from a registered user
        receives the deterministic "chat-mode is not in MVP" reply.
      - Unknown command receives the friendly
        `error.unknown_command` reply.
- Out-of-scope MUST list:
    infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
- Spec_refs (all verified):
  * docs/spec/messaging.md §Required SPI surface
  * docs/spec/messaging.md §Capability flags (minimum set)
  * docs/spec/messaging.md §Per-adapter trust level and identity
  * docs/spec/commands.md §Surface conventions
  * docs/spec/security.md §Authorization model
  * docs/design/06-messaging.md §6.2.1 Startup validation —
    supportsMarkdownLinks fail-fast
  * docs/design/06-messaging.md §6.6 InMemoryAdapter
    (production-deployment exclusion paragraph)
  * docs/design/06-messaging.md §6.7 Adapter selection (multi-adapter, D46)
  * docs/design/06-messaging.md §6.8 Trust levels and operator opt-in
- decision_refs: D10, D11, D30, D46

### M1-035c — /help command + auto-register-on-first-DM
- blocked_by: [M1-035b]
- complexity: medium, risk: medium
- security_relevant: TRUE   (auto-register is the only path that
  creates a `users` row at runtime; getting the
  `(adapter, contact_id)` uniqueness wrong opens a duplicate-row
  race that breaks cross-adapter isolation; getting the
  `is_admin=false` default wrong creates a privilege-escalation
  primitive; getting the `probation_until` default wrong shortcuts
  the T2-A slow-start tier before it's even wired)
- migration_touch: FALSE
- round_cap: 2
- files_budget: 9   (HelpCommandHandler + AutoRegisterService +
  bundle infrastructure (BundleLoader, BundleKeys) + en bundle
  resource + tests)
- files_scope:
    - infochat-provider/src/main/java/io/infochat/provider/messaging/HelpCommandHandler.java
    - infochat-provider/src/main/java/io/infochat/provider/messaging/AutoRegisterService.java
    - infochat-provider/src/main/java/io/infochat/provider/bundle/BundleLoader.java
    - infochat-provider/src/main/java/io/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/test/java/io/infochat/provider/messaging/HelpCommandHandlerTest.java
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AutoRegisterServiceTest.java
    - infochat-provider/src/test/java/io/infochat/provider/bundle/BundleLoaderTest.java
- Scope:
  * `AutoRegisterService`:
      - On every InboundRouter dispatch (called from M1-035b's
        InboundRouter before slash-prefix check, but after the
        intake normalization pass), look up the user by
        `(adapter, contact_id)`. If absent, INSERT a new row with
        `is_admin=false`, the V5-default `probation_until`,
        `display_name = Identity.displayName`, and `adapter =
        Identity.<via the inbound>.adapter` (the inbound adapter's
        `name()` value).
      - INSERT uses `ON CONFLICT (adapter, contact_id) DO NOTHING`
        to make auto-register idempotent under concurrent first-DMs.
      - Returns the resolved `users` row (the just-inserted or the
        pre-existing).
      - Does NOT write `audit_log` (see "Locked decisions —
        Audit-log carve-out for MVP auto-register" above).
  * `HelpCommandHandler` (slash-prefix `/help`):
      - Reads the caller's permitted command set. MVP: the three
        commands from docs/design/00-mvp.md §4 (/help, /add-source,
        /summary). For T1-E, only /help itself is implemented in
        code; /add-source and /summary are T1-F. The bundle keys
        for all three MUST exist in `en.properties` so the bundle-
        completeness CI assertion (per docs/spec/commands.md
        §Discovery /help "Bundle composition") doesn't regress when
        T1-F adds the impls.
      - Composes the reply per docs/design/03-commands.md §3.4
        /help bundle-key naming: header (`help.header.dm-user`) +
        per-command short-help lines (`help.cmd.help.short`,
        `help.cmd.add-source.short`, `help.cmd.summary.short`) +
        no footer (MVP has no probation tier yet).
      - The MVP permitted set is the same for every non-admin user;
        admin-only commands are NOT shipped in T1-E so admin-set
        bundle keys are not authored. T2-A's invite-and-probation
        ticket adds the probation footer key and the per-actor-tier
        filtering.
      - Output is plain text per D30 + docs/spec/messaging.md
        §Output formatting (transport view). No markdown links, no
        emoji, no auto-formatting beyond the literal bundle string.
  * `BundleLoader` + `BundleKeys`:
      - Java-side bundle loading from `src/main/resources/bundles/
        <lang>.properties` (Quarkus locale infrastructure or plain
        ResourceBundle is acceptable; pick whichever is the smaller
        diff).
      - `BundleKeys` is a constant-holder class with the exact
        keys T1-E uses so a typo in a key name fails at
        compile-time. CI bundle-completeness check (a unit test in
        `BundleLoaderTest`) asserts every constant in `BundleKeys`
        resolves in `en.properties`.
      - The MVP ships ONLY `en`. The pattern is in place for `cs`
        in T2-C; no `cs.properties` file in T1-E.
  * Tests:
      - `AutoRegisterServiceTest`: first-DM inserts; second-DM is
        a no-op; concurrent first-DMs from the same contact_id
        produce exactly one row (DO NOTHING handles the race);
        cross-adapter contact_ids (same contact_id, different
        adapter) produce TWO distinct rows (covers the
        `(adapter, contact_id)` cross-adapter-isolation invariant
        from docs/spec/messaging.md §Per-adapter trust level —
        Signal cross-adapter isolation invariant paragraph).
      - `HelpCommandHandlerTest`: returns the composed reply for a
        registered MVP user; a missing bundle key fails the test
        (regression-guard against bundle drift).
      - `BundleLoaderTest`: every constant in `BundleKeys` resolves
        in `en.properties`; an unknown key throws (defense against
        silently-empty output).
- Out-of-scope MUST list:
    infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
- Spec_refs (all verified):
  * docs/spec/commands.md §Surface conventions
  * docs/spec/commands.md §Discovery (/help)
  * docs/spec/commands.md §Permission model
  * docs/spec/messaging.md §Output formatting (transport view)
  * docs/spec/messaging.md §Per-adapter trust level and identity
  * docs/design/03-commands.md §3.1 Conventions
  * docs/design/03-commands.md §3.4 Discovery commands (/help)
  * docs/design/00-mvp.md §4 Messaging adapter and commands
- decision_refs: D11, D30, D43, D44, D45

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by `grep -n '^## \|^### ' <file>` at this
session's authoring time. Any spec_ref you cite that ISN'T in this
list, verify the anchor exists by reading the cited file before
using it. The clarity-preflight subagent will FAIL the ticket if
a spec_ref doesn't resolve.

  docs/spec/messaging.md §Goals                               (line 8)
  docs/spec/messaging.md §Required SPI surface                (line 26)
  docs/spec/messaging.md §Capability flags (minimum set)      (line 102)
  docs/spec/messaging.md §Message handles                     (line 144)
  docs/spec/messaging.md §Progress notifications              (line 158)
  docs/spec/messaging.md §Per-adapter trust level and identity (line 193)
  docs/spec/messaging.md §Identity and groups                 (line 226)
  docs/spec/messaging.md §Output formatting (transport view)  (line 237)
  docs/spec/messaging.md §Failure handling                    (line 250)
  docs/spec/messaging.md §What lives in design notes          (line 354)
  docs/spec/commands.md §Surface conventions                  (line 8)
  docs/spec/commands.md §Command catalogue                    (line 94)
  docs/spec/commands.md §Discovery                            (line 100)
  docs/spec/commands.md §Permission model                     (line 965)
  docs/spec/commands.md §Onboarding                           (line 1027)
  docs/spec/security.md §Authorization model                  (line 300)
  docs/spec/security.md §Trust boundaries                     (line 38)
  docs/design/06-messaging.md §6.1 Module layout              (line 15)
  docs/design/06-messaging.md §6.2 The SPI                    (line 52)
  docs/design/06-messaging.md §6.2.1 supportsMarkdownLinks fail-fast (line 213)
  docs/design/06-messaging.md §6.2.2 Inbound message size cap (line 238)
  docs/design/06-messaging.md §6.2.3 Mention-recognition rule (line 272)
  docs/design/06-messaging.md §6.3 Contract every adapter MUST honor (line 305)
  docs/design/06-messaging.md §6.6 InMemoryAdapter            (line 719)
  docs/design/06-messaging.md §6.7 Adapter selection (multi-adapter, D46) (line 813)
  docs/design/06-messaging.md §6.8 Trust levels and operator opt-in (line 851)
  docs/design/06-messaging.md §6.9 Translation interaction    (line 876)
  docs/design/06-messaging.md §6.11 Audit considerations      (line 899)
  docs/design/06-messaging.md §6.14 Verification              (line 940)
  docs/design/03-commands.md §3.1 Conventions                 (line 21)
  docs/design/03-commands.md §3.4 Discovery commands          (line 360)
  docs/design/03-commands.md §3.4 /help                       (line 362)
  docs/design/00-mvp.md §4 Messaging adapter and commands     (line 88)
  docs/design/00-mvp.md §5 What is NOT in MVP                 (line 106)
  docs/design/00-mvp.md §6 MVP exit criteria                  (line 164)

## Style requirements

Match M1-007 + M1-007a/b/c (the closest umbrella+subticket
analogue) and M1-008 + M1-008a/b/c (the schema umbrella, similar
shape) in docs/plan/m1/tickets/. Read both umbrella files + at
least one subticket each once for style. Read
docs/process/ticket-template.md once for the canonical schema.
Then write.

Length per ticket: M1-035 umbrella is the short one (~160-200
lines — the IT is non-trivial but well-scoped); M1-035a is the
longest (~290-360 lines — SPI fill-in + InMemoryAdapter +
substantial unit-test list); M1-035b is the next-longest (~270-330
lines — six startup gates + registry + router each carry weight);
M1-035c ~200-260 lines (auto-register + /help bundle composition).
Total: ~920-1150 lines authored this session.

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep/test/SQL assertions, not prose.
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.

Use today's date (2026-05-17) for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-007, M1-007a, M1-007b, M1-007c once for style.
- DO read M1-008 + M1-008a once for umbrella+subticket pattern.
- DO read docs/process/ticket-template.md once.
- DO read docs/process/workflow.md §Ticket-ID placeholder
  convention once.
- DO read docs/spec/messaging.md in one pass (whole file — ~360 lines).
- DO read docs/spec/commands.md §Surface conventions + §Discovery
  + §Permission model + §Onboarding in one pass.
- DO read docs/spec/security.md §Authorization model + §Trust
  boundaries in one pass.
- DO read docs/design/06-messaging.md §6.1 + §6.2 + §6.2.1 +
  §6.2.2 + §6.2.3 + §6.3 + §6.6 + §6.7 + §6.8 + §6.9 + §6.11 +
  §6.14 in one pass.
- DO read docs/design/03-commands.md §3.1 + §3.4 in one pass.
- DO read docs/design/00-mvp.md §4 + §5 + §6 in one pass.
- DO read infochat-messaging-adapter/src/main/java/io/infochat/messaging/
  (every file — ~7 small files) once to see what M1-007c left on disk.
- DO read infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  lines 1-100 (the `users` table shape + the `(adapter, contact_id)`
  unique constraint) once.
- DO NOT spawn Explore or any other subagent.
- DO NOT pre-load the full docs/spec/ tree.
- DO NOT re-read sections you already loaded.
- DO NOT read docs/spec/llm.md (no LLM calls in T1-E).
- DO NOT read docs/spec/schema.md sections beyond what V5 says
  about `users` (the schema is migrated; this session does not
  modify it).

## After authoring all tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm the umbrella's integration test path matches what each
   subticket listed in out_of_scope. The locked path is
   infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
   in all four files. Fix any mismatch BEFORE you stop.
3. Confirm each ticket's `out_of_scope` correctly punts the items
   from this handoff's "Out-of-scope for T1-E entirely" list
   (group dispatch; invite-gating; probation; /ban; translation;
   ProgressNotifier; SimpleX/Signal beans; bootstrap-admin
   @Startup; permanent-delivery-failure cleanup; inbound back-
   pressure; transport-layer size cap enforcement; LLM output
   sanitizer; audit-log writes from auto-register; confirmation
   pending state; full input length caps; mention recognition for
   groups).
4. Confirm each ticket's spec_refs list contains ONLY anchors from
   "Spec anchors verified" above; if you needed a different
   anchor, verify it in the file before citing.
5. Confirm `migration_touch: false` in every T1-E ticket's
   frontmatter (T1-E is migration-free per "State at handoff" — if
   you reached for V12, escalate before authoring).
6. **Cross-reference update: M1-020 deferred_on.** Edit
   `docs/plan/m1/tickets/M1-020-exception-message-sanitization.md`
   frontmatter:
     - `deferred_on: M1-035b` (the Provider-side router subticket
       — that is where InboundHandler exception sites first land
       under `infochat-provider/src/main/java/.../messaging/`,
       which M1-020's grep targets).
     - `last_updated: 2026-05-17`.
   Do NOT change M1-020's status, blocked_by, files_scope,
   acceptance, or any other field. This is a two-line metadata
   edit. Re-run
   `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and verify the Deferred section now
   shows `M1-020 → M1-035b`.
7. Do NOT touch M1-019 (already wired to M1-033 by the T1-D
   authoring session).
8. Do NOT touch M1-021 or M1-031 (their fields are operator-
   updated when/if they're un-deferred).
9. Commit the M1-020 metadata edit AND the STATUS.md regen as a
   SINGLE `process:` commit on `main`. Subject:
   `process: Wire M1-020 deferred_on → M1-035b (T1-E router subticket)`
   One commit, two files (M1-020 ticket + STATUS.md). The four new
   T1-E ticket files stay UNTRACKED.
10. Print a one-paragraph summary: "T1-E adapter+router drafted as
    M1-035 + M1-035a + M1-035b + M1-035c under
    docs/plan/m1/tickets/. The ticket files are untracked on main.
    M1-020's deferred_on was updated to M1-035b and STATUS.md
    regenerated, committed as a single `process:` commit. The user
    runs /m1-tick start M1-035a (the first leaf the dependency
    graph surfaces) when ready."
11. STOP. Do NOT commit the four new ticket files. Do NOT run
    /m1-tick start.

## What you do NOT do

- Do NOT commit any new ticket file (drafts ride untracked through
  /m1-tick start). The M1-020 deferred_on update IS committed as a
  single `process:` commit per step 9 above; the new T1-E ticket
  files are NOT.
- Do NOT run /m1-tick start or any other /m1-tick subcommand
  beyond `/m1-tick status` (acceptable as a sanity check that the
  STATUS regen worked).
- Do NOT renumber. The IDs M1-035, M1-035a, M1-035b, M1-035c are
  LOCKED at the tail; only re-grep + shift the whole group
  together if a new ticket has landed since this handoff (per "ID
  allocation" above).
- Do NOT begin authoring T1-F tickets. That is a separate session
  with its own JIT handoff.
- Do NOT touch M1-019 / M1-021 / M1-031. Their fields are
  maintained elsewhere.
- Do NOT add a new Flyway migration. T1-E is migration-free; if
  you reach for V12, escalate.
- Do NOT add a new `audit_log.action` verb. MVP auto-register
  skips audit_log; widening the closed verb set is a spec
  amendment + a separate `spec:` commit.
- Do NOT extend `CapabilityFlags` by renaming it to
  `AdapterCapabilities` — keep the existing type name; the design
  doc's rename is a separate `spec:` follow-up after T1-E merges.
- Do NOT add group `@mention` dispatch, the `supportsMentionByContactId`
  check on the inbound path, or any group-keyed code path.
  ScopeRef.Group is type-complete for T2-F but NOT dispatched-to
  in MVP.
- Do NOT add invite-gating, slow-start probation, `/ban`,
  `/unban`, `/invite *`, `/vouch`. Those are T2-A.
- Do NOT add `TranslationProvider` impls or `/lang`. Those are T2-C.
- Do NOT extend `ProgressNotifier` or `ProgressStage`. /help
  bypasses the notifier per spec.
- Do NOT add SimpleX or Signal adapter beans. Those are T3-A.
- Do NOT add the bootstrap-admin @Startup bean. MVP relies on a
  manual SQL grant; the @Startup bean is a future ticket.
- Do NOT wire the LLM output sanitizer. /help text is
  deterministic; the sanitizer lands in T1-F's /summary.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
  Suggested slugs:
    M1-035  : adapter-router-umbrella
    M1-035a : inmemory-adapter-spi-fillin
    M1-035b : adapter-registry-router-gates
    M1-035c : help-and-auto-register
  Re-check slug length ≤30 and trim if needed.
- Drafts ride UNTRACKED through /m1-tick start.
- Suffix-IDs (M1-035a/b/c) and umbrella semantics: see
  docs/process/workflow.md §Ticket-ID placeholder convention.
- "M" prefix → /m1-tick flow; "process:" prefix → direct commit on
  main; "spec:" prefix → direct commit on main. This handoff itself
  is a `process:` commit; the four new tickets it authors are
  M-prefix commits later. The M1-020 deferred_on metadata edit is a
  `process:` commit (it edits a tracked ticket file that already
  exists; it adds no code, no migration, no spec change).

## Your immediate task when the user says "go"

1. Re-grep `docs/plan/m1/tickets/` for `^id: M1-` to confirm the
   next free numeric IDs (M1-035/M1-035a/M1-035b/M1-035c expected;
   bump as a group if a new ticket was authored since this handoff).
2. Read M1-007 + M1-007a in docs/plan/m1/tickets/ once for style.
3. Read M1-008 + M1-008a in docs/plan/m1/tickets/ once (umbrella+
   subticket pattern for the schema group — closest structural
   analogue).
4. Read M1-007c in docs/plan/m1/tickets/ once (the SPI-stub
   precedent you're extending).
5. Read docs/process/ticket-template.md once.
6. Read docs/process/workflow.md §Ticket-ID placeholder convention
   once.
7. Read docs/spec/messaging.md in one pass.
8. Read docs/spec/commands.md §Surface conventions + §Discovery +
   §Permission model + §Onboarding in one pass.
9. Read docs/spec/security.md §Authorization model + §Trust
   boundaries in one pass.
10. Read docs/design/06-messaging.md §6.1 + §6.2 + §6.2.1 + §6.2.2
    + §6.2.3 + §6.3 + §6.6 + §6.7 + §6.8 + §6.9 + §6.11 + §6.14 in
    one pass.
11. Read docs/design/03-commands.md §3.1 + §3.4 in one pass.
12. Read docs/design/00-mvp.md §4 + §5 + §6 in one pass.
13. Read every file under
    infochat-messaging-adapter/src/main/java/io/infochat/messaging/
    once (7 small files — the existing SPI stubs).
14. Read infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
    lines 1-100 once (the `users` table shape).
15. Write M1-035a (longest; SPI fill-in template).
16. Write M1-035b (next-longest; registry + router + gates).
17. Write M1-035c (medium; /help + auto-register).
18. Write M1-035 umbrella (shortest; the cross-cutting IT only).
19. Edit M1-020's `deferred_on` per step 6 of "After authoring all
    tickets" and run scripts/regen-status.py.
20. Commit the M1-020 metadata edit + STATUS.md regen as a single
    `process:` commit per step 9.
21. Print the summary. STOP.
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Four new ticket files appear under `docs/plan/m1/tickets/`
      (M1-035, M1-035a, M1-035b, M1-035c) — UNTRACKED.
- [ ] One `process:` commit lands on `main` updating M1-020's
      `deferred_on: M1-035b` and regenerating STATUS.md.
- [ ] Working tree shows the four new ticket files as untracked,
      nothing else.
- [ ] No new Flyway migration (T1-E is migration-free).
- [ ] No new audit_log.action verb in V5 (MVP auto-register skips
      audit_log).
- [ ] STATUS.md "Deferred → post-mvp-hardening" section shows
      `M1-020 → M1-035b` after the regen.

If the session deviates (touches code, tries to author M1-036, adds
a Flyway migration, renumbers, or commits the four new ticket
files), it has misread the brief — abort and start over with the
same prompt.
