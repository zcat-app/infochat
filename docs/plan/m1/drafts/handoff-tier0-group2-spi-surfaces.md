# Session handoff — Tier 0 Group 2: SPI surfaces (umbrella + 3 subtickets)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author four ticket files and stop. Do NOT
include this preamble paragraph when pasting — only the fenced block
that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- M1-001, M1-002, M1-003 done and merged on main.
- Process patches landed on main (commit 2daa4d6): umbrella + subticket
  idiom in /m1-tick skill, workflow.md, /redteam skill, ticket-template.md.
- M1-004, M1-005 ticket files exist as UNTRACKED drafts on main
  (status: pending). Per workflow they remain untracked until /m1-tick
  start runs.
- M1-006 and M1-009 may or may not exist as untracked drafts depending
  on whether the Group 1 session has run yet. Either state is fine —
  this session does not depend on either of those files.
- Branch is main, otherwise clean.

## What you do this session

Author exactly four ticket files in docs/plan/m1/tickets/:
  M1-007  — umbrella ticket (SPI interface surfaces integration test)
  M1-007a — infochat-core module + Fetcher + StreamSource SPIs
  M1-007b — infochat-llm-adapter module + LlmProvider + EmbeddingProvider SPIs
  M1-007c — infochat-messaging-adapter module + MessagingAdapter +
            TranslationProvider + ProgressNotifier SPIs

These four share heavy context — docs/spec/architecture.md §Ingest
SPIs and §Architectural principles, docs/spec/messaging.md (whole
SPI section), docs/spec/llm.md (whole SPI section), and
docs/design/01-architecture.md §1.2 Module layout. The three subtickets
follow the SAME Maven-module-introduction shape (parent pom + new
module pom + interface files + adding dep on downstream modules); once
you've authored M1-007a, M1-007b and M1-007c are largely
substitution-templated. The umbrella M1-007 references the subtickets'
integration test, so authoring it last in the SAME session keeps the
paths consistent.

When you finish, leave the four new files UNTRACKED on main (workflow
rule: drafts ride untracked through /m1-tick start). Do NOT commit.

## The 5-step macro plan (you are in step 4)

  1. ✓ done: 1 calibration ticket (M1-003)
  2. ✓ done: skeleton-pass enumeration (~47 tickets)
  3. ✓ done: dependency graph rendered, no cycles
  4. ⬅ Tier 0 ticket files in flight; THIS SESSION lands the SPI group
       (M1-007 umbrella + 3 subtickets). A separate session authors
       M1-006 + M1-009 (DB roles + advisory lock); see
       docs/plan/m1/drafts/handoff-tier0-group1-db-roles-and-advisory-lock.md.
  5. (later) /m1-tick start invocations with user-driven escalation.

## Locked decisions for the four tickets

All IDs and structural choices are LOCKED. Don't re-debate.

The umbrella+subticket pattern was adopted in this milestone's
process work — see docs/process/workflow.md §Ticket-ID placeholder
convention for the "Umbrella + subticket idiom" paragraph. Read it
once. Subtickets MUST list the umbrella's integration test file in
out_of_scope (the ticket-template.md hint is the canonical reminder).

### Shared invariants across all four tickets

- The umbrella's integration test path is LOCKED:
    infochat-collector/src/test/java/io/infochat/collector/spi/AllSpisLoadIT.java
  Each subticket lists this exact path in its out_of_scope.
- "No impls" is a HARD rule for the subtickets. M1-007a/b/c each define
  empty interfaces + supporting types ONLY. No InMemoryAdapter, no
  Ollama provider, no English/Czech translator, no FetcherImpl. Those
  land in later tickets (Tier 1 and Tier 3).
- Tests in each subticket are minimal: a Class.forName-style verification
  that the new interfaces compile and load. The cross-cutting
  whole-topic verification ("all SPIs from all three modules load
  together") lives ONLY in the M1-007 umbrella.

### M1-007 — umbrella (whole-topic integration test)
- blocked_by: [M1-007a, M1-007b, M1-007c]
- complexity: low, risk: low
- security_relevant: FALSE
- migration_touch: FALSE
- round_cap: 2
- files_budget: 2  (the test file + at most one wiring stub)
- files_scope:
    - infochat-collector/src/test/java/io/infochat/collector/spi/AllSpisLoadIT.java
- Scope:
  * A single plain-JUnit @Test (NOT @QuarkusTest — Class.forName does
    not need a Quarkus context) that loads every SPI interface from
    all three new modules by fully-qualified name and asserts
    Class.isInterface() is true for each.
  * The test verifies that Collector's classpath sees all three
    adapter modules (transitively: collector → infochat-core, collector
    → infochat-llm-adapter; provider adds → infochat-messaging-adapter,
    but the messaging SPI is provider-only by design so the umbrella
    test runs from a module that depends on it. Per
    design/01-architecture.md §1.2, only Provider depends on
    infochat-messaging-adapter. Resolution: put the umbrella test in
    Collector AND add a second test in Provider that loads the
    messaging SPIs. OR put ONE test in Provider that loads ALL SPIs
    since Provider transitively depends on core + llm-adapter +
    messaging-adapter. Pick the second — single test, lives in
    infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java.
    UPDATE the locked path above to the provider location when you
    write the umbrella ticket.)
  * Body explains WHY this is a separate commit: the umbrella idiom
    (workflow.md §Ticket-ID placeholder convention) — whole-topic
    verification is meaningfully different from any single slice's
    test, so it ships as its own reviewable unit.
- Spec_refs (all verified):
  * docs/spec/architecture.md §Architectural principles
  * docs/design/01-architecture.md §1.2 Module layout (Maven)
- decision_refs: (none — pure structural ticket)

### M1-007a — infochat-core + Fetcher + StreamSource SPIs
- blocked_by: [M1-001]
- complexity: medium, risk: low
- security_relevant: FALSE
- migration_touch: FALSE
- round_cap: 2
- files_budget: 10  (parent pom + module pom + 2 SPI interfaces +
  supporting types + dependency entries in collector/provider poms +
  smoke tests)
- Scope:
  * Add new Maven module infochat-core/ — pom.xml depending on the
    parent BOM, no Quarkus extensions (it's a library jar, not a
    Quarkus app), Java 25 source/target.
  * Add <module>infochat-core</module> to the parent pom.
  * Add infochat-core as a <dependency> in BOTH
    infochat-collector/pom.xml and infochat-provider/pom.xml.
  * Define two SPI interfaces in package io.infochat.core.ingest:
      - Fetcher  — polled, request/response. Method shape per
        docs/spec/architecture.md §Ingest SPIs. Returns a List of
        NormalizedPost.
      - StreamSource — long-lived, event-driven. Method shape per
        the same spec section. Lifecycle methods: start(), stop().
  * Define supporting record NormalizedPost in
    io.infochat.core.ingest with the minimum field set: source_id
    (long), upstream_identifier (String), title (String, nullable),
    body (String), url (String, nullable), published_at (Instant,
    nullable), fetched_at (Instant), raw_metadata (Map<String,String>
    — non-null, possibly empty). Use a Java record (immutable by
    design).
  * Out-of-scope MUST list:
    infochat-collector/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java
    (the umbrella's integration test — wait, see umbrella note above;
    the locked path is in Provider not Collector; double-check before
    listing).
- Spec_refs (all verified):
  * docs/spec/architecture.md §Ingest SPIs
  * docs/spec/architecture.md §Architectural principles
  * docs/design/01-architecture.md §1.2 Module layout (Maven)
- decision_refs: D38

### M1-007b — infochat-llm-adapter + LlmProvider + EmbeddingProvider SPIs
- blocked_by: [M1-001]
- complexity: medium, risk: low
- security_relevant: FALSE
- migration_touch: FALSE
- round_cap: 2
- files_budget: 9
- Scope:
  * Add new Maven module infochat-llm-adapter/.
  * Add as <dependency> in BOTH Collector and Provider poms (Collector
    uses LlmProvider in the eval pipeline; Provider uses it for the
    chat agent).
  * Define SPI interfaces in package io.infochat.llm:
      - LlmProvider — method shape per docs/spec/llm.md §SPI shape.
        The closed task discriminator must exist as a sibling enum
        LlmTask. Keep the interface MINIMAL: just generate(LlmTask
        task, String systemPrompt, String userPrompt) → LlmResponse
        or similar. Match what the spec mandates, no more.
      - EmbeddingProvider — embed(String text) → float[] or
        EmbeddingResult record. Per docs/spec/llm.md §Embedding
        pipeline.
  * Supporting types in the same package:
      - LlmTask enum (closed set; the spec enumerates the values
        explicitly under §SPI shape — read it).
      - LlmResponse record (just the response text + token-usage
        if the spec mandates).
      - EmbeddingResult record (just the float[] for now; allows
        future expansion to multi-vector returns).
  * Out-of-scope MUST list the umbrella's integration test path.
- Spec_refs (all verified):
  * docs/spec/llm.md §SPI shape
  * docs/spec/llm.md §Embedding pipeline
  * docs/spec/architecture.md §Architectural principles
  * docs/design/01-architecture.md §1.2 Module layout (Maven)
- decision_refs: (none — the spec section IS the contract)

### M1-007c — infochat-messaging-adapter + 3 messaging SPIs
- blocked_by: [M1-001]
- complexity: medium, risk: low
- security_relevant: FALSE
- migration_touch: FALSE
- round_cap: 2
- files_budget: 12  (largest of the subtickets — 3 SPIs + 2 supporting
  records + module/parent pom + provider pom dep)
- Scope:
  * Add new Maven module infochat-messaging-adapter/.
  * Add as <dependency> in PROVIDER ONLY (not Collector — Collector
    has no messaging surface per design 1.2).
  * Define SPI interfaces in package io.infochat.messaging:
      - MessagingAdapter — per docs/spec/messaging.md §Required SPI
        surface. Inbound message handler registration, outbound
        send/update/finalize methods, capability-flag accessor.
      - TranslationProvider — per docs/spec/llm.md §Translation
        flow. Method shape: translate(String text, Locale target)
        → String. (TranslationProvider is in messaging-adapter
        because translation is presentation-layer per the spec —
        translates BOT prose, not user input. Provider-only.)
      - ProgressNotifier — per docs/spec/messaging.md §Progress
        notifications. Cross-cutting Provider-side notifier;
        publish(StageEvent) → coalesced edit on the adapter.
  * Supporting types in same package:
      - MessageHandle (record) — opaque per docs/spec/messaging.md
        §Message handles. Adapter-defined contents; the record is
        a wrapper so the type system tracks it, but callers MUST
        NOT inspect the inside. Document this invariant on the
        record's Javadoc.
      - CapabilityFlags (record) — closed set per
        docs/spec/messaging.md §Capability flags (minimum set).
        v1 minimum: supportsCodeFormatting (bool),
        supportsMarkdownLinks (bool, always false in v1 per the
        spec). The record's Javadoc must reference §Capability
        flags so the next reader knows the closed set.
  * Out-of-scope MUST list the umbrella's integration test path.
- Spec_refs (all verified):
  * docs/spec/messaging.md §Required SPI surface
  * docs/spec/messaging.md §Capability flags (minimum set)
  * docs/spec/messaging.md §Message handles
  * docs/spec/messaging.md §Progress notifications
  * docs/spec/llm.md §Translation flow
  * docs/design/01-architecture.md §1.2 Module layout (Maven)
- decision_refs: D32, D46

## Spec anchors verified (use ONLY these; others MUST be re-verified)

  docs/spec/architecture.md §Service split (line 12)
  docs/spec/architecture.md §Ingest SPIs (line 138)
  docs/spec/architecture.md §Architectural principles (line 334)
  docs/spec/architecture.md §Hardware profiles (line 356)
  docs/spec/messaging.md §Required SPI surface (line 26)
  docs/spec/messaging.md §Capability flags (minimum set) (line 102)
  docs/spec/messaging.md §Message handles (line 144)
  docs/spec/messaging.md §Progress notifications (line 158)
  docs/spec/llm.md §SPI shape (line 27)
  docs/spec/llm.md §Embedding pipeline (line 155)
  docs/spec/llm.md §Translation flow (line 195)
  docs/design/01-architecture.md §1.2 Module layout (Maven) (line 89)

Any spec_ref you cite that ISN'T in this list, verify the anchor by
reading the file. Clarity-preflight FAILs on missing anchors.

## Style requirements

Match M1-003, M1-004, M1-005 in docs/plan/m1/tickets/ — read all three
once for style. Read docs/process/ticket-template.md once for the
canonical schema. Then write.

Length per ticket: M1-007 umbrella is the short one (~120-150 lines);
M1-007a/b/c each ~180-230 lines. Total: ~700-850 lines authored this
session.

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-003, M1-004, M1-005 once for style.
- DO read docs/process/ticket-template.md once.
- DO read docs/spec/messaging.md §Required SPI surface + §Capability
  flags + §Message handles + §Progress notifications in one pass
  (they're contiguous in the file).
- DO read docs/spec/llm.md §SPI shape + §Embedding pipeline +
  §Translation flow in one pass.
- DO read docs/spec/architecture.md §Ingest SPIs in one pass.
- DO NOT spawn Explore subagent.
- DO NOT pre-load full docs/spec/ tree.
- DO NOT re-read sections you already loaded.

## After authoring all four tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm the umbrella's integration test path matches what each
   subticket listed in out_of_scope. Fix any mismatch BEFORE you stop.
3. Print a one-paragraph summary: "M1-007 umbrella + M1-007a/b/c
   drafted as untracked files on main. With M1-004 and M1-005 already
   present (and M1-006/M1-009 if the other session has run), the
   Tier-0 ticket-authoring step is complete pending whichever group
   hasn't run yet."
4. STOP. Do NOT commit. Do NOT run /m1-tick start.

## What you do NOT do

- Do NOT commit any ticket file.
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT renumber. The IDs are LOCKED.
- Do NOT begin authoring M1-006 or M1-009 — those are the other
  session.
- Do NOT add SPI impls (no InMemoryAdapter, no Ollama provider,
  no English/Czech translator). Subtickets are interfaces-only.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start.
- Suffix-IDs (M1-007a/b/c) and umbrella semantics: see
  docs/process/workflow.md §Ticket-ID placeholder convention.

## Your immediate task when the user says "go"

1. Read M1-003, M1-004, M1-005 in docs/plan/m1/tickets/ once for style.
2. Read docs/process/ticket-template.md once.
3. Read docs/process/workflow.md §Ticket-ID placeholder convention
   once (it covers the umbrella+subticket idiom you're applying).
4. Read the relevant spec sections in the order suggested above.
5. Write M1-007a (the largest of the subtickets in scope; serves as
   a template for the next two).
6. Write M1-007b (substitute LLM-adapter scope into the template).
7. Write M1-007c (substitute messaging-adapter scope).
8. Verify the integration test path; write M1-007 umbrella that
   matches.
9. Print the summary. STOP.
```
