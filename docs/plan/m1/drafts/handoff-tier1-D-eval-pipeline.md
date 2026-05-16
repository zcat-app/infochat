# Session handoff — Tier 1 Group D: ingest evaluation pipeline (Stage 1 + Stage 2 + tagger + embedding)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T1-D ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets are done and merged on main (M1-001..M1-007 +
  M1-009).
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
    M1-027 (Provider catch-up: provider_state V9 migration +
            NewPostReconciler + new_post LISTEN listener +
            NewPostHandler stub)
    M1-028 (Collector outbox: PostPersister + EvalQueueProducer +
            OutboxRehydrator + FetchScheduler)
- M1-019 (stdout API-key redaction) is `status: deferred` with
  `deferred_reason: post-mvp-hardening` and empty `deferred_on:`.
  Updating `deferred_on:` to point at the LLM-call ticket authored
  in THIS session is one of the after-authoring steps below — see
  "After authoring all tickets" step 6. (M1-019 protects the LLM
  call-site logs from leaking API keys; the LLM call site enters
  the codebase in T1-D's Stage-2 ticket.)
- M1-020 (exception-message sanitization) is `status: deferred`,
  `deferred_reason: post-mvp-hardening`. Leave its `deferred_on:`
  empty — it pairs with T1-E (messaging adapter), not T1-D.
- M1-021 (identity/audit redteam remediation) is `status: deferred`,
  `deferred_reason: end-of-tier-1-redteam`. T1-D does not block on
  it and does not unblock it.
- M1-029 (RSS body-read timeout test tolerance) is done.
- M1-030 (Provider catch-up hardening backlog) is done — addressed
  3 OUT-OF-MODEL advisories from the M1-027 red-team.
- M1-031 (Provider catch-up hardening followup) was DRAFTED AS
  `status: deferred` from 3 M1-030 OUT-OF-MODEL advisories. It is
  NOT runnable and consumes no slot; it surfaces if and when the
  user un-defers it.
- Flyway migrations on disk under
  infochat-core/src/main/resources/db/migration/:
    V1__init.sql, V2__roles.sql, V3__heartbeat.sql, V4__nologin.sql,
    V5__identity_audit.sql, V6__sources_tags.sql, V7__joins_post.sql,
    V8__bootstrap_meta.sql, V9__provider_state.sql.
  T1-D adds at least one new migration (the quarantine table and
  per-tier supporting indexes — see the locked migration list under
  "Locked decisions" below). The next free integer at this session's
  start is V10. Re-grep the migration directory at /m1-tick start
  time and pick the next free integer per ticket; the names suggested
  below are the EXPECTED slots when only V1..V9 exist.
- Branch is main, otherwise clean.

## Already-on-disk SPI scaffolding (do not redo)

The LLM SPI surfaces landed in M1-007b and are stable empty stubs
that the T1-D tickets WIRE INTO concrete impls. Do not re-author
the interfaces themselves; pick them up by name:

  infochat-llm-adapter/src/main/java/io/infochat/llm/
    LlmProvider.java        — generate(ModelTask, system, user) -> LlmResponse
    EmbeddingProvider.java  — embed(List<String>) -> List<EmbeddingResult>
    ModelTask.java          — SECURITY_JUDGE, TAGGER, ENTITY,
                              SUMMARIZER, CHAT_AGENT, TRANSLATOR
    LlmResponse.java
    EmbeddingResult.java

There is no Router, no CallContext, no concrete impl yet. T1-D
introduces the first concrete LlmProvider impl (locked below as the
OpenAI-compatible HTTP impl — Ollama / llama.cpp / OpenAI /
NanoGPT all share the same protocol per design §5.3) and the first
concrete EmbeddingProvider impl. The router lands in the Stage-2
ticket (it is the smallest place the (ModelTask, scope_language) →
LlmProvider lookup is actually used in v1).

The eval-queue channel is wired one direction only: the Collector's
EvalQueueProducer (M1-028) emits `PostPersister.PersistedPostKey`
messages on the in-memory SmallRye Reactive Messaging channel
`eval-queue`. There is no consumer yet — T1-D ships the consumer.

## What you do this session

Author ticket files in docs/plan/m1/tickets/ for the T1-D group.

T1-D per docs/plan/m1/drafts/session-grouping-plan.md §Tier 1 is
the ingest evaluation pipeline. The plan defaults T1-D to **3
tickets** (Stage 1 deterministic security; LLM + Stage 2;
tagger + embedding). The user opted to **decide the split at
authoring time** rather than locking 3 in the handoff. See "Open
question for the authoring session" below — pick Option A, B, or C
at the top of the session and document the choice in the first
ticket's "Implementation notes."

When you finish, leave the new ticket files UNTRACKED on main
(workflow rule: drafts ride untracked through /m1-tick start). Do
NOT commit the ticket files.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T1-D gets fresh IDs at
the tail at authoring time. The next free integer at this session's
start is M1-032 (M1-019/020/021/031 are deferred and consume no new
slots; M1-022..M1-030 are done).

Default (3-ticket split — Option B in "Open question" below):
  M1-032 — Stage 1 deterministic security
           (HTML sanitization + Unicode normalization +
            prompt-injection regex set + watchdog + quarantine
            row writes + post.stage1_done/stage1_flagged advance)
  M1-033 — Stage 2 LLM judge + first concrete LlmProvider impl + router
           (BENIGN/INJECTION/MALWARE/UNKNOWN verdict handling +
            verdict-vs-infra split + stage2_failed flag +
            release-on-stage2-failure config flag honour +
            OpenAI-compatible LlmProvider impl +
            (ModelTask, scope_language) → LlmProvider router)
  M1-034 — Tagger + embedding (Stage 4 + Stage 5)
           (LLM tagger with bootstrap-tags fallback +
            EmbeddingProvider impl + post_embedding table +
            model-identity guard + dimensionality invariant +
            status→READY transition + pg_notify('new_post'))

Re-grep the tickets directory at /m1-tick start time to confirm
M1-032/M1-033/M1-034 are still the next free slots before committing.
If a new ticket has been authored in the interim, take the next free
slot — the slug → file-name mapping is the only invariant; the
numeric ID is allocated mechanically.

## Where you are in the milestone

Tier 1 (MVP vertical slice) is in flight.
  T1-A schema (done)
  T1-B ingest sources (done — 5 tickets including SSRF chain)
  T1-C outbox/NOTIFY (done — 2 tickets, M1-027 + M1-028)
  T1-D eval pipeline (this session — 2/3/4 tickets, default 3)
  T1-E adapter + router (umbrella + InMemoryAdapter + router + /help)
  T1-F first commands (/add-source, /summary)

After T1-D, the next session authors T1-E's detailed handoff JIT.
See docs/plan/m1/drafts/session-grouping-plan.md for the full plan.

## Open question for the authoring session

**2 vs 3 vs 4 tickets?** session-grouping-plan §Tier 1 says 3 was
the original sizing. The user opted in this handoff session to defer
the call — pick at the top of the authoring session and document
the choice in the first ticket's "Implementation notes." The viable
shapes are:

- **Option A (2 tickets — aggressive).** Combine Stage 1 + Stage 2
  into one ticket and tagger + embedding into a second. Lower
  bookkeeping cost, but the Stage 2 ticket carries BOTH the LLM-judge
  semantics AND the first-ever concrete LlmProvider impl + router,
  which is review-heavy. Stage 1 has no LLM dependency at all and
  has its own correctness argument (regex set + watchdog + quarantine
  row writes); pairing it with Stage 2 forces the reviewer to chase
  two unrelated correctness models in one diff. ACCEPTABLE only if
  you assess after reading the spec sections below that each
  combined ticket's files_budget fits ≤ 12 and the acceptance
  criteria stay readable as a single list.

- **Option B (3 tickets — DEFAULT, matches session-grouping-plan).**
  M1-032 Stage 1, M1-033 Stage 2 (+ first LlmProvider + router),
  M1-034 tagger + embedding (+ first EmbeddingProvider + model
  identity guard). Each ticket maps cleanly to one stage-shaped
  diff with one correctness argument. The shared `infochat-llm-
  adapter/routing/` package lands in M1-033 with the router; M1-034
  consumes it. ID allocation above is locked for this option.

- **Option C (4 tickets — split tagger off from embedding).**
  M1-032 Stage 1, M1-033 Stage 2 + first LlmProvider + router,
  M1-034 tagger only, M1-035 embedding only (+ first
  EmbeddingProvider + model identity guard + status→READY +
  pg_notify). Lowest per-ticket review surface, but the tagger and
  embedding tickets each ship a small files_budget (~5 files) and
  the bookkeeping overhead per ticket is the same regardless of
  size. Pick this ONLY if the embedding lifecycle (model identity
  row, dimensionality invariant, pgvector column dimension by
  profile, the model-identity-mismatch-at-startup-refuses-start
  rule from `docs/spec/llm.md` §Embedding pipeline) feels too heavy
  to share a ticket with the tagger's fallback-to-bootstrap-tags
  rule.

Default Option B. Pick at the top of the session; document the
choice in the first ticket's "Implementation notes." Do NOT split
the difference (a half-baked Stage 1 in one ticket and the regex
watchdog separately is the worst of both).

### Out-of-scope for T1-D entirely (regardless of split)

These belong to later groups and MUST appear in every T1-D ticket's
`out_of_scope` list:

- **EntityExtractor (Stage 3 of §1.3.4).** The session-grouping-plan
  T1-D row reads "Stage 1 deterministic security, LLM + Stage 2,
  tagger + embedding" — entity extraction is not enumerated. The
  `post_entity` and `post_reference` tables are only consumed by
  `LinkingJob` (`docs/design/01-architecture.md §1.3.5`), which is
  a separate Tier-1/2 boundary not in T1-D. Entity extraction
  belongs in the LinkingJob ticket (T2 or later); T1-D's pipeline
  goes S1 → (S2 if S1 hit) → Tagger → Embedding → READY, skipping
  what spec §1.3.4 numbers as stage 3. State this explicitly in
  every T1-D ticket so the reviewer's negative-space check doesn't
  flag the gap.
- **LinkingJob (§1.3.5).** Same boundary — scheduled job that walks
  post_reference / post_entity / post_embedding to compute Tier-2
  cross-links. Not T1-D.
- **Summarizer / chat-agent / translator call sites.** Those are
  Provider-side LLM-output surfaces (T1-F, T2-D, T2-C). T1-D's only
  LLM call sites are Stage 2 judge, Tagger, and Embedding (the
  embedder is not a ModelTask but uses the EmbeddingProvider SPI).
- **LLM output sanitizer (spec §LLM output sanitizer).** Sanitizer
  guards LLM-AUTHORED OUTPUT (summarizer prose, chat replies, digest
  prose). T1-D's LLM outputs are Stage-2 labels (BENIGN/INJECTION/
  MALWARE/UNKNOWN — a 4-token closed set, parsed by exact match)
  and Tagger JSON (parsed against the controlled vocabulary). Neither
  reaches a user. Sanitizer lives in T1-F (where /summary first
  produces user-visible LLM prose).
- **Re-evaluation job (§Re-evaluation job).** Periodic background
  re-submit of `stage2_failed=true` or `UNKNOWN` posts to Stage 2;
  per-source UNKNOWN auto-disable rule. Belongs in T2 alongside
  the quarantine admin workflow. T1-D's tickets MAY add the
  `stage2_failed` flag advance and the `UNKNOWN → QUARANTINED`
  transition that FEED the re-eval queue, but MUST NOT add the
  re-eval scheduler itself, the per-post attempt counter, the
  NEEDS_REVIEW transition, or the per-source UNKNOWN auto-disable.
- **Admin notifier throttling (`docs/spec/security.md §Failure
  handling` admin notifications).** Stage 2 infra failure, Stage 1
  infra failure (watchdog crash), tagger fallback, and embedding
  failure all spec-promise a "throttled admin notification" via a
  coalesced `(channel, error_class)` window. T1-D MUST log every
  such failure at WARN with the canonical error_class string but
  MUST NOT wire the throttled admin notifier itself — that lives in
  T2-G (quarantine workflow / admin notifications). Log the
  error_class as a structured field so the future notifier can
  pick it up without diff churn. Document this stub-vs-notifier
  boundary in each ticket's body and out_of_scope.
- **`new_price_snapshot` channel.** Asset commands (T2-H, decision
  D39). Not T1-D.
- **`quarantine_review` channel.** Quarantine state-machine NOTIFYs
  fire on the PENDING insert in T1-D's Stage-1 ticket (because the
  quarantine row is inserted there), but the Provider-side
  reconciler + listener for that channel is M2 territory per
  `docs/design/01-architecture.md §1.5`. T1-D MUST NOT add a
  Provider-side quarantine_review listener; M1-027's NewPostListener
  + NewPostReconciler pattern is the template for when M2 wires
  it up.
- **`/quarantine list/approve/reject` admin commands and the
  `approve_quarantine` / `reject_quarantine` stored procedures.**
  T2-G territory. T1-D's quarantine row state machine is
  `PENDING` insert only; transitions to `BENIGN_CLOSED` from a
  Stage-2 BENIGN verdict happen in M1-033/M1-034 (Stage 2 ticket).
  Transitions to `APPROVED`, `REJECTED`, and NEEDS_REVIEW are all
  T2-G.

## Locked decisions (apply to every option)

If any of these conflicts with what you'd otherwise pick at
authoring time, escalate — don't silently override. The locks
exist because they were resolved in this handoff session against
verified spec anchors.

### Stage 1 ticket (M1-032 in Option B numbering)

- blocked_by: [M1-008c, M1-028]
  (post table from V7 — Stage 1 writes to post.body, post.stage1_done,
  post.stage1_flagged. M1-028's PostPersister + EvalQueueProducer +
  OutboxRehydrator are the upstream that puts a RAW post on the
  eval-queue. Stage 1 is the first consumer.)
- complexity: medium
- risk: high
  (the regex set is the deterministic security boundary. A wrong
  regex pattern, a missing Unicode normalization step, a watchdog
  that fails open, or a placeholder marker that an attacker can
  forge silently degrades the entire ingest security posture. The
  `release-on-stage2-failure=true` profile defaults on laptop/pi
  amplify the impact because Stage 1 redactions are the ONLY
  protection on the post body during a Stage-2 outage on those
  profiles.)
- security_relevant: TRUE
- migration_touch: TRUE
  (adds Flyway V10 — at minimum the `quarantine` table from
  `docs/design/02-schema.md §2.5.1`; the per-table GRANTs (NO
  SELECT on `quarantine.original_html` from infochat_provider,
  view-shaped read access via `quarantine_review_view`) are
  spec-load-bearing per `docs/spec/security.md §DB roles`. The
  `approve_quarantine` / `reject_quarantine` stored procedures
  from `§2.5.2` are T2-G territory and MUST NOT land in V10 — only
  the bare table, the view, the GRANTs, and the indexes from
  §2.5.1. State this exclusion in the migration's header comment
  and in the ticket's out_of_scope.)
- round_cap: 3
  (high-risk + first-ever security-boundary code on the ingest
  path. Allow one extra REWORK round for tightening regex/watchdog
  semantics if round-1 review finds gaps; the round-N must-shrink
  rule still applies.)
- Scope items the ticket MUST address (transcribe each as a separate
  acceptance criterion per the memory-feedback "Transcribe spec
  promises into security-ticket acceptance items" rule — every
  separable spec sentence in `docs/spec/security.md §Ingest pipeline`
  becomes one acceptance item, verbatim, no summarizing):
  * HTML allowlist sanitization with the exact tag set from
    `docs/design/04-security.md §4.2 Stage 1` (p, br, a (href only,
    http/https), strong, em, ul, ol, li, code, pre, blockquote, h1-h6).
    Strip everything else, including `script`, `style`, `iframe`,
    `object`, `form`, `on*` event attributes, `javascript:`/`data:`/
    `file:` href schemes. Convert allowed-but-formatted HTML to plain
    text for `post.body`.
  * Unicode normalization steps applied UNCONDITIONALLY (no fenced-
    code carve-out on the ingest path — that carve-out exists ONLY
    on the Provider chat intake, per spec §Ingest pipeline parenthetical):
      - NFKC normalize
      - Strip bidi control characters U+202A..U+202E, U+2066..U+2069
      - Strip zero-width characters U+200B, U+200C, U+200D, U+FEFF
  * Prompt-injection regex set per `docs/design/04-security.md §4.2
    Stage 1` step 3. The exact patterns are LOCKED at design level;
    the ticket may copy them verbatim into Java compile-time string
    constants. Each pattern is case-insensitive.
  * Watchdog: `java.util.regex` engine + per-input wall-clock cap
    per spec §Ingest pipeline "Regex engine commitment (v1)". The
    cap value is profile-driven per `docs/design/04-security.md §4.2`
    table:
      laptop 100ms / vps 100ms / pi 250ms / remote-llm 100ms.
    A watchdog abort is a Stage 1 INFRASTRUCTURE FAILURE, not a
    verdict. Per spec §Failure handling "Stage 1 infrastructure
    failure" → fail-closed: `post.status='QUARANTINED'`,
    `stage1_done=true`, ONE quarantine row with `flagged_by='stage1'`,
    `rule_id='regex_timeout'`, span = whole body. NEVER auto-released.
    Admin notified via the throttled channel (log only in T1-D — see
    "Out-of-scope for T1-D entirely" above).
  * For each Stage-1 regex match:
      - record `(span_start, span_end, rule_id)`
      - replace the match in `post.body` with the SPEC-COMMITTED
        placeholder marker `[REDACTED:<id>]` where `<id>` is a
        per-row random opaque token (base32 over 16 random bytes ⇒ 26
        chars, per design §4.2 step 4 — design-tier choice; the
        `[REDACTED:` and `]` brackets are byte-identical across every
        implementation per spec §Ingest pipeline)
      - insert one `quarantine` row with `flagged_by='stage1'`,
        `status='PENDING'`, `placeholder_id=<id>`, `original_html =
        <verbatim matched span>`, `rule_id=<which regex matched>`
  * Set `post.stage1_flagged = true` if any match, and
    `post.stage1_done = true` at the end of Stage 1 processing
    regardless of match.
  * `post.stage1_done = true` UPDATE is the **persistence cursor**:
    on Stage-1 success with no hits, the post advances to the
    Tagger stage; on hits the post advances to Stage 2 (M1-033 /
    Option B). The transition from "stage1 done" to "stage2
    decision" is the boundary between this ticket and the next.
    For Stage 1 hits: the eval consumer enqueues the post for
    Stage 2 with the original (pre-redaction) body retained for
    the judge. Document the "original retained for Stage 2" path
    explicitly — Stage 2 sees the original per spec §Ingest
    pipeline "Stage 2 — LLM judge" — but Stage 1's UPDATE writes
    the REDACTED body.
  * NEVER block release on its own — Stage 1 scrubs and routes to
    review. Per spec §Ingest pipeline "Stage 1 is a coarse filter,
    not a complete defense." Document the partial-defense framing
    in the ticket body so the reviewer doesn't expect Stage 1 to
    detect non-English / paraphrased / base64-encoded payloads.
  * Tests (testcontainers Postgres; no H2/in-memory substitutes,
    per CLAUDE.md M1 stack-specific rule and engineering-rules
    §8 stack-specific):
      - Clean post (no Stage 1 hits): body unchanged after Unicode
        normalization; `stage1_done=true`, `stage1_flagged=false`,
        zero quarantine rows.
      - Single Stage-1 hit: original span replaced with
        `[REDACTED:<id>]` in body; placeholder regex
        `^\[REDACTED:[A-Z2-7]{26}\]$` matches the per-row id;
        one quarantine row with `flagged_by='stage1'`,
        `status='PENDING'`, `placeholder_id=<id>`,
        `original_html=<verbatim matched span>`,
        `rule_id=<regex id>`; `stage1_flagged=true`.
      - Multiple Stage-1 hits in one post: one quarantine row per
        hit; placeholder ids are pairwise distinct (per-row
        randomization guarantee, per spec §Ingest pipeline).
      - NFKC normalization: a payload using compatibility-form
        characters that resolve to one of the regex set's literal
        keywords post-NFKC is detected.
      - Bidi-control stripping: U+202E followed by an admin verb is
        detected after the bidi stripping pass (and the original
        bidi sequence is in the quarantine `original_html`).
      - Zero-width stripping: a zero-width-joined `ignore previous
        instructions` is detected as a single keyword match after
        the strip.
      - HTML sanitization: a `<script>alert(1)</script>` in the
        body is stripped, leaves no Stage-1 quarantine row (Stage 1
        records the regex hits, not the HTML strips), and is absent
        from `post.body`. A `<a href="javascript:alert(1)">` is
        stripped to plain text with the href dropped.
      - Pre-existing `<<<UNTRUSTED>>>` marker in feed body: stripped
        by the prompt-injection regex set, recorded as a Stage 1
        hit with `rule_id` naming the delimiter-injection pattern,
        and replaced with a `[REDACTED:<id>]` placeholder. This
        guarantees the spec property that an attacker cannot
        pre-craft a fake placeholder that would survive the
        Stage-1 `<<<UNTRUSTED>>>` marker strip (spec §Ingest
        pipeline + spec §Prompt-injection-aware prompt shape).
      - Watchdog abort: a deliberate catastrophic-backtracking
        input wired against ONE of the bounded `.{0,40}` regex
        patterns (the test author picks the most vulnerable in
        the set) trips the per-profile watchdog within
        `1.5 × cap_ms` wall-clock; the post lands with
        `status='QUARANTINED'`, `stage1_done=true`, one quarantine
        row with `rule_id='regex_timeout'`, span = whole body.
        Per the M1-029 precedent ("Loosen wall-clock tolerance"),
        the assertion tolerance is `cap_ms..cap_ms*3` not exact
        match — wall-clock tests are inherently non-deterministic
        under CI load.
- Files_budget: 10
- Spec_refs:
  * docs/spec/security.md §Ingest pipeline (security side)
  * docs/spec/security.md §Failure handling
    (Stage 1 infrastructure failure; Stage 2 verdict vs infra split)
  * docs/spec/security.md §DB roles
    (quarantine.original_html GRANT discipline)
  * docs/spec/llm.md §Prompt-injection-aware prompt shape
    (Stage 1 strips literal `<<<UNTRUSTED>>>` markers upstream)
  * docs/spec/schema.md §Posts and derivatives
    (status state machine, RAW → QUARANTINED for Stage 1 infra fail)
  * docs/spec/schema.md §Invariants (Invariant 5)
  * docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  * docs/design/04-security.md §4.2 Layered ingest security
    (Stage 1 — deterministic, runs on every post)
  * docs/design/04-security.md §4.7 Eval pipeline failure handling
  * docs/design/02-schema.md §2.5.1 quarantine (DDL + view + GRANTs)
- decision_refs: D20, D22

### Stage 2 ticket (M1-033 in Option B numbering)

- blocked_by: [M1-007b, M1-032]
  (LlmProvider SPI from M1-007b — this is the first concrete impl.
  M1-032 is the upstream Stage 1; Stage 2 fires only on Stage 1
  hits per spec §Ingest pipeline.)
- complexity: high
- risk: high
  (first LLM call site in the codebase; first concrete LlmProvider
  impl; the verdict-vs-infrastructure split is the heart of the
  security policy per spec §Failure handling. A wrong verdict
  classification, a parse-error path that doesn't retry, or a
  schema-violating reply silently bucketed as BENIGN would defeat
  the security boundary. The `release-on-stage2-failure` config
  flag inverts the default on `vps`/`remote-llm` vs `laptop`/`pi`
  per design §4.7 — wiring it wrong leaks the pre-eval body on a
  profile that should fail closed.)
- security_relevant: TRUE
- migration_touch: FALSE
- round_cap: 3
- Scope items (each transcribed as one acceptance criterion):
  * First concrete `LlmProvider` impl: HTTP client against an
    OpenAI-compatible chat-completions endpoint, per design §5.3
    "OpenAiCompatibleProvider" — covers Ollama, llama.cpp, OpenAI,
    OpenRouter, NanoGPT. Distinguished by base-url + api-key.
    The Anthropic native impl (design §5.3 "AnthropicProvider") is
    a SEPARATE later ticket per session-grouping-plan §Tier 3 T3-D
    and MUST be out-of-scope here.
  * Router: `(ModelTask, scope_language) → LlmProvider` resolution
    per spec §Per-task routing rules. T1-D's only consumer is
    SECURITY_JUDGE; later tickets (M1-034 tagger, T1-F summarizer,
    T2-D chat-agent) wire additional ModelTasks. The router's
    spec-promised behaviors that MUST land in this ticket:
      - explicit per-task override property (highest priority)
      - language-aware capability check
      - profile default for the task
      - local-only posture: when `infochat.llm.local-only=true`,
        the router NEVER picks a remote provider; a per-task
        override pointing to a remote provider while local-only is
        set is a configuration conflict that FAILS PROVIDER
        STARTUP with a fatal log line identifying the offending
        task and provider (per spec §Per-task routing rules
        "Local-only is the most-restrictive posture", checked once
        at startup not per call).
      - no fallback chain in v1 — exactly one resolution per call;
        an unreachable provider degrades that task to its
        task-specific failure path and does NOT silently switch
        (per spec §Per-task routing rules "No fallback chain in v1"
        and spec §Failure handling "No router-side fallback in v1").
  * Stage 2 verdict label set: parse the LLM reply by EXACT MATCH
    against the four labels `BENIGN`, `INJECTION`, `MALWARE`,
    `UNKNOWN`. Anything else is treated as unparseable per spec
    §Failure handling "Schema-violating LLM output ... retry once,
    then apply the stage-specific failure path."
  * Retry-once-then-fallback policy: on unparseable / schema-violating
    reply OR LLM unreachable OR timeout, retry once. After the
    retry exhausts, this is the **infrastructure failure** path
    (NOT a verdict).
  * Verdict outcomes (each its own acceptance criterion per spec
    §Failure handling — Stage 2):
      - `BENIGN`: `post.status='READY'`, Stage 1 redactions
        retained in `post.body`; quarantine row(s) for this post
        transition `PENDING → BENIGN_CLOSED`. Stage 1 redactions
        are NOT lifted — only `/quarantine approve` lifts (T2-G
        territory; out-of-scope here). The state machine for
        quarantine is `PENDING → BENIGN_CLOSED` only — `APPROVED`
        and `REJECTED` are T2-G. `stage2_done=true`.
      - `INJECTION` or `MALWARE`: `post.status='QUARANTINED'`,
        quarantine row stays `PENDING`. `stage2_done=true`.
      - `UNKNOWN`: `post.status='QUARANTINED'`, quarantine row
        stays `PENDING`. Per spec §Failure handling "the judge
        model treating `UNKNOWN` as a soft injection signal is
        intentional: a degraded judge must never auto-release."
        `stage2_done=true`. The re-eval queue feed (the
        per-post attempt counter + periodic re-submit) is T2-G.
  * Infrastructure-failure outcome:
      - Honour `infochat.security.release-on-stage2-failure` config
        flag per design §4.7. Profile defaults:
          laptop  true   (release with Stage 1 only)
          pi      true
          vps     false  (stay QUARANTINED)
          remote-llm false
      - `true` → release as `post.status='READY'` with Stage 1
        redactions retained, set `post.stage2_failed=true`, log at
        WARN with a canonical `error_class` string for the future
        throttled admin notifier. `stage2_done=true`.
      - `false` → `post.status='QUARANTINED'`, `post.stage2_failed=true`,
        quarantine rows stay `PENDING`, log WARN with the
        `error_class` string. `stage2_done=true`.
      - Startup WARN: when `release-on-stage2-failure=true` is in
        effect, the Provider emits the prominent WARN-level startup
        line from design §4.7 and writes ONE `audit_log` row with
        `action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE'`. (The
        warning lives on the Collector side because Stage 2 runs in
        Collector; the audit_log row is written by the Collector
        with its DB role. Document this in the ticket body — the
        design line is ambiguously labeled "Provider"; treat that
        as a doc bug and route the WARN where the code is.)
  * Prompt assembly: per design §5.4.1 "Security Stage 2 judge"
    the prompt is loaded from
    `infochat-llm-adapter/src/main/resources/prompts/security-judge.md`
    (or equivalent path the ticket author picks; document the
    rejected alternative path). The body is wrapped per spec
    §Prompt-injection-aware prompt shape with a per-call random
    delimiter UUID. Judge sees the ORIGINAL (pre-redaction)
    content, not the redacted body.
  * Bounded concurrency per provider (a worker semaphore), per
    spec §Bounded concurrency and observability. Concurrency cap
    is profile-driven per design §5.7
    (`infochat.llm.security.max-concurrency`: laptop 4 / vps 2 /
    pi 1 / remote-llm 8). Document the wiring; the metric
    surface (`llm.concurrency.inflight` gauge etc.) is design-
    tier and can be deferred to the observability ticket later.
  * Stage 2 NEVER auto-releases the original (pre-Stage-1)
    content; the worst-case release path is the Stage-1-redacted
    body. Document this invariant explicitly in the ticket body —
    it is the heart of the verdict-vs-infrastructure split.
  * `stage2_done = true` UPDATE is the persistence cursor for the
    Stage-2 boundary. The post advances to the Tagger stage
    iff `stage2_done = true AND post.status IN ('READY')` —
    quarantined posts do not enter the Tagger queue. Document
    the cursor advance.
  * Tests (testcontainers Postgres + a STUB LlmProvider that
    returns canned responses; real LLM is NOT a test dependency):
      - BENIGN verdict: quarantine row PENDING → BENIGN_CLOSED,
        post.status RAW → READY, stage2_done=true, redactions
        retained in post.body.
      - INJECTION: post.status QUARANTINED, quarantine PENDING,
        stage2_done=true.
      - MALWARE: same shape as INJECTION.
      - UNKNOWN: same shape as INJECTION; document the
        re-eval-queue-feed boundary in the test comment.
      - Schema-violating reply: stub returns `BENIGN_PLEASE`; the
        retry fires (one); on retry the stub returns the same
        garbage; the post follows the infra-failure path under
        the active profile's `release-on-stage2-failure` setting.
      - Empty reply: same shape as schema-violating.
      - Unreachable LLM (stub throws): retry once then infra
        path.
      - `release-on-stage2-failure=true` profile: infra fail →
        status=READY, stage2_failed=true, redactions retained.
      - `release-on-stage2-failure=false` profile: infra fail →
        status=QUARANTINED, stage2_failed=true.
      - Local-only conflict detection: provider startup with
        `local-only=true` and `infochat.llm.security.base-url`
        pointing at a non-loopback host FAILS startup with a
        fatal log line naming SECURITY_JUDGE + the offending
        base-url. (This is the spec §Per-task routing rules
        "configuration conflict that fails Provider startup"
        rule; in T1-D the check fires on the COLLECTOR because
        Stage 2 runs there, but the spec wording says "Provider
        startup" — treat as another doc-bug routing call and
        wire the check on the Collector. Document the routing
        choice.)
      - Router resolution: a per-task override property for
        SECURITY_JUDGE picks that provider; absence falls through
        to the profile default. Verify both branches.
- Files_budget: 12
- Spec_refs:
  * docs/spec/security.md §Ingest pipeline (Stage 2 — LLM judge)
  * docs/spec/security.md §Failure handling
    (Stage 2 verdict + infra split; Provider-side LLM failures)
  * docs/spec/security.md §DB roles
  * docs/spec/llm.md §SPI shape
  * docs/spec/llm.md §Per-task routing rules
  * docs/spec/llm.md §Determinism boundary
  * docs/spec/llm.md §Failure handling (recap)
  * docs/spec/llm.md §Bounded concurrency and observability
  * docs/spec/schema.md §Posts and derivatives (status state machine)
  * docs/spec/schema.md §Invariants (Invariant 5)
  * docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  * docs/design/04-security.md §4.2 Layered ingest security
    (Stage 2 — LLM judge, only on Stage 1 hits)
  * docs/design/04-security.md §4.7 Eval pipeline failure handling
    (release-on-stage2-failure flag + profile defaults)
  * docs/design/05-llm-and-embeddings.md §5.1 SPI overview
    (OpenAiCompatibleProvider, router placement)
  * docs/design/05-llm-and-embeddings.md §5.4.1 Security Stage 2 judge
- decision_refs: D20, D22, D27, D32

### Tagger + Embedding ticket (M1-034 in Option B numbering)

- blocked_by: [M1-008c, M1-008b, M1-033]
  (post + tag tables from V7/V6; M1-033 is upstream Stage 2 that
  hands READY posts to the Tagger.)
- complexity: high
- risk: medium
  (the tagger has a deterministic fallback (`source.bootstrap_tags`)
  that bounds risk on bad-LLM-output. The embedding stage's
  model-identity guard + dimensionality invariant is the
  highest-risk slice — a wrong dimension migration silently
  corrupts cosine-similarity scores per spec §Embedding pipeline
  "Dimensionality mismatch at runtime is fatal.")
- security_relevant: FALSE
  (no security-boundary code; the LLM output sanitizer is T1-F.
  Tagger output is validated against the controlled vocabulary —
  invalid tags are silently dropped per spec §Failure handling
  "Tagger ... Partial-valid handling" — but this is data
  hygiene, not a security boundary.)
- migration_touch: TRUE
  (adds Flyway V11 — the `post_embedding` table from
  `docs/design/02-schema.md §2.4.2`, profile-driven dimension. For
  T1-D the migration creates the column matching the active
  profile's embedding model dimension. The model identity row +
  override flag from spec §Embedding pipeline "Model identity
  guard" is design-tier (singleton metadata row); add it in this
  migration too. Migration V11 (or V12 if M1-021 has landed)
  contains:
    - post_embedding table per §2.4.2 with profile-driven dimension
    - HNSW or IVFFlat index per profile per design §5.5
    - one-row embedding_metadata table (model_identifier,
      dimension) per spec §Embedding pipeline; INSERT default
      row at migration time
    - per-table GRANTs per spec §DB roles
  The `post_entity` and `post_reference` tables from §2.4.1 / §2.4.3
  are LinkingJob territory (T2) and MUST NOT land in V11.)
- round_cap: 3
- Scope items:
  * Tagger LLM call: invoke the (ModelTask.TAGGER, scope_language)
    → LlmProvider from M1-033's router. Prompt assembled from
    `prompts/tagger.md` per design §5.4.2; output is JSON
    `{"tags": ["tag1","tag2"]}` parsed strictly.
  * Tagger output validation: tags are normalized per
    `commands.md §Surface conventions` (NFC + lower-case +
    character class) BEFORE validation against the controlled
    vocabulary loaded from the `tag` table seeded in M1-008b.
  * Partial-valid handling per spec §Failure handling "Tagger ...
    Partial-valid handling": when some tags pass validation and
    some don't, KEEP the valid ones, SILENTLY DROP the invalid
    ones. Record a per-post counter for observability
    ("tagger emitted N valid + M invalid") — log at INFO with
    structured fields; the operator alert on sustained high
    invalid rates is T2 territory.
  * Bootstrap-tags fallback per spec §Failure handling "Tagger
    failure":
      - schema-violating output (wrong JSON shape, unparseable):
        retry once with the line-oriented fallback prompt from
        design §5.4.2 (`prompts/tagger-fallback.md`); if that
        also fails to parse → fall back to `source.bootstrap_tags`
        and set `post.tagger_fallback=true`.
      - zero valid tags after partial-valid handling: fall back
        to `source.bootstrap_tags` and set
        `post.tagger_fallback=true`.
      - LLM unreachable / timeout: retry once; on second failure
        fall back to `source.bootstrap_tags` and set
        `post.tagger_fallback=true`.
    Log every fallback at WARN with the canonical error_class
    string for the future throttled admin notifier.
  * `tagger_done = true` UPDATE is the persistence cursor for the
    Tagger boundary.
  * First concrete `EmbeddingProvider` impl: HTTP client against
    an OpenAI-compatible embeddings endpoint per design §5.3
    (covers Ollama, OpenAI). Distinguished by base-url + api-key.
  * Embedding model identity guard per spec §Embedding pipeline
    "Model identity guard": on every COLLECTOR startup the
    EmbeddingProvider reports its current model identifier and
    dimensionality; if either differs from the singleton
    `embedding_metadata` row, the Collector refuses to start with
    a descriptive error referencing the re-embed procedure. An
    explicit operator override flag (property key:
    `infochat.embeddings.allow-model-change=true`) bypasses the
    check for intentional migration runs.
  * Dimensionality mismatch at runtime is fatal per spec
    §Embedding pipeline "Dimensionality mismatch at runtime is
    fatal." Document the failure mode in the embedding-worker
    code: if a returned vector's dimension differs from the
    `embedding_metadata.dimension`, throw immediately. (This
    overlaps with the startup guard but covers the case where the
    provider silently switches models mid-process — e.g., Ollama
    pulls a different version.)
  * Batch SPI per spec §Embedding pipeline: the EmbeddingProvider
    `embed(List<String>)` returns `List<EmbeddingResult>` in input
    order. The Collector's eval consumer batches by a
    profile-driven batch size (value in design notes; pick a
    reasonable default and document it). A flush timer fires when
    a batch's worth of embedding-ready posts is queued OR the
    flush timer fires (whichever first).
  * One-failure-fails-batch retry per spec §Embedding pipeline
    "One-failure-fails-batch retry": on per-element error the
    Collector can't map back to a specific post, OR a shape
    mismatch, OR an exception, the ENTIRE batch retries once. If
    retry also fails, EVERY post in the batch follows the
    embedding-failure release path (release without a vector,
    embedding_done=true, no `post_embedding` row).
  * Per spec §Embedding pipeline "Retry policy": on a batch
    failure the same batch is resubmitted as-is; the batch is NOT
    split on retry.
  * Single-post calls remain valid (a batch of one) so the SPI
    does not force batching on callers that don't need it.
  * Embedding-failure release path per spec §Failure handling
    "Embedding": release without a vector; the post is otherwise
    normal and fully visible. `embedding_done=true`, no
    `post_embedding` row inserted. Log at WARN with the canonical
    error_class string.
  * Embedding success path: INSERT one `post_embedding` row per
    post with `(post_id, embedding, embedding_model, fetched_at)`
    per design §2.4.2; the partition key is the post's fetched_at.
    `embedding_done=true`.
  * Stage 5 — the FINAL transition per design §1.3.4 step 5:
    UPDATE `post.status='READY'`, `post.ready_at=now()`,
    `post.status_changed_at=now()`. Then `pg_notify('new_post',
    <(ready_at, post_id) JSON>)` per spec §Inter-service
    communication "new_post". Payload is cursor only — `(ready_at,
    post_id)` — per spec "Payload-size bound" rule.
  * Pre-conditions for status→READY: stage1_done AND (stage1_flagged
    ⇒ stage2_done) AND tagger_done AND embedding_done.
    Posts in QUARANTINED state (Stage 2 verdict was non-BENIGN
    or Stage 1 was an infra fail) do NOT go through Tagger or
    Embedding in this ticket — quarantined posts are excluded
    from the consumer's pickup query. Document the predicate.
    On infra-failure release paths (stage2_failed=true with
    `release-on-stage2-failure=true`), the post IS routed
    through Tagger + Embedding because status went to READY.
  * Same-transaction-as-side-effect rule (spec §Inter-service
    communication "Catch-up" + §Pipelines): the
    `pg_notify('new_post', ...)` MUST fire in the same DB
    transaction as the `status='READY'` UPDATE so a duplicate
    NOTIFY or a repeated catch-up pass produces no additional
    effect.
  * Tests:
      - Tagger happy path: stub LLM returns 2 valid tags;
        post.tags = [normalized values]; tagger_done=true,
        tagger_fallback=false.
      - Tagger partial-valid: stub returns 3 tags, 1 invalid;
        post.tags = [2 valid]; tagger_done=true,
        tagger_fallback=false; one INFO log with the counter.
      - Tagger zero-valid: stub returns 2 invalid tags; falls
        back to source.bootstrap_tags; tagger_fallback=true.
      - Tagger schema-violating: stub returns garbage; retries
        with fallback prompt; second stub returns one valid;
        tagger uses the valid tag set; tagger_done=true.
      - Tagger total fail: both prompts return garbage; falls
        back to source.bootstrap_tags; tagger_fallback=true.
      - Tagger LLM unreachable: stub throws; retries; on second
        failure falls back to bootstrap_tags.
      - Embedding happy path: stub EmbeddingProvider returns
        N vectors for N inputs; N post_embedding rows inserted;
        embedding_done=true for each.
      - Embedding batch failure: stub throws on the batch; retry
        once; on second failure all N posts in the batch follow
        the no-vector release path; embedding_done=true,
        zero post_embedding rows for the batch.
      - Embedding partial-failure (provider returns wrong-shape
        result): same as batch failure — the entire batch retries
        once.
      - Embedding dimensionality mismatch at runtime: stub returns
        a vector of dimension D' ≠ D; throws immediately, the
        post stays in flight (no post_embedding row, no
        embedding_done advance). Document the recovery path:
        operator runs the re-embed procedure.
      - Embedding model identity startup guard: pre-seed
        embedding_metadata with model='alpha' dim=768; configure
        the EmbeddingProvider to report model='beta'; Collector
        startup FAILS with the descriptive error referencing the
        re-embed procedure. Configure `infochat.embeddings.allow-
        model-change=true`; startup succeeds and the
        embedding_metadata row is overwritten.
      - status→READY transition: post with stage1_done=true,
        stage1_flagged=false, tagger_done=true,
        embedding_done=true, status='RAW' is updated to
        status='READY' with ready_at and status_changed_at set;
        one NOTIFY new_post payload `{ready_at, post_id}` is
        observed by a JDBC LISTEN test fixture.
      - Same-transaction rule: a deliberate failure between the
        UPDATE and the pg_notify (force the transaction to roll
        back) leaves status='RAW' AND no NOTIFY observable.
      - Quarantined post exclusion: a post with
        status='QUARANTINED' is not picked up by the Tagger or
        Embedding workers; tagger_done and embedding_done stay
        FALSE.
      - Stage2-infra-failure release path: a post with
        stage2_failed=true and status='READY' (from M1-033's
        release-on-stage2-failure=true path) IS picked up by
        Tagger and Embedding; flags advance as normal; final
        status remains READY.
- Files_budget: 14
- Spec_refs:
  * docs/spec/security.md §Failure handling (Tagger, Embedding)
  * docs/spec/llm.md §SPI shape (EmbeddingProvider is NOT a ModelTask)
  * docs/spec/llm.md §Per-task routing rules (TAGGER routing)
  * docs/spec/llm.md §Embedding pipeline
    (batch SPI, one-failure-fails-batch retry, model identity
    guard, dimensionality fatal-at-runtime, profile-driven index)
  * docs/spec/llm.md §Failure handling (recap) — Tagger
    partial-valid, embedding retry policy
  * docs/spec/schema.md §Posts and derivatives
    (status state machine; post embedding is optional, may reach
    READY without an embedding)
  * docs/spec/schema.md §Invariants (Invariant 5, Invariant 6)
  * docs/spec/architecture.md §Inter-service communication
    (new_post channel; payload cursor; same-transaction rule)
  * docs/spec/architecture.md §Pipelines
    (ingest pipeline end-to-end + outbox discipline)
  * docs/design/01-architecture.md §1.3.4 Eval pipeline workers
    (stages 2/3/4/5; we ship 2/4/5 but NOT 3 (entity) here)
  * docs/design/02-schema.md §2.3.1 post
    (per-stage *_done flag set + tagger_fallback + status enum)
  * docs/design/02-schema.md §2.4.2 post_embedding
    (DDL + dimension by profile + HNSW/IVFFlat per profile)
  * docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
    (prompt + fallback prompt + parse rules)
  * docs/design/05-llm-and-embeddings.md §5.5 Embeddings
    (pipeline + model/dimension by profile + index choice)
- decision_refs: D5, D22, D27, D32

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by `grep -n '^## \|^### \|^  ## \|^  ### ' <file>`
at this session's authoring time. Any spec_ref you cite that ISN'T in
this list, verify the anchor exists by reading the cited file before
using it. The clarity-preflight subagent will FAIL the ticket if a
spec_ref doesn't resolve.

  docs/spec/architecture.md §Inter-service communication        (line 33)
  docs/spec/architecture.md §Ingest SPIs                        (line 138)
  docs/spec/architecture.md §Pipelines                          (line 316)
  docs/spec/architecture.md §Architectural principles           (line 334)
  docs/spec/llm.md §Goals                                       (line 9)
  docs/spec/llm.md §SPI shape                                   (line 27)
  docs/spec/llm.md §Prompt-injection-aware prompt shape         (line 101)
  docs/spec/llm.md §Per-task routing rules                      (line 118)
  docs/spec/llm.md §Embedding pipeline                          (line 155)
  docs/spec/llm.md §Translation flow                            (line 195)
  docs/spec/llm.md §Determinism boundary                        (line 266)
  docs/spec/llm.md §Failure handling (recap)                    (line 310)
  docs/spec/llm.md §Hardware profile contract                   (line 370)
  docs/spec/llm.md §Bounded concurrency and observability       (line 379)
  docs/spec/security.md §Ingest pipeline (security side)        (line 56)
  docs/spec/security.md §Per-source trust boundaries            (line 157)
  docs/spec/security.md §Prompt-injection defenses (LLM call sites) (line 222)
  docs/spec/security.md §LLM output sanitizer                   (line 269)
  docs/spec/security.md §Failure handling                       (line 724)
  docs/spec/security.md §Re-evaluation job                      (line 817)
  docs/spec/security.md §DB roles                               (line 943)
  docs/spec/security.md §Secrets handling                       (line 986)
  docs/spec/schema.md §Sources and tags                         (line 175)
  docs/spec/schema.md §Posts and derivatives                    (line 245)
  docs/spec/schema.md §Operational                              (line 449)
  docs/spec/schema.md §Invariants                               (line 560)
  docs/design/01-architecture.md §1.3 Key data flow: ingest     (line 120)
  docs/design/01-architecture.md §1.3.1 Polled Fetcher → outbox (line 127)
  docs/design/01-architecture.md §1.3.4 Eval pipeline workers   (line 211)
  docs/design/01-architecture.md §1.4.3 Startup-bean ordering   (line 435)
  docs/design/01-architecture.md §1.5 Architectural principles  (line 521)
  docs/design/01-architecture.md §1.6 Concurrency and rate limiting (line 570)
  docs/design/01-architecture.md §1.7 Hardware profiles         (line 627)
  docs/design/02-schema.md §2.3 Posts (ingest)                  (line 592)
  docs/design/02-schema.md §2.3.1 post                          (line 594)
  docs/design/02-schema.md §2.4 Tier-2 cross-linking            (line 709)
  docs/design/02-schema.md §2.4.2 post_embedding                (line 727)
  docs/design/02-schema.md §2.5 Quarantine                      (line 798)
  docs/design/02-schema.md §2.5.1 quarantine                    (line 800)
  docs/design/02-schema.md §2.8 Embedding model migration       (line 1289)
  docs/design/04-security.md §4.1 Threat model                  (line 14)
  docs/design/04-security.md §4.2 Layered ingest security       (line 48)
  docs/design/04-security.md §4.3 Prompt-injection defenses     (line 162)
  docs/design/04-security.md §4.7 Eval pipeline failure handling (line 463)
  docs/design/05-llm-and-embeddings.md §5.1 SPI overview        (line 21)
  docs/design/05-llm-and-embeddings.md §5.4 Prompt templates    (line ~143)
  docs/design/05-llm-and-embeddings.md §5.4.1 Security Stage 2 judge (line ~147)
  docs/design/05-llm-and-embeddings.md §5.4.2 Tagger            (line ~164)
  docs/design/05-llm-and-embeddings.md §5.5 Embeddings          (line ~320)
  docs/design/05-llm-and-embeddings.md §5.7 Profile defaults    (line ~434)
  docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task (line ~489)

NOTE: docs/design/05-llm-and-embeddings.md uses indented sub-headers
(`  5.x` rather than `## 5.x`) for sections past §5.1. Cite them as
"docs/design/05-llm-and-embeddings.md §5.x.y <title>" in spec_refs;
the clarity-preflight subagent treats the citation as the search key
and matches on the title even when the leading `##` is absent.

## Style requirements

Match M1-027 + M1-028 in docs/plan/m1/tickets/ — those are the
closest structural analogues for ticket-frontmatter shape, runnable
acceptance criteria, and the per-acceptance-item transcription of
spec promises (the memory-feedback rule
"Transcribe spec promises into security-ticket acceptance items").
M1-008c is the closest analogue for migration-tier scope and
per-table GRANT discipline. M1-024 is the closest analogue for a
high-risk security ticket with watchdog + fail-closed semantics.
Read those once for style. Read docs/process/ticket-template.md
once for the canonical schema. Then write.

Length per ticket: M1-032 ~280-340 lines (Stage 1 carries the
HTML+Unicode+regex+watchdog correctness argument plus the
fail-closed-on-watchdog path); M1-033 ~340-400 lines (verdict +
infra split + first LlmProvider impl + router + local-only conflict
detection); M1-034 ~360-420 lines (tagger fallback chain + first
EmbeddingProvider impl + model identity guard + status→READY +
pg_notify). The high-end is the security-relevant tickets — keep
each separable spec promise its own acceptance item; do not
collapse.

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep / test / SQL assertions, not
  prose. Per the memory-feedback "Run the regex, don't paraphrase
  it" rule, execute every regex/grep predicate in the DoD against
  the inlined fragments before saving the ticket.
- For security_relevant tickets (M1-032 and M1-033), every separable
  spec sentence becomes one acceptance item (verbatim, no
  summarizing) — per the memory-feedback "Transcribe spec promises"
  rule. The verdict-vs-infrastructure split, the local-only
  conflict-detection guarantee, the same-transaction-as-side-effect
  rule, the model-identity guard's startup-refuses-start rule, the
  dimensionality-fatal-at-runtime rule, the per-row randomization
  guarantee for placeholder ids — each gets its own acceptance item.
  Do not collapse "verdict-vs-infra split" into one item; it is at
  least four separable promises (BENIGN release path, INJECTION
  quarantine path, UNKNOWN soft-injection path, infra-fail flag
  path).
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic. State the
  EntityExtractor exclusion (T2 territory) explicitly in every
  T1-D ticket so the reviewer's negative-space check doesn't flag
  the gap as a missed stage.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-027 + M1-028 once for style (NOTIFY + cursor + per-stage
  flag advance patterns).
- DO read M1-024 once (high-risk security ticket with watchdog
  fail-closed semantics).
- DO read M1-008c once (the post-table schema you're writing against,
  including all 7 *_done / *_flagged / *_failed / *_fallback flags).
- DO read docs/process/ticket-template.md once.
- DO read docs/spec/security.md §Ingest pipeline + §Failure handling
  + §Re-evaluation job (re-eval is OUT but read it to know the
  boundary) + §DB roles in one pass.
- DO read docs/spec/llm.md §SPI shape + §Per-task routing rules +
  §Embedding pipeline + §Failure handling (recap) in one pass.
- DO read docs/spec/schema.md §Posts and derivatives + §Invariants
  (Invariant 5 in particular) in one pass.
- DO read docs/spec/architecture.md §Inter-service communication +
  §Pipelines + §Architectural principles in one pass.
- DO read docs/design/01-architecture.md §1.3 + §1.3.4 + §1.4.3 +
  §1.6 + §1.7 in one pass.
- DO read docs/design/02-schema.md §2.3.1 + §2.4.2 + §2.5.1 + §2.8
  + §2.9.1 in one pass.
- DO read docs/design/04-security.md §4.1 + §4.2 + §4.3 + §4.7 in
  one pass.
- DO read docs/design/05-llm-and-embeddings.md §5.1 + §5.4.1 +
  §5.4.2 + §5.5 + §5.7 + §5.8 in one pass.
- DO NOT spawn Explore or any other subagent.
- DO NOT pre-load the full docs/spec/ tree.
- DO NOT re-read sections you already loaded.
- DO NOT read the messaging.md / commands.md spec files — neither
  is in T1-D's path.

## After authoring all tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm each ticket's `out_of_scope` correctly punts:
     - EntityExtractor (T2 territory — `post_entity` /
       `post_reference` / LinkingJob §1.3.5)
     - LinkingJob itself (T2)
     - summarizer / chat-agent / translator (T1-F, T2-C, T2-D)
     - LLM output sanitizer (T1-F)
     - Re-evaluation job + per-source UNKNOWN auto-disable + NEEDS_REVIEW
       transition (T2-G)
     - throttled admin notifier wiring (T2-G)
     - `/quarantine list/approve/reject` admin commands +
       `approve_quarantine`/`reject_quarantine` stored procedures (T2-G)
     - `quarantine_review` Provider listener (M2)
     - `new_price_snapshot` (T2-H asset commands)
     - any change to V1..V9 migrations already on disk
3. Confirm M1-032's migration filename matches the next free
   integer at this moment (re-grep
   `infochat-core/src/main/resources/db/migration/` and pick — V10
   if M1-021 hasn't landed, V11 if it has).
4. Confirm M1-034's migration filename is exactly one greater than
   M1-032's (V11 or V12 respectively).
5. Confirm each ticket's spec_refs list contains ONLY anchors from
   "Spec anchors verified" above; if you needed a different anchor,
   verify it in the file before citing.
6. **Cross-reference update: M1-019 deferred_on.** Edit
   `docs/plan/m1/tickets/M1-019-stdout-log-key-redaction.md`
   frontmatter:
     - `deferred_on: M1-033` (the Stage 2 ticket — that is when the
       first LLM call site lands and starts logging URLs/keys).
     - If you picked Option A (Stage 1 + Stage 2 combined), point
       at the combined ticket's ID instead.
     - `last_updated: <today>`.
   Do NOT change M1-019's status or any other field. This is a
   one-line metadata edit. Re-run
   `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and verify the Deferred section now
   shows `M1-019 → M1-033` (or your chosen target).
7. Do NOT touch M1-020 (`deferred_on:` empty until T1-E messaging
   adapter is authored).
8. Do NOT touch M1-021 or M1-031.
9. Print a one-paragraph summary: "T1-D eval pipeline drafted as
   <list IDs and titles> under docs/plan/m1/tickets/. The tickets
   are untracked on main. M1-019's deferred_on was updated to
   <id> and STATUS.md regenerated. The user runs /m1-tick start
   <first ticket id> when ready." Name the chosen option (A / B /
   C) at the start of the summary.
10. STOP. Do NOT commit the ticket files. Do NOT run /m1-tick start.

## What you do NOT do

- Do NOT commit any ticket file (drafts ride untracked through
  /m1-tick start). The M1-019 deferred_on update IS committed as a
  `process:` commit because it's a metadata edit on an existing
  tracked file; commit subject:
  `process: Wire M1-019 deferred_on → M1-033 (T1-D LLM call site)`
  (or your chosen target ID). One commit, one file.
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT begin authoring T1-E or T1-F tickets. Those are separate
  sessions with their own JIT handoffs.
- Do NOT touch M1-020 / M1-021 / M1-031. Their fields get updated
  by later sessions (M1-020 by T1-E, M1-021/031 by the operator).
- Do NOT add EntityExtractor (stage 3 in §1.3.4) — T2 territory.
  Document the exclusion in out_of_scope explicitly.
- Do NOT add LinkingJob (§1.3.5) — T2 territory.
- Do NOT add the LLM output sanitizer (§LLM output sanitizer) —
  T1-F.
- Do NOT add the re-evaluation job (§Re-evaluation job) or the
  per-source UNKNOWN auto-disable or the NEEDS_REVIEW transition —
  T2-G.
- Do NOT add the throttled admin notifier — T2-G. T1-D logs
  failure events at WARN with the canonical error_class string so
  the future notifier can pick them up without diff churn.
- Do NOT add `/quarantine list/approve/reject` admin commands or
  the `approve_quarantine` / `reject_quarantine` stored procedures —
  T2-G.
- Do NOT add the `quarantine_review` channel Provider listener —
  M2 territory.
- Do NOT add the Anthropic native LLM impl (design §5.3
  AnthropicProvider) — T3-D per session-grouping-plan §Tier 3.
- Do NOT add the asset-command price_snapshot path / asset
  Fetchers / `new_price_snapshot` channel — T2-H per decision D39.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start.
- "M" prefix → /m1-tick flow; "process:" prefix → direct commit on
  main; "spec:" prefix → direct commit on main. This handoff itself
  is a `process:` commit; the tickets it authors are M-prefix
  commits later. The M1-019 deferred_on metadata edit is a
  `process:` commit (it edits a tracked ticket file that already
  exists; it adds no code, no migration, no spec change).

## Your immediate task when the user says "go"

1. Re-grep `infochat-core/src/main/resources/db/migration/` to
   confirm the next free integer for M1-032's quarantine table
   migration (V10 if M1-021 hasn't landed, V11 if it has).
2. Re-grep `docs/plan/m1/tickets/` for `^id: M1-` to confirm the
   next free numeric IDs (M1-032/M1-033/M1-034 expected for
   Option B; bump if a new ticket was authored since this
   handoff).
3. Decide Option A (2 tickets) vs. Option B (3 tickets — default)
   vs. Option C (4 tickets) and document the choice + the rejected
   alternatives in the first ticket's "Implementation notes."
4. Read M1-027 + M1-028 in docs/plan/m1/tickets/ once for style.
5. Read M1-024 in docs/plan/m1/tickets/ once (high-risk security
   ticket reference).
6. Read M1-008c in docs/plan/m1/tickets/ once (the post-table
   schema you're writing against).
7. Read docs/process/ticket-template.md once.
8. Read docs/spec/security.md §Ingest pipeline + §Failure handling
   + §Re-evaluation job + §DB roles in one pass.
9. Read docs/spec/llm.md §SPI shape + §Per-task routing rules +
   §Embedding pipeline + §Failure handling (recap) in one pass.
10. Read docs/spec/schema.md §Posts and derivatives + §Invariants
    in one pass.
11. Read docs/spec/architecture.md §Inter-service communication +
    §Pipelines + §Architectural principles in one pass.
12. Read docs/design/01-architecture.md §1.3 + §1.3.4 + §1.4.3 +
    §1.6 + §1.7 in one pass.
13. Read docs/design/02-schema.md §2.3.1 + §2.4.2 + §2.5.1 + §2.8
    + §2.9.1 in one pass.
14. Read docs/design/04-security.md §4.1 + §4.2 + §4.3 + §4.7 in
    one pass.
15. Read docs/design/05-llm-and-embeddings.md §5.1 + §5.4.1 +
    §5.4.2 + §5.5 + §5.7 + §5.8 in one pass.
16. Write the ticket files per the chosen option (slugs ≤30 chars,
    lower-case ASCII, hyphenated). Suggested slugs:
      M1-032: stage1-deterministic-security
      M1-033: stage2-llm-judge-router
      M1-034: tagger-embedding-status-ready
17. Edit M1-019's deferred_on per step 6 of "After authoring all
    tickets" and run scripts/regen-status.py.
18. Commit the M1-019 metadata edit as a single `process:` commit.
19. Print the summary. STOP.
```
