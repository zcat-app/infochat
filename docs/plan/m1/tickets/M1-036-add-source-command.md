---
id: M1-036
title: /add-source command (handler + kind resolver + URL probe + upsert + audit)
status: pending
created: 2026-05-17
last_updated: 2026-05-18
blocked_by:
  - M1-035
files_budget: 15
files_scope:
  - infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-provider/pom.xml
  - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/java/io/infochat/provider/source/KindResolver.java
  - infochat-provider/src/main/java/io/infochat/provider/source/UrlProbe.java
  - infochat-provider/src/main/java/io/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceArgsTest.java
  - infochat-provider/src/test/java/io/infochat/provider/source/KindResolverTest.java
  - infochat-provider/src/test/java/io/infochat/provider/source/UrlProbeTest.java
  - infochat-provider/src/test/java/io/infochat/provider/source/SourceUpsertServiceIT.java
  - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change under infochat-messaging-adapter/ (SPI + InMemoryAdapter are FROZEN at M1-035a; defects file a follow-up per docs/process/workflow.md §M1 workflow — never amend a passed commit)
  - any change to SsrfGuardedHttpClient.java EXCEPT a narrow additive carve-out: appending one new method overload `get(URI uri, Map<String,String> extraHeaders)` is permitted (UrlProbe needs Range-header injection to probe a feed without downloading its full body; the existing `get(URI)` constructs requests with a fixed Accept/User-Agent and offers no header injection). Modifying or removing the existing `get(URI)` overload is FORBIDDEN; existing callers (Collector RSS fetcher) must continue to use the bare `get(URI)` form unchanged. The new overload SHARES the existing SSRF guards (DNS resolution + IP-policy check + redirect handling); it differs only in the per-request headers attached to the underlying HttpRequest builder.
  - any change to AdapterRegistry, InboundRouter, MessagingStartup, CommandHandler interface, AutoRegisterService, BundleLoader, HelpCommandHandler, or the M1-035c bundle infrastructure (the T1-E surface is FROZEN at the M1-035 umbrella's round; this ticket consumes the CommandHandler SPI as-is). BundleKeys is a NARROW carve-out from the freeze: appending new `public static final String` constants for this ticket's new bundle keys is permitted (the BundleLoader CI completeness check requires constant-and-value parity), but modifying or removing existing constants is FORBIDDEN.
  - any Flyway migration under infochat-core/src/main/resources/db/migration/ (T1-F is migration-free; the `source`, `source_subscription`, `tag`, and `audit_log` tables exist from M1-008b; reaching for V12 is an escalation trigger, not an authoring choice)
  - any /summary command surface (M1-037 territory; this ticket adds NO new BundleKeys / handler / DAO that /summary would also need — the two T1-F handlers share no implementation files)
  - any /list-sources, /unfollow-source, /remove-source, /source-enable, /source-disable command (T2-B territory; the source-status state machine `active ↔ disabled ↔ failed` is not exercised here — fresh `/add-source` writes `status='active'` and stops)
  - any /follow-tag / /unfollow-tag / /tag-mode (T2-B territory; this ticket UNIONS the supplied --tags into the controlled vocabulary so they are addressable by /follow-tag, but does NOT author the /follow-tag handler)
  - any group `@mention` dispatch / group scope (T2-F; this ticket exercises DM scope only — the spec's "Group: group admin only" branch is covered by a permission-rejection test that asserts the friendly-error path WITHOUT requiring group-membership infrastructure; the rejection branch falls through the auth check and returns the bundle string, no group-membership lookup is needed in MVP)
  - any invite-gating (D44), slow-start probation filter (D45), `/ban` / `/unban` (D11), or chat-mode dispatcher (T2-A / T2-D; this ticket's handler is reached only after InboundRouter dispatches a `/add-source` slash command, and InboundRouter's upstream intake is M1-035c + T2-A territory)
  - any TranslationProvider integration / `/lang` (T2-C; outbound replies use English BundleKeys only — translation pre-pass is T2-C's wiring)
  - any LLM output sanitizer integration (M1-037 territory; /add-source has NO LLM-authored output surface — every reply is a deterministic localization-bundle string, so the sanitizer is not in this ticket's path)
  - any Anthropic / OpenAI client wiring (the M1-033 OpenAI-compatible client exists from T1-D; /add-source makes NO LLM calls — the URL probe is an HTTP HEAD via infochat-ssrf, not an LLM call)
  - any RSS Fetcher / FetchScheduler change (M1-022 + M1-023 territory; this ticket's URL probe reaches the URL once via `infochat-ssrf.SsrfGuardedHttpClient` to confirm reachability, then writes the source row; the Collector's FetchScheduler picks up the new `status='active'` source row on its next per-kind tick — NO `pg_notify('new_source', ...)` channel is added, the per-kind tick model from docs/spec/architecture.md §Inter-service communication is the existing pickup mechanism)
  - any post-tier / scope_preferences / scope_tag DAO changes (the only writes are: 1 INSERT/UPSERT into `source`, 1 INSERT/UPSERT into `source_subscription`, 0–N INSERT into `tag` for new vocab values, 1 INSERT into `audit_log` for the bot-admin tag-replacement path or the fresh-insert path per docs/design/00-mvp.md §2 — no `scope_preferences` write)
  - any `/add-source --tags` against an existing row by a BOT ADMIN where the supplied `--tags` differs from `bootstrap_tags` — i.e. the audit-logged `bootstrap_tags` REWRITE path on an existing row is implemented (it is in spec scope per docs/spec/commands.md §Source management) but no test ASSERTS the audit_log row's `action` verb beyond "is present and references the source_id" because the action-verb closed catalogue per docs/design/04-security.md is M1-024's commit and continues to pass unchanged; the ticket may use the existing `source.tags.replace` (or equivalent name from M1-024's catalogue) verb without adding a new one
  - any new AuditLogWriter helper class (no standalone AuditLogWriter exists in the codebase as of M1-035 — M1-024 was the infochat-ssrf module ticket, not an audit-writer commit; the audit_log writes in this ticket are inline JDBC INSERT statements in SourceUpsertService's transaction, matching the inline-SQL style used elsewhere for audit writes; if a separate writer-class abstraction is needed later, that is a T2-A onboarding-tier ticket, not this one)
  - any new `audit_log.action` verb (the V5 §2.1.8 closed verb catalogue already names `ADD_SOURCE` — this ticket consumes that verb as-is; adding a new verb is an escalation trigger, not an authoring choice)
acceptance:
  - "infochat-provider/src/main/java/io/infochat/provider/command/AddSourceCommandHandler.java exists, is `@ApplicationScoped`, and implements `io.infochat.provider.messaging.CommandHandler`. Verify: `grep -E '@ApplicationScoped' AddSourceCommandHandler.java` returns ≥1 match AND `grep -E 'implements\\s+CommandHandler' AddSourceCommandHandler.java` returns ≥1 match"
  - "AddSourceCommandHandler.name() returns the literal string \"add-source\" (no leading slash; InboundRouter strips the slash before lookup per M1-035b InboundRouter.handleSlash). Verify: `grep -E '\"add-source\"' AddSourceCommandHandler.java` returns ≥1 match in a `name()` accessor"
  - "AddSourceCommandHandler is discovered by InboundRouter via `Instance<CommandHandler>` (no manual registration / no static map). Verify: AddSourceCommandHandlerTest boots `@QuarkusTest` (or uses InboundRouter directly) and asserts that calling InboundRouter.onMessage with a `/add-source` body reaches AddSourceCommandHandler.handle exactly once"
  - "AddSourceArgs (positional URL + required --tags + optional --type + optional --category + optional --name) parses verbatim from the raw inbound body after the `/add-source` token. Per docs/spec/commands.md §Source management. Verify: AddSourceArgsTest covers (a) missing --tags → friendly error referencing `error.add_source.tags_required` bundle key; (b) empty `--tags=` → same error path; (c) unknown --type → `error.add_source.unknown_kind` with fuzzy-suggestion footer over the closed source.kind enum; (d) unknown --category → `error.add_source.unknown_category` enumerating `news|blog|social`; (e) malformed URL (no scheme / invalid host) → `error.add_source.malformed_url`"
  - "KindResolver applies the docs/spec/commands.md §Source management closed table in the documented order: explicit --type wins (case-insensitive enum match), then host-pattern table (wss/ws → nostr; bsky.app|bsky.social subdomains → bluesky; reddit.com|redd.it subdomains → reddit; youtube.com|youtu.be subdomains → youtube; odysee.com subdomains → odysee), then RSS auto-detection (path ends in .xml | .rss | contains /feed), then ambiguous → friendly error. Per-rule assertions in KindResolverTest:
    - explicit `--type rss` against any URL returns `KIND_RSS`
    - explicit `--type RSS` (uppercase) against any URL returns `KIND_RSS` (case-insensitive match)
    - `wss://relay.example.com` returns `KIND_NOSTR`
    - `https://bsky.app/profile/foo` returns `KIND_BLUESKY`
    - `https://news.bsky.social/feed` returns `KIND_BLUESKY` (subdomain match wins over RSS path)
    - `https://reddit.com/r/x/.rss` returns `KIND_REDDIT` (host match wins over RSS path)
    - `https://example.com/feed.xml` returns `KIND_RSS` (RSS auto-detect)
    - `https://example.com/about` returns `AMBIGUOUS`
    - IDN host `https://блюски.рф/feed` is folded via `java.net.IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)` before comparison (a separate per-rule @Test verifies the IDN-folded host compares against the ASCII pattern correctly)"
  - "UrlProbe reaches the URL via `io.infochat.ssrf.SsrfGuardedHttpClient` (the same client M1-024/M1-025 ship for Collector fetcher traffic) — NOT via `java.net.http.HttpClient` directly. Verify: `grep -E 'SsrfGuardedHttpClient' UrlProbe.java` returns ≥1 match AND `grep -E 'java\\.net\\.http\\.HttpClient' UrlProbe.java` returns zero matches"
  - "UrlProbe issues a small-range `GET` (`Range: bytes=0-0`) via a new `SsrfGuardedHttpClient.get(URI uri, Map<String,String> extraHeaders)` overload (additive carve-out per `out_of_scope`). The probe verifies reachability and surfaces the response `Content-Type` so the caller can confirm or contradict the URL-pattern hint (per docs/design/03-commands.md §`/add-source`, which permits 'lightweight HEAD (or, for servers that reject HEAD, a small-range GET)'; this ticket chooses small-range GET as the single probe shape to stay within the existing SSRF-client surface area + one additive overload). UrlProbeTest covers (using the same `com.sun.net.httpserver.HttpServer` fixture pattern as `SsrfGuardedHttpClientTest` — no WireMock dep in the repo): (a) range-GET returning 206 (or 200 ignoring Range) with `Content-Type: application/rss+xml` → probe SUCCESS with detected content-type returned; (b) range-GET returning 4xx/5xx → FAILURE referencing `error.add_source.url_unreachable`; (c) SsrfGuardedHttpClient rejection (e.g. localhost / RFC1918 / metadata IP) propagates as FAILURE referencing `error.add_source.url_blocked_ssrf`; (d) timeout → FAILURE referencing `error.add_source.url_timeout`. Verify the new overload preserves SSRF guards: `grep -E 'isAllowed|SsrfPolicy|resolveAndCheck' SsrfGuardedHttpClient.java` returns ≥1 match in the `get(URI, Map)` body (the new overload MUST share the same DNS-resolution + IP-policy guard the existing `get(URI)` runs)."
  - "UrlProbe's success result includes the detected `Content-Type` so the caller can confirm an RSS-pattern URL against the response's `application/rss+xml | application/atom+xml | application/xml`. AddSourceCommandHandlerTest asserts: a URL `https://example.com/about` with the range-GET probe returning `text/html` AND no --type AND no RSS path-pattern → AMBIGUOUS friendly error (the probe response contradicts the URL-pattern hint per spec §Source management)"
  - "SourceUpsertService performs a single transaction that (1) upserts the `source` row by `(kind, identifier)` per decision D38, (2) for a fresh insert unions `--tags` into the controlled `tag` vocabulary table per decision D5 BEFORE the source row write, (3) upserts the `source_subscription` row for the caller's scope. SourceUpsertServiceIT covers: (a) fresh insert writes one source row with `status='active'`, `deleted_at IS NULL`, `kind=<resolved>`, `bootstrap_tags=<--tags array>`, `category=<--category-or-default-news>`; (b) the same `--tags` values appear as `tag` rows (UPSERT — pre-existing tag rows are not duplicated); (c) one `source_subscription` row exists for the caller's scope; (d) a SECOND `/add-source` for the same URL by the SAME caller is idempotent — no second `source` row, the existing subscription row remains, `--tags` are NOT rewritten on the source row"
  - "Permission gate enforces docs/spec/commands.md §Source management: DM-any-non-banned + group-group-admin-only. Per-element assertions in AddSourceCommandHandlerTest:
    - DM scope, non-banned non-admin user → handler proceeds to URL probe (mock the probe so the test stays unit-scoped)
    - DM scope, banned user → rejected with `error.add_source.banned` (the upstream T2-A ban check is not in scope here; this ticket's handler MAY assume the InboundRouter upstream has already filtered banned users — but the handler MUST NOT crash on a banned-flag DB lookup; the bare-minimum is to read `users.is_banned` once and reject if true)
    - GROUP scope, non-group-admin caller → rejected with `error.add_source.group_admin_only`
    - GROUP scope, group admin → handler proceeds (group_membership stub helper inserted by the IT)"
  - "Tag-conflict resolution branches match docs/spec/commands.md §Source management exactly. Per-branch assertions in SourceUpsertServiceIT:
    - **Branch A (fresh insert):** caller's `--tags` populate `bootstrap_tags`, vocab union runs, subscription upsert runs in the same transaction. Reply equals `bundle('reply.add_source.fresh_insert')` interpolated with the source name PLUS the URL-visibility disclosure bundle key
    - **Branch B (non-admin caller against existing row):** the supplied `--tags` are quietly ignored, `bootstrap_tags` is unchanged on the source row, `source_subscription` is upserted, reply equals `bundle('reply.add_source.subscribed_existing')`
    - **Branch C (bot-admin caller against existing row with --tags):** `bootstrap_tags` is REPLACED with the supplied `--tags`, vocab union runs over the new values, one `audit_log` row is INSERTed referencing `source.id` and the caller's `user.id` (the action verb is whatever M1-024's closed catalogue exposes for source-tag mutation — see docs/design/04-security.md §Audit log; the IT asserts presence of a row with `source_id = <the source>` AND `user_id = <the bot admin>`, NOT the specific action-verb string), reply equals `bundle('reply.add_source.admin_tags_replaced')`. **NO URL-visibility disclosure** on Branch B or Branch C (URL already in the global set)"
  - "Fresh-insert reply includes the URL-visibility disclosure literal docs/spec/commands.md §Source management — 'URL visibility disclosure': 'Note: source URLs are global state and are visible to bot admins via /list-sources --all.' Verify: en.properties has bundle key `reply.add_source.url_visibility_disclosure` whose value contains the literal substring `visible to bot admins` AND AddSourceCommandHandlerTest asserts the fresh-insert reply contains this substring; Branch B / Branch C tests assert the reply does NOT contain it"
  - "Bot-admin tag replacement (Branch C) writes one `audit_log` row in the same transaction as the source update. Verify: SourceUpsertServiceIT runs Branch C and asserts `SELECT COUNT(*) FROM audit_log WHERE user_id=<bot-admin> AND source_id=<source>` returns 1 (the action-verb closed catalogue from M1-024 is consumed as-is; no new verb is added in this ticket)"
  - "AddSourceIT exercises MVP exit criterion §4 end-to-end via the InMemoryAdapter: (a) `adapter.deliverDm(\"mvp-user-1\", \"/add-source https://example.com/feed.xml --tags news,tech\")` — using a JDK `com.sun.net.httpserver.HttpServer` fixture bound to a loopback-allowed test port (the SsrfGuardedHttpClient test seam from `SsrfGuardedHttpClientTest`) so the range-GET probe succeeds — produces exactly ONE outbound message whose body equals the fresh-insert reply + URL-visibility disclosure; (b) `SELECT COUNT(*) FROM source WHERE kind='rss' AND identifier='https://example.com/feed.xml'` returns 1; (c) `SELECT COUNT(*) FROM source_subscription WHERE source_id=<that source>` returns 1; (d) `SELECT bootstrap_tags FROM source WHERE id=<that source>` returns `{news,tech}` (or the equivalent array form); (e) `SELECT COUNT(*) FROM tag WHERE name IN ('news','tech')` returns 2 (both unioned into vocab)"
  - "en.properties under infochat-provider/src/main/resources/bundles/ ships ALL the bundle keys referenced above. Per-key assertions (one per key — heterogeneous string set, count is NOT load-bearing):
    - `error.add_source.tags_required` present
    - `error.add_source.unknown_kind` present
    - `error.add_source.unknown_category` present
    - `error.add_source.malformed_url` present
    - `error.add_source.url_unreachable` present
    - `error.add_source.url_blocked_ssrf` present
    - `error.add_source.url_timeout` present
    - `error.add_source.ambiguous_url` present
    - `error.add_source.banned` present
    - `error.add_source.group_admin_only` present
    - `reply.add_source.fresh_insert` present (interpolation token for source name)
    - `reply.add_source.subscribed_existing` present
    - `reply.add_source.admin_tags_replaced` present
    - `reply.add_source.url_visibility_disclosure` present (literal substring `visible to bot admins`)
    Verify per key: `grep -E '^<key>=' en.properties` returns exactly 1 match"
  - "BundleLoader's bundle-completeness CI check (M1-035c's commit) continues to pass: every BundleKeys constant has a matching en.properties value AND no orphan en.properties entries exist. Verify: `mvn -B clean verify` exits 0 — the BundleLoaderTest from M1-035c asserts no missing-key and no orphan-key on boot"
  - "Plain-text invariant docs/spec/commands.md §Surface conventions: replies use single backticks for inline code and bare URLs (no markdown link syntax) — `[text](url)` patterns are FORBIDDEN per CLAUDE.md §Key conventions. Verify: `grep -E '\\]\\(http' en.properties` returns zero matches (no markdown links in any reply key authored by this ticket)"
  - "Every prior test continues to pass: M1-003 @QuarkusTest stubs, M1-007 cross-module AllSpisLoadIT, M1-007a/b/c SPI smoke tests, M1-008 per-scope isolation IT, M1-008a/b/c per-row schema tests, M1-022/023 bootstrap-loader + rss-fetcher, M1-024/025/026 SSRF, M1-027/028 outbox/NOTIFY, M1-032/033/034a/034b eval-pipeline, M1-035/035a/035b/035c T1-E surface (including AdapterRouterIT). Verify: `mvn -B clean verify` from the repo root exits 0 AND failsafe / surefire reports show zero failures"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceArgsTest.java (unit)
    - infochat-provider/src/test/java/io/infochat/provider/source/KindResolverTest.java (unit)
    - infochat-provider/src/test/java/io/infochat/provider/source/UrlProbeTest.java (unit; JDK com.sun.net.httpserver.HttpServer fixture, same pattern as SsrfGuardedHttpClientTest)
    - infochat-provider/src/test/java/io/infochat/provider/source/SourceUpsertServiceIT.java (DB-tier @QuarkusTest)
    - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceCommandHandlerTest.java (handler-tier @QuarkusTest with mocked UrlProbe)
    - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceIT.java (end-to-end via InMemoryAdapter — MVP exit criterion §4)
  preserves:
    - all M1-008a/b/c *Test.java classes (schema tier)
    - all M1-022/023/024/025/026 *Test.java and *IT.java classes (ingest + SSRF)
    - all M1-027/028 *Test.java and *IT.java classes (outbox/NOTIFY)
    - all M1-032/033/034a/034b *Test.java and *IT.java classes (eval pipeline)
    - all M1-035/035a/035b/035c *Test.java and *IT.java classes (T1-E surface)
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §SSRF
  - docs/spec/security.md §Source URL visibility
  - docs/spec/architecture.md §Inter-service communication
  - docs/design/03-commands.md §`/add-source`
  - docs/design/00-mvp.md §6 MVP exit criteria (criterion §4)
  - docs/design/00-mvp.md §2 Schema (MVP audit_log scope: bot-admin bootstrap + /add-source)
decision_refs:
  - D5
  - D7
  - D14
  - D38

reviews: []
escalations:
  - date: 2026-05-18
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The ticket has three independent showstoppers that must be
      reconciled before implementation can start. (1) The clarity_check
      WARN on infochat-ssrf POM dependency is confirmed real — UrlProbe
      cannot satisfy acceptance item 6 ("UrlProbe uses
      SsrfGuardedHttpClient") without the dep, and adding the dep takes
      the file count to 13, violating both files_budget: 12 and the
      explicit files_scope enumeration. (2) BundleKeys.java is listed in
      out_of_scope as FROZEN AND excluded from files_scope, but the
      acceptance items + BundleLoader CI shape effectively require
      appending 14 named constants there; the implementation-notes
      carve-out contradicts the out_of_scope freeze. (3) The ticket body
      claims "M1-024 ships AuditLogWriter; this ticket calls into it"
      but M1-024 is the infochat-ssrf module ticket and no
      AuditLogWriter class exists anywhere in the codebase; the handler
      must either author inline INSERT INTO audit_log SQL (matching
      BootstrapLoader's style — acceptable but contradicts the ticket
      body) or introduce a new helper class (15th file).

      SUGGESTED ESCALATION: refine — surface the five-way menu so the
      user can decide for each of the three blockers whether to (a)
      widen files_budget to 14 + add infochat-provider/pom.xml and
      BundleKeys.java to files_scope, (b) accept inline INSERT INTO
      audit_log SQL in SourceUpsertService and strike the "M1-024 ships
      AuditLogWriter" body sentence, (c) decompose into two tickets, or
      (d) defer until a separate spec-amend ticket grants Provider
      INSERT/UPDATE on source/tag and lands the AuditWriter helper.
  - date: 2026-05-18
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED (round 2 — Plan subagent flagged via Risks
      section rather than literal "## OUTLINE FAILED" prefix; treated
      as outline-fail per CLAUDE.md §"Never trade rules against each
      other" because acceptance item 7 cannot be satisfied within the
      refined frozen scope).

      REASON: Acceptance item 7 mandates "UrlProbe issues HEAD first;
      on 405/501/connection-reset it falls back to a range-GET
      (Range: bytes=0-0)". But infochat-ssrf's SsrfGuardedHttpClient
      exposes only get(URI) — no head(URI) sibling, and the get(URI)
      builder constructs the request internally with a fixed
      Accept/User-Agent so the caller cannot inject Range: bytes=0-0
      either. Two viable paths:
        (a) Issue a range-GET via two get(URI) calls, one with the
            Range: bytes=0-0 header — BLOCKED because the wrapper does
            not expose header injection.
        (b) Add sibling overloads on SsrfGuardedHttpClient — touches
            infochat-ssrf, which is in the M1-024 frozen module and
            not in this ticket's files_scope.

      Round-1 OUTLINE FAILED caught the POM-dep accounting problem
      ("UrlProbe needs the dep, adding it would breach the 12-file
      budget"). The fix widened files_budget 12→14 and added pom.xml
      + BundleKeys.java to files_scope. But it never drilled into the
      *method-level surface* of SsrfGuardedHttpClient itself; it only
      verified the *file-level dep* would compile. Round-2 Plan
      caught the API-surface gap.

      Process gap recorded in:
      .claude/projects/-home-ubuntu5-Projects-quarkus-projects-
      infochat/memory/feedback_replan_after_outline_fail_refine.md

      SUGGESTED ESCALATION: refine — replace "HEAD first; range-GET
      fallback" in acceptance item 7 with "range-GET only (Range:
      bytes=0-0) via a new SsrfGuardedHttpClient.get(URI, Map<String,
      String> extraHeaders) overload". Add SsrfGuardedHttpClient.java
      to files_scope as a NARROW carve-out (additive new method only;
      existing get(URI) FORBIDDEN to modify). Bump files_budget 14→15.
revisions:
  - date: 2026-05-18
    reason: outline-fail-refine (round 2 — SsrfGuardedHttpClient API surface)
    snapshot: |
      files_budget: 14
      files_scope:
        - infochat-provider/pom.xml
        - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceCommandHandler.java
        - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceArgs.java
        - infochat-provider/src/main/java/io/infochat/provider/source/KindResolver.java
        - infochat-provider/src/main/java/io/infochat/provider/source/UrlProbe.java
        - infochat-provider/src/main/java/io/infochat/provider/source/SourceUpsertService.java
        - infochat-provider/src/main/java/io/infochat/provider/messaging/BundleKeys.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceArgsTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/KindResolverTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/UrlProbeTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/SourceUpsertServiceIT.java
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceCommandHandlerTest.java
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceIT.java
      acceptance item 7 (verbatim, pre-refine):
        "UrlProbe issues `HEAD` first; on 405 / 501 / connection-reset-after-headers
         it falls back to a range-`GET` (`Range: bytes=0-0`) per
         docs/spec/commands.md §Source management. UrlProbeTest covers (using a
         Vert.x or JDK-stub HTTP server / WireMock): (a) HEAD returning 200 with
         `Content-Type: application/rss+xml` → probe SUCCESS with detected
         content-type returned; (b) HEAD returning 405, range-GET returning 200
         with body byte → SUCCESS; (c) HEAD returning 4xx/5xx → FAILURE
         referencing `error.add_source.url_unreachable`; (d) SsrfGuardedHttpClient
         rejection (e.g. localhost / RFC1918 / metadata IP) propagates as FAILURE
         referencing `error.add_source.url_blocked_ssrf`; (e) timeout → FAILURE
         referencing `error.add_source.url_timeout`"
      out_of_scope (relevant frozen entry, pre-refine):
        - "any change under infochat-messaging-adapter/ ... BundleKeys is a
           NARROW carve-out: appending new constants is permitted, modifying or
           removing existing constants is FORBIDDEN."
        - "SsrfGuardedHttpClient is NOT mentioned in out_of_scope; the freeze on
           the infochat-ssrf module is implicit (M1-024 territory) — refining now
           adds an explicit narrow carve-out."
  - date: 2026-05-18
    reason: outline-fail-refine
    snapshot: |
      files_budget: 12
      files_scope:
        - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceCommandHandler.java
        - infochat-provider/src/main/java/io/infochat/provider/command/AddSourceArgs.java
        - infochat-provider/src/main/java/io/infochat/provider/source/KindResolver.java
        - infochat-provider/src/main/java/io/infochat/provider/source/UrlProbe.java
        - infochat-provider/src/main/java/io/infochat/provider/source/SourceUpsertService.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceArgsTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/KindResolverTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/UrlProbeTest.java
        - infochat-provider/src/test/java/io/infochat/provider/source/SourceUpsertServiceIT.java
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceCommandHandlerTest.java
        - infochat-provider/src/test/java/io/infochat/provider/command/AddSourceIT.java
      out_of_scope (relevant frozen entries):
        - "any change to AdapterRegistry, InboundRouter, MessagingStartup,
           CommandHandler interface, AutoRegisterService, BundleLoader,
           BundleKeys, HelpCommandHandler, or the M1-035c bundle infrastructure
           (the T1-E surface is FROZEN at the M1-035 umbrella's round; this
           ticket consumes the CommandHandler SPI and BundleKeys as-is)"
        - "any AuditLogWriter helper class change (M1-024 ships AuditLogWriter;
           this ticket calls into it but does NOT modify its surface — if a new
           verb is required, escalate, do NOT add it inline)"
      Implementation notes (contradicting clauses):
        - "infochat-ssrf dependency. The provider POM does NOT currently
           depend on infochat-ssrf — add the dependency in this ticket's POM
           edit if and only if the dependency is missing (the file-budget
           enumeration above does NOT list infochat-provider/pom.xml; if the
           dep is missing, that is a 13th file and an escalation trigger ...)"
        - "Bundle keys. en.properties under infochat-provider/src/main/
           resources/bundles/ is the file M1-035c authors; this ticket APPENDS
           keys to it. The BundleKeys constants class is also M1-035c's; this
           ticket APPENDS new constants (one per new key — compile-time safety
           per M1-035c's design)."
        - "AuditLogWriter. M1-024 ships an audit-log writer with a closed
           catalogue of action verbs (docs/design/04-security.md §Audit log).
           Consume it as-is; do NOT add a new verb."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-036: /add-source command (handler + kind resolver + URL probe + upsert + audit)

## Context

First of T1-F's two MVP-completing command handlers. After this ticket
and M1-037 land, MVP exit criterion §4 (docs/design/00-mvp.md §6)
— `/add-source <rss-url> --tags news,tech` inserts a source row, a
source_subscription, and the Collector's per-kind FetchScheduler picks
up the new `status='active'` row on its next tick — runs end-to-end
against the full T1-E surface (M1-035 umbrella).

The ticket's scope is the docs/spec/commands.md §Source management
contract for `/add-source` in its entirety: positional URL + required
`--tags` + optional `--type`/`--category`/`--name`; the deterministic
kind-resolution closed table; the SSRF-guarded URL probe; the
idempotent `(kind, identifier)` upsert; the three-branch tag-conflict
resolution; the URL-visibility disclosure; the audit-log row on
bot-admin tag replacement. Everything cited in §Source management
for `/add-source` ships in this ticket; the sibling section commands
(`/list-sources`, `/unfollow-source`, `/remove-source`,
`/source-enable`, `/source-disable`) are explicitly T2-B and excluded.

## Definition of Done

- AddSourceCommandHandler is a CDI-discovered `@ApplicationScoped`
  implementation of the M1-035b `CommandHandler` SPI, bound to the
  literal name `"add-source"`, and InboundRouter dispatches a
  `/add-source ...` inbound to it without router-side changes.
- Argument parsing handles the spec's positional + flag shape and
  produces friendly errors via en.properties bundle keys for every
  rejection path (missing tags, unknown kind, unknown category,
  malformed URL).
- Kind resolution applies the closed host-pattern table from
  docs/spec/commands.md §Source management in the documented order;
  IDN hosts fold via `IDN.toASCII` before compare.
- UrlProbe issues a small-range `GET` (`Range: bytes=0-0`) via a new
  `SsrfGuardedHttpClient.get(URI, Map<String,String> extraHeaders)`
  overload (additive, preserves existing SSRF guards). NOT a raw
  `HttpClient`; the wrapper's DNS-resolution + IP-policy check fires
  before the byte hits the wire. Failures produce a friendly error
  and no row is written.
- SourceUpsertService runs a single DB transaction: vocab union →
  `(kind, identifier)` upsert → subscription upsert; the fresh-insert
  branch emits the URL-visibility disclosure; the bot-admin
  tag-replacement branch writes an audit_log row.
- AddSourceIT exercises MVP exit criterion §4 end-to-end through the
  InMemoryAdapter with a stubbed feed URL.
- `mvn -B clean verify` from the repo root exits 0; every prior
  test continues to pass.

## Implementation notes

Non-binding hints — the developer reads these as context, not a recipe.

- **CommandHandler dispatch contract.** M1-035b
  `infochat-provider/src/main/java/io/infochat/provider/messaging/CommandHandler.java`
  is the SPI: `String name()` returns the literal slash-less command
  name; `OutboundMessage handle(ScopeRef, String rawText)` returns the
  reply. The router has already normalized the body (NFKC + bidi/ZWS
  strip + trim) and confirmed the leading slash; the handler sees
  `/add-source <url> --tags ...` verbatim with the leading slash.
- **InMemoryAdapter test helpers.** M1-035a ships `deliverDm(contactId,
  text)` + `sentMessages()` + `reset()`; AddSourceIT consumes them
  exactly as M1-035 umbrella's AdapterRouterIT does. Use
  `@TestProfile` to set `infochat.adapters=inmemory` +
  `infochat.adapters.inmemory.allow-low-trust=true` (M1-035b
  startup gates 5 + 6).
- **infochat-ssrf dependency + narrow API-surface carve-out.** The
  provider POM does NOT currently depend on `infochat-ssrf`; this ticket
  adds the dependency in `infochat-provider/pom.xml` (in `files_scope`).
  Dependency shape matches the existing collector-side dep
  (compile-time, project-version-pinned via `${project.version}`).
  Additionally, `SsrfGuardedHttpClient.java` itself is in `files_scope`
  as a NARROW additive carve-out: append ONE new method overload
  `public HttpResponse<InputStream> get(URI uri, Map<String,String> extraHeaders)`
  that mirrors the existing `get(URI)` body — same DNS resolution, same
  IP-policy check, same redirect handling — and additionally calls
  `extraHeaders.forEach(reqBuilder::header)` on the `HttpRequest.Builder`
  before `build()`. Modifying or removing the existing `get(URI)`
  overload is FORBIDDEN; the new overload exists for UrlProbe's
  `Range: bytes=0-0` injection and any future caller that needs to
  attach per-request headers.
- **Audit-log write (inline JDBC).** No standalone `AuditLogWriter`
  helper exists in the codebase as of M1-035 — M1-024 was the
  infochat-ssrf module ticket, not an audit-writer commit. The
  audit_log writes in this ticket are inline JDBC `INSERT` statements
  in `SourceUpsertService`'s single transaction, matching the
  inline-SQL style used in `BootstrapLoader` for the
  `BOOTSTRAP_SOURCE_LOAD` audit row. The action verb is the literal
  `'ADD_SOURCE'` from V5 §2.1.8 — already in the closed catalogue;
  do NOT add a new verb. The IT asserts the audit row exists with
  the correct `target_kind`/`target_id`, not its verb spelling.
- **BundleKeys append.** `BundleKeys.java` is in `files_scope` for
  this ticket as a narrow additive carve-out from the T1-E freeze:
  append one `public static final String` constant per new bundle
  key authored in en.properties. Do NOT modify existing constants
  or remove keys; the BundleLoader CI completeness check requires
  constant-and-value parity in both directions.
- **JDK HttpServer fixture (no WireMock).** UrlProbeTest needs a probe
  target that returns controlled status codes + Content-Type headers
  for a range-GET request. The repo does NOT have a WireMock dependency;
  `SsrfGuardedHttpClientTest` already uses `com.sun.net.httpserver.HttpServer`
  bound to a loopback-allowed test port via an SSRF test-seam (see the
  `SsrfPolicy.testSeamAllowLoopback` toggle the M1-024 ticket ships).
  Reuse the same harness: spin up an `HttpServer` per test, register
  per-test handlers that echo the range-byte and the configured
  Content-Type, tear down after the test.
- **Bundle keys.** en.properties under
  `infochat-provider/src/main/resources/bundles/` is the file M1-035c
  authors; this ticket APPENDS keys to it. The BundleKeys constants
  class is also M1-035c's; this ticket APPENDS new constants (one per
  new key — compile-time safety per M1-035c's design). The bundle-
  completeness CI check from M1-035c (`BundleLoaderTest`) asserts no
  missing key and no orphan key — both directions matter when
  appending.
- **Per-kind tick model.** docs/spec/architecture.md §Inter-service
  communication: the Collector's FetchScheduler polls source rows by
  kind on a per-kind cadence (M1-022 + M1-028 + M1-023 wire it up).
  A fresh `source` row with `status='active'` is picked up on the next
  tick; NO `pg_notify('new_source', ...)` channel is added — the MVP
  superseded doc (docs/design/00-mvp.md §6) mentions a NOTIFY, but the
  v1 architecture uses the per-kind tick instead. This ticket's IT
  asserts the DB writes; it does NOT assert any NOTIFY.

## Big-picture notes

What the implementer must keep in mind that isn't in the immediate diff.

- **Three replies, one transaction.** The fresh-insert reply (Branch
  A), subscribed-existing reply (Branch B), and admin-tags-replaced
  reply (Branch C) all emit AFTER a single committed DB transaction.
  Build the reply from the post-commit DB state, not from the
  pre-write args, so a concurrent commit (Branch A racing Branch B
  for the same URL) cannot produce a Branch-A reply when the row was
  actually inserted by the other caller. SourceUpsertService's
  transaction can use `INSERT ... ON CONFLICT (kind, identifier) DO
  UPDATE SET ... RETURNING *, xmax = 0 AS was_inserted` to
  distinguish insert-vs-update reliably; the boolean drives the
  reply branch.
- **URL-visibility disclosure is Branch A only.** docs/spec/commands.md
  §Source management is explicit: the disclosure is omitted on the
  already-existed paths because the URL was already in the global
  set. The test surface enforces this: Branch B and Branch C MUST NOT
  contain the disclosure literal.
- **Bot-admin --tags REWRITE is the only mutation path on existing
  rows.** Non-admin --tags against an existing row are QUIETLY IGNORED
  — a friendly notice (`reply.add_source.subscribed_existing`) is
  the user-visible signal. The spec is explicit: letting any DM user
  mutate `bootstrap_tags` would silently change ingest behaviour for
  every other subscriber on the same global row.
- **Ambiguous URL is a friendly error, not a guess.** docs/spec/
  commands.md §Source management: there is no silent fallback for
  self-hosted Nitter instances or non-canonical mirrors. The probe
  result is allowed to contradict the URL-pattern hint (an RSS-shaped
  path returning `text/html`) — when it does, the result is AMBIGUOUS
  and the caller is told to supply `--type` explicitly.
- **The handler does NOT emit `pg_notify('new_source', ...)`.** The
  Collector's per-kind FetchScheduler picks up new `status='active'`
  rows on its next tick. The MVP design doc's mention of LISTEN/NOTIFY
  for /add-source (00-mvp.md §6 criterion §4) is SUPERSEDED by the v1
  per-kind tick model; the IT asserts the DB writes only.
- **MVP audit_log scope.** docs/design/00-mvp.md §2 commits to
  audit_log writes for ONLY the bot-admin bootstrap and `/add-source`.
  Branch A (fresh insert) and Branch C (bot-admin tag replace) BOTH
  write audit_log rows in this ticket. Branch B does not. The audit
  surface BROADENS in later tiers (T2-A onboarding, T2-G quarantine,
  etc.); this ticket commits to the MVP-tier audit scope only.
- **Decomposition watch.** If clarity pre-flight (per docs/process/
  workflow.md §Ticket-clarity pre-flight) FAILs this ticket as too
  large given the spec breadth (kind resolution + URL probe +
  three-branch tag-conflict + audit log + IT), the workflow path is
  `/m1-tick escalate decompose` into a (a) umbrella + (b)
  KindResolver+UrlProbe subticket + (c) handler+IT subticket. Do NOT
  decompose inline; the umbrella+subticket pattern (docs/process/
  workflow.md §Ticket-ID placeholder convention) carries the
  whole-topic IT.

## Out-of-scope expansion

The `out_of_scope` list above carries the load-bearing exclusions; the
prose here explains the boundaries the reviewer uses to judge scope
drift.

- **Sibling source-management commands are T2-B.** `/list-sources`,
  `/unfollow-source`, `/remove-source`, `/source-enable`,
  `/source-disable` are spec'd in docs/spec/commands.md §Source
  management but are not in MVP exit criteria — they land in T2-B's
  source-management ticket. This ticket writes `status='active'` on
  fresh inserts and stops; the active↔disabled↔failed state machine
  has no reachable transition path from /add-source alone.
- **Group dispatch is T2-F.** docs/spec/commands.md §Source management
  defines a "Group: group admin only" branch for /add-source. This
  ticket exercises the AUTHORIZATION path (a non-group-admin in group
  scope is rejected with a friendly error) but does NOT add any
  group-membership infrastructure — the test seeds the rejection
  branch via a `ScopeRef.group(<id>)` direct construction. The group
  intake path proper (group `@mention` dispatch, first-mention
  auto-promote per CLAUDE.md §Bootstrap admin) is T2-F.
- **Translation is T2-C.** Every outbound reply in this ticket uses
  English BundleKeys; TranslationProvider integration / `/lang` is
  T2-C. The bundle keys are designed for translation (interpolation
  tokens, no concatenation of substrings) so T2-C's wiring drops in
  without re-authoring the keys.
- **LLM is M1-037's concern, not this ticket's.** /add-source has no
  LLM-authored output surface — every reply is a deterministic
  localization-bundle string. The LLM output sanitizer
  (docs/spec/security.md §LLM output sanitizer) is the M1-037
  /summary ticket's territory.

## Authorized test changes

This ticket adds tests but does not modify existing ones. Touching any
of the M1-035/035a/035b/035c tests or the M1-008a/b/c schema tests
constitutes a test-integrity violation per
docs/process/engineering-rules-verbatim.md §8.

- (none — this ticket adds the six tests enumerated in `test_plan.adds`
  and does not modify existing ones)

## Alternatives considered

- **Alt A — Single handler+probe+upsert class (god class).** Rejected:
  KindResolver and UrlProbe are independently testable units with
  closed input/output contracts (host table; HEAD/range-GET response →
  detected Content-Type). Folding them into AddSourceCommandHandler
  would couple the per-rule unit tests to handler-level CDI setup, a
  bad trade.
- **Alt B — Emit `pg_notify('new_source', ...)` so the Collector
  picks up the row immediately rather than on the next per-kind
  tick.** Rejected: docs/spec/architecture.md §Inter-service
  communication commits to a per-kind tick cadence, not a per-source
  notification. The MVP doc's mention of LISTEN/NOTIFY for
  /add-source is superseded. Adding a new NOTIFY channel would also
  require Collector-side listener wiring beyond T1-F's scope.
- **Alt C — Skip the URL probe and let the Fetcher discover bad URLs
  later.** Rejected: docs/spec/commands.md §Source management is
  explicit that the probe runs BEFORE the row is written; an
  unreachable / SSRF-blocked URL must produce a friendly error with
  no DB write. Skipping the probe would shift the failure surface
  to the Collector and produce orphan `failed` source rows on every
  bad `/add-source`.
- **Alt D — Authoring as an umbrella + three subtickets
  (KindResolver/UrlProbe/handler+IT).** Held in reserve as the
  decomposition path if clarity pre-flight FAILs the single-ticket
  shape. Not chosen up-front because the T1-F handoff
  (docs/plan/m1/drafts/session-grouping-plan.md §Tier 1) commits to
  2 tickets for /add-source + /summary, not 4+. The decomposition
  path is documented in Big-picture notes so the workflow path is
  clear if it triggers. After round-2 outline-fail-refine, the
  evaluation stands: the pieces interlock around one command surface,
  the IT (MVP exit §4) cannot pass until everything lands, so a
  single tight ticket is correct.
