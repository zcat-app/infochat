# infochat — consolidated cross-model audit handout

**Date:** 2026-06-02
**Consumed:** 5 primary deep-review runs (opus-47, opus-48, deepseek, mimo, kimi-k) + 4 independent fresh-eyes audit runs (opus-48-audit, deepseek-audit, kimi-k-audit, mimo-audit) — 36 report files, ~11,800 lines.
**Method:** group every finding by code locus, cross-reference reporters, ground every load-bearing claim against the current code, drop or down-rank where ground truth contradicts the report. The "Discarded" section is load-bearing — silent drops would defeat the consolidation.

Findings here are descriptive, not prescriptive: this is the catalogue an M1 ticket queue can be drawn from. The "Suggested ticket bundling" section is a starting point for the user, not a decided plan. Severity labels follow the highest reporter's call; disagreements are flagged inline. The "Reported by" line names the primary-run identifiers (opus-47 / opus-48 / deepseek / mimo / kimi-k) and the independent-audit identifiers (with the `-audit` suffix); a single name on that line means single-reporter (extra falsifier scrutiny).

---

## Top priority

Ranked by `severity × corroboration` and by blast radius if shipped to production.

1. **F-CRIT-01** — Anthropic auth/version headers use the wrong names; every production Anthropic call will 401 and the test pins the bug.
2. **F-CRIT-02** — Single `volatile MessagingAdapter replyTarget` makes the last-registered adapter the reply path for every inbound, so in a SimpleX+Signal deployment a SimpleX user's reply ships through Signal (cross-adapter user spoof + outbound to unrelated identity).
3. **F-CRIT-03** — Application DB connections use the `infochat` owner role, not the per-service roles; every defense-in-depth layer the spec attaches to the role split (audit redaction, quarantine procedure carve-outs, Invariants 4/10) is decorative.
4. **F-CRIT-04** — No `_202606` (or later) partitions exist for any of the five partitioned tables (`post`, `post_embedding`, `post_entity`, `post_reference`, `price_snapshot`). Today is 2026-06-02; the first INSERT into any of these tables fails with `no partition of relation … found for row`.
5. **F-CRIT-05** — `InstanceLockGuard` writes the heartbeat row once at `@Startup` and never refreshes it; if the held connection drops silently (TCP keepalive, server restart, idle-in-transaction timeout) a second instance can acquire the advisory lock and the spec's single-instance invariant (D41) is broken.
6. **F-CRIT-06** — `infochat.reeval.*` config keys (`infra-failure-cap`, `unknown-cap`, `poll-interval`, etc.) are declared **only** in `src/test/resources/application.properties`; production startup in any operator profile fails because `@ConfigProperty` cannot resolve them and `@Scheduled(every = "{infochat.reeval.poll-interval}")` aborts the scheduler config.
7. **F-CRIT-07** — Only `/zcash` and `/monero` are wired as `CommandHandler`s in `AssetCommandRouter`; any third asset added to `bootstrap-assets.json` is invisible to the slash dispatcher even though the bootstrap loader accepts it, contradicting the per-asset extensibility commitment.
8. **F-CRIT-08** — `ReEvaluationJob.processOne`'s `re_eval_attempts >= cap → transitionToNeedsReview` branch is unreachable in production because `enumerateCandidates` filters `re_eval_attempts < cap`; cap-exceeded rows stay `QUARANTINED` forever, the spec-mandated `NEEDS_REVIEW` transition never fires, and operator admin notifications never come.

---

## SECURITY

### F-SEC-01 — Anthropic auth/version headers use the wrong names (critical, SECURITY)

- **Reported by:** opus-47 (per-module-llm-adapter F1 + F2)
- **Locations:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:139,142`; test at `infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java:133-134,154-157`
- **What's wrong:** The code emits `x-anthropic-version` and `anthropic-api-key`. The documented Anthropic Messages API requires `anthropic-version` (no `x-` prefix) and `x-api-key` (with `x-` prefix). The test was written against the wrong names, so the bug is pinned: any future correction fails the test, locking the bug in (§8 test-integrity violation).
- **Why resolve:** Without the fix, AnthropicProvider is non-functional in production — every call returns 401 and the secret value is shipped over TLS into a header Anthropic discards (minor exposure to mirroring/audit proxies that key off header names). The test is the regression-guard against drift; fixing it in the same diff is mandatory.
- **Why it might not be an issue:** The internal mock-server tests pass because they assert the same wrong names the code emits; the failure only surfaces against the real Anthropic gateway. If the bot never reaches production with AnthropicProvider as the active impl (OpenAI-compatible is the v1 default), the bug is dormant — but the impl is a v1 deliverable per M1-085 / M1-120.
- **Verified against code:** Yes — `grep -n` returned `x-anthropic-version` at :139 and `anthropic-api-key` at :142.
- **Scope hints:** Two header strings + four test assertions + class javadoc lines 50-52 + handoff doc `docs/plan/m1/drafts/handoff-tier3-D-anthropic-llm.md` lines 80-81, 194, 305. One file in main + one in test. Low complexity.
- **Verdict:** RESOLVE

### F-SEC-02 — Single `replyTarget` makes multi-adapter outbound route to the wrong adapter (critical, SECURITY)

- **Reported by:** opus-47 (per-module-provider F1)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:284`, lookup at `:604-611`; setter called from `AdapterRegistry.java:254-266` in the activation loop.
- **What's wrong:** `private volatile MessagingAdapter replyTarget;` is a single field set inside the per-adapter activation loop in `AdapterRegistry.start`. With `infochat.adapters=simplex,signal` the loop calls `setReplyTarget(simplex)` then `setReplyTarget(signal)`; the last one wins and is the reply path for every inbound message, regardless of which adapter delivered it. A SimpleX user's reply is sent out via Signal — cross-adapter outbound to a different identity space and a different operator-managed contact id.
- **Why resolve:** D46 + `docs/spec/security.md` §Per-adapter admin threat profile commit to per-adapter isolation. The same field also flips state under heavy operational load (an adapter restart in a running deployment changes the reply path for OTHER adapters' users). The fix is structural: track reply targets per inbound adapter name (the `onMessage(msg, adapterName)` already carries the discriminator).
- **Why it might not be an issue:** Single-adapter deployments (laptop / pi / vps with `simplex` only) cannot exhibit the bug. The multi-adapter shape is the v1 commitment per D46 but is not the default profile. If multi-adapter is deferred to v2, this is a no-op — but the spec keeps the commitment.
- **Verified against code:** Yes — `replyTarget` is a single `volatile MessagingAdapter` field; lookup at the reply site uses no adapter-name discriminator.
- **Scope hints:** `Map<String, MessagingAdapter> replyTargetByName` keyed by adapter name, plumbed through `setReplyTarget(String, MessagingAdapter)` and looked up by `inboundContext.adapterName()` at reply time. Also touches `AdapterRegistry.start`, `MessagingStartup`. Medium complexity — recommend a plan-writer pass.
- **Verdict:** RESOLVE

### F-SEC-03 — Application DB connections use the owner role, not per-service roles (critical, SECURITY)

- **Reported by:** opus-47 (per-module-architecture F1)
- **Locations:** `infochat-collector/src/main/resources/application.properties:13`, `infochat-provider/src/main/resources/application.properties:18`, role definitions at `infochat-core/src/main/resources/db/migration/V2__roles.sql:32-65`.
- **What's wrong:** Both services connect as `quarkus.datasource.username=infochat` (the bootstrap owner). V2 creates `infochat_collector`, `infochat_provider`, `infochat_admin` as `NOLOGIN`. No `SET ROLE` is issued in either service's main sources. Every GRANT/REVOKE the migrations attach to the per-service roles is decorative: the runtime session has owner-level privilege on every table.
- **Why resolve:** `docs/spec/security.md` §DB roles names this split as the defense-in-depth layer that lets `audit_log_view` exist (the Provider role lacks SELECT on raw `audit_log`), keeps `quarantine.original_html` off the Provider's reach (Provider has EXECUTE on the SECURITY DEFINER procedures, not raw quarantine SELECT), enforces Invariant 4 (only `infochat_admin` can DELETE from `source`), and gives Invariant 10 a structural backstop (INSERT-only grants on `audit_log`). None of those properties is in force today.
- **Why it might not be an issue:** The spec's role split is a defense-in-depth layer; the application code paths still go through the documented surfaces (audit_log_view, stored procs). A SQL-injection bug would still need a foothold to abuse the missing role guard. But the rule is the role split is *part of* the defense — not an optional enhancement — and every additional code path that lands accumulates more privilege-mismatched DML that the eventual switch must sweep.
- **Verified against code:** Yes — V2 explicitly carries the comment "Until the named-datasource wiring ticket lands, the bootstrap `infochat` superuser remains the connecting role." The wiring ticket has not landed.
- **Scope hints:** New migration `V30__role_login.sql` adding `LOGIN` to the two service roles; Quarkus named-datasource wiring (Flyway on owner datasource, runtime on role datasource); two extra operator-input passwords in deployment docs; sweep of all main-source code paths against the new GRANT matrix. High complexity; recommend a plan-writer pass; expect IT failures that reveal real privilege-mismatched DML.
- **Verdict:** RESOLVE

### F-SEC-04 — `SET LOCAL infochat.actor_id = '…'` is built by SQL concatenation in 10+ command handlers (high, SECURITY)

- **Reported by:** kimi-k-audit (1.1, 4 sites named), kimi-k (F3 — same family, AuditCommandHandler WHERE-builder angle), deepseek (per-module-provider F5 — GrantAdminCommandHandler angle)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/{ApproveGroup,Ban,Quarantine (3 sites),RejectGroup,RevokeAdmin,Vouch,GrantAdmin,Unban (2 sites)}CommandHandler.java` — 12 `st.execute("SET LOCAL infochat.actor_id = '" + … + "'")` sites total.
- **What's wrong:** The pattern interpolates `actor.id` (a `UUID` typed value) into raw SQL via `Statement`. The current implementation is incidentally safe because the input is a `UUID` typed value with a `"`-free / `'`-free text form. The hazard is normalization of an injection-shaped pattern across the codebase: a future change that lets any user-derived String reach this site (a forged contact id parsed as UUID, a non-UUID GUC name, a downstream UnbanCommandHandler that also concatenates a `request_id`) is a direct SQL injection at a `SET LOCAL` site that influences Row-Level Security (RLS) and audit-trigger behavior.
- **Why resolve:** Engineering rules §3 (SQL injection is one of the OWASP top 10 the system prompt flags). The same files already use `PreparedStatement` for every other DML on the same connection — the `SET LOCAL` is the only outlier, which makes the inconsistency itself a maintenance smell. Note: kimi-k-audit's proposed `PreparedStatement` with `?` for `SET LOCAL` is **not portable** — Postgres rejects `SET LOCAL ? = ?` in the simple query protocol; the real fix is either `set_config('infochat.actor_id', ?, true)` (a function call that DOES accept bind params) or stronger upstream validation that `actor.id` cannot be supplied as a string.
- **Why it might not be an issue:** `actor.id` is typed `UUID` throughout the load path — `UUID.fromString` rejects anything that isn't a valid UUID before this code runs. Today the pattern is safe by construction. If the column type or the loader contract were ever loosened, the safety dies silently.
- **Verified against code:** Yes — `grep` returned 12 `SET LOCAL infochat.actor_id` sites; one site also concatenates `actor_id` AND `request_id` (UnbanCommandHandler:229-230), suggesting the pattern is being copied with each new handler.
- **Scope hints:** Single shared helper `setActor(Connection conn, UUID actorId)` and `setRequestId(Connection conn, String requestId)` using `set_config('infochat.actor_id', ?, true)` parameterized via `PreparedStatement`. Replace all 12 sites + the UnbanCommandHandler request_id site. Medium complexity; mostly mechanical.
- **Verdict:** RESOLVE-BUNDLED (with F-SIM-01 JSON-quoting consolidation — both are "extract the helper that every command handler reimplements")

### F-SEC-05 — `audit_log_view` redaction is unimplemented; Provider reads raw contact ids and secrets (high, SECURITY)

- **Reported by:** opus-48 (per-module-architecture F1)
- **Locations:** `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:324-352` (view definition + stub `redact_contact_id` / `redact_details_json`), Provider read path at `infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java:179-204`, hook stub at `infochat-provider/src/main/java/app/zcat/infochat/provider/audit/DefaultRedactionHook.java:14-21`.
- **What's wrong:** The view exists and the Provider correctly reads it (rather than the raw table), but the redaction functions are no-op stubs documented as "the audit-write redaction-hook ticket can supersede the bodies." `/audit` therefore returns raw contact ids and unredacted `details_json` to the Provider — the confidentiality property the view was created to enforce is hollow.
- **Why resolve:** The view is the documented trust boundary at which Provider observability is meant to be redaction-safe (operator can grep without leaking subject identities). The stub state is supposed to be temporary; the redaction-hook ticket hasn't landed and the stub state is now load-bearing for `/audit` output.
- **Why it might not be an issue:** The view is at least the path that gets the Provider's SELECT (not raw `audit_log`), so the role-split fix (F-SEC-03) would still block direct-table reads even with stub redactors. Concretely, the leakage is to `/audit`'s bot-admin consumer, who is already authorized to see audit data. Whether that consumer should see contact ids in plaintext depends on the deployment threat model — but the spec's view design says no.
- **Verified against code:** Yes — V5:32-34 has the inline comment "stub `redact_contact_id` and `redact_details_json` PL/pgSQL functions that return their input unchanged; the audit-write redaction-hook ticket can supersede the bodies."
- **Scope hints:** Implement `redact_contact_id(TEXT, TEXT)` and `redact_details_json(JSONB)` with the redaction policy from `docs/spec/security.md`. New migration. Code-side hook also needs to flip on. Medium complexity, coupled with F-SEC-03.
- **Verdict:** RESOLVE-BUNDLED (with F-SEC-03 — both touch the audit trust boundary)

### F-SEC-06 — IPv6 transition ranges (6to4 / Teredo / NAT64 / IPv4-compatible) bypass the IpBlocklist (high, SECURITY)

- **Reported by:** opus-48 (per-module-ssrf F1), opus-48-audit (Finding 5 — narrower subset, LOW severity)
- **Locations:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java:114-119,208-215`; isBlockedV6 at `:166-188`.
- **What's wrong:** `IpBlocklist` decodes only the IPv4-mapped form (`::ffff:a.b.c.d`, bytes 10-11 = `0xFFFF`). 6to4 (`2002:…/16`), Teredo (`2001::/32`), NAT64 (`64:ff9b::/96`), and IPv4-compatible (`::a.b.c.d`) embed an IPv4 target but pass the v6 blocklist (not all-zero, not `::1`, not `fe80/fc00/ff00`). A literal-IP URL `http://[::127.0.0.1]/` returns the address from the resolver seam and the blocklist returns `false`.
- **Why resolve:** Fail-closed egress is the SSRF guard's purpose. The IPv4-mapped check exists because the kernel can route mapped addresses to loopback; the same is true on common cloud images for 6to4 (it can reach `169.254.169.254`). Coverage gap exists whether or not the kernel currently exploits it.
- **Why it might not be an issue:** opus-48-audit's falsifier note: IPv4-compatible addresses are deprecated and not routed to loopback on modern Linux kernels; NAT64 only resolves to internal targets when a NAT64 gateway is deployed. Practical exploitability is narrow but the rule the blocklist enforces is "cover kernel-level bypass forms," and these are the same class as the form it already handles.
- **Verified against code:** Yes — `grep` for `isIpv4Mapped|6to4|teredo|NAT64|2002:|2001:|isIpv4Compat` returned only `isIpv4Mapped` and one call site. No decoders for the other transition ranges exist.
- **Scope hints:** Extend `isBlocked(byte[])` to decode and dispatch the four embedded-IPv4 forms; add to the existing `IpBlocklistTest` matrix. Single file, single test file. Low-medium complexity.
- **Verdict:** RESOLVE

### F-SEC-07 — local-only startup guard misses the embedding endpoint and provider-name overrides (high, SECURITY)

- **Reported by:** opus-48 (per-module-llm-adapter F1), deepseek (per-module-llm-adapter F3 — additional spec-drift angle: guard runs on Collector startup, spec promises Provider)
- **Locations:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java:96-103, 183-204`; spec at `docs/spec/llm.md:132-134`.
- **What's wrong:** The guard scans per-task base-urls when `infochat.llm.local-only=true` is set, but does not scan the embedding endpoint (`infochat.embeddings.base-url`) or the provider-name override keys. A `local-only=true` deployment with a remote embedding endpoint silently ships post bodies (title + summary) off-host with no startup failure.
- **Why resolve:** The local-only commitment is the privacy invariant the spec sells operators on. A guard that closes 6 of 7 paths is the same as no guard for the path it leaves open. deepseek's separate concern (the guard lives on Collector, spec says Provider) is also worth surfacing in the same fix — embedding generation happens in the collector ingest pipeline, so the placement matches what the code does, but the spec text needs reconciling.
- **Why it might not be an issue:** Default profile values point embedding at a loopback Ollama, so the gap is dormant unless an operator explicitly sets a remote base-url. The spec's "explicit confirmation log line on startup" (cited in opus-47 llm-adapter synthesizer obs) is the warning channel for the non-local-only case; that log line is also missing.
- **Scope hints:** Extend `LlmRouterStartupGuard.validate` to include the embedding base-url and any provider-name override keys; same `URI` parse + loopback test the existing per-task scan uses. Spec amendment to clarify "Collector startup" or move the guard to a Provider-side hook. Medium complexity.
- **Verdict:** RESOLVE

### F-SEC-08 — SimpleX mention recognition is non-injective; collisions can spoof or suppress mentions (high, SECURITY)

- **Reported by:** opus-48 (per-module-messaging-adapter F1)
- **Locations:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java:57-93`
- **What's wrong:** The canonicalization the parser uses is not 1:1: two distinct SimpleX queue-address strings can hash to the same recognition key, so a non-mention can be read as a bot mention (forge a "@bot" in group output) or a real mention is suppressed (a mention against the bot's queue collides with a different identity and the dispatcher routes it elsewhere).
- **Why resolve:** D10 makes mentions the trust anchor for group-mode authorization. The spec promises an attacker cannot forge or suppress mentions. The non-injective canonicalization breaks the property both ways.
- **Why it might not be an issue:** Single-reporter; the falsifier (constructing the collision) requires understanding SimpleX queue-address structure. opus-48's report describes the canonicalization rule; whether real-world inputs actually collide in practice depends on the queue-address format's symbols-vs-canonicalized-symbols overlap.
- **Scope hints:** Single file (`SimpleXMentionParser`). Replace the canonicalization with a constant-time exact-bytes compare (the SimpleX queue address is already a stable opaque identifier). Add a regression test with the collision pair opus-48 names. Medium complexity once the collision is understood; recommend reading the per-module report's detail before scoping.
- **Verdict:** RESOLVE

### F-SEC-09 — extraHeaders leak across cross-origin redirects (medium, SECURITY)

- **Reported by:** opus-47 (per-module-ssrf F3)
- **Locations:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:319-335`
- **What's wrong:** The redirect loop re-applies `extraHeaders` to every hop without checking same-origin. The signature `Map<String, String> extraHeaders` looks innocuous; a future caller that adds `Authorization` for `feed.example.com` will ship that credential to a 302-redirect target on `attacker.example.org` because the redirect re-validation is transport-only (DNS + blocklist), not payload-scrub.
- **Why resolve:** The current consumer (`UrlProbe`'s `Range: bytes=0-0`) is benign. The risk is shape: nothing in the signature warns a future caller. Browsers, curl, and well-known HTTP clients strip sensitive headers on cross-origin redirects for this reason. The spec's "DNS re-resolves after every redirect" rule structurally implies the same scrub extends to credentials.
- **Why it might not be an issue:** Today's only `extraHeaders` use is a non-credential `Range` header that is safe to forward. If `/add-source` URL probes never grow an `Authorization` knob, the dormant risk stays dormant.
- **Scope hints:** Cross-origin scrub list (`Authorization`, `Cookie`, `Proxy-Authorization`) gated on same-host+port+scheme test in the redirect loop. Single file. Low complexity.
- **Verdict:** RESOLVE-BUNDLED (with other SSRF small-fixes: F-SEC-06, F-MAINT-05 javadoc, F-MAINT-09 IPv6 literal canonicalization)

### F-SEC-10 — Body-size cap bypass via unpaired UTF-16 surrogates / off-by-one StringIndexOutOfBoundsException (medium, SECURITY)

- **Reported by:** deepseek-audit (SEC-1, bypass angle), kimi-k-audit (2.3, OOB-exception angle)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:817-836` — method `exceedsUtf8ByteLength(String, int)`
- **What's wrong:** Two distinct issues on the same surrogate-pair branch:
  1. **Bypass** (deepseek-audit): an unpaired high surrogate counts as 4 UTF-8 bytes (actual: 3) and the `i++` skips the next character entirely, under-counting by `actual_bytes(next_char)`. An attacker chains such pairs to smuggle ~2× the `maxInboundBodyBytes` cap through the size check.
  2. **OOB exception** (kimi-k-audit): if the high surrogate is the last character, `i++` walks past the end and the next loop iteration's `s.charAt(i)` throws.
- **Why resolve:** The body-size cap is defense-in-depth; bypass weakens (not removes) the layer that protects the normalize + invite-code-parse pipeline from a larger-than-intended body. The OOB exception is a definite crash on malformed input. Both are fixed by the same change: verify the low surrogate before counting + skipping.
- **Why it might not be an issue:** `chatBodyCap` bounds chat-mode by character count, so a CJK-heavy adversarial body still goes through normalize but is bounded at the higher-level layer for chat. Slash commands are short by nature. The deepseek-audit falsifier note explicitly says the bypass does not defeat any security boundary by itself.
- **Verified against code:** Locus exists; the surrogate-pair branch is the one both reporters flag.
- **Scope hints:** Single method, ~3-line change (bounds check + low-surrogate check). Plus a unit test with the OOB-triggering input and the surrogate-bypass pair. Low complexity.
- **Verdict:** RESOLVE

### F-SEC-11 — Hand-rolled JSON arg parser in ChatAgent silently mangles non-trivial tool payloads (medium/high, SECURITY+CORRECTNESS)

- **Reported by:** opus-47 (per-module-provider F8), opus-48-audit (Finding 1 — array-arg never parsed, HIGH severity), kimi-k-audit (1.4 — escaped-quote state flip, HIGH), deepseek-audit (COR-1 — nested JSON via reluctant regex, LOW), kimi-k (own F4 — fragile parser angle)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305` (`parseToolArgs`, `splitTopLevel`); regex at `:43` (`TOOL_CALL_PATTERN`); consuming tools at `SearchPostsTool.java:45-46`, `RecallMemoryTool.java:38-39`, `ListSavesTool.java:44-45`.
- **What's wrong:** Three independent defects in the same hand-rolled parser, found by four reporters from different angles:
  1. **No array support.** The parser only emits `String` or `Integer`. For `{"tags": ["bitcoin"]}` it stores the literal string `"[\"bitcoin\"]"` under key `tags`; consumers cast `(List<String>) args.get("tags")` and throw `ClassCastException`. `recallMemory` (which requires `keywords`) is **entirely broken**; tag-filtered `searchPosts`/`listSaves` always fail.
  2. **Reluctant `\{.*?\}` regex.** Nested JSON (`{"params": {"key":"v"}}`) matches the inner `}` as the shortest end; the parsed args are malformed / empty and the tool call silently fails.
  3. **Escaped-quote state flip.** `splitTopLevel`'s `s.charAt(i-1) != '\'` check is one character deep; `\\\"` (escaped backslash followed by an escaped quote) flips `inQuote` incorrectly.
- **Why resolve:** This is a user-visible functional break in a core feature with no test coverage on the failing path. The system prompt tells the LLM to emit array arguments; the parser cannot understand them. From the user seat, chat search/recall is broken whenever it matters. The dispatcher's `ChatToolDispatcher.dispatch` catches only `IllegalArgumentException`/`SQLException`, so `ClassCastException`/`DateTimeParseException` from bad arg types escape to `ERROR_CHAT_UNAVAILABLE` and the LLM gets no structured retry signal — see F-MAINT-13.
- **Why it might not be an issue:** opus-48-audit explicitly grepped the tool-loop tests and the `parseToolArgs` tests and found no array-arg coverage — that is the falsifier: the code IS broken and IS unexercised. Quarkus ships Jackson; the cleanest fix is to replace `parseToolArgs` + `splitTopLevel` + the regex with `ObjectMapper.readValue(args, Map.class)`.
- **Scope hints:** Replace `parseToolArgs` and the regex with a Jackson `ObjectMapper` parse; add tests covering array args (`tags: ["a","b"]`), nested objects, and escaped quotes inside string values; widen the dispatcher's catch in the same diff (F-MAINT-13). Medium complexity; recommend a plan-writer pass because three reporters disagree on which subset to fix first.
- **Verdict:** RESOLVE

### F-SEC-12 — `/promote` reads the actor row without `FOR UPDATE`, leaving a TOCTOU window with `/revoke-admin` (medium, SECURITY)

- **Reported by:** opus-47 (per-module-provider F6)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java:90-93,158-169`
- **What's wrong:** `/promote` reads the actor row without `FOR UPDATE`, so a concurrent `/revoke-admin` that strips the actor's `is_admin` flag between the read and the promote can let an ex-admin's promote succeed.
- **Why resolve:** Authorization checks against concurrent state changes need row locking; the rest of the admin command handlers use `FOR UPDATE` for this exact reason.
- **Why it might not be an issue:** The race window is very narrow — the actor would have to issue `/promote` essentially simultaneously with another admin's `/revoke-admin` against them. Single-reporter.
- **Scope hints:** Add `FOR UPDATE` to the SELECT in the existing transaction; mirror the pattern from BanCommandHandler. Single file. Low complexity.
- **Verdict:** RESOLVE-BUNDLED (with the lookupUser/lookupActor consolidation in F-SIM-04)

### F-SEC-13 — `/save` accepts unbounded personal-tag strings and counts (medium, SECURITY)

- **Reported by:** opus-47 (per-module-provider F10)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:305-321,265-284`
- **What's wrong:** No length cap on individual tag strings or the count of tags per save. A user can fill their personal-tag namespace with arbitrarily large entries.
- **Why resolve:** Per-user resource bound; the spec promises per-(user, scope) isolation, and unbounded growth is a per-user DoS surface even though it doesn't leak across users.
- **Why it might not be an issue:** Personal tags are scoped to the user — the blast radius is the user's own data. The DoS only hurts the abuser.
- **Scope hints:** Two literal caps (tag length, tag count per save) read from config; add validation at the system boundary in `SaveCommandHandler`. Low complexity.
- **Verdict:** RESOLVE

### F-SEC-14 — Other security observations (low)

| ID | Title | Locations | Reporters | Verified |
|---|---|---|---|---|
| F-SEC-15 | `ThrottledAdminNotifier` getState logs raw caller-supplied key on read-failure path, defeating the notifier's own line-injection guard | `infochat-core/src/main/java/app/zcat/infochat/core/notify/ThrottledAdminNotifier.java:280-307` | opus-48 (core F1), deepseek (core F3) | yes-by-locus |
| F-SEC-16 | LlmOutputSanitizer closed-list strip uses `Pattern.quote(token)` with literal whitespace; `/invite  create` (two spaces) evades the multi-word entries | `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:87-118,187-209` | opus-48 (provider F4), opus-48-audit (Finding 7), mimo (L10), deepseek (provider F7) | yes (defense-in-depth layer) |
| F-SEC-17 | `canonicalizeHost` uses `IDN.ALLOW_UNASSIGNED` in security-critical SSRF path | `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:273` | mimo (ssrf F3) | yes |
| F-SEC-18 | NOTIFY payload regexes are not anchored — `NewPostListener.parsePayload` + `QuarantineReviewListener.parsePayload` | `infochat-provider/.../NewPostListener.java:311`, `QuarantineReviewListener.java:270` | kimi-k (F7) | yes |
| F-SEC-19 | `AddSourceArgs.parseUri()` accepts userinfo in the URI; credentials stored verbatim in DB | `infochat-collector/.../AddSourceArgs.java:229-248` | kimi-k-audit (3.1) | not-verified |
| F-SEC-20 | `SsrfGuardedHttpClient` follows status 304 as a redirect (304 must not have `Location`, but a non-conformant server could send one) | `infochat-ssrf/.../SsrfGuardedHttpClient.java:340` | kimi-k-audit (2.1) | yes-by-locus |
| F-SEC-21 | `PinnedDial.close()` is not idempotent — double-close throws `IllegalMonitorStateException` | `infochat-ssrf/.../SsrfGuardedHttpClient.java:582-586` | kimi-k-audit (2.2) | yes-by-locus |
| F-SEC-22 | InviteCodeConsumer brute-force counter is per-`(adapter, contact_id)`, not per-invite-code; 10 contacts × 10 attempts = 100 attempts on a single code | `infochat-provider/.../InviteCodeConsumer.java:74-76` | mimo-audit (M6) | yes-by-locus |
| F-SEC-23 | `BootstrapLoader` accepts a path-traversal-shaped config path without canonicalization | `infochat-collector/.../BootstrapLoader.java:105-106,124` | mimo-audit (L5) | yes-by-locus |
| F-SEC-24 | Unicode bidi-control coverage gap — U+061C, U+200E, U+200F not stripped | `infochat-provider/.../InboundRouter.java:962-965`, `infochat-collector/.../Stage1Pipeline.java:283-294` | mimo-audit (L2) | yes-by-locus |
| F-SEC-25 | `Redactor.CATALOGUE` generic pattern allows extended backtracking before the watchdog fires | `infochat-core/.../log/Redactor.java:52-54` | deepseek (core F4), mimo-audit (L3) | yes-by-locus |
| F-SEC-26 | `UrlRedactor` omits brackets around IPv6 addresses | `infochat-ssrf/.../UrlRedactor.java:64` | deepseek (ssrf F3) | not-verified |
| F-SEC-27 | Anthropic error message leaks into exception/log without truncation | `infochat-llm-adapter/.../AnthropicProvider.java:158-164` | mimo-audit (L8) | yes-by-locus |
| F-SEC-28 | Stage1RegexSet allows pathological backtracking on adversarial inputs within the bounded quantifier window | `infochat-collector/.../Stage1RegexSet.java` | kimi-k-audit (2.6) | yes-by-locus |

Each row gets its own short entry below when it warrants more than a one-liner; the rest are RESOLVE-BUNDLED into a single "small-hardening" ticket family.

---

## PERFORMANCE

### F-PERF-01 — Connection-per-step churn in the inbound path (high, PERFORMANCE)

- **Reported by:** opus-47 (per-module-provider F5, HIGH), mimo-audit (M11, MEDIUM), deepseek-audit (PERF-1, LOW)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:300-553`; per-step callers `BanCheck.isBanned`, `ProbationCheck.{inProbation,clearIfPromoted,probationExpiry}`, `GroupApprovalCheck.check`, `GroupAutoPromoteService.tryAutoPromote`, `InviteCodeConsumer.consume`.
- **What's wrong:** A single inbound message acquires 6-9 separate JDBC connections from the pool across the pipeline. Each is short-lived (single PreparedStatement + auto-commit) but the churn is per-message and the Agroal default pool is 20.
- **Why resolve:** opus-47 ranks this HIGH because the inbound path is on every message dispatch and the pool sizing is implicit (mimo's M10). deepseek-audit's falsifier note — that the design deliberately separates concerns so `BanCheck.isBanned` sees the freshest state — applies to the FRESH-BAN-CHECK step only (step 4.5); steps 1, 5 read the same `users` row and could share a connection.
- **Why it might not be an issue:** At v1's RSS-cadence message rate (not real-time chat), the pool handles the churn gracefully. The architectural separation (each check sees its own snapshot) is intentional for the fresh-ban-check correctness invariant. Three reporters disagree on severity: low/medium/high.
- **Scope hints:** Two reasonable shapes — (a) plumb a single connection through the pipeline at the top (the bulk of checks share isolation requirements), keeping the fresh-ban-check as a separate connection; or (b) declare explicit pool sizing (mimo's M10) and accept the churn. Recommend (b) for now and (a) as a separate ticket if M11 ever moves from MEDIUM to HIGH in operational practice.
- **Verdict:** DEFER (declare pool sizing inline as a small process: edit; the structural refactor waits for evidence of pool wait)

### F-PERF-02 — SimpleXAdapter handle table grows unbounded (high, PERFORMANCE)

- **Reported by:** opus-47 (per-module-messaging-adapter F1)
- **Locations:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:88-91,255-256,282-284`
- **What's wrong:** The adapter's per-correlation-id handle table accumulates entries indefinitely as messages flow. No bounded cache, no expiry. A long-running adapter slowly grows memory; an attacker can accelerate it.
- **Why resolve:** Unbounded process memory is a real DoS / availability surface even at RSS cadence. Bounded-size + LRU eviction is the standard fix.
- **Why it might not be an issue:** Single-reporter; no concrete OOM observation. The growth rate per message is small.
- **Scope hints:** Replace the unbounded map with `Caffeine` or a JDK `LinkedHashMap` with access-order eviction. Single file. Low-medium complexity.
- **Verdict:** RESOLVE

### F-PERF-03 — `LlmOutputSanitizer` compiles 26 patterns per call (medium, PERFORMANCE)

- **Reported by:** opus-47 (per-module-provider F9)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:187-209`
- **What's wrong:** The closed-list-strip phase compiles 26 `Pattern.compile` per LLM response.
- **Why resolve:** Patterns are immutable and compilation is the expensive step. Per-instance `static final` pre-compilation is the textbook fix.
- **Why it might not be an issue:** 26 short patterns compile in microseconds; the LLM call itself dominates response time by orders of magnitude.
- **Scope hints:** Move `Pattern.compile` into `static final` fields. Single file. Low complexity.
- **Verdict:** RESOLVE

### F-PERF-04 — `latestPublishedAtEpochSeconds` on every Nostr reconnect lacks supporting index (medium, PERFORMANCE)

- **Reported by:** opus-47 (per-module-collector F5)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java:439-458`; migrations at V7 (`idx_post_source`), V7 (`idx_post_published`).
- **What's wrong:** `SELECT MAX(published_at) FROM post WHERE source_id = ?` runs per relay reconnect; no `(source_id, published_at DESC)` composite index exists. On flapping relays the query runs frequently and degenerates to an index scan over the source's slice or a global descending scan.
- **Why resolve:** Reconnect frequency is not operator-controlled (DNS rebind, peer-IP divergence, generic disconnect). The fix is a single composite index.
- **Why it might not be an issue:** For low-volume sources, both available plans terminate quickly. The pain only surfaces under stress.
- **Scope hints:** New migration adding `CREATE INDEX idx_post_source_published ON post(source_id, published_at DESC);`. Alternative: cache the value in memory inside the StreamSource. Single index + alternative explored. Low complexity.
- **Verdict:** RESOLVE

### F-PERF-05 — Per-call `ExecutorService` spawn in `readBounded` (medium, PERFORMANCE)

- **Reported by:** opus-47 (per-module-ssrf F4), mimo (ssrf perf F1)
- **Locations:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:420-424,471`
- **What's wrong:** A fresh single-thread `ExecutorService` (and an OS platform thread) is created per `get()` call to enforce the per-read watchdog. The wrapper is on every outbound HTTP fetch in both services.
- **Why resolve:** The project targets JDK 25 + Quarkus 3.33 with virtual threads. A `Thread.ofVirtual()` per read makes the spawn essentially free and removes the executor / shutdownNow bookkeeping. Per-instance shared executor is the alternative if virtual threads are not appropriate at this seam.
- **Why it might not be an issue:** Linux thread creation is microseconds; at v1 RSS cadence the cost is real but small.
- **Scope hints:** Static `Thread.ofVirtual().factory()`; replace `submit` + `Future.get` with the same shape over a virtual thread. Single file. Low complexity.
- **Verdict:** RESOLVE

### F-PERF-06 — Per-call `HttpClient` is built and never closed (medium, PERFORMANCE)

- **Reported by:** opus-48-audit (Finding 2)
- **Locations:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:324-327` (inside the redirect loop)
- **What's wrong:** `HttpClient.newBuilder()...build()` builds a fresh client on every call and every redirect hop, and never `close()`s it (JDK 21+ `HttpClient` is `AutoCloseable` and owns a `SelectorManager` daemon thread + a connection pool). Under sustained fetch concurrency this produces thread + FD churn that lags GC, and defeats connection reuse — a fresh TCP+TLS handshake every time, even to the same host.
- **Why resolve:** Steady-state cost + latent FD exhaustion + lost connection reuse on a code path that runs on every outbound fetch.
- **Why it might not be an issue:** Cleaner-based reclamation happens eventually; FD exhaustion is latent rather than active.
- **Scope hints:** Build one `HttpClient` per call and reuse across hops; `close()` in `finally` after `readBounded`. Or instance-level reuse (slightly more invasive). Single method. Low complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-PERF-05 — both touch `readBounded` lifecycle)

### F-PERF-07 — Global SSRF resolver lock held across the full WebSocket handshake (medium, PERFORMANCE)

- **Reported by:** opus-48-audit (Finding 3), opus-47 (synthesizer-relevant observation, ssrf module report)
- **Locations:** `infochat-ssrf/.../SsrfGuardedHttpClient.java:502-517` (`checkAndPinForWebSocket`), `NostrRelayConnection.java:255-266`
- **What's wrong:** `PinnedDnsResolver.Provider` is a JVM-wide singleton; the lock is held for the duration of `buildAsync(...).get(connectTimeout+1s)`. A single slow or stalled relay handshake blocks all outbound connection establishment process-wide for up to ~`CONNECT_TIMEOUT + 1s`.
- **Why resolve:** This couples the slowest relay's connect latency to every unrelated fetcher in the JVM. Multiple Nostr relays in reconnect backoff produce a head-of-line bottleneck.
- **Why it might not be an issue:** Availability concern, not security — correctness (the pin survives the connect) is preserved. v1 RSS cadence may not exercise the bottleneck. Tight `CONNECT_TIMEOUT` bounds the worst case.
- **Scope hints:** Three approaches in increasing scope: (a) document and tune `CONNECT_TIMEOUT` tight; (b) scope the lock to the DNS-resolution window rather than the full handshake (requires confirming JDK's `buildAsync` ordering); (c) replace JVM-global resolver with per-`HttpClient` resolver (largest change, removes the global serialization). Pick by operational evidence.
- **Verdict:** DEFER (option (a) inline; (b)/(c) wait for symptom)

### F-PERF-08 — Other performance observations (low)

| ID | Title | Locations | Reporters | Verdict |
|---|---|---|---|---|
| F-PERF-09 | Unbounded `BodyHandlers.ofString()` in LLM provider HTTP calls — multi-GB response can OOM the JVM | `infochat-llm-adapter/.../OpenAiCompatibleProvider.java:189`, `AnthropicProvider.java:148`, `OpenAiCompatibleEmbeddingProvider.java` | mimo-audit (M1) | RESOLVE |
| F-PERF-10 | Body-read deadline TOCTOU — `bodyReadDeadline` check fires at top of loop, then `Future.get(readTimeout)` can overshoot by up to one full `readTimeout` | `infochat-ssrf/.../SsrfGuardedHttpClient.java:430-441` | mimo-audit (M2) | RESOLVE |
| F-PERF-11 | `HttpClient.newHttpClient()` instances lack default connect/request timeouts | `ProductionAdapterBeans.java:138`, `NostrStreamSource.Registrar.java:282` | kimi-k (F9) | RESOLVE |
| F-PERF-12 | `TreeMap` allocation on every `ChatToolDispatcher` cache-key | `infochat-provider/.../chat/ChatToolDispatcher.java:116-117` | deepseek-audit (PERF-2) | DEFER (LOW) |
| F-PERF-13 | DigestScheduler missed-slot atomicity gap (audit-log INSERT commits before sentinel cache + admin notify) | `infochat-provider/.../digest/DigestScheduler.java:130-158` | deepseek (provider F4), mimo-audit (M9) | RESOLVE |
| F-PERF-14 | `FetchScheduler.enumerateActiveSources` + `DigestScheduler.queryActiveGroups` load full result sets with no LIMIT | (named files) | mimo-audit (L18) | DEFER |
| F-PERF-15 | No explicit connection-pool sizing in either application.properties | both `application.properties` | mimo-audit (M10) | RESOLVE (one-line per profile) |
| F-PERF-16 | Levenshtein recomputes distances in `GroupTimezoneCommandHandler.parseTimezone` filter | `infochat-provider/.../GroupTimezoneCommandHandler.java:206-210` | mimo-audit (L17) | DEFER |
| F-PERF-17 | `DigestScheduler` issues 2×N DB queries per tick (one per group per slot) | `infochat-provider/.../DigestScheduler.java` | mimo-audit (L34) | DEFER |
| F-PERF-18 | `NostrDedupFilter` carries 10K entries per source × ~1MB each | (named file) | mimo-audit (L35) | DEFER |
| F-PERF-19 | No 429/503/Retry-After handling in LLM providers | `OpenAiCompatibleProvider.java`, `AnthropicProvider.java` | mimo-audit (M8) | RESOLVE-BUNDLED |

---

## MAINTAINABILITY-RULES-DRIFT

### F-MAINT-01 — No `_202606` partitions exist for any partitioned table (critical, DATA)

- **Reported by:** mimo-audit (C1) — single-reporter but immediately verifiable
- **Locations:** `infochat-core/src/main/resources/db/migration/V7__joins_post.sql`, `V11__post_embedding.sql`, `V17__price_snapshot.sql`, `V28__post_entity.sql`, `V29__post_reference.sql` — each carries only a `_202605` partition with `FROM ('2026-05-01') TO ('2026-06-01')`.
- **What's wrong:** Today is 2026-06-02. The first INSERT with `fetched_at >= 2026-06-01` (which is every new post) fails with `no partition of relation … found for row`. The Collector cannot persist new posts; the entire ingest pipeline is dead until a partition is added.
- **Why resolve:** Immediate hard production failure on first INSERT. The spec mentions an "application-tier partition scheduler" but no such scheduler exists in the codebase (verified by grep — no `@Scheduled` partition creator).
- **Why it might not be an issue:** Only if the deployment has been idle since 2026-05-31 and no new posts have been attempted. If `mvn verify` was run successfully recently, IT containers are spun up fresh per test so they don't hit the boundary; production-shaped deployments do.
- **Verified against code:** YES — `grep` returned exactly five `PARTITION OF` migrations and exactly five `2026-05-01 → 2026-06-01` ranges. No June or July partition anywhere.
- **Scope hints:** Two parts:
  1. **Immediate:** new migration `V30__partitions_202606_202607.sql` creating `_202606` and `_202607` partitions for all five tables.
  2. **Long-term:** `@Scheduled` partition-creation bean that runs monthly and provisions the next month's partition; CHECK constraint on the cadence; alarm if it hasn't run in 25 days.
- **Verdict:** RESOLVE (immediate); the scheduler is a separate follow-up ticket

### F-MAINT-02 — `InstanceLockGuard` heartbeat is written once at startup, never refreshed (critical, CONCURRENCY)

- **Reported by:** mimo-audit (C2)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/startup/InstanceLockGuard.java:84,175-188`; same shape in `infochat-provider/src/main/java/app/zcat/infochat/provider/startup/InstanceLockGuard.java`.
- **What's wrong:** The advisory lock is held via a long-lived JDBC connection (`heldConnection.setAutoCommit(true)`), but the heartbeat row is written once at startup and never updated. If the held connection dies silently (server restart, TCP keepalive loss, network partition, `idle_in_transaction_session_timeout`, NAT timeout), the advisory lock is released server-side while the JVM continues running as a zombie. A second instance can acquire the lock and run alongside the zombie — the single-instance invariant (D41) is broken.
- **Why resolve:** D41 ("exactly one Collector / exactly one Provider per deployment") is the foundation for the advisory-lock-driven safety properties: the outbox rehydrator's claim that no two workers race for a queue, the FetchScheduler's per-tick advisory-locked enumeration, etc.
- **Why it might not be an issue:** In a healthy network with stable TCP and no idle-timeout policy, the connection is held indefinitely and the heartbeat-once is sufficient. The risk is silent under those conditions and acute under the failure modes above.
- **Verified against code:** YES — `@Startup` only, no `@Scheduled` refresh method. The `upsertHeartbeat` call is at line 84 with no other invocation site.
- **Scope hints:** `@Scheduled(every = "30s")` method that (a) verifies the held connection is alive via `SELECT 1`, (b) `pg_try_advisory_lock` to re-verify the lock is still held (returns true if the same session holds it), (c) refreshes the heartbeat row, (d) calls `Quarkus.asyncExit(1)` if any of those fail. Set TCP keepalives on the held connection. Bundled with deployment-doc updates. Medium complexity; recommend a plan-writer pass.
- **Verdict:** RESOLVE

### F-MAINT-03 — `infochat.reeval.*` config keys are declared only in test resources (critical, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-collector F1)
- **Locations:** `infochat-collector/src/main/resources/application.properties` (missing); referenced from `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:74-86`, `PerSourceUnknownTracker.java:41-50`, `AdminReviewTtlJob.java:51-57`.
- **What's wrong:** Nine `infochat.reeval.*` keys live ONLY in `src/test/resources/application.properties`. The consuming `@ConfigProperty(name = "…")` declarations carry no `defaultValue`. The `@Scheduled(every = "{infochat.reeval.poll-interval}")` expression fails earlier at scheduler-config-parse. Collector startup in any of the four operator profiles (`laptop`, `vps`, `pi`, `remote-llm`) fails before the first tick.
- **Why resolve:** The entire re-evaluation policy (`docs/spec/security.md` §Re-evaluation job) is dead until the keys land in main config. Production startup is the immediate blocker.
- **Why it might not be an issue:** Only the test profile (`@QuarkusTest` picks `%test.*`) boots successfully today. If the Collector is never run outside `mvn quarkus:dev` (which inherits `%dev.*`), the missing keys may have been silently rescued by a profile-specific override — but `%dev.*` does NOT carry `infochat.reeval.*` either.
- **Verified against code:** YES — `grep` found `infochat.reeval.*` keys only in `infochat-collector/src/test/resources/application.properties`. The `ReEvaluationJob.java` `@ConfigProperty` declarations carry no `defaultValue` except `batch-size`.
- **Scope hints:** Add the nine keys to `infochat-collector/src/main/resources/application.properties` with profile overrides per `docs/design/04-security.md`. Plus a CI guard that asserts every `@ConfigProperty(name = "infochat.*")` has a base declaration in main config (separate ticket). Single file edit. Low-medium complexity.
- **Verdict:** RESOLVE

### F-MAINT-04 — Hardcoded `/zcash`, `/monero` `CommandHandler`s break `bootstrap-assets.json` extensibility (critical, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-provider F2)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java:24-55`
- **What's wrong:** `AssetCommandRouter` declares two static inner `CommandHandler` beans — `ZcashCommandHandler` returning `"zcash"` and `MonerorCommandHandler` returning `"monero"`. Any third asset added to `bootstrap-assets.json` is loaded into `price_snapshot`/`asset_config` correctly, but the slash dispatcher has no `CommandHandler` bean for it — `InboundRouter` returns "Unknown command". Probation gate is also evaded (a probation user passes the gate then receives the "Unknown command" reply).
- **Why resolve:** The asset family is the explicit operator-config-driven extensibility surface in the spec. Constraining it to the two assets the AssetCommandRouter knows about means every new asset is a code change. `CommandHandler` discovery is already CDI-driven; the fix is a `Producer` that emits one bean per registered asset.
- **Why it might not be an issue:** The probation-gate leak is an interesting falsifier — the gate runs BEFORE the dispatcher, so the user reaches the dispatcher only if they pass the gate. Whether "passing probation then getting Unknown command" is a substantive harm vs an "asset not yet wired" diagnostic is policy.
- **Verified against code:** YES — `AssetCommandRouter` has two anonymous `@Singleton` inner classes; `grep -n` confirms only "zcash" and "monero" are returned from `name()`.
- **Scope hints:** Replace the two hardcoded beans with a `@Produces @Dependent List<CommandHandler> producesAssetHandlers(AssetRegistry registry, AssetHandler shared)` that iterates the registered assets and emits one wrapper per asset. Single file (`AssetCommandRouter`) + tests covering a third asset added at runtime. Medium complexity.
- **Verdict:** RESOLVE

### F-MAINT-05 — `NEEDS_REVIEW` cap-exhaustion transition is unreachable in production (critical, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-collector F2)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:107-112,282-293`
- **What's wrong:** `enumerateCandidates` filters `re_eval_attempts < cap`, so a row with `re_eval_attempts == cap` never enters `processOne`, and the `transitionToNeedsReview` early-return branch never fires. Rows stay `QUARANTINED` forever, the spec-mandated `NEEDS_REVIEW` transition never happens, the throttled admin notification never fires. The test passes only because it bypasses `enumerateCandidates` and hand-constructs a cap-exceeded candidate.
- **Why resolve:** `docs/spec/security.md` §Re-evaluation job: "After cap exhaustion the post transitions to NEEDS_REVIEW." The operator alerting commitment ("Throttled NEEDS_REVIEW notifications") depends on this transition firing.
- **Why it might not be an issue:** Single-reporter. The opus-47 detail report explicitly walks through the cap-loop: a post climbs from 0 to `cap−1` over `cap−1` ticks, then `incrementAttemptCounter` advances to `cap` on a verdict, then the post is excluded from re-enumeration forever. That progression matches the cited SQL.
- **Scope hints:** Drop the `re_eval_attempts < ?` predicates from the enumerate SQL; let `processOne`'s in-process cap check (which already exists at line 109) drive the transition. Plus an IT that exercises the full scheduled path on a seeded cap-exceeded row. Single file + new test. Low complexity once understood; recommend a plan-writer pass because the right-shaped fix depends on the eval-pipeline invariants.
- **Verdict:** RESOLVE

### F-MAINT-06 — DigestScheduler queries all non-removed groups, not just approved ones (high, RULES-DRIFT)

- **Reported by:** opus-48 (per-module-provider F1), mimo (per-module-provider F1) — both HIGH
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java:175`; integration test at `DigestRoundtripIT.java:321-336`
- **What's wrong:** `SELECT id, timezone FROM groups WHERE removed_at IS NULL` — no `approval_status = 'approved'` filter. Pending or rejected groups receive periodic digests. The digest integration test only seeds approved groups, so the missing filter has no fixture row whose delivery would fail.
- **Why resolve:** Per `docs/spec/commands.md` and group-approval semantics, only approved groups should receive digests. Pending groups have not yet been admitted to the deployment; rejected groups have been explicitly denied. Both should be silent.
- **Why it might not be an issue:** Two reporters convergent; both flagged HIGH; the locus exact-matches and the test gap is verified. No falsifier survives.
- **Verified against code:** YES — `grep -rn "FROM groups"` returned the SELECT verbatim at DigestScheduler.java:175.
- **Scope hints:** Add `AND approval_status = 'approved'` to the SELECT. Add a fixture group with `approval_status = 'pending'` to `DigestRoundtripIT` and assert no delivery. Single file change + test fixture. Low complexity.
- **Verdict:** RESOLVE

### F-MAINT-07 — `ReadyPromoter.promoteOne` is `@Transactional` but reached via self-invocation, breaking same-tx NOTIFY (high, RULES-DRIFT)

- **Reported by:** opus-48 (per-module-collector F1) HIGH, mimo-audit (L4) LOW
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java:114, 122-130, 143-192`
- **What's wrong:** `promoteOne` is annotated `@Transactional` but reached only by self-invocation from the `@Scheduled` `tick()` (CDI interceptor does NOT fire on self-invocation — Quarkus-ARC builds the proxy at the bean boundary, not for in-class calls). The UPDATE and `pg_notify` therefore run as two separate auto-commits. The IT masks the gap because it calls `promoteOne` directly through the CDI proxy.
- **Why resolve:** The same-transaction NOTIFY guarantee is a documented atomicity property. Any future second mutation in `promoteOne` gets silent non-atomicity. mimo-audit's milder framing (LOW) acknowledges the issue but underweights the implication.
- **Why it might not be an issue:** Today `promoteOne` has only one DB write + one NOTIFY, so the non-atomicity is observable only on crash exactly between them. Single-statement-followed-by-NOTIFY is the minimal failure window.
- **Scope hints:** Two fixes — (a) move the @Transactional method to a separate bean and inject it, so the call goes through the proxy; or (b) manage the transaction explicitly within `promoteOne` (`conn.setAutoCommit(false)` + commit on success). Plus updating the IT to invoke through `tick()` not direct. Single file. Medium complexity.
- **Verdict:** RESOLVE

### F-MAINT-08 — `approve_quarantine` and `reject_quarantine` do not fire `quarantine_review` NOTIFY (high, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-architecture F2)
- **Locations:** `infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:46-65, 67-104`
- **What's wrong:** The two SECURITY DEFINER procedures update `quarantine.status` to `APPROVED` / `REJECTED` and write the audit row, but emit no `pg_notify('quarantine_review', …)`. Every other writer in the codebase (Stage1, Stage2VerdictHandler, ReEvaluationJob, AdminReviewTtlJob) goes through `QuarantineNotifyEmitter`. The two procedures are the only writers that skip the channel.
- **Why resolve:** `docs/spec/architecture.md` §Inter-service communication commits the channel to firing on `PENDING insert / BENIGN_CLOSED / APPROVED / REJECTED / NEEDS_REVIEW`. The Provider's `QuarantineReviewListener.handleEvent` advances the cursor unconditionally; APPROVED/REJECTED rows never get NOTIFY, so the cursor stays pinned to the last non-admin-driven event. On Provider restart the reconciler replays everything past that cursor — including weeks-old APPROVED/REJECTED rows.
- **Why it might not be an issue:** Cursor over-replay is harmless (the rows are non-actionable; the reconciler advances through them without effect), so the practical impact today is bandwidth + bootstrap latency on restart, not correctness. The spec's stated v2 rationale ("v2 code attaching a side effect to APPROVED via the channel would silently never fire") is forward-looking.
- **Scope hints:** New migration `V30__quarantine_review_notify.sql` with `CREATE OR REPLACE FUNCTION` redeclarations adding `PERFORM pg_notify('quarantine_review', jsonb_build_object(…)::text);` to both procedures. Single migration. Low-medium complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-MAINT-09 PENDING-NOTIFY-at-wrong-stage and F-MAINT-10 NOTIFY-string-concat — all touch the quarantine_review channel)

### F-MAINT-09 — Stage 2 `emitQuarantineNotifyForPendingRows` fires PENDING at the wrong stage (high, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-collector F4)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:269-281`, `QuarantineDao.java:66-90`
- **What's wrong:** Spec says `quarantine_review` PENDING fires on row insert. Current code defers PENDING NOTIFY until Stage 2 returns and emits PENDING for every row still in PENDING status on every Stage 2 quarantine verdict. So Stage 1 → Stage 2 BENIGN never fires PENDING (the row is BENIGN_CLOSED before the emit-PENDING-rows SELECT runs); Stage 1 → Stage 2 INJECTION/MALWARE/UNKNOWN fires PENDING redundantly per verdict.
- **Why resolve:** Aligning code with the spec's "PENDING insert" wording moves the emit to `QuarantineDao.insert`, which is the row-creation site. Removes the redundant signal. Closes the BENIGN-fast-path gap.
- **Why it might not be an issue:** The Provider's cursor advances on `reviewed_at` (the quarantine row's `updated_at`); the redundant signals are bandwidth waste, not duplicate side effects. The BENIGN-fast-path gap is only observable to a Provider that wants to see the transient PENDING state — and the spec doesn't require that observation.
- **Scope hints:** Move PENDING NOTIFY into `QuarantineDao.insert` (add `RETURNING id` + emit inside the same Stage 1 transaction). Remove `emitQuarantineNotifyForPendingRows` from `Stage2VerdictHandler.applyQuarantineVerdict`. Plumb `QuarantineNotifyEmitter` into the DAO (modest layering change). Medium complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-MAINT-08)

### F-MAINT-10 — `QuarantineNotifyEmitter` builds JSON payload by string concat without escaping (medium, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-architecture F3), mimo (per-module-collector F1 SECURITY), deepseek (per-module-collector F5)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:39-43`
- **What's wrong:** The emitter accepts `String targetKind` and `String newStatus` and concatenates them into a JSON template without escaping. The spec constrains both to closed enums, so today no caller passes an unsafe value — but the SPI shape (`String`, not `enum`) doesn't enforce the constraint, and `PriceSnapshotStore` (a peer NOTIFY emitter on the same architecture surface) DOES escape, creating internal inconsistency.
- **Why resolve:** Tighten the emitter signature to closed enums; the type system then enforces the contract, removing the asymmetry with `PriceSnapshotStore` and pre-empting future-caller hazards. The Provider-side parser is also regex-based; aligning producer and parser in the same diff is cleaner.
- **Why it might not be an issue:** Single-reporter for the "enum-ify the SPI" framing; convergent with mimo + deepseek as a "JSON escape consistency" issue. The current concatenation is safe by caller discipline. §7 (no defensive code) doesn't require escaping for impossible inputs.
- **Scope hints:** `enum QuarantineNotifyKind {quarantine, post}` + `enum QuarantineNotifyStatus {PENDING, BENIGN_CLOSED, APPROVED, REJECTED, NEEDS_REVIEW}`; emitter signature switches to these; update four call sites. Medium complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-MAINT-08, F-MAINT-09 — quarantine_review channel family)

### F-MAINT-11 — `NormalizedPost.sourceId` javadoc contradicts the runtime value (high, RULES-DRIFT)

- **Reported by:** mimo (per-module-core F1)
- **Locations:** `infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java:17-41`, `Fetcher.java:33`, `StreamSource.java:37`
- **What's wrong:** The record's javadoc describes `sourceId` as a database key; the runtime value is a per-startup `dispatchKey` (an opaque per-process integer assigned at `FetchScheduler.enumerateActiveSources` time, see F-MAINT-12 — actually per-TICK, not per-startup, which is itself a separate bug). A Fetcher/StreamSource implementor reading the javadoc assumes they receive a stable database id and may build state keyed on it.
- **Why resolve:** Misleading SPI contract on a public type. The cost is documented confusion → buggy impls.
- **Why it might not be an issue:** Current impls don't depend on stability (the Fetcher SPI documents `sourceId` as opaque), so the disagreement is documentation-only today.
- **Scope hints:** Rewrite the javadoc to match the actual contract ("opaque per-tick token, do not key state on it"). Plus the F-MAINT-12 fix that aligns the comment with intent. Single file. Low complexity.
- **Verdict:** RESOLVE (bundled with F-MAINT-12 since they touch the same `dispatchKey` surface)

### F-MAINT-12 — `FetchScheduler.dispatchKey` is per-tick, not per-startup (low, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-collector F9)
- **Locations:** `infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java:408-419`; record javadoc on `SourceRow`.
- **What's wrong:** The javadoc says "monotonically-assigned per-startup token" but the implementation is `long dispatch = 1L` LOCAL to `enumerateActiveSources()`, reset per call. Between ticks the same source can receive different dispatch keys.
- **Why resolve:** Either fix the implementation to match the comment, or fix the comment to match the implementation. The implementation is the source of truth; fix the comment.
- **Why it might not be an issue:** Downstream Fetcher impls document `sourceId` as opaque, so no current code depends on stability.
- **Scope hints:** Javadoc edit; rename the field if helpful. Single file. Trivial complexity.
- **Verdict:** RESOLVE (bundled with F-MAINT-11)

### F-MAINT-13 — `ChatToolDispatcher` catches only two exception types, swallowing `ClassCastException`/`DateTimeParseException` (medium, RULES-DRIFT)

- **Reported by:** opus-48-audit (Finding 4), opus-47 (provider F11 — Duration.parse throws past the filter)
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java:137-145`; `SearchPostsTool.java:46-48` (`Duration.parse`)
- **What's wrong:** `try { tool.execute(...) } catch (IllegalArgumentException e) { ValidationError } catch (SQLException e) { rethrow }`. `ClassCastException` from F-SEC-11's parser bug and `DateTimeParseException` from `Duration.parse` are not in the filter; they bubble up to `ChatAgent.handle`'s generic `catch (Exception)` and surface as `ERROR_CHAT_UNAVAILABLE`. The LLM gets no structured feedback to retry with corrected arguments.
- **Why resolve:** The dispatcher is the right boundary for "the LLM produced a malformed tool call" — it already validates lengths and clamps limits. Translating type/parse failures into `ToolResult.ValidationError` lets the model self-correct.
- **Why it might not be an issue:** Two reporters convergent. The only thing blocking the fix is deciding which exception types to add — `ClassCastException`, `DateTimeParseException`, `NumberFormatException`.
- **Scope hints:** Widen the catch and translate to `ValidationError`. Single file. Coordinated with F-SEC-11. Low complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-SEC-11)

### F-MAINT-14 — `IpBlocklist` M1-025 compatibility-shim constructor (high, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-ssrf F1)
- **Locations:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java:78-87`
- **What's wrong:** The `IpBlocklist(Set<InetAddress>)` constructor is explicitly labeled "M1-025 test-mode constructor — preserved as an overload so M1-025 tests pass unchanged." `engineering-rules-verbatim.md` §7 verbatim: "Feature flags and backwards-compatibility shims are forbidden when the change can simply be made. M1 is a greenfield build; there is no prior version to be compatible with."
- **Why resolve:** The shim self-identifies as the prohibited pattern. The two test callsites are in the same module; rewriting them to `new IpBlocklist(() -> Set.of(hostIp))` is a one-line change per site.
- **Why it might not be an issue:** Single-reporter. The shim is harmless at runtime — but the rule prohibits it on shape grounds, not behavior.
- **Scope hints:** Delete the `Set<InetAddress>` overload; rewrite the two test callsites in `IpBlocklistTest.java:150,160` to the Supplier form. Single file + one test file. Trivial complexity.
- **Verdict:** RESOLVE

### F-MAINT-15 — Anthropic `extractErrorMessage` silently swallows `Exception` (medium, RULES-DRIFT+SECURITY)

- **Reported by:** opus-48 (per-module-llm-adapter F2, MEDIUM), kimi-k (F5, MEDIUM), mimo (per-module-llm-adapter F2, LOW), deepseek (per-module-llm-adapter F2, MEDIUM)
- **Locations:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:195-205` (or :201 per the count)
- **What's wrong:** `catch (Exception ignored)` swallows `OutOfMemoryError`-class subclasses *only when wrapped*, but more concretely swallows runtime exceptions that the diagnostic method should propagate (`NullPointerException`, `IllegalStateException`). The legitimate failure is `IOException` from `JSON.readTree`; everything else is a programming error and should not be swallowed.
- **Why resolve:** Four reporters convergent on the same locus. The narrow `catch (IOException)` is the one-line correct fix.
- **Why it might not be an issue:** `extractErrorMessage` is a diagnostic helper; broad catches reduce the chance of cascading failures into the LLM call path. The trade-off is real but the consensus says narrow.
- **Scope hints:** Replace `Exception` with `IOException`. Single line. Trivial complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-SEC-01, F-PERF-09 — Anthropic family)

### F-MAINT-16 — Embedding provider silently breaks the SPI's size-equals-input contract (high, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-llm-adapter F3)
- **Locations:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java:162-201`
- **What's wrong:** `EmbeddingProvider.embed` javadoc explicitly states "size equals texts.size()". The implementation returns a mismatched-size list with only a WARN log when the provider returns fewer/more elements. A caller that trusts the SPI contract zip-indexes vectors with input texts and silently mis-attributes post-A's vector to post-B.
- **Why resolve:** The SPI contract is load-bearing. The spec at `docs/spec/llm.md` §Embedding pipeline mandates per-batch retry on shape mismatch; detection belongs at the SPI seam, not at every caller.
- **Why it might not be an issue:** No current caller actually depends on size equality (the EmbeddingWorker uses index correlation). But the contract is the contract.
- **Scope hints:** Replace the WARN log with `throw new EmbeddingCallFailedException(…)` when `results.size() != expectedCount`. Single file. Trivial complexity.
- **Verdict:** RESOLVE

### F-MAINT-17 — `EmbeddingResult` exposes a mutable array via a record value type (high, RULES-DRIFT)

- **Reported by:** opus-47 (per-module-llm-adapter F4 HIGH), mimo (per-module-llm-adapter F1 MEDIUM)
- **Locations:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingResult.java:14`
- **What's wrong:** `public record EmbeddingResult(float[] vector) {}`. Records auto-generate `equals`/`hashCode` using reference equality on arrays — two identical embeddings are NOT equal. Accessor `vector()` returns the live array reference; any caller can mutate the held value.
- **Why resolve:** Textbook record-with-array hazard. The wrapper's only justification (per its own javadoc) is forward-compatibility; that justification doesn't survive broken equality + shared-reference mutability.
- **Why it might not be an issue:** No current caller uses `equals` on EmbeddingResult or mutates the returned array. The hazard is latent.
- **Scope hints:** Either (a) drop the wrapper and expose `List<float[]>` from the SPI, or (b) defensive-copy on construction + accessor + Arrays.equals/hashCode overrides. (a) is the simpler fix per opus-47's recommendation. Single file. Low complexity.
- **Verdict:** RESOLVE

### F-MAINT-18 — Other RULES-DRIFT findings (medium and low)

| ID | Title | Locations | Reporters | Verdict |
|---|---|---|---|---|
| F-MAINT-19 | `/help` ignores spec-promised per-tier filtering | `infochat-provider/.../HelpCommandHandler.java:46-74` | opus-47 (provider F4) | RESOLVE |
| F-MAINT-20 | `/stop` is a no-op in group scope and uses the wrong scope key in DM | `infochat-provider/.../StopCommandHandler.java:62-69,97-100` | opus-47 (provider F3) | RESOLVE |
| F-MAINT-21 | Multi-line TOOL_CALL leaks JSON arguments to user (strip pattern missing DOTALL) | `infochat-provider/.../chat/ChatAgent.java:49-51,159-160` | mimo-audit (H2) | RESOLVE |
| F-MAINT-22 | Signal inbound handler exception kills JSON-RPC reader thread; adapter half-dead | `SignalJsonRpcClient.java:433`, `SignalGroupHandler.java:167` | mimo-audit (H1) | RESOLVE |
| F-MAINT-23 | `SignalConfig.validate()` provides a misleading boot-time guarantee (post-boot filesystem changes defeat the check) | `SignalConfig.java:63-79` | deepseek (messaging F1) | RESOLVE |
| F-MAINT-24 | `MessagingAdapter.onMembershipEvent` is a confused SPI method (creates two incompatible dispatch shapes) | `MessagingAdapter.java:163-174` | opus-47 (messaging F2), opus-48 (messaging F4) | RESOLVE |
| F-MAINT-25 | "Adapter not connected" classifies inconsistently between Signal (TRANSIENT) and SimpleX (PERMANENT) | `SignalAdapter.java:330-338`, `SimpleXAdapter.java:348-355` | opus-47 (messaging F5) | RESOLVE |
| F-MAINT-26 | Adapter codec validators raise `IllegalStateException`/`IllegalArgumentException` past the `throws MessagingException` contract | `SimpleXAdapter.java:178-183`, `SimpleXMessageCodec.java:226-232` | opus-47 (messaging F4) | RESOLVE |
| F-MAINT-27 | Adapter capability-flag drift from design notes (`supportsTypingIndicator`, `supportsCodeFormatting`) | `SimpleXAdapter.java:64-78,288-298`, `SignalAdapter.java:70-84`, `InMemoryAdapter.java:61` | opus-47 (messaging F3, F7), opus-48 (messaging F4), mimo (messaging F1, F2, F3) — severity disagreement low↔high | RESOLVE |
| F-MAINT-28 | `MessagingException` public constructors lack `@NonNull`/`@Nullable` annotations | `MessagingException.java:22-30` | opus-47 (messaging F6) | RESOLVE-BUNDLED (with F-MAINT-44) |
| F-MAINT-29 | SECURITY DEFINER procedures drop the spec-mandated `actor_contact_id`/`actor_adapter` columns from audit_log INSERT | `V24__identity_audit_remediation.sql:44-53`, `V25__quarantine_procedure_remediation.sql:58-60,100-101` | opus-47 (core F1) | RESOLVE |
| F-MAINT-30 | V21/V25 `pg_notify` payload built by raw `||` concat | `V21__quarantine_admin.sql:74-75`, `V25__quarantine_procedure_remediation.sql:62-63` | opus-47 (core F4) | RESOLVE-BUNDLED (with F-MAINT-10) |
| F-MAINT-31 | V16 grant-block comment hard-codes a pre-relocation wiring assumption (ThrottledAdminNotifier was relocated to infochat-core) | `V16__admin_notification_state.sql:67-73` | opus-47 (core F3) | RESOLVE |
| F-MAINT-32 | V7 grant-block comment references an `infochat_listen` role that V2 never creates (design 02-schema.md §DB roles enumerates it too) | `V7__joins_post.sql:212-214`, `docs/design/02-schema.md:26` | opus-47 (core F2) | RESOLVE |
| F-MAINT-33 | SsrfGuardedHttpClient class javadoc claims ws/wss are rejected while the same class supports them | `SsrfGuardedHttpClient.java:33-82, 119-121, 502-538` | opus-47 (ssrf F5), opus-48 (ssrf F2), mimo (ssrf F2), deepseek-audit (SEC-2) | RESOLVE |
| F-MAINT-34 | `HostInterfaceSet` javadoc describes the abandoned construction-time-snapshot semantics; M1-026 replaced it with a per-call Supplier | `HostInterfaceSet.java:21-26` | opus-48 (ssrf F3), deepseek-audit (SEC-2) | RESOLVE-BUNDLED (with F-MAINT-33) |
| F-MAINT-35 | `LangCommandHandler`/`FollowTagCommandHandler` javadoc describes a "group scope not in v1" short-circuit the body no longer implements | `LangCommandHandler.java:37-48`, `FollowTagCommandHandler.java:37-41` | opus-48 (provider F5) | RESOLVE |
| F-MAINT-36 | DAG documentation in `docs/design/09-reference.md` §9.1 disagrees with the actual sibling-module poms (claims `infochat-ssrf`/`infochat-llm-adapter`/`infochat-messaging-adapter` depend on `infochat-core` — they don't) | `docs/design/09-reference.md:31-38` | opus-47 (arch F4), deepseek (arch F1) | RESOLVE |
| F-MAINT-37 | `AdapterRegistry` parses `infochat.adapters` CSV without duplicate-name detection (a stutter `simplex,simplex` wires the same adapter twice) | `AdapterRegistry.java:150-159` | opus-47 (arch F5) | RESOLVE |
| F-MAINT-38 | Chat-mode body cap runs after DB writes the spec forbids for oversized messages | `InboundRouter.java:509-543` | opus-48 (provider F2) | RESOLVE |
| F-MAINT-39 | `summary_anchor` omits the `scope_kind` discriminator carried by every other per-(user, scope) table | `V19__summary_anchor.sql:5-30` | opus-48 (core F2) | RESOLVE |
| F-MAINT-40 | V27 writes an `audit_log.action` verb that is absent from the `AuditAction` closed set | `V27__d47_remove_group_only.sql:51-52` | opus-48 (core F3) | RESOLVE |
| F-MAINT-41 | `finalize` SPI method on `MessagingAdapter` shadows `Object.finalize()` | `MessagingAdapter.java:125` | opus-48 (arch F2) | RESOLVE (rename to `shutdown`/`stop`) |
| F-MAINT-42 | `onMembershipEvent` default SPI method is dead surface (no wired producer/consumer pair) | `MessagingAdapter.java:162-174` | opus-48 (messaging F4) | DROP or RESOLVE — single-reporter, falsifier: spec D47 commitment may require it; reconcile design |
| F-MAINT-43 | `MicroProfileConfigReader.get` silently converts `s.toLowerCase().equals("null") → ""` with no comment | `LlmRouter.java:399` | opus-47 (llm-adapter F5), deepseek (llm-adapter F5) | RESOLVE |
| F-MAINT-44 | Widespread missing JSpecify `@NonNull`/`@Nullable` annotations on public method parameters | cross-cutting (kimi-k F2 lists 5 representative samples; opus-47 LlmRouter F7; opus-47 messaging F6; deepseek ssrf F1, llm-adapter F4) | kimi-k (F2 HIGH), opus-47 (llm-adapter F7), opus-47 (messaging F6), deepseek (ssrf F1, llm-adapter F4) | RESOLVE-BUNDLED (single retroactive lint-pass ticket) |
| F-MAINT-45 | Defensive null-checks on internal trust boundaries (LlmRouter, SSRF, OpenAiCompatibleProvider, AssetSnapshotFetcher, multiple provider handlers) | (many cited files) | opus-47 (llm-adapter F6), opus-48 (ssrf F4, llm-adapter F3, F4, collector F3), deepseek (provider F2) | RESOLVE-BUNDLED (single sweep) |
| F-MAINT-46 | `LlmRouter.providerName` couples router to concrete impls via `instanceof` chain | `LlmRouter.java:298-315` | opus-47 (llm-adapter F8) | RESOLVE |
| F-MAINT-47 | `findFirstString` in SimpleXMessageCodec does an attacker-influenced key search instead of reading the known field | `SimpleXMessageCodec.java:520-582` | opus-48 (messaging F3 SIMPLIFICATION) | RESOLVE |
| F-MAINT-48 | Kind-6 repost edge `to_post` is never resolvable to a real post (deterministic UUID v3 of event-id vs random UUID on persisted post) | `Kind6Handler.java:142-153,164-167`, `PostPersister.java:108-119`, `GetReferencesTool.java:67-80` | opus-47 (collector F3) | RESOLVE |
| F-MAINT-49 | Signal group handler duplicates DM decode logic and has no wired producer | `SignalGroupHandler.java:103-168`, `SignalMessageCodec.java:132-157`, `SignalJsonRpcClient.java:412-434` | opus-48 (messaging F2) | RESOLVE |
| F-MAINT-50 | Oversize-line character-at-a-time drain in SignalJsonRpcClient reader loop | `SignalJsonRpcClient.java:87,326-370` | deepseek (messaging F3) | RESOLVE |
| F-MAINT-51 | `SimpleXConfig.validate()` is never called for idle adapters | `SimpleXConfig.java:73-88` | deepseek (messaging F2), mimo-audit (L14) | RESOLVE |
| F-MAINT-52 | Asset-command tokens lowercased with the JVM-default locale | `AssetHandler.java:156,160` | opus-47 (provider F13) | RESOLVE |
| F-MAINT-53 | InfochatProfile duplicated between collector + provider; doc explicitly notes intent to consolidate into infochat-core | `infochat-{collector,provider}/.../config/InfochatProfile.java` | mimo-audit (L11) | RESOLVE |
| F-MAINT-54 | `InstanceLockGuard` duplicated byte-for-byte between collector + provider | `infochat-{collector,provider}/.../startup/InstanceLockGuard.java` | kimi-k (F4) | RESOLVE (bundled with F-MAINT-02 heartbeat fix) |
| F-MAINT-55 | NewPostListener does not re-run reconciler after a transient reconnect; permanently lost NOTIFYs until restart | `NewPostListener.java:164-211` | mimo-audit (M3) | RESOLVE |
| F-MAINT-56 | No signal-cli hung-process detection (`Process.onExit` doesn't fire if the process deadlocks) | `SignalSubprocess.java` | mimo-audit (M4) | DEFER |
| F-MAINT-57 | SimpleXWebSocketClient `sendCommand` race with `close()` throws raw RuntimeException past the catch clauses | `SimpleXWebSocketClient.java:162-198` | mimo-audit (M7) | RESOLVE |
| F-MAINT-58 | DigestWorker has no concurrency guard for same-group duplicate processing (Tick overrun) | `DigestWorker.java:69-75` | mimo-audit (M9) | RESOLVE |
| F-MAINT-59 | V28 issues an unbatched UPDATE on the partitioned `post` table | `V28__post_entity.sql:32` | mimo-audit (M5) | DEFER (documentation; long-term batched migration is separate) |
| F-MAINT-60 | `DigestScheduler.parseTimezone(String)` catches generic `Exception` and returns null silently — no log when a group's timezone is unparseable | `DigestScheduler.java:189-195` | kimi-k (F10), mimo-audit (L1) | RESOLVE |
| F-MAINT-61 | `DigestWorker.execute` broad catch `(Exception e)` suppresses programming errors | `DigestWorker.java:69-74` | kimi-k (F8) | RESOLVE |
| F-MAINT-62 | `InboundContext.adapterName()` / `senderContactId()` return `String` but javadoc says nullable; missing `@Nullable` on return type | `InboundContext.java:48,65` | kimi-k (F11) | RESOLVE-BUNDLED (with F-MAINT-44) |
| F-MAINT-63 | `GroupAutoPromoteService` eligibility check runs outside transaction boundary | `GroupAutoPromoteService.java:71-83` | mimo-audit (L9) | RESOLVE |
| F-MAINT-64 | `acquireUninterruptibly()` in worker semaphores swallows interrupt, preventing clean shutdown | `TaggerWorker.java:214`, `EmbeddingWorker.java`, `EntityExtractorWorker.java` | mimo-audit (L7) | RESOLVE-BUNDLED (with F-SIM-05 dead-semaphore removal — same workers) |
| F-MAINT-65 | V22 missing CHECK constraint on `post.stage2_verdict` (closed set documented but unenforced) | `V22__post_stage2_verdict.sql:9` | mimo-audit (L6) | RESOLVE |
| F-MAINT-66 | `InboundRouter.lookupGroupId` throws on missing group, leaks via timing | `InboundRouter.java:740-756` | kimi-k-audit (3.5) | RESOLVE |
| F-MAINT-67 | `MembershipEventHandler` writes audit rows AFTER state mutation and swallows failures (Invariant 7 audit-before-effect drift) | `MembershipEventHandler.java:105-127` | opus-47 (provider F7) | RESOLVE |
| F-MAINT-68 | `Stage1WatchdogIT` 50ms cap is marginal — flaked once at 51ms during M1-040 mvn verify; retry once, widen to 10× only on second hit | `Stage1WatchdogIT` | (memory entry, not a primary-run finding) | DROP from this handout (already tracked) |
| F-MAINT-69 | Upstream pagination cursors are concatenated into URLs without URL-encoding | `BlueskyFetcher.java:110-117`, `RedditFetcher.java:108-114` | opus-47 (collector F6), opus-48 (collector F2), deepseek (collector F1) | RESOLVE |
| F-MAINT-70 | `IngestSpisLoadTest` checks only what the compiler already guarantees (Class.forName / isInterface) | `IngestSpisLoadTest.java:20-39` | opus-47 (core F5) | RESOLVE (delete the test) |
| F-MAINT-71 | `ChatToolDispatcher` constructor does not validate registry-tool completeness against the tools the system prompt advertises | `ChatToolDispatcher.java:69-75` | deepseek (provider F3) | RESOLVE |
| F-MAINT-72 | `InboundRouter.UserSnapshot.isBanned` field is dead code | `InboundRouter.java:601` | deepseek (provider F6) | RESOLVE-BUNDLED (with F-MAINT-45) |
| F-MAINT-73 | IPv6 URL-literal hosts cannot pass `canonicalizeHost` because IDN.toASCII rejects brackets | `SsrfGuardedHttpClient.java:269-279` | opus-47 (ssrf F2), deepseek (ssrf F2) | RESOLVE |
| F-MAINT-74 | Indistinct constructor-validation error messages (same "timeout must be configured" for two distinct knobs) | `SsrfGuardedHttpClient.java:194-205` | opus-47 (ssrf F6) | RESOLVE |
| F-MAINT-75 | `PostgresSchemaTestBase.truncateAll()` omits key tables from cleanup — cross-test pollution risk | `PostgresSchemaTestBase.java:80-84` | deepseek (core F1) | RESOLVE |
| F-MAINT-76 | `ThrottledAdminNotifier.sanitize()` cross-sectional risk with no enforcement boundary | `ThrottledAdminNotifier.java:115-122,217-219,284,305` | deepseek (core F2) | RESOLVE |
| F-MAINT-77 | `BootstrapAssetsLoader` defensive code for an unreachable scenario | `BootstrapAssetsLoader.java:301-305` | deepseek (collector F3) | RESOLVE-BUNDLED (with F-MAINT-45) |
| F-MAINT-78 | `NostrRelayConnection.backoffDelay` is static but uses an instance-level Random | `NostrRelayConnection.java:354` | deepseek (collector F4) | RESOLVE |
| F-MAINT-79 | Entity extraction prompt embedded as Java string constant (could be a resource file) | `EntityExtractorWorker.java:127-142` | deepseek (collector F7) | DEFER |
| F-MAINT-80 | `SignalAdapter` null field reliance for error messages | `SignalAdapter.java:91-95` | deepseek (messaging F4) | RESOLVE |
| F-MAINT-81 | Missing Flyway migration V20 — sequence jumps V19 → V21 with no documented rationale | `infochat-core/src/main/resources/db/migration/` | kimi-k-audit (2.5) | RESOLVE (document or backfill) |
| F-MAINT-82 | SQLException last-admin substring match for trigger error is fragile (relies on literal message preservation through JDBC + PgBouncer) | `BanCommandHandler.java:294`, `RevokeAdminCommandHandler.java:261` | deepseek-audit (SEC-3) | RESOLVE (use SQLSTATE) |
| F-MAINT-83 | Multiple busy-wait loops in adapter startup (fixed 100ms sleep) without exponential backoff | `SimpleXAdapter.java:369-391`, `SignalAdapter.java:340-356` | kimi-k-audit (3.4) | RESOLVE-BUNDLED |
| F-MAINT-84 | `ProgressNotifier` SPI has zero production implementations and zero call sites | `ProgressNotifier.java` | mimo-audit (L13) | DROP or DEFER — confirm intent |
| F-MAINT-85 | `MessagingAdapter` SPI lacks `start()`/`stop()` lifecycle methods; `MessagingStartup` uses reflective `Class.getMethod("start")` with `catch (Throwable)` | `MessagingAdapter.java`, `MessagingStartup.java` | mimo-audit (L12) | RESOLVE |
| F-MAINT-86 | Six test files exceed the project's 3-inner-class guideline (up to 13 in `InboundRouterProbationOrderingTest`) | (named test files) | mimo-audit (L16) — already tracked in memory `feedback_avoid_test_inner_classes.md` | RESOLVE-BUNDLED (test-cleanup ticket family) |
| F-MAINT-87 | Five TODO(T1-D) comments in production code (tag-normalization consolidation that has not happened) | `TagVocabulary.java:125`, `TaggerWorker.java:423`, `BootstrapLoader.java:92,265`, `InviteCodeConsumer.java:182` | mimo-audit (L15) | RESOLVE-BUNDLED (with F-SIM-02 tag-normalizer extraction) |
| F-MAINT-88 | `runToolLoop` accumulated conversation has no size bound; up to 10 iterations of `searchPosts` JSON results can exceed LLM context window with no diagnostic | `ChatAgent.java:194-243` | kimi-k-audit (2.4 MEDIUM), deepseek-audit (COR-2 LOW) | RESOLVE |

---

## SIMPLIFICATION

### F-SIM-01 — Duplicate per-handler `quoteJsonString` / `jsonEscape` helpers (low/medium, SIMPLIFICATION)

- **Reported by:** opus-47 (provider F12, collector F10), opus-48 (CT4 cross-cutting), mimo (collector F4 LOW, provider F2 MEDIUM), deepseek (collector F5 LOW, audit SIM-1 MEDIUM), kimi-k (F3 LOW), kimi-k-audit (3.3 LOW), deepseek-audit (SIM-1 MEDIUM)
- **Locations:** `BanCommandHandler.java:462-486`, `UnbanCommandHandler.java`, `GrantAdminCommandHandler.java:368-392`, `RevokeAdminCommandHandler.java:363-388` (`ApproveGroupCommandHandler.java:331-351`, `RejectGroupCommandHandler.java`), `LlmOutputSanitizer.java:269-289`, `SourceUpsertService.java`, `InviteCommandHandler.inviteCreateOpenIntentDetailsJson:279-281` (partial impl), `BootstrapLoader.java:297-310`, `StartupReleaseOnStage2FailureWarn.java:152-175`, `PriceSnapshotStore.java:133-135`, `ChatAgent.writeAuditRow:319-320` (no escaping at all)
- **What's wrong:** Five+ independent JSON-escaping implementations across the codebase. Variants escape different subsets:
  - `\ " \n \r \t` only (most handlers)
  - `\ " \n \r \t` + `c < 0x20 → \u%04x` (`LlmOutputSanitizer`)
  - Backslash + quote only (partial `InviteCommandHandler` impl)
  - Nothing (`ChatAgent.writeAuditRow` concatenation)
- **Why resolve:** Eight reporters convergent — the most-cited cross-cutting theme. A bug in JSON escaping (e.g., missing a C0 control char, Unicode edge case) must be fixed in 5+ places. The `LlmOutputSanitizer.jsonEscape` is the correct shape (`c < 0x20 → \u%04x`); the others are weaker.
- **Why it might not be an issue:** Today the inputs are constrained (`scope_kind` is `"dm"`/`"group"`, etc.) so the weaker escapers don't fail in practice. But the rule the repeat enforces is "extract the helper, don't fan it out."
- **Scope hints:** Extract `JsonStrings.escape(String)` to `infochat-core` (both modules already depend on it). Use the `LlmOutputSanitizer.jsonEscape` shape (the most thorough). Replace 8+ call sites. Plus pre-check that the test suite was using the weaker escapers and update them. Medium complexity; mostly mechanical.
- **Verdict:** RESOLVE (the bundle leader)

### F-SIM-02 — Duplicate tag-normalization across three sites (medium, SIMPLIFICATION)

- **Reported by:** mimo (collector F2), opus-48 (CT3), deepseek (collector F6)
- **Locations:** `BootstrapLoader.java:266-274`, `TaggerWorker.java:425-432`, `TagVocabulary.java:127-134`
- **What's wrong:** Three copies of the same canonicalization (lowercase + trim + character class). Code already carries TODO(T1-D) comments acknowledging the duplication.
- **Why resolve:** Tag canonicalization is the keying primitive for the controlled vocabulary; divergence between callers silently produces orphan tags.
- **Scope hints:** `TagNormalizer.normalize(String)` in `infochat-core`; both callers go through it. Plus removing the TODOs. Low-medium complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-SIM-01 — both are extract-to-core)

### F-SIM-03 — Two distinct SHA-256-to-hex helpers (low, SIMPLIFICATION)

- **Reported by:** opus-47 (collector F10), mimo (collector F3), deepseek (collector F6 — broader)
- **Locations:** `BootstrapLoader.java:285-289` (uses `String.format("%02x", b & 0xff)`), `PostPersister.java:168-177` (uses `HexFormat.of().formatHex`)
- **What's wrong:** Two implementations, same output, different idiom.
- **Why resolve:** `HexFormat.of()` is the JDK-17+ canonical shape. A shared helper in `infochat-core` collapses both.
- **Scope hints:** `Sha256.hex(byte[])` helper. Low complexity.
- **Verdict:** RESOLVE-BUNDLED (with F-SIM-01)

### F-SIM-04 — `lookupUser` / `lookupActorForUpdate` pattern duplicated across 10+ handlers (medium, SIMPLIFICATION)

- **Reported by:** deepseek-audit (SIM-2)
- **Locations:** ~10 command handlers + `InboundRouter` — see the audit's table for the full list.
- **What's wrong:** Every handler that needs a user-id resolves it with its own copy of `SELECT id, contact_id, is_admin, is_banned, registration_state FROM users WHERE adapter = ? AND contact_id = ?`. Each implementation opens its own connection, prepares the same SQL, maps the ResultSet to its own slightly-different record type.
- **Why resolve:** A schema change to `users` requires updating 20+ methods. The duplication is systematic — every new command handler copies the pattern from an existing one.
- **Why it might not be an issue:** Each call deliberately returns a slightly-different record subset; an over-abstracted repository may hide which columns matter where. The fix has to keep the per-handler record types but share the SQL + ResultSet → row mapping.
- **Scope hints:** Introduce a `UserRepository` bean in `infochat-provider`. Three methods cover most callers: basic lookup, FOR UPDATE within-tx, bare-UUID. 20+ call sites; medium-effort refactor; recommend its own ticket. Medium complexity.
- **Verdict:** RESOLVE (separate ticket, not bundled — the refactor warrants its own scope)

### F-SIM-05 — Dead semaphores in TaggerWorker / EntityExtractorWorker (medium, SIMPLIFICATION)

- **Reported by:** opus-47 (collector F7)
- **Locations:** `TaggerWorker.java:160-205`, `EntityExtractorWorker.java:153-193`
- **What's wrong:** `enumeratePending(maxConcurrency)` limits per-tick batch to `maxConcurrency` and the for-loop processes serially on the scheduler thread; the `Semaphore(maxConcurrency)` never has more than one acquirer at a time. The semaphore is dead code that misleads a reader about what bounds in-flight LLM calls.
- **Why resolve:** Removes a misleading abstraction. Documents that batch + serial-loop is the actual concurrency bound. (Alternative: fan-out via `Thread.ofVirtual()` and make the semaphore live — larger change with a real throughput implication.)
- **Scope hints:** Two files. Drop the semaphore fields + acquire/release. Low complexity for the simplification; medium for the virtual-thread fan-out alternative.
- **Verdict:** RESOLVE (the simplification; defer the fan-out alternative)

### F-SIM-06 — Other simplifications (low)

| ID | Title | Locations | Reporters | Verdict |
|---|---|---|---|---|
| F-SIM-07 | Task-key-segment mapping triplicated across router + Anthropic provider + Anthropic test | `AnthropicProvider.java:207-218`, `LlmRouter.java:249-258`, `AnthropicProviderTest.java:221-230` | opus-48 (llm-adapter F5 LOW), opus-47 (synthesizer obs), deepseek (llm-adapter F1 MEDIUM) | RESOLVE-BUNDLED (with F-MAINT-46) |
| F-SIM-08 | `AssetSnapshotFetcher` duplicates SourceRepository failure-counter logic | `AssetSnapshotFetcher.java:228-297` | deepseek (collector F2) | RESOLVE |
| F-SIM-09 | `IngestSpisLoadTest` already covered in F-MAINT-70 | (covered) | (covered) | DROP duplicate |
| F-SIM-10 | Unused `Optional` import in AnthropicProvider | `AnthropicProvider.java:24` | opus-47 (llm-adapter F9) | RESOLVE (delete line) |

---

## Cross-cutting themes

Patterns visible only after consolidating across reports.

### CT1. The "shared helper everyone keeps re-implementing" pattern

The single most-cited cross-cutting theme. Four logical primitives are re-implemented across the codebase in 3-8 copies each:

- **JSON escape** (F-SIM-01) — 5+ copies, 3+ different escape sets. The `LlmOutputSanitizer.jsonEscape` form is correct; the others are weaker.
- **Tag normalization** (F-SIM-02) — 3 copies with TODO(T1-D) markers already acknowledging the issue.
- **SHA-256 hex** (F-SIM-03) — 2 copies in 2 different idioms.
- **`lookupUser` / `lookupActorForUpdate`** (F-SIM-04) — 10+ copies.

The single system-level fix is "extract every primitive that has ≥2 copies to `infochat-core` or a `shared` package and replace call sites." This is the largest single line-count reduction available.

### CT2. NOTIFY-channel contract drift

Three different writers on `quarantine_review` and `new_post`, three different payload-construction styles, two payload-emission stages that disagree with the spec's stated trigger. The architecture pass should treat `QuarantineNotifyEmitter` as the lone owner of the channel's wire format; all four channels should be audited for insert-vs-state-machine fidelity.

Findings: F-MAINT-08, F-MAINT-09, F-MAINT-10, F-MAINT-30. Plus opus-48-architecture F2 `new_price_snapshot` orphan channel and deepseek-architecture F2.

### CT3. Stale ticket-time documentation rots and now actively misleads

Class javadoc, V*.sql comments, and design notes that describe code-as-it-existed-at-an-earlier-ticket but no longer match. The drift includes both directions: stale code paths that have moved (V16 ThrottledAdminNotifier relocation, V7 `infochat_listen` role, `HostInterfaceSet` snapshot semantics, `SsrfGuardedHttpClient` ws/wss rejection, `LangCommandHandler` group-scope short-circuit, `NormalizedPost.sourceId` per-startup-vs-per-tick) and intentionally-preserved shims (`IpBlocklist` M1-025 constructor — itself a §7 violation).

System-level fix: sweep for ticket-id references in long-lived comments and javadoc; each one is either a fossil to delete or a current-state comment to rewrite. The memory `feedback_no_plan_refs_in_docs.md` already codifies this for spec/design — extend the convention to source comments.

### CT4. Defensive code inside the trust boundary (§7 violation)

Multiple modules carry null-checks and `catch (RuntimeException)` arms guarding against scenarios that cannot happen given the trust boundary the code lives in. The §7a positive complement (explicit `@NonNull` parameter contracts) is also missing on many of the same surfaces — both halves of the §7 + §7a engineering-rule pair violated symmetrically.

Findings: F-MAINT-44 (missing annotations), F-MAINT-45 (defensive code sweep), and the per-finding references they collect.

### CT5. SPI shape / contract drift between similar adapters

Adapter implementations and SPI surfaces inconsistently honor their own contracts: the same semantic state classifies differently across adapters (`SignalAdapter`/`SimpleXAdapter` disagree on "not connected" TRANSIENT-vs-PERMANENT classification — F-MAINT-25), capability flags drift from design notes (F-MAINT-27), SPI methods declare exception contracts they do not honor (F-MAINT-26).

System-level fix: cross-adapter contract test suite. Pick a single classification per semantic state; align capability flags to design notes (or amend design in the same commit); force codec/encoder validators to throw the SPI's checked exception.

### CT6. Tests exercise or seed only the path that hides the production defect

Two modules carry a defect whose companion test cannot observe it because the test drives a non-production code path or seeds only the passing case. In the collector, `ReadyPromoterIT` calls `promoteOne` through the CDI proxy (interceptor fires, real transaction wraps the body) while the production scheduler calls via self-invocation (no interceptor, auto-commit — F-MAINT-07). In the provider, `DigestRoundtripIT` seeds exclusively `approval_status='approved'` groups, so the missing approval-status filter has no fixture row whose delivery would fail — F-MAINT-06.

System-level fix: integration tests must invoke the unit-of-work through the same entry point production uses (not a directly-injected proxy method), and must include a negative-case fixture for every "X never happens" spec commitment. A targeted audit of other `@Transactional` beans reached by `@Scheduled`/self-invocation and other system-initiated paths gated by status predicates would surface siblings.

### CT7. Hand-rolled JSON / regex parsers at trust boundaries

`ChatAgent.parseToolArgs` (F-SEC-11) is the most-cited example: four reporters, three independent defect angles. The same pattern shows up in `NewPostListener.parsePayload` / `QuarantineReviewListener.parsePayload` (F-SEC-18 — unanchored regex on trusted-but-structured NOTIFY payloads).

System-level fix: Quarkus ships Jackson; use it. Replace `parseToolArgs` + `splitTopLevel` + `TOOL_CALL_PATTERN` with `ObjectMapper.readValue(args, Map.class)`. Replace NOTIFY parser regexes with Jackson on structured payloads (after F-MAINT-10 emitter normalizes the payload shape).

---

## Discarded

Findings that were reported but did not survive the consolidation pass. Each is named with the source report and the reason.

- **kimi-k F1 — Duplicate `infochat-messaging-adapter` dependency in `infochat-provider/pom.xml` (HIGH).** FALSE POSITIVE. The "duplicate" is two distinct dependencies on the same artifact with different classifiers: the first at `:27` is the default jar, the second at `:53` has `<type>test-jar</type><scope>test</scope>` carrying the test-jar produced by the messaging-adapter's `maven-jar-plugin`. Maven treats them as separate dependencies; this is the canonical idiom for "include the production code and the test fixtures of the same module." Verified by reading `infochat-provider/pom.xml` lines 27-53. The kimi-k report missed the `<type>` / `<scope>` discriminator.

- **opus-47 F1 (ssrf) — `IpBlocklist` M1-025 compat shim verdict ↔ kept; not discarded.** Listed in MAINTAINABILITY as F-MAINT-14.

- **mimo-audit L16 — Six test files exceed the 3-inner-class guideline.** Not discarded but **already tracked** by memory `feedback_avoid_test_inner_classes.md`. Listed in F-MAINT-86 with the existing rule. The handout's job is to surface; the existing memory is the durable rule.

- **opus-47 / Stage1WatchdogIT 50ms flake (project memory `project_stage1watchdogit_flake.md`).** Not a primary-run finding; already tracked separately as project memory. Not part of this consolidation.

- **deepseek-audit SIM-3 — Audit insert pattern duplicated.** Acknowledged in `BanCommandHandler` Javadoc as "M1-041 AuditLogWriter consolidation is deferred." Already a tracked deferral; not discarded but flagged here to keep on radar. Verdict: DEFER-by-existing-ticket.

- **mimo-audit M5 — V28 unbatched UPDATE on partitioned post table.** The migration has already run in every existing deployment; future deployments will pay the UPDATE cost once at install time. The "batched UPDATE in a loop" alternative requires a follow-up migration. Listed as F-MAINT-59 with verdict DEFER, but explicitly NOT discarded.

- **mimo-audit L19 — No circuit breakers.** Scope drift — adding Resilience4j or MicroProfile Fault Tolerance is a v2 architecture decision, not an M1 remediation. The D42 failure-counter is the spec's v1 mechanism. Not a finding the handout should drive into a ticket.

- **mimo-audit L18 — Unbounded result sets in FetchScheduler / DigestScheduler.** Listed as F-PERF-14 with verdict DEFER. Workload-scaling concern, no current symptom.

- **mimo-audit L17 — Levenshtein recomputes.** Listed as F-PERF-16, DEFER. Microoptimization on an admin-rate command.

- **mimo-audit L13 — `ProgressNotifier` SPI has zero implementations.** Listed as F-MAINT-84 with verdict "DROP or DEFER — confirm intent." The SPI may be intentionally placeholder for v2 ProgressBar features; reconcile with design before deleting.

- **opus-48-audit Finding 5 (low) "IPv6 transition ranges miss the blocklist" SUBSUMED by F-SEC-06.** Opus-48-primary flagged a broader version (6to4 + Teredo + NAT64 + IPv4-compatible at HIGH); opus-48-audit flagged a narrower version (IPv4-compatible + NAT64 only at LOW). Consolidated as F-SEC-06 at HIGH.

- **kimi-k F1 (HIGH) "Widespread missing JSpecify" + opus-47 (llm-adapter F7) + opus-47 (messaging F6) + deepseek (ssrf F1, llm-adapter F4) — CONSOLIDATED as F-MAINT-44.** Not discarded; collapsed under one ticket per CT4.

- **Various scattered "defensive null-check on internal field" findings — CONSOLIDATED as F-MAINT-45.** Not discarded; collapsed under one sweep.

- **deepseek-audit PERF-1 LOW + opus-47 (provider F5) HIGH + mimo-audit M11 MEDIUM — Connection-per-step churn.** Reporters disagree on severity. Consolidated as F-PERF-01 with explicit DEFER verdict (operational pool sizing fixes the immediate concern; the structural refactor waits for symptom).

- **deepseek (collector F1) `BlueskyFetcher` URL-encoding finding + opus-48 (collector F2) + opus-47 (collector F6) — CONSOLIDATED as F-MAINT-69.** Not discarded.

- **opus-48 synthesizer observation: V27 audit verb missing from AuditAction closed set + opus-48 core F3 — same finding, F-MAINT-40.** Not duplicate; correctly collapsed.

- **mimo-audit L10 LOW + deepseek (provider F7) LOW + opus-48 (provider F4) LOW + opus-48-audit (Finding 7) LOW — Closed-list sanitizer whitespace evasion.** Consolidated as F-SEC-16. Four-reporter convergence on the same locus.

---

## Suggested ticket bundling

User decides bundle granularity; this is a starting point.

### Priority 0 — blocks production

1. **M1-NNN: V30 partitions for June + July 2026** (F-MAINT-01) — single migration; immediate prod-blocker. Plus a `@Scheduled` partition creator as a follow-up.
2. **M1-NNN: InstanceLockGuard heartbeat refresh + lock re-verification** (F-MAINT-02, F-MAINT-54 InstanceLockGuard dedup) — production safety invariant + the duplication that means one fix has to land in two places.
3. **M1-NNN: `infochat.reeval.*` keys in main application.properties + CI guard for `@ConfigProperty(name="infochat.*")` coverage** (F-MAINT-03) — Collector startup fix.

### Priority 1 — security / correctness criticals

4. **M1-NNN: Anthropic auth headers + test alignment + extractErrorMessage narrow catch** (F-SEC-01, F-MAINT-15) — bundle the three Anthropic-provider finds.
5. **M1-NNN: Per-adapter reply target** (F-SEC-02) — multi-adapter correctness; recommend plan-writer pass.
6. **M1-NNN: DB role switch (collector + provider use per-service roles)** (F-SEC-03, F-SEC-05 audit redaction) — large, recommend plan-writer pass; expect IT failures that surface real privilege-mismatched DML.
7. **M1-NNN: `/zcash` `/monero` extensibility via `@Produces` CommandHandler list** (F-MAINT-04) — operator-config-driven asset surface.
8. **M1-NNN: `ReEvaluationJob` enumerate filter + cap-exhaustion transition** (F-MAINT-05).
9. **M1-NNN: `DigestScheduler` `approval_status='approved'` filter + IT negative-case fixture** (F-MAINT-06, CT6).
10. **M1-NNN: `ReadyPromoter.promoteOne` transaction boundary fix + IT through `tick()`** (F-MAINT-07, CT6).
11. **M1-NNN: ChatAgent parseToolArgs via Jackson + dispatcher exception filter widening** (F-SEC-11, F-MAINT-13) — three reporters, two angles; full fix per CT7.
12. **M1-NNN: `SET LOCAL` helper extraction + `JsonStrings.escape` shared utility** (F-SEC-04 + F-SIM-01 + F-SIM-02 + F-SIM-03 — the CT1 shared-helper bundle) — single biggest line-count reduction.

### Priority 2 — security / correctness mediums and highs

13. **M1-NNN: `quarantine_review` channel completeness** (F-MAINT-08, F-MAINT-09, F-MAINT-10, F-MAINT-30 — CT2 bundle) — V30 migration + emitter enum SPI + DAO plumbing + V21/V25 jsonb_build_object.
14. **M1-NNN: IPv6 blocklist + canonicalize-host + cross-origin headers + ws/wss javadoc** (F-SEC-06, F-SEC-09, F-MAINT-33, F-MAINT-34, F-MAINT-73 — SSRF bundle) plus F-MAINT-74 indistinct-error-messages.
15. **M1-NNN: local-only guard + remote-embedding confirmation log** (F-SEC-07).
16. **M1-NNN: SimpleX mention canonicalization fix** (F-SEC-08).
17. **M1-NNN: Signal handler exception isolation + Signal config validation + Signal half-death detection** (F-MAINT-22, F-MAINT-23, F-MAINT-56).
18. **M1-NNN: TOOL_CALL_STRIP_PATTERN DOTALL** (F-MAINT-21).
19. **M1-NNN: Kind-6 repost edge resolution (storage shape or query shape)** (F-MAINT-48) — recommend plan-writer pass.
20. **M1-NNN: audit_log SECURITY DEFINER actor-column denormalization** (F-MAINT-29).
21. **M1-NNN: EmbeddingResult mutable array + EmbeddingProvider size contract** (F-MAINT-16, F-MAINT-17).
22. **M1-NNN: JSpecify annotation retroactive pass + lint-contracts CI guard** (F-MAINT-44 — CT4 first half).
23. **M1-NNN: defensive-code sweep** (F-MAINT-45 — CT4 second half).
24. **M1-NNN: UserRepository extraction** (F-SIM-04) — own ticket.

### Priority 3 — small hardening, javadoc cleanup, RULES-DRIFT bundles

25. **M1-NNN: IpBlocklist M1-025 shim removal** (F-MAINT-14).
26. **M1-NNN: pagination cursor URL-encoding** (F-MAINT-69).
27. **M1-NNN: SsrfGuardedHttpClient HttpClient lifecycle + body-read TOCTOU + readBounded virtual thread** (F-PERF-05, F-PERF-06, F-PERF-10).
28. **M1-NNN: LLM provider response-body size cap + 429/503 Retry-After** (F-PERF-09, F-PERF-19).
29. **M1-NNN: `HelpCommandHandler` per-tier filtering + `StopCommandHandler` group/DM scope fix + `LangCommandHandler`/`FollowTagCommandHandler` javadoc** (F-MAINT-19, F-MAINT-20, F-MAINT-35).
30. **M1-NNN: AdapterRegistry duplicate-name detection + finalize→shutdown rename + `MessagingAdapter.start/stop` SPI methods** (F-MAINT-37, F-MAINT-41, F-MAINT-85).
31. **M1-NNN: Stage 1 inner-class extraction** (F-MAINT-86 — test-cleanup family, per memory rule).
32. **M1-NNN: Tag-normalizer + SHA-256 utility consolidation + T1-D TODOs** (F-SIM-02, F-SIM-03, F-MAINT-87).
33. **M1-NNN: DigestWorker concurrency guard + DigestScheduler.parseTimezone WARN + DigestWorker broad catch** (F-MAINT-58, F-MAINT-60, F-MAINT-61, F-PERF-13).
34. **M1-NNN: PostgresSchemaTestBase truncateAll completeness** (F-MAINT-75).
35. **M1-NNN: Documentation sweep** (F-MAINT-36 DAG doc + F-MAINT-31 V16 + F-MAINT-32 V7 + F-MAINT-12 dispatchKey javadoc + F-MAINT-11 NormalizedPost javadoc + F-MAINT-43 MicroProfileConfigReader null-sentinel — CT3 bundle).

---

*End of consolidated handout.*
