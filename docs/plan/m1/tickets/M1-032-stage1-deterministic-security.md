---
id: M1-032
title: Stage 1 deterministic security (HTML sanitizer + Unicode + regex + watchdog + quarantine)
status: pending
created: 2026-05-16
last_updated: 2026-05-16
blocked_by:
  - M1-008c
  - M1-028
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration/V10__quarantine.sql
  - infochat-collector/pom.xml
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/Stage1RegexSet.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/PlaceholderIds.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/QuarantineDao.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1PipelineIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1WatchdogIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1RegexSetTest.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - any Stage 2 LLM judge call, BENIGN/INJECTION/MALWARE/UNKNOWN verdict handling, retry-once-then-fallback policy, `stage2_done` advance, `stage2_failed` flag set, or `release-on-stage2-failure` config-flag wiring (M1-033 territory — this ticket lands Stage 1 only; the Stage-2 hand-off is "the eval consumer routes a Stage-1-flagged post to the Stage-2 worker with the ORIGINAL pre-redaction body retained" and that consumer wiring lives in M1-033)
  - any first concrete `LlmProvider` impl (OpenAiCompatibleProvider per `docs/design/05-llm-and-embeddings.md` §5.3), `(ModelTask, scope_language) → LlmProvider` router, `LlmRouter` class, or per-task override / language-capability / local-only conflict-detection logic — all M1-033 territory; this ticket makes ZERO LLM calls
  - any Tagger LLM call, `tagger_done` advance, controlled-vocabulary validation, partial-valid handling, or bootstrap-tags fallback path — M1-034 territory
  - any `EmbeddingProvider` impl, embedding-batch worker, `post_embedding` table, dimensionality-fatal-at-runtime guard, model-identity startup guard, or `embedding_metadata` singleton row — M1-034 territory (the V11 migration in M1-034 adds these; V10 here adds the `quarantine` table only)
  - any `post.status → READY` UPDATE, `post.ready_at` set, `post.status_changed_at` advance for READY, or `pg_notify('new_post', …)` emit — M1-034 territory at Stage 5
  - any EntityExtractor (Stage 3 in `docs/design/01-architecture.md` §1.3.4), `post_entity` table, `post_reference` table, or LinkingJob (`docs/design/01-architecture.md` §1.3.5) — T2 territory. The session-grouping-plan T1-D row reads "Stage 1 deterministic security, LLM + Stage 2, tagger + embedding" — entity extraction is intentionally not enumerated; the pipeline goes S1 → (S2 if S1 hit) → Tagger → Embedding → READY, skipping the stage 3 entity extractor that lives in T2
  - any Re-evaluation job (`docs/spec/security.md` §Re-evaluation job), per-post attempt counter, `QUARANTINED → NEEDS_REVIEW` transition, per-source UNKNOWN auto-disable, `RE_EVAL_RELEASED` audit row, or `source.status → 'failed'` mutation — T2-G territory
  - any throttled admin notifier wiring, `AdminNotifier` coalescing by `(channel, error_class)`, or per-(channel, error_class) summary-message template — T2-G territory; this ticket logs every Stage-1 infra failure at WARN with a canonical `error_class` string for the future notifier to pick up without diff churn
  - any LLM output sanitizer (`docs/spec/security.md` §LLM output sanitizer) — T1-F territory; Stage 1 sanitizes the upstream feed body but does NOT sanitize an LLM reply (there is no LLM reply in this ticket)
  - any `/quarantine list`, `/quarantine approve`, `/quarantine reject` admin command, or the `approve_quarantine` / `reject_quarantine` stored procedures from `docs/design/02-schema.md` §2.5.2 — T2-G territory. V10 contains only the `quarantine` table, the `quarantine_review_view`, the per-table GRANTs, and the indexes from §2.5.1. The stored procedures and their `EXECUTE` GRANTs are intentionally absent from V10
  - any Provider-side `quarantine_review` LISTEN listener, NewQuarantineReviewReconciler, or quarantine-review high-water-mark `provider_state` row — M2 territory per `docs/design/01-architecture.md` §1.5 ("M1 only ships the `new_post` reconciler; the `quarantine_review` reconciler lands in M2 alongside the admin quarantine-review commands"). Stage 1 inserts the `quarantine` row with `status='PENDING'` but emits NO `pg_notify('quarantine_review', …)` in this ticket — the channel listener is M2 and the M2 listener will receive the PENDING insert when its trigger is wired
  - any `new_price_snapshot` channel, asset Fetcher, `price_snapshot` write path, or `asset_config` row — T2-H per decision D39
  - any change to V1..V9 Flyway migrations already on disk (this ticket adds V10 only; re-grep the migration directory at `/m1-tick start` time and pick the next free integer if M1-021 has landed in the interim, in which case slide this migration to V11 and M1-034's to V12)
  - any `infochat-provider` module change (Stage 1 runs in the Collector; this ticket is collector-side + core-migration only)
  - any modification to M1-028's `PostPersister`, `EvalQueueProducer`, `OutboxRehydrator`, or `FetchScheduler` beyond the bare consumer wiring needed to pick `PersistedPostKey`s off the `eval-queue` channel — those classes are frozen; this ticket adds the consumer downstream of `EvalQueueProducer`
  - any modification to the V7 `post` table schema authored in M1-008c (this ticket consumes the table; it UPDATEs `post.body`, `post.stage1_done`, `post.stage1_flagged` on rows that already exist with `status='RAW'`)
  - any feature-flag or backwards-compatibility shim (M1 is greenfield per CLAUDE.md §No defensive code; this ticket implements one regex set, one watchdog policy, and one fail-closed path)
  - any RE2/J or true linear-time regex engine swap (`docs/spec/security.md` §Ingest pipeline pins `java.util.regex` + watchdog at v1; an RE2-style swap is a v2 candidate and must NOT land here)
  - any per-source / per-post-kind regex-set override or operator-tunable regex catalogue (the regex set is a CLOSED spec-level commitment in `docs/design/04-security.md` §4.2 step 3; operator tuning is out of v1)
  - any chat-input Unicode normalization or fenced-code-block carve-out (`docs/spec/security.md` §Ingest pipeline parenthetical pins ingest normalization as unconditional — fenced-code carve-out exists ONLY on the Provider chat intake, which lives in T1-E)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V10__quarantine.sql exists and creates the quarantine table per docs/design/02-schema.md §2.5.1 — grep -E 'CREATE TABLE\\s+quarantine\\s*\\(' V10__quarantine.sql returns at least one match"
  - "V10 declares every column from §2.5.1 with the exact spec-required types — grep -E 'id\\s+UUID\\s+PRIMARY KEY\\s+DEFAULT\\s+gen_random_uuid\\(\\)' V10__quarantine.sql returns at least one match AND grep -E 'post_id\\s+UUID\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'post_uid\\s+TEXT\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'post_fetched_at\\s+TIMESTAMPTZ\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'flagged_at\\s+TIMESTAMPTZ\\s+NOT NULL\\s+DEFAULT\\s+now\\(\\)' V10__quarantine.sql returns at least one match AND grep -E 'flagged_by\\s+TEXT\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'rule_id\\s+TEXT' V10__quarantine.sql returns at least one match AND grep -E 'span_start\\s+INT' V10__quarantine.sql returns at least one match AND grep -E 'span_end\\s+INT' V10__quarantine.sql returns at least one match AND grep -E 'original_html\\s+TEXT\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'placeholder_id\\s+TEXT\\s+NOT NULL' V10__quarantine.sql returns at least one match AND grep -E 'status\\s+TEXT\\s+NOT NULL\\s+DEFAULT\\s+''PENDING''' V10__quarantine.sql returns at least one match AND grep -E 'updated_at\\s+TIMESTAMPTZ\\s+NOT NULL\\s+DEFAULT\\s+now\\(\\)' V10__quarantine.sql returns at least one match AND grep -E 'reviewed_by\\s+UUID\\s+REFERENCES\\s+users\\s*\\(\\s*id\\s*\\)' V10__quarantine.sql returns at least one match AND grep -E 'review_note\\s+TEXT' V10__quarantine.sql returns at least one match"
  - "V10 declares the flagged_by CHECK over ('stage1','stage2') per §2.5.1 — grep -E \"flagged_by\\s+IN\\s*\\(\\s*'stage1'\\s*,\\s*'stage2'\\s*\\)\" V10__quarantine.sql returns at least one match"
  - "V10 declares the status CHECK over the four-value state machine ('PENDING','BENIGN_CLOSED','APPROVED','REJECTED') per docs/spec/schema.md §Posts and derivatives — grep -E \"status\\s+IN\\s*\\(\\s*'PENDING'\\s*,\\s*'BENIGN_CLOSED'\\s*,\\s*'APPROVED'\\s*,\\s*'REJECTED'\\s*\\)\" V10__quarantine.sql returns at least one match"
  - "V10 declares the three indexes from §2.5.1 — grep -E 'CREATE INDEX\\s+idx_quarantine_status\\s+ON\\s+quarantine\\s*\\(\\s*status\\s*,\\s*flagged_at\\s*\\)' V10__quarantine.sql returns at least one match AND grep -E 'CREATE INDEX\\s+idx_quarantine_post\\s+ON\\s+quarantine\\s*\\(\\s*post_uid\\s*\\)' V10__quarantine.sql returns at least one match AND grep -E 'CREATE INDEX\\s+idx_quarantine_review_cursor\\s+ON\\s+quarantine\\s*\\(\\s*updated_at\\s*,\\s*id\\s*\\)' V10__quarantine.sql returns at least one match"
  - "V10 creates the quarantine_review_view per §2.5.1 with EVERY column from quarantine MINUS original_html — grep -E 'CREATE OR REPLACE VIEW\\s+quarantine_review_view' V10__quarantine.sql returns at least one match AND grep -E 'original_html' V10__quarantine.sql returns matches ONLY in CREATE TABLE quarantine context (the view's SELECT list does NOT include original_html — verify by reading the SELECT body of CREATE VIEW)"
  - "V10 GRANTs align with docs/spec/security.md §DB roles per-table discipline. Collector (the only writer of stage1 quarantine rows in M1) has SELECT + INSERT + UPDATE on quarantine; Provider has SELECT on quarantine_review_view ONLY and has NO SELECT on quarantine.original_html — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+quarantine\\s+TO\\s+infochat_collector' V10__quarantine.sql returns at least one match AND grep -E 'REVOKE\\s+ALL\\s+ON\\s+quarantine_review_view\\s+FROM\\s+PUBLIC' V10__quarantine.sql returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+quarantine_review_view\\s+TO\\s+infochat_provider' V10__quarantine.sql returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+quarantine\\s+TO\\s+infochat_provider' V10__quarantine.sql returns zero matches"
  - "V10 does NOT create the approve_quarantine or reject_quarantine stored procedures from §2.5.2 (those are T2-G territory; only the table + view + GRANTs land here) — grep -E 'CREATE\\s+OR\\s+REPLACE\\s+PROCEDURE\\s+approve_quarantine' V10__quarantine.sql returns zero matches AND grep -E 'CREATE\\s+OR\\s+REPLACE\\s+PROCEDURE\\s+reject_quarantine' V10__quarantine.sql returns zero matches AND grep -E 'GRANT\\s+EXECUTE\\s+ON\\s+PROCEDURE' V10__quarantine.sql returns zero matches"
  - "V10 carries a leading SQL comment explicitly stating that the stored procedures + their EXECUTE grants are T2-G territory and intentionally absent from V10 — grep -E 'T2-G|approve_quarantine.*T2|reject_quarantine.*T2' V10__quarantine.sql returns at least one match"
  - "infochat-collector/pom.xml declares the OWASP Java HTML Sanitizer dependency required by docs/design/04-security.md §4.2 Stage 1 step 1 — grep -E '<artifactId>owasp-java-html-sanitizer</artifactId>' infochat-collector/pom.xml returns at least one match"
  - "Stage1RegexSet.java declares the seven prompt-injection patterns LOCKED at docs/design/04-security.md §4.2 step 3, each as a Pattern compiled with CASE_INSENSITIVE — grep -cE 'Pattern\\.compile\\(' Stage1RegexSet.java is at least 7 AND grep -E 'CASE_INSENSITIVE|(?i)' Stage1RegexSet.java returns at least one match"
  - "Stage1RegexSet.java's compiled patterns include the spec-locked anchors: the 'ignore/disregard/forget … previous/prior/above/all/earlier … instruction(s)/prompt(s)/rule(s)/directive(s)' pattern, the role-redefinition pattern with admin/root/system/developer, the system/assistant impersonation prefix at line start, the secrets-leak pattern (reveal/leak/print/output … system prompt/instructions/api key/password), the HTML-comment hide pattern <!--.*?-->, the delimiter-injection markers (<<<UNTRUSTED>>>, </UNTRUSTED>, triple-backtick role names, </?(system|user|assistant)>), and the tool-call simulation patterns (function_call:/( and tool:/(). Verify by reading the file: each of those seven match families is one Pattern in the array/list. Each Pattern carries a distinct rule_id string identifying it for the quarantine row (rule_id values are stable across builds — they are the audit key)"
  - "Stage1Pipeline.java's Stage-1 entry point applies the steps in the documented order from docs/design/04-security.md §4.2 step 1..5: (1) OWASP allowlist HTML sanitize, (2) NFKC normalize + bidi-strip U+202A..U+202E and U+2066..U+2069 + zero-width strip U+200B/U+200C/U+200D/U+FEFF, (3) prompt-injection regex set (under watchdog), (4) per-match (record span, replace with [REDACTED:<id>] placeholder, INSERT quarantine row), (5) UPDATE post.stage1_flagged=true if any match AND UPDATE post.stage1_done=true unconditionally on success — grep -E 'PolicyFactory|Sanitizers\\.|HtmlPolicyBuilder' Stage1Pipeline.java returns at least one match AND grep -E 'Normalizer\\.normalize.*NFKC|Form\\.NFKC' Stage1Pipeline.java returns at least one match AND grep -E '\\\\u202[A-E]|\\\\u206[6-9]' Stage1Pipeline.java returns at least one match AND grep -E '\\\\u200[BCD]|\\\\uFEFF' Stage1Pipeline.java returns at least one match"
  - "Stage1Pipeline.java's Unicode normalization runs UNCONDITIONALLY on the entire body (NO fenced-code carve-out — that exists ONLY on the Provider chat intake per docs/spec/security.md §Ingest pipeline parenthetical 'the chat-intake fenced-code carve-out (below) does not apply on the ingest path') — grep -E 'fence|fenced|backtick|code_block|```' Stage1Pipeline.java returns zero matches (the carve-out is absent — the ingest body is normalized in full)"
  - "PlaceholderIds.java generates the spec-committed placeholder marker '[REDACTED:<id>]' where <id> is base32-encoded over 16 random bytes (26 chars) per docs/design/04-security.md §4.2 step 4 — grep -E 'SecureRandom|nextBytes' PlaceholderIds.java returns at least one match AND grep -E '16' PlaceholderIds.java returns at least one match (the 16-byte length is profile-driven design-tier — read the file to confirm the byte count and base32 encoding) AND the regex predicate ^\\[REDACTED:[A-Z2-7]{26}\\]$ matches every id PlaceholderIds.next() returns (one test in Stage1RegexSetTest.java asserts this — see test_plan)"
  - "PlaceholderIds.java's id token is per-row random (NOT process-startup-fixed, NOT per-post-fixed) per docs/spec/security.md §Ingest pipeline 'the per-row <id> randomization is what stops attackers from pre-crafting a fake placeholder that would survive the Stage 1 <<<UNTRUSTED>>> marker strip' — the next() method draws fresh bytes from SecureRandom on every invocation. Verify by reading the method body: no cached id, no static final byte[]; every call to next() reaches SecureRandom.nextBytes"
  - "Stage1Pipeline.java's prompt-injection regex set runs UNDER a per-input wall-clock watchdog per docs/spec/security.md §Ingest pipeline 'Regex engine commitment (v1)' — the watchdog cap is profile-driven per docs/design/04-security.md §4.2 (laptop 100ms / vps 100ms / pi 250ms / remote-llm 100ms); the cap value is read from the property 'infochat.security.stage1.regex-timeout-ms' (or equivalent — document the property key in the file). Implementation: an interruptible CharSequence wrapper that throws on charAt after the wall-clock cap, OR a Future + Future.get(timeout) wrapping the matcher loop. grep -E 'infochat\\.security\\.stage1\\.regex-timeout-ms|regex-timeout|stage1\\.timeout' Stage1Pipeline.java returns at least one match AND grep -E 'TimeoutException|interrupted|InterruptedException|cancel\\(' Stage1Pipeline.java returns at least one match"
  - "Stage1Pipeline.java's watchdog abort is a Stage 1 INFRASTRUCTURE FAILURE per docs/spec/security.md §Failure handling 'Stage 1 infrastructure failure → fail-closed: the post is immediately QUARANTINED and never auto-released'. On abort: post.status UPDATE to 'QUARANTINED', post.stage1_done=true, one quarantine row with flagged_by='stage1', rule_id='regex_timeout', span = (0, body.length()) covering the whole body, original_html = the unredacted normalized body, placeholder_id = a freshly-generated id, status='PENDING'. The watchdog path NEVER falls through to a success body UPDATE — once the watchdog fires, the post is sealed in QUARANTINED — grep -E \"status\\s*=\\s*'QUARANTINED'|'QUARANTINED'\" Stage1Pipeline.java returns at least one match AND grep -E \"'regex_timeout'\" Stage1Pipeline.java returns at least one match"
  - "Stage1Pipeline.java's watchdog-failure path logs at WARN with a canonical error_class string for the future throttled admin notifier to pick up (T2-G territory — this ticket does NOT wire the notifier itself per CLAUDE.md §No defensive code 'Logging is not throttling; the notifier ticket wires the throttling') — grep -E 'log\\.warn|LOG\\.warn|Log\\.warn' Stage1Pipeline.java returns at least one match AND grep -E 'error_class|errorClass|stage1\\.regex_timeout|stage1_infra_failure' Stage1Pipeline.java returns at least one match"
  - "Stage1Pipeline.java NEVER blocks release on Stage 1's own match decisions per docs/spec/security.md §Ingest pipeline 'Stage 1 never blocks release on its own — it scrubs and routes to review'. On a Stage-1 MATCH (not a watchdog infra-failure): post.stage1_flagged=true, post.stage1_done=true, post.body is rewritten with [REDACTED:<id>] placeholders, post.status remains 'RAW' (the Stage-2 dispatcher in M1-033 advances the status from there). On a Stage-1 CLEAN run (no matches, no watchdog): post.stage1_flagged=false, post.stage1_done=true, post.body is the Unicode-normalized HTML-sanitized version of the input, post.status remains 'RAW' (the Tagger consumer in M1-034 advances the status from there). Verify by reading: there is NO UPDATE that flips post.status to 'READY' or 'QUARANTINED' in the regular Stage-1 success path — only the watchdog fail-closed path writes 'QUARANTINED'"
  - "Stage1Pipeline.java retains the ORIGINAL (pre-redaction) normalized body alongside the redacted body when stage1_flagged=true, and the Stage 2 hand-off carries the original (NOT the redacted body) per docs/spec/security.md §Ingest pipeline 'The judge sees the original (pre-redaction) content'. The hand-off shape is the eval-channel message the Stage-2 worker in M1-033 will consume. In this ticket the original-retained data is exposed via a method or message-payload field (e.g. Stage1Result.originalBody()) the M1-033 Stage-2 worker can pick up; the field is not optional — it is set on every stage1_flagged=true path. Verify by reading: the Stage1Pipeline returns a result object that carries both originalBody and redactedBody fields, or emits an eval-channel message with both fields; M1-033's Stage-2 reads originalBody"
  - "QuarantineDao.java inserts one quarantine row per Stage-1 match with flagged_by='stage1', status='PENDING', placeholder_id=<the id woven into post.body>, original_html=<the verbatim matched span from the ORIGINAL body before HTML sanitization OR the normalized body, depending on which span the match indexed against — document the choice in Implementation notes; the spec is silent on this detail and either choice is internally consistent>, rule_id=<the Stage1RegexSet rule_id corresponding to the matched pattern>, span_start/span_end as byte offsets, post_id/post_uid/post_fetched_at locating the parent post for partition-aware lookup. grep -E \"flagged_by\\s*=\\s*'stage1'|VALUES\\s*\\([^)]*'stage1'\" QuarantineDao.java returns at least one match AND grep -E \"'PENDING'\" QuarantineDao.java returns at least one match AND grep -E 'INSERT\\s+INTO\\s+quarantine' QuarantineDao.java returns at least one match"
  - "QuarantineDao.java is the SOLE write path to quarantine in M1 (the M2 admin commands write through the stored procedures from §2.5.2; this ticket's DAO is the production code path for stage1) — grep -rE 'INSERT\\s+INTO\\s+quarantine|UPDATE\\s+quarantine' infochat-collector/src/main/java/ returns matches ONLY inside QuarantineDao.java AND grep -rE 'INSERT\\s+INTO\\s+quarantine|UPDATE\\s+quarantine' infochat-provider/src/main/java/ returns zero matches"
  - "Stage1Worker.java is a CDI bean consuming the in-memory SmallRye Reactive Messaging channel 'eval-queue' authored by M1-028's EvalQueueProducer — grep -E '@Incoming\\s*\\(\\s*\"eval-queue\"\\s*\\)' Stage1Worker.java returns at least one match AND grep -E 'Stage1Pipeline' Stage1Worker.java returns at least one match"
  - "Stage1Worker.java picks up PersistedPostKey messages emitted by M1-028's EvalQueueProducer and invokes Stage1Pipeline for the named post. Verify the consumer wiring by reading: the @Incoming method's parameter type matches the producer's emit shape (PostPersister.PersistedPostKey from M1-028); the method body reads post.body + post.id + post.fetched_at and invokes Stage1Pipeline. The consumer MUST handle the case where post.stage1_done is already true (the outbox rehydrator in M1-028 may re-enqueue a post mid-evaluation per Invariant 5; the @Incoming method short-circuits with an INFO log when stage1_done=true)"
  - "Pre-existing literal '<<<UNTRUSTED>>>' or '<<<UNTRUSTED:' string in a feed body is detected by the delimiter-injection regex in Stage1RegexSet.java and recorded as a Stage 1 hit with rule_id naming the delimiter-injection pattern, then replaced with a [REDACTED:<id>] placeholder. This guarantees the spec property from docs/spec/security.md §Ingest pipeline that an attacker cannot pre-craft a fake placeholder that would survive the Stage-1 <<<UNTRUSTED>>> marker strip. Verify in Stage1RegexSetTest.java: at least one test case with a body containing the literal '<<<UNTRUSTED>>>' asserts the test's assertion that the produced redacted body contains the [REDACTED:...] marker AND the produced rule_id contains 'delimiter' or 'untrusted' (the exact rule_id wording is design-tier; document in Stage1RegexSet.java)"
  - "Stage1PipelineIT.java is a @QuarkusTest integration test against a real Postgres (Quarkus DevServices acceptable per the pattern from M1-027/M1-028) that exercises the following scenarios end-to-end, each writing a real post row and asserting the final state via SELECT: (1) clean post (no Stage-1 hits) — post.body is the Unicode-normalized HTML-sanitized form, post.stage1_done=true, post.stage1_flagged=false, zero quarantine rows; (2) single Stage-1 hit — post.body contains exactly one [REDACTED:<id>] placeholder matching the regex predicate ^\\[REDACTED:[A-Z2-7]{26}\\]$; one quarantine row with flagged_by='stage1', status='PENDING', placeholder_id=<id>, rule_id=<the matched pattern's rule_id>, post.stage1_flagged=true; (3) multiple Stage-1 hits in one post — one quarantine row per hit; the placeholder_ids are pairwise distinct (per-row randomization guarantee); (4) NFKC normalization — a payload using compatibility-form characters that resolve to 'ignore previous instructions' post-NFKC is detected as a hit (the input bytes are NOT 'ignore previous instructions' literally; the NFKC pass produces them); (5) bidi-control stripping — a payload containing U+202E followed by an admin verb is detected after the bidi strip, and the original bidi sequence is verbatim in the quarantine.original_html column; (6) zero-width stripping — a payload like 'ignore\\u200Bprevious\\u200Binstructions' is detected as a single match after the strip; (7) HTML sanitization — a body containing '<script>alert(1)</script>' is stripped (no script tag in post.body); a body containing '<a href=\"javascript:alert(1)\">x</a>' is stripped to plain text 'x' with the javascript: href dropped; HTML strips are NOT recorded as Stage-1 quarantine rows (Stage 1 records regex hits, not HTML strips); (8) pre-existing '<<<UNTRUSTED>>>' literal in body — detected as a Stage-1 hit with rule_id naming the delimiter-injection pattern and replaced with [REDACTED:<id>] — grep -E '@Test' Stage1PipelineIT.java returns at least eight matches"
  - "Stage1WatchdogIT.java is a @QuarkusTest integration test that triggers a watchdog abort: an input crafted against ONE of the bounded `.{0,40}` regex patterns from Stage1RegexSet.java is exercised against the active profile's watchdog cap; the test author picks the most vulnerable pattern (typically the 'ignore … previous … instructions' family which carries two `.{0,40}` segments with unbounded alternation). The test asserts: post.status='QUARANTINED', post.stage1_done=true, one quarantine row with rule_id='regex_timeout', span_start=0, span_end equal to the body length (whole-body span per the infra-failure path), original_html equal to the unredacted normalized body, placeholder_id a freshly-generated id; the wall-clock duration of the Stage1Pipeline call is in the inclusive range [cap_ms, cap_ms × 3] (the M1-029 precedent — wall-clock tests are inherently non-deterministic under CI load, so the assertion is a range not exact match; document this in Implementation notes). grep -E '@Test' Stage1WatchdogIT.java returns at least one match AND grep -E \"'regex_timeout'|regex_timeout\" Stage1WatchdogIT.java returns at least one match"
  - "Stage1RegexSetTest.java is a unit test (NOT a @QuarkusTest — Pattern matching is in-process, no DB required) that asserts each of the seven rule_id slots in Stage1RegexSet.java has at least one positive match case AND at least one negative case (a benign string that resembles but does NOT match the pattern, e.g. 'I should probably ignore that previous email about lunch' tests the 'ignore … previous instructions' pattern's word-boundary requirements). The placeholder-id-format test from acceptance item 15 also lives here: assert that 100 successive calls to PlaceholderIds.next() return pairwise-distinct ids each matching ^[A-Z2-7]{26}$ — grep -E '@Test' Stage1RegexSetTest.java returns at least seven matches AND grep -E 'PlaceholderIds|next\\(\\)' Stage1RegexSetTest.java returns at least one match"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports show Stage1PipelineIT and Stage1WatchdogIT executed; surefire reports show Stage1RegexSetTest executed — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least two new matches across the two new IT classes AND grep -rE 'Tests run: [1-9]' infochat-collector/target/surefire-reports returns at least one new match for Stage1RegexSetTest"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (M1-003, M1-007, M1-007a/b/c, M1-008/008a/b/c, M1-009, M1-017, M1-022..M1-029) continue to pass alongside the new V10 migration and the Stage 1 worker"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1PipelineIT.java (@QuarkusTest IT against real Postgres exercising clean / single-hit / multi-hit / NFKC / bidi / zero-width / HTML-sanitize / pre-existing-delimiter scenarios end-to-end)
    - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1WatchdogIT.java (@QuarkusTest IT for the regex-timeout fail-closed path; quarantine row with rule_id='regex_timeout' and whole-body span)
    - infochat-collector/src/test/java/io/infochat/collector/eval/stage1/Stage1RegexSetTest.java (unit test of each regex pattern's positive + negative cases and PlaceholderIds.next() distinctness + format)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V10 must apply cleanly alongside V1..V9)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - all M1-008a / M1-008b / M1-008c schema tests
    - all M1-022 / M1-023 / M1-024 / M1-025 / M1-026 / M1-029 ingest + SSRF tests
    - M1-027's three provider outbox ITs
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/architecture.md §Pipelines
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.3 Key data flow ingest
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/02-schema.md §2.3.1 post
  - docs/design/02-schema.md §2.5 Quarantine
  - docs/design/02-schema.md §2.5.1 quarantine
  - docs/design/04-security.md §4.2 Layered ingest security
  - docs/design/04-security.md §4.7 Eval pipeline failure handling
decision_refs:
  - D20
  - D22
---

# M1-032: Stage 1 deterministic security (HTML sanitizer + Unicode + regex + watchdog + quarantine)

## Context

First ticket of T1-D (eval pipeline). Stage 1 is the deterministic
security boundary that runs on every post before any LLM call:
HTML allowlist sanitize + Unicode normalize (NFKC + bidi-strip +
zero-width-strip) + prompt-injection regex set under a wall-clock
watchdog + per-match quarantine row + placeholder marker rewrite.
Stage 1 NEVER blocks release on its own (`docs/spec/security.md`
§Ingest pipeline "Stage 1 never blocks release on its own — it
scrubs and routes to review") — a match scrubs the body and
records a quarantine row but leaves the post for Stage 2 to
judge. A watchdog abort is the one Stage-1 fail-closed path:
infrastructure failure → `post.status='QUARANTINED'`, never
auto-released (`docs/spec/security.md` §Failure handling).

This ticket lands the Collector-side consumer for M1-028's
`eval-queue` channel. M1-028's `EvalQueueProducer` is the
upstream; the `@Incoming("eval-queue")` consumer here is the
first reader. The consumer fans into `Stage1Pipeline`, which is
the unit of work. Stage 1 output advances `post.stage1_done` and
optionally `post.stage1_flagged` — those flags are the Invariant-5
durable cursor that the M1-033 Stage-2 worker and the M1-034
Tagger consumer use to decide what to pick up next.

This is the first ticket in T1-D that adds a Flyway migration:
V10 creates the `quarantine` table per `docs/design/02-schema.md`
§2.5.1, the `quarantine_review_view` for Provider-role
admin-review access (with `original_html` deliberately absent
from the view's SELECT list), and the per-table GRANTs that
implement the spec-load-bearing rule "the Provider has NO SELECT
on `quarantine.original_html`" (`docs/spec/security.md` §DB
roles). The `approve_quarantine` and `reject_quarantine` stored
procedures from `docs/design/02-schema.md` §2.5.2 are T2-G
territory and intentionally absent from V10.

Stage 1 is **security-relevant** at the highest level: a wrong
regex pattern, a missed Unicode normalization step, a watchdog
that fails open, a placeholder marker an attacker can forge —
each silently degrades the entire ingest security posture. The
`release-on-stage2-failure=true` profile defaults on laptop and
pi (`docs/design/04-security.md` §4.7) amplify the impact because
Stage 1's redactions are the ONLY protection on the post body
during a Stage-2 outage on those profiles.

## Definition of Done

- **V10 Flyway migration** under
  `infochat-core/src/main/resources/db/migration/V10__quarantine.sql`
  creates the `quarantine` table per `docs/design/02-schema.md`
  §2.5.1 with every column at the exact spec-required type
  (`id` UUID PK, `post_id` UUID NOT NULL, `post_uid` TEXT NOT
  NULL, `post_fetched_at` TIMESTAMPTZ NOT NULL, `flagged_at`
  TIMESTAMPTZ NOT NULL DEFAULT now(), `flagged_by` TEXT NOT NULL
  with CHECK over `('stage1','stage2')`, `rule_id` TEXT,
  `span_start` INT, `span_end` INT, `original_html` TEXT NOT
  NULL, `placeholder_id` TEXT NOT NULL, `status` TEXT NOT NULL
  DEFAULT 'PENDING' with CHECK over the four-value state
  machine, `updated_at` TIMESTAMPTZ NOT NULL DEFAULT now(),
  `reviewed_by` UUID REFERENCES users(id), `review_note` TEXT),
  the three indexes (`idx_quarantine_status`,
  `idx_quarantine_post`, `idx_quarantine_review_cursor`), the
  `quarantine_review_view` with `original_html` deliberately
  absent from the SELECT list, and the per-table GRANT discipline:
  - `GRANT SELECT, INSERT, UPDATE ON quarantine TO
    infochat_collector` (Collector is the only M1 writer of
    Stage-1 rows; UPDATE is granted for the future Stage-2 BENIGN
    transition in M1-033).
  - `REVOKE ALL ON quarantine_review_view FROM PUBLIC; GRANT
    SELECT ON quarantine_review_view TO infochat_provider`.
  - **NO** `GRANT SELECT ON quarantine TO infochat_provider` —
    the Provider sees redacted content via the view only; raw
    `original_html` is admin-only (`docs/spec/security.md` §DB
    roles).
  - The `approve_quarantine` / `reject_quarantine` stored
    procedures from §2.5.2 and their `EXECUTE` GRANTs are
    explicitly absent from V10 — those land in T2-G alongside
    the admin commands. A leading SQL comment in V10 names the
    omission so a future reader knows the absence is intentional.
- **OWASP Java HTML Sanitizer** added as a dependency in
  `infochat-collector/pom.xml`. The library implements step 1 of
  `docs/design/04-security.md` §4.2 Stage 1.
- **`Stage1RegexSet.java`** carries the seven prompt-injection
  patterns LOCKED at `docs/design/04-security.md` §4.2 step 3 as
  Java compile-time `Pattern` constants compiled with
  `CASE_INSENSITIVE`. Each pattern has a stable `rule_id` string
  that becomes the audit key in `quarantine.rule_id`. The patterns:
  1. `ignore/disregard/forget … previous/prior/above/all/earlier
     … instruction(s)/prompt(s)/rule(s)/directive(s)`
  2. role-redefinition with admin/root/system/developer
  3. `system|assistant` impersonation prefix at line start
  4. secrets-leak (reveal/leak/print/output … system
     prompt/instructions/api key/password)
  5. HTML comment hide `<!--.*?-->`
  6. delimiter-injection (`<<<UNTRUSTED>>>`, `</UNTRUSTED>`,
     triple-backtick role names, `</?(system|user|assistant)>`)
  7. tool-call simulation (`function[_-]?call\s*[:(]`,
     `tool\s*[:(]`)
- **`Stage1Pipeline.java`** is the unit-of-work processor. Given
  a `(post_id, fetched_at)` cursor (or the raw body, depending on
  the consumer's preference — document in Implementation notes),
  it:
  1. Applies the OWASP allowlist HTML sanitize per
     `docs/design/04-security.md` §4.2 step 1 (tag set: `p, br,
     a (href only, http/https), strong, em, ul, ol, li, code,
     pre, blockquote, h1-h6`; strip `script`, `style`, `iframe`,
     `object`, `form`, `on*` event attributes; strip
     `javascript:`/`data:`/`file:` schemes; allowed-but-formatted
     HTML converted to plain text).
  2. Applies the three Unicode steps **UNCONDITIONALLY** on the
     entire body — NO fenced-code carve-out: NFKC normalize, bidi
     control strip U+202A..U+202E + U+2066..U+2069, zero-width
     strip U+200B/U+200C/U+200D/U+FEFF. The carve-out exists
     ONLY on the Provider chat intake
     (`docs/spec/security.md` §Ingest pipeline parenthetical
     "the chat-intake fenced-code carve-out (below) does **not**
     apply on the ingest path").
  3. Runs the prompt-injection regex set **under the watchdog**.
     The watchdog cap is profile-driven per
     `docs/design/04-security.md` §4.2 (laptop 100ms / vps 100ms
     / pi 250ms / remote-llm 100ms), read from the property
     `infochat.security.stage1.regex-timeout-ms` (key in
     Implementation notes). Implementation: an interruptible
     `CharSequence` wrapper whose `charAt` throws after the
     wall-clock cap, or a `Future` + `Future.get(timeout)`
     wrapping the matcher loop. Either is acceptable; document
     the choice.
  4. For each match: record `(span_start, span_end, rule_id)`;
     generate a per-row random `[REDACTED:<id>]` placeholder via
     `PlaceholderIds.next()`; replace the matched span in
     `post.body` with the placeholder; INSERT a `quarantine` row
     with `flagged_by='stage1'`, `status='PENDING'`,
     `placeholder_id=<id>`, `original_html=<verbatim matched
     span>`, `rule_id=<the matched pattern's rule_id>`,
     `post_id`/`post_uid`/`post_fetched_at` locating the parent.
  5. UPDATE the post: `body` = the redacted body (or the
     normalized body if no match), `stage1_flagged` = true iff
     any match, `stage1_done` = true unconditionally on success.
     `post.status` is left at `'RAW'` (the next stage advances it).
- **Watchdog abort path** (Stage 1 infrastructure failure per
  `docs/spec/security.md` §Failure handling): UPDATE
  `post.status='QUARANTINED'`, `post.stage1_done=true`; INSERT
  one quarantine row with `flagged_by='stage1'`,
  `rule_id='regex_timeout'`, `span_start=0`,
  `span_end=body.length()`, `original_html` = the unredacted
  normalized body, `placeholder_id` = a freshly-generated id,
  `status='PENDING'`. Log at WARN with the canonical
  `error_class='stage1.regex_timeout'` string for the future
  throttled admin notifier (T2-G).
- **`PlaceholderIds.java`** generates the spec-committed
  placeholder marker `[REDACTED:<id>]` where `<id>` is base32 of
  16 random bytes (26 chars). Per-row randomization — every
  call to `next()` reaches `SecureRandom.nextBytes` fresh; no
  caching. The brackets and `REDACTED:` literal are
  byte-identical across the implementation per
  `docs/spec/security.md` §Ingest pipeline.
- **`QuarantineDao.java`** is the SOLE production write path to
  `quarantine` in M1. M2's admin commands write through the
  §2.5.2 stored procedures, which this ticket does NOT add.
- **`Stage1Worker.java`** is the `@Incoming("eval-queue")` CDI
  bean that consumes M1-028's `EvalQueueProducer` emissions. The
  consumer's parameter type is M1-028's
  `PostPersister.PersistedPostKey`. The method body loads the
  post, invokes `Stage1Pipeline`, and short-circuits with an
  INFO log when `post.stage1_done` is already true (rehydrator
  re-enqueue case per Invariant 5).
- **Three new tests**:
  - `Stage1PipelineIT.java` (`@QuarkusTest`) — end-to-end
    scenarios per acceptance item 26 (clean / single-hit /
    multi-hit / NFKC / bidi / zero-width / HTML-sanitize /
    pre-existing-delimiter).
  - `Stage1WatchdogIT.java` (`@QuarkusTest`) — watchdog abort
    fail-closed path per acceptance item 27.
  - `Stage1RegexSetTest.java` (unit) — each pattern's positive
    + negative cases and `PlaceholderIds.next()` distinctness +
    format per acceptance item 28.
- `mvn -B clean verify` from repo root exits 0.

## Implementation notes

- **Option B (3 tickets) chosen at the top of this authoring
  session.** Per the operator's JIT handoff, T1-D is split into
  M1-032 (this ticket, Stage 1 deterministic), M1-033 (Stage 2
  + first concrete LlmProvider + (ModelTask, scope_language)
  router), and M1-034 (Tagger + Embedding + status→READY +
  pg_notify). Each ticket maps cleanly to one stage-shaped diff
  with one correctness argument: Stage 1 is "regex set + watchdog
  + fail-closed semantics"; Stage 2 is "LLM-judge verdict-vs-infra
  split + first LlmProvider impl + router"; tagger+embedding is
  "downstream of judge, advance to READY, fire NOTIFY." Rejected:
  Option A (2 tickets — bundling Stage 1 with Stage 2 forces the
  reviewer to chase two unrelated correctness models in one
  high-risk diff), Option C (4 tickets — the tagger and embedding
  cores are too small to justify their own tickets; their
  fallback chains are coupled to the same `status→READY`
  transition).
- **Migration version is V10.** V1..V9 already live on disk per
  M1-005, M1-006, M1-009, M1-016, M1-017, M1-008a/b/c, M1-022,
  M1-027. If a later authoring session lands M1-021's
  identity/audit redteam remediation migration as V10 before
  this ticket starts, slide this ticket's migration to V11 and
  M1-034's to V12 — re-grep
  `infochat-core/src/main/resources/db/migration/` at
  `/m1-tick start` time and pick the next free integer. The
  slug `quarantine` is the invariant; the numeric prefix is
  allocated mechanically.
- **Eval-queue consumer wiring.** M1-028 ships
  `EvalQueueProducer` emitting `PostPersister.PersistedPostKey`
  messages on the `eval-queue` channel. This ticket's
  `Stage1Worker` is the first consumer. The consumer's
  `@Incoming("eval-queue")` method takes a `PersistedPostKey`
  parameter; the method body uses raw JDBC (or an entity layer,
  if the developer prefers — but raw JDBC matches M1-027 and
  M1-028 style) to load `post.id`, `post.fetched_at`, and
  `post.body` for the row identified by the key. The consumer
  MUST short-circuit on `post.stage1_done = true` (the
  rehydrator may re-enqueue a post mid-evaluation per Invariant
  5: "in-flight evaluation = RAW + per-stage `*_done` flags").
- **Stage-2 hand-off shape.** The Stage 2 worker in M1-033
  sees the **original (pre-redaction)** body per
  `docs/spec/security.md` §Ingest pipeline. Stage 1 here MUST
  preserve the original alongside the redacted body for that
  hand-off. Acceptable shapes: (a) return a `Stage1Result`
  record from `Stage1Pipeline.process(...)` with both fields
  and let the M1-033 worker invoke Stage 1 in-process; (b) emit
  a new in-memory channel message (e.g. `stage2-queue`) carrying
  both bodies; (c) write the original to a transient column on
  `post` and read it back in Stage 2. (a) is simplest and matches
  the M1-028 → M1-032 hand-off style; (b) duplicates the
  channel-emit pattern unnecessarily; (c) adds a column that
  must be cleared post-Stage-2 and risks leaking the original.
  Pick (a) unless implementation reveals a reason; document the
  rejected alternatives in the commit message.
- **Watchdog implementation.** Two viable shapes for the
  per-input wall-clock cap on `java.util.regex`:
  1. **Interruptible `CharSequence`** — wrap the normalized
     body in a `CharSequence` whose `charAt(int)` throws after
     the wall-clock cap fires. `Matcher.matches()` calls
     `charAt` per character; the exception unwinds the matcher
     cleanly. Cheap (no thread spawn) and matches the
     `docs/design/04-security.md` §4.2 step 3 suggestion
     ("Matcher.interrupt() or wrapping CharSequence with an
     interruptible charAt").
  2. **`Future` + `Future.get(timeout)`** — submit the matcher
     loop to a shared executor and cancel on timeout. Cleaner
     code path but spawns a worker thread per Stage 1 call
     (cheap on virtual threads per the
     `project_quarkus_jdk25` memory but still extra machinery).
  (1) is the recommended shape; document the choice and trade-
  off in `Stage1Pipeline.java`'s class JDoc.
- **Watchdog test tolerance.** Wall-clock assertions on the
  watchdog cap are inherently non-deterministic under CI load.
  Per the M1-029 precedent ("Loosen wall-clock tolerance"), the
  test assertion tolerance is `[cap_ms, cap_ms × 3]` — the
  cap is the lower bound (the matcher must have run for at
  least cap_ms before the abort fires) and `cap_ms × 3` is the
  upper bound (the abort path must complete within roughly
  triple the cap under realistic load). Tighter assertions
  flake under failsafe parallelization.
- **Placeholder id encoding.** Base32 of 16 random bytes is 26
  characters (`A-Z2-7`). The choice of base32 over hex is
  design-tier per `docs/design/04-security.md` §4.2 step 4 —
  base32 is more compact (26 chars vs. 32 hex chars) and the
  alphabet avoids visual ambiguity. The brackets and the
  `REDACTED:` literal are byte-identical per the spec.
- **`quarantine.original_html` for watchdog abort.** When the
  watchdog fires the matched span is unknown (the matcher
  didn't return). The fail-closed contract from
  `docs/spec/security.md` §Failure handling stores the whole
  body in `original_html` so the admin reviewer can read the
  full body and the post body in `post.body` carries the
  whole-body placeholder. (The body has not been redacted —
  Stage 1 didn't run to completion — so the post body must be
  rewritten in full to the placeholder shape; otherwise the
  Provider could surface the unredacted body to a user when
  `release-on-stage2-failure=true`. Wait: actually that's wrong
  on a watchdog abort because the post stays `QUARANTINED`
  regardless of `release-on-stage2-failure` per the spec
  "Stage 1 infrastructure failure must never default to
  release." So the post body content is moot from a
  user-visibility standpoint. But the consistency property
  "post.body always matches the `[REDACTED:<id>]` placeholders
  that correspond to its quarantine rows" should hold; rewrite
  the body in full to a single whole-body placeholder.)
- **Logging the canonical `error_class`.** Every Stage-1
  infrastructure failure logs at WARN with a structured field
  named `error_class` (or equivalent — pick a stable name and
  document it) carrying one of two values: `stage1.regex_timeout`
  (watchdog abort), `stage1.html_sanitizer_exception` (OWASP
  threw). The future throttled admin notifier (T2-G) keys on
  `(channel, error_class)` per `docs/spec/security.md`
  §Failure handling "Admin notifications are coalesced per
  `(channel, error_class)`" — landing the canonical string
  here means the notifier picks it up without a diff in this
  module.
- **No `pg_notify('quarantine_review', …)`** in this ticket.
  The PENDING-insert NOTIFY emit happens upstream of the M2
  channel listener; M2 wires both the NOTIFY trigger and the
  Provider listener. Stage 1 here just INSERTs the row.
  (Future M2 work: a trigger on `quarantine` AFTER INSERT or
  AFTER UPDATE OF status that fires `pg_notify('quarantine_review', …)`.
  V10 explicitly does NOT add that trigger.)
- **Test seam for the OWASP sanitizer.** The sanitizer is a
  third-party library with deterministic behavior; the IT
  exercises it through real inputs (no mocking). The
  `Stage1PipelineIT` cases (7) and (8) verify the strip
  behavior against a representative `<script>` / `javascript:`
  payload.

## Big-picture notes

- **Stage 1 is a coarse filter, not a complete defense.**
  `docs/spec/security.md` §Ingest pipeline is explicit: the
  regex set is English-language and pattern-based; multilingual,
  paraphrased, base64-encoded, and otherwise obfuscated injection
  payloads bypass Stage 1 by design. Stage 1 earns its
  complexity for two reasons: (a) reduce Stage 2 load by
  skipping the LLM judge on the ~95%+ clean majority, and (b)
  provide a degraded mode (Stage-1-redacted-but-released) when
  the judge can't run. Stage 2 (M1-033) is the actual security
  boundary. Adding more regex patterns to Stage 1 buys very
  little once the chat output sanitizer (T1-F) and the
  deterministic-command boundary are in place. We deliberately
  do NOT pursue regex enrichment as a defense layer.
- **The watchdog cap value lives in `application.properties`.**
  Per the M1-028 review revision ("the default value belongs in
  application.properties where Quarkus config defaults
  conventionally live; the @Scheduled annotation references the
  property without an inline default"), the
  `infochat.security.stage1.regex-timeout-ms` default per
  profile is set in `application.properties` (or the
  profile-specific overrides if the bootstrap supports per-profile
  property files). The `Stage1Pipeline` reads it as a
  `@ConfigProperty(name = "infochat.security.stage1.regex-timeout-ms")`
  injection. Inlining the default in the source is a v1-shortcut
  anti-pattern.
- **The quarantine table is the security audit surface.** A
  Stage-1 hit produces one row per match; the row holds the
  verbatim original span (in `original_html`) and the regex
  rule_id that matched. Admins reviewing the quarantine queue
  see the exact bytes that tripped the filter and the exact
  pattern that flagged them, so a false positive can be
  diagnosed and a true positive can be confirmed without
  guessing. The `quarantine_review_view` redacts `original_html`
  for the Provider role (the admin reviewer uses the view from
  the Provider side and only escalates to raw psql for the rare
  case where they need the bytes).
- **The per-row randomization of `<id>` is load-bearing.** Per
  `docs/spec/security.md` §Ingest pipeline: "the per-row `<id>`
  randomization is what stops attackers from pre-crafting a
  fake placeholder that would survive the Stage 1
  `<<<UNTRUSTED>>>` marker strip." If the id were
  process-startup-fixed or per-post-fixed, an attacker who
  knows the value could embed `[REDACTED:<known_id>]` in a
  feed body, and Stage 1's `<<<UNTRUSTED>>>` strip would NOT
  remove it (the regex set strips the delimiter literals, not
  the placeholder shape). On Stage 2 the judge would see the
  attacker-injected placeholder and possibly treat it as legit
  redacted content, undermining the prompt-injection-aware
  wrapper guarantee from `docs/spec/llm.md`
  §Prompt-injection-aware prompt shape. SecureRandom on every
  `next()` call closes this gap — the attacker cannot guess
  the per-row id ahead of time.
- **The `release-on-stage2-failure=true` profile defaults
  matter.** On `laptop` and `pi` profiles, when Stage 2 is
  unreachable, the post is RELEASED with Stage 1's redactions
  retained. That makes Stage 1's redactions the LAST LINE OF
  DEFENSE on those profiles during a Stage-2 outage. A wrong
  regex (a pattern that fails to match a known injection), a
  missing Unicode normalization step (an attacker uses
  compatibility forms to evade detection), or a placeholder
  marker that an attacker can forge (per-row randomization
  defeat) defeats the system entirely under that profile +
  outage condition.
- **Stage 2 sees the ORIGINAL body.** This is the
  fail-closed-but-information-preserving design from
  `docs/spec/security.md` §Ingest pipeline. Stage 1 stores the
  full original in `quarantine.original_html` per match; Stage 2
  in M1-033 receives the original (not the redacted body) for
  judging. The `Stage1Result` (or equivalent) hand-off shape
  must carry the original.
- **Subticket isolation against M1-033 / M1-034.** M1-033 lives
  under `infochat-collector/.../eval/stage2/` and
  `infochat-llm-adapter/.../impl/` + `routing/`. M1-034 lives
  under `infochat-collector/.../eval/tagger/` and
  `infochat-collector/.../eval/embedding/` + the V11 migration.
  This ticket lives under
  `infochat-collector/.../eval/stage1/` and the V10 migration.
  The three `files_scope` lists are disjoint at the file path
  level. M1-033 blocks on this ticket (Stage 2 fires only on
  Stage-1 hits) and M1-034 blocks on M1-033 (Tagger runs only
  on `status='READY'` posts).
- **EntityExtractor is not in T1-D.** Per the session-grouping-
  plan T1-D row, T1-D delivers "Stage 1 deterministic security,
  LLM + Stage 2, tagger + embedding." Entity extraction
  (`docs/design/01-architecture.md` §1.3.4 step 3) is T2
  territory and is intentionally skipped in T1-D's pipeline.
  The pipeline shape in M1 is S1 → (S2 if S1 hit) → Tagger →
  Embedding → READY; step 3 of the design's eval-worker list
  (EntityExtractor → `post_entity` rows) is absent. Documented
  here so the reviewer's negative-space check does not flag
  the gap.

## Out-of-scope expansion

- **Stage 2 LLM judge, BENIGN/INJECTION/MALWARE/UNKNOWN verdict
  handling, retry-once-then-fallback, `stage2_done`/`stage2_failed`
  flags, `release-on-stage2-failure` flag.** All M1-033. This
  ticket lands Stage 1 only; the Stage-2 hand-off is "the
  Stage1Result carries the original body to the Stage-2 worker"
  and the worker itself + the consumer wiring live in M1-033.
- **First concrete `LlmProvider` impl, router, local-only
  conflict detection.** M1-033. This ticket makes zero LLM
  calls.
- **Tagger LLM call, controlled-vocabulary validation,
  bootstrap-tags fallback, `tagger_done`/`tagger_fallback`
  flags.** M1-034.
- **EmbeddingProvider impl, `post_embedding` table,
  dimensionality fatal-at-runtime guard, model-identity startup
  guard.** M1-034.
- **`post.status → READY` UPDATE, `pg_notify('new_post', …)`
  emit.** M1-034 at Stage 5.
- **EntityExtractor, `post_entity` table, `post_reference`
  table, LinkingJob.** T2.
- **Re-evaluation job, per-post attempt counter,
  `QUARANTINED → NEEDS_REVIEW` transition, per-source UNKNOWN
  auto-disable, `RE_EVAL_RELEASED` audit row.** T2-G.
- **Throttled admin notifier wiring, `AdminNotifier`
  coalescing.** T2-G. Stage 1 logs every infrastructure
  failure at WARN with `error_class` set to the canonical
  string; the notifier picks up later without diff churn.
- **LLM output sanitizer (`docs/spec/security.md` §LLM output
  sanitizer).** T1-F. Stage 1 sanitizes the upstream feed body,
  not an LLM reply.
- **`/quarantine list/approve/reject` admin commands and the
  `approve_quarantine`/`reject_quarantine` stored procedures.**
  T2-G. V10 has the table + view + GRANTs only.
- **Provider-side `quarantine_review` LISTEN listener and
  high-water-mark `provider_state` row.** M2 per
  `docs/design/01-architecture.md` §1.5. Stage 1 INSERTs the
  PENDING row but does NOT emit `pg_notify('quarantine_review',
  …)`; the NOTIFY trigger + Provider listener land together in
  M2 alongside the admin commands.
- **`new_price_snapshot` channel, asset Fetcher, `price_snapshot`
  write path.** T2-H per decision D39.
- **Changes to V1..V9 migrations already on disk.** Frozen.
  V10 adds the quarantine table, view, and GRANTs only.
- **`infochat-provider` module changes.** Stage 1 runs in the
  Collector; this ticket is collector + core-migration only.
- **RE2/J or true linear-time regex engine swap.** v2 candidate
  per `docs/spec/security.md` §Ingest pipeline "Regex engine
  commitment (v1)". v1 is `java.util.regex` + watchdog,
  explicitly.
- **Per-source / per-post-kind regex-set override or operator-
  tunable regex catalogue.** Out of v1. The set is closed at
  the design-tier commitment.
- **Chat-input Unicode normalization or fenced-code carve-out.**
  Provider chat intake (T1-E); ingest normalization is
  unconditional per the spec parenthetical.

## Authorized test changes

- (none — this ticket adds three new test files under
  `infochat-collector/src/test/java/io/infochat/collector/eval/stage1/`
  and one new Flyway migration under `infochat-core`. No
  pre-existing tests are modified. The V10 migration applies
  cleanly alongside V1..V9; M1-017's `FlywayMigrationIT`
  continues to pass without edit; M1-006's `DbRoleMatrixIT`
  continues to pass against the new GRANT additions without
  edit — the new GRANTs widen the matrix, they do not modify
  prior grants.)

## Alternatives considered

- **Option A — combine Stage 1 + Stage 2 into one ticket** (the
  2-ticket T1-D shape). Rejected: Stage 1 has no LLM dependency
  and its correctness model is "regex set + watchdog + fail-closed
  semantics"; Stage 2 carries BOTH "LLM-judge verdict-vs-infra
  split" AND "first concrete LlmProvider impl + router + local-
  only conflict detection." Pairing them forces the reviewer to
  chase two unrelated correctness models in one diff; the
  files_budget would push 18+, and the high-risk + high-risk
  combination puts the round-cap-3 ticket at the edge of the
  reviewer's load. The 3-ticket split lets Stage 1 land with its
  own narrow correctness argument and lets Stage 2's reviewer
  focus exclusively on the LLM-call path.
- **Option C — split tagger off from embedding** (the 4-ticket
  T1-D shape). Rejected: the tagger and embedding tickets each
  ship a small `files_budget` (~5 files each) and the bookkeeping
  cost per ticket is the same regardless of size. The two
  fallback chains share the `status→READY` transition (a tagger
  fallback to `bootstrap_tags` still advances to embedding; an
  embedding failure still advances to READY without a vector);
  splitting them forces the reviewer to follow the same
  state-machine across two diffs. The 3-ticket split keeps the
  tagger and embedding correctness arguments inside one diff
  where their shared `status→READY` advance is visible.
- **Use a true linear-time regex engine (RE2/J).** Rejected:
  pinned by `docs/spec/security.md` §Ingest pipeline "Regex
  engine commitment (v1)" to `java.util.regex` + watchdog
  explicitly so an implementation choosing a linear-time engine
  does so as a v2 amendment, not a silent design tweak. v2 may
  reconsider. The watchdog mitigates the trade-off at v1.
- **Use the `Future` + `Future.get(timeout)` watchdog shape.**
  Acceptable but the interruptible `CharSequence` shape is
  simpler (no thread spawn, no executor management) and matches
  the `docs/design/04-security.md` §4.2 step 3 suggestion. The
  acceptance items accept either form; the recommended choice
  is the interruptible `CharSequence`.
- **Skip the per-row randomization of placeholder ids** (use a
  process-startup-fixed id, or a per-post-fixed id). Rejected
  on spec grounds. Per `docs/spec/security.md` §Ingest pipeline:
  "the per-row `<id>` randomization is what stops attackers
  from pre-crafting a fake placeholder that would survive the
  Stage 1 `<<<UNTRUSTED>>>` marker strip." A fixed id lets an
  attacker pre-craft a fake placeholder and undermine the
  prompt-injection-aware wrapper. SecureRandom on every
  `next()` call is non-negotiable.
- **Carve out fenced code blocks from the ingest-side Unicode
  normalization** (mirror the Provider chat intake's carve-out).
  Rejected on spec grounds. `docs/spec/security.md` §Ingest
  pipeline is explicit: "the chat-intake fenced-code carve-out
  (below) does **not** apply on the ingest path." Ingest
  content is upstream-untrusted; the regex set must operate on
  a fully normalized form. The Provider chat intake's carve-out
  exists so a user typing exotic code samples sees them
  round-trip — that concern does not apply to feed bodies.
- **Emit `pg_notify('quarantine_review', …)` on the PENDING
  insert here in M1.** Acceptable but premature: M1 does not
  ship the Provider-side `quarantine_review` listener (M2
  territory). Firing the NOTIFY without a listener does nothing
  in M1 and risks the trigger drifting from M2's expected
  payload shape. The cleaner shape is to land the NOTIFY
  trigger together with the M2 listener in one focused diff.
  V10 explicitly does NOT add the NOTIFY trigger.
- **Land the `approve_quarantine` / `reject_quarantine` stored
  procedures from §2.5.2 in V10.** Rejected: the procedures
  are only called by the `/quarantine approve` / `/quarantine
  reject` admin commands, which are T2-G territory. Landing the
  procedures without their callers is a stranded surface that
  invites accidental use; landing them together is the focused
  diff.
- **Use an entity layer (Panache or Hibernate mapping) for
  `quarantine` and `post`.** Rejected: matches the M1-027 /
  M1-028 raw-JDBC choice. The DAO is intentionally thin and
  SQL-first; the cost of an ORM layer outweighs the readability
  benefit at this scope.
- **Store the watchdog cap as a Java constant rather than a
  property.** Rejected per the M1-028 revision precedent ("the
  default value belongs in `application.properties` where
  Quarkus config defaults conventionally live"). The cap is
  profile-driven; the property surface is the right shape.
