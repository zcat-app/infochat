# infochat Specification — Independent Review

**Reviewer:** Senior software architect (automated audit)
**Date:** 2026-05-07
**Scope:** `docs/SPEC.md`, `docs/spec/*.md`, `docs/00-mvp.md`
**Excluded:** `docs/design/` (working notes, allowed to drift)

---

## 1. Executive summary

I read the full 11-file specification (SPEC.md map, 9 spec section files, plus 00-mvp.md). The spec defines a two-service Quarkus application — a headless Collector ingesting and evaluating posts, a user-facing Provider handling chat commands and summaries — communicating over a shared PostgreSQL database via LISTEN/NOTIFY. The architecture is well-reasoned: the determinism boundary, service split, layered ingest security, and per-(user, scope) isolation are all clearly articulated.

**Overall verdict: strong foundation with a systematic blind spot around failure modes and a verification suite that doesn't cover several v1 commitments.** The top three issues are: (1) the re-evaluation mechanism for Stage-2-failed posts is referenced but never specified; (2) `/stop`, `/retry`, asset commands, and periodic summaries have no verification entries despite being explicit v1 commitments; (3) a small cluster of security/privacy adjacency issues — translation cache side-channel, Nostr kind filtering before signature verification, and the unspecified group-timezone setting path — need resolution before v1 ships.

---

## 2. Findings

### BLOCKER

---

▎ **F01** Stage 1 infrastructure failure behavior is unspecified

▎ **Severity:** blocker
▎ **Category:** gap / failure-mode
▎ **Location:** `docs/spec/security.md` § Ingest pipeline + § Failure handling; `docs/spec/decisions.md` D22
▎ **Confidence:** high

▎ **What the spec says.** D22 enumerates failure policies for Stage 2 infra failure, tagger failure, and entity/embedding failure. Security.md §Failure handling repeats the same three. Stage 1 is described as "never blocks release on its own — it scrubs and routes to review" — but that covers detection hits, not the case where Stage 1 *itself* crashes.

▎ **Why it's a problem.** Stage 1 runs deterministic regex and Unicode normalization. If a crafted input causes the regex watchdog to trip (catastrophic backtracking detected), the spec says fail-closed — but doesn't say whether the post is quarantined, released raw, or dropped. If the Unicode normalizer hits a JDK bug on a specific codepoint sequence, does the post enter the pipeline un-normalized? A competent engineer will quarantine on any Stage 1 failure; another will release-and-flag. In a system whose safety argument depends on Stage 1 always running first, this ambiguity is a blocker.

▎ **Suggested resolution.** Add a row to the failure handling table: "**Stage 1 infrastructure failure** (regex watchdog timeout, unrecoverable parse error) → quarantine the post as `QUARANTINED`, throttled admin notify, do NOT release." This is consistent with Stage 1's role as a safety gate and with the existing quarantine-review workflow.

---

▎ **F02** Re-evaluation mechanism for Stage-2-failed posts is referenced but never specified

▎ **Severity:** blocker
▎ **Category:** gap
▎ **Location:** `docs/spec/security.md` § Failure handling; `docs/spec/verification.md` § Security
▎ **Confidence:** high

▎ **What the spec says.** Security.md: "mark the post for re-evaluation when the LLM returns." Verification.md: "the periodic re-eval job picks it up when the LLM recovers." Nowhere else in the spec is a re-evaluation job described — not in architecture.md's pipelines, not in deployment.md's bootstrap behavior, not in any decision.

▎ **Why it's a problem.** An implementer reading only the spec (as they should) will find a dangling reference. They don't know: Is re-evaluation a scheduled job? What cadence? Does it re-run only Stage 2, or the full pipeline from Stage 1? Does it pick up *all* `stage2_failed=true` posts, or only those within some window? Is it a Collector concern or a Provider concern? Two teams implementing Collector and Provider separately will each assume the other owns it, or both will implement it differently.

▎ **Suggested resolution.** Add a subsection to `architecture.md` §Pipelines or `security.md` §Failure handling: "**Re-evaluation job.** A Collector-scheduled task runs on a profile-driven interval (default: every 5 min on laptop/vps, every 30 min on pi). It selects posts with `stage2_failed=true` and `status='READY'`, re-runs Stage 2 against the stored original body, and updates the post status per the normal verdict/infra-failure split. Posts that pass Stage 2 on re-eval have their redactions lifted (the original spans are restored). Posts that fail again stay released with redactions retained and increment a re-eval attempt counter; after a profile-driven max attempts the post is left as-is and admin is notified." The values (interval, max attempts) go in design notes.

---

▎ **F03** `/stop`, `/retry`, asset commands, and periodic summaries have no verification entries

▎ **Severity:** blocker
▎ **Category:** verification
▎ **Location:** `docs/spec/verification.md`; `docs/spec/commands.md` § Conversation control, § Asset commands, § Periodic group summaries
▎ **Confidence:** high

▎ **What the spec says.** Verification.md enumerates spec-level invariants that must be tested. It covers the core command path (permission matrix, banned-user intake, confirmation tokens, slash-prefix, onboarding, pagination) but has zero entries for:
- `/stop` cancellation behavior (immediate, per-scope isolated, idempotent, progress-notifier renders "stopped" state)
- `/retry` semantics (anchor clearing, bounded retry cap, friendly error on no anchor)
- Asset commands (sub-verb allowlist enforcement, mandatory attribution, stale-data timestamp, bootstrap assets idempotency)
- Periodic group summary generation (staggered start, cache reuse, degraded fallback, per-group timezone)

▎ **Why it's a problem.** These are all explicit v1 commitments with detailed decision text (D35, D36, D39, D17). Verification.md is the gate for "done." Without test entries, these behaviors can silently regress. `/stop` is particularly critical — it's a safety valve for a stuck chat agent consuming the only LLM slot, and if it doesn't work in production the operator has no recourse short of restarting Provider.

▎ **Suggested resolution.** Add verification entries for each:
- `/stop`: isolated cancellation, idempotent no-op, progress-notifier terminal state, periodic-digest immunity
- `/retry`: anchor clearing on non-`/retry` input, cap enforcement, friendly error cases
- Asset commands: allowlist enforcement (reject `/monero binance`), mandatory source+n URL in output, capture timestamp present, bootstrap-assets.json idempotent reload
- Periodic summaries: staggered scheduling (two groups with the same timezone don't fire simultaneously), cached digest reuse, degraded-fallback prose shape, timezone used correctly

---

### MAJOR

---

▎ **F04** Group timezone has no setting mechanism

▎ **Severity:** major
▎ **Category:** gap
▎ **Location:** `docs/spec/commands.md` § Periodic group summaries; `docs/spec/schema.md` § Identity and access
▎ **Confidence:** high

▎ **What the spec says.** Schema.md: "Group. ... Has a per-group timezone for digest scheduling." Commands.md: "Groups receive a morning and evening digest at per-group local times." No command in the catalogue sets a group's timezone.

▎ **Why it's a problem.** An operator creates a group (via adapter) and the group has a timezone column. How does it get populated? If it defaults to UTC, groups in other timezones get digests at the wrong local time. If the group admin is supposed to set it, there's no command. If the operator sets it via SQL, that's an operational burden the spec should acknowledge. If it's auto-detected from the group admin's locale, that's a feature the spec doesn't describe.

▎ **Suggested resolution.** Either (a) add a `/group-timezone <tz>` command gated to group admin, or (b) state explicitly that the default is UTC and the operator sets it via a bootstrap/config mechanism. Option (a) is more user-friendly and consistent with the self-service design of other per-scope settings like `/lang`.

---

▎ **F05** Nostr kind filter runs before signature verification — JSON parsing on untrusted input

▎ **Severity:** major
▎ **Category:** security
▎ **Location:** `docs/spec/security.md` § Per-source trust boundaries § Nostr
▎ **Confidence:** medium

▎ **What the spec says.** "The kind filter runs before signature verification (cheap reject) and before any body interpretation." Nostr events are JSON arrays `[0, pubkey, created_at, kind, tags, content, id, sig]`. The `kind` field is at position 3 in the array.

▎ **Why it's a problem.** To read `kind`, you must parse the JSON array structure. A malicious relay can send a crafted JSON payload designed to exploit the parser (deeply nested objects in the `tags` array, extremely large strings, etc.) before signature verification runs. The spec's stated order (kind filter → sig check → body interpretation) means the JSON parser runs at a lower trust level than signature verification. This inverts the expected trust hierarchy: signature verification should be the first thing that touches a wire-format message, with parsing happening only after the message is known to be from the claimed pubkey.

▎ **Suggested resolution.** Swap the order: signature verification first (requires parsing the full event array to access `id`, `pubkey`, `sig` fields anyway), then kind filter on the verified event, then body interpretation. The spec text "The kind filter runs before signature verification (cheap reject)" should become "Signature verification runs first on every event; post-verification, the kind filter drops non-allowlisted kinds before any body interpretation." Add a note in design notes about bounding the JSON parser (max depth, max string length, max array elements) as a defense-in-depth measure regardless of ordering.

▎ **Trade-off.** Accepting the current order: if the operator controls the relay list and trusts those relays not to send malicious JSON, the risk is low. The counter-argument is that a compromised or buggy relay is exactly the threat this boundary should defend against. The "cheap reject" optimization (avoiding signature verification for kind-4/7 events) is real but small — signature verification for a secp256k1 event is a handful of milliseconds.

---

▎ **F06** Translation cache is a cross-scope information channel

▎ **Severity:** major
▎ **Category:** security / smell
▎ **Location:** `docs/spec/llm.md` § Translation flow
▎ **Confidence:** medium

▎ **What the spec says.** "Translated outputs are cached by `(hash(text), target_language)` for a short window so a digest sent to ten group members is not translated ten times."

▎ **Why it's a problem.** If the cache key is `(hash(text), target_language)` without a scope discriminator, then a user in Group A who receives a Czech translation of a digest can infer that Group B received the *exact same English source text* (because the translation was cached and served instantly rather than incurring a translator call). This is a timing side-channel: measure response latency, infer whether another scope just received the same content. Per-(user, scope) isolation is a stated invariant (schema.md invariant 1, architecture principle 4). A global translation cache violates the spirit of that invariant even if it doesn't leak post bodies directly. The risk is low for v1 (requires an attacker with two scopes and precise timing), but the pattern is worth flagging because it establishes "cross-scope shared state" as acceptable.

▎ **Suggested resolution.** Two options: (a) Include `scope_id` in the cache key so each scope translates independently — this costs extra translation calls for identical digests across groups. (b) Acknowledge the side-channel explicitly in security.md under a "residual risks" or "accepted v1 trade-offs" section, with the rationale that digest texts are derived from public posts and the timing granularity is too coarse to exploit in practice. Option (b) is pragmatic for v1.

---

▎ **F07** Per-source (Fetcher) failure behavior is unspecified

▎ **Severity:** major
▎ **Category:** failure-mode / gap
▎ **Location:** `docs/spec/architecture.md` § Ingest SPIs; `docs/spec/security.md` § Failure handling
▎ **Confidence:** high

▎ **What the spec says.** The `StreamSource` SPI has explicit degradation handling (mark-bad relay, cooldown, reconnect backoff). The `Fetcher` SPI has none. Deployment.md mentions "fetch success/fail per source" as a metric but never says what happens when an RSS feed is down.

▎ **Why it's a problem.** A fetcher for an RSS source returns HTTP 500 for 3 hours. Does the Collector retry? On what schedule? Does it notify the admin? Does it mark the source as degraded? Does fetching for other sources continue? The `StreamSource` has a rich degradation story (decision D38); the `Fetcher` has silence. An implementer will invent a retry policy — probably the JVM default HTTP client retry behavior — which may not match the operator's expectations.

▎ **Suggested resolution.** Add a short subsection to `architecture.md` §Ingest SPIs or `security.md` §Failure handling: "**Fetcher failures.** A fetcher that returns a non-2xx status, times out, or throws is retried on the next scheduler tick for that source (the tick interval is per-source, profile-driven). After N consecutive failures (value in design notes), the source is marked degraded and admin is notified via the throttled channel. Fetching for other sources continues unaffected." The retry count and degraded threshold go in design notes.

---

▎ **F08** `/unfollow-source` permission tier is unspecified

▎ **Severity:** major
▎ **Category:** gap
▎ **Location:** `docs/spec/commands.md` § Source management
▎ **Confidence:** medium

▎ **What the spec says.** `/unfollow-source <id>` — "per-scope unsubscribe. Different from `/remove-source`: does not touch the global source row." No permission tier is stated. Compare: `/add-source` is "DM: any non-banned user adds to their own scope. Group: group admin only" (decision D14). `/remove-source` is "bot-admin only."

▎ **Why it's a problem.** In a group, can any member unsubscribe a source that a group admin added? If yes, one member can silently degrade the group's digest coverage. If no (group admin only), a member who added a source to their DM can't later remove someone else's group subscription — but can they remove their *own* subscription within a group? The permission matrix is in design notes, but the spec-level description is silent on a command that changes what content a scope sees.

▎ **Suggested resolution.** Add to the `/unfollow-source` description: "DM: calling user unsubscribes from their own scope. Group: calling user unsubscribes their own subscription; group admin can unsubscribe any source from the group scope. In a group, the last subscription to a source cannot be removed by a non-admin (prevents one member from stripping the group's digest)."

---

▎ **F09** Chat session / context window persistence is unspecified

▎ **Severity:** major
▎ **Category:** gap
▎ **Location:** `docs/spec/schema.md` § Per-scope state; `docs/spec/commands.md` § Conversation control
▎ **Confidence:** medium

▎ **What the spec says.** Schema.md lists "Chat session / context window. Per-(user, scope) live context state. `/clear` wipes only this; chat memory is independent (decision D25)." It's described under "Entities" but its implementation (in-memory vs. persisted) is never stated.

▎ **Why it's a problem.** If the context window is in-memory only, a Provider restart wipes all active conversations — users return to a blank slate. If it's persisted, the Provider must have a table for it, which schema.md doesn't describe. An implementer building the Provider will need to know: does "context window" survive restarts? The `/clear` command and the auto-compress trigger near "context window ceiling" both reference it, but neither defines where it lives.

▎ **Suggested resolution.** Add to schema.md: "The context window is an in-memory structure keyed by `(user, scope)`. It does not survive Provider restarts. On restart, users resume with an empty context window but their `chat_memory` (persisted checkpoints from `/compress`) is intact and the chat agent's pre-fetch will load it on the next message." This is a design choice; if the intent is persistence, describe the backing table instead.

---

▎ **F10** Provider-Collector data flow for asset price snapshots is unspecified

▎ **Severity:** major
▎ **Category:** gap
▎ **Location:** `docs/spec/commands.md` § Asset commands; `docs/spec/architecture.md` § Inter-service communication
▎ **Confidence:** high

▎ **What the spec says.** Commands.md: "Polled data sources reuse the existing `Fetcher` SPI. ... Repeated user calls within the cache window are served from cache." The Collector fetches prices and stores them in `price_snapshot`. The Provider responds to `/zcash` and `/monero` commands.

▎ **Why it's a problem.** How does the Provider read the `price_snapshot` table? Does it query it directly (shared DB, yes — but is there a LISTEN/NOTIFY for new snapshots, or does the Provider just `SELECT` the latest row)? If the Provider `SELECT`s, what's the freshness contract? If the Collector's fetcher hasn't run yet, does the Provider return stale data, say "no data yet," or trigger a synchronous fetch? The spec says "repeated user calls within the cache window are served from cache" but doesn't say where the cache lives (Collector's table is the cache? Or Provider has an in-memory cache?).

▎ **Suggested resolution.** Add to commands.md §Asset commands or architecture.md: "The Provider queries `price_snapshot` directly (shared DB) for the latest row matching `(asset, sub_verb, vs_currency)`. If no row exists within the freshness window (profile-driven, default matching the Collector's refresh interval), the Provider returns a friendly 'no recent data' reply with the timestamp of the last available snapshot. The Collector NOTIFYs `new_price_snapshot` on each refresh so live Provider instances can warm their in-memory cache, but the Provider always falls back to a direct query."

---

▎ **F11** Embedding model change detection is underspecified

▎ **Severity:** major
▎ **Category:** gap
▎ **Location:** `docs/spec/llm.md` § Embedding pipeline; `docs/spec/verification.md` § LLM and embeddings
▎ **Confidence:** medium

▎ **What the spec says.** llm.md: "The embedding model is chosen per profile and must not change for an existing deployment without a re-embed plan, because vectors from different models are not comparable." Verification.md: "Embedding model swap is detected (a vector built with one model is not silently mixed with another)."

▎ **Why it's a problem.** Verification.md says the test must prove swap detection. But the spec never says *how* detection works. Does the system store a model fingerprint and refuse to start if it changed? Does it tag each embedding row with a model version and skip cross-model comparisons at query time? Two implementers will build different detection mechanisms: one will add a `model_version` column to `post_embedding` and filter at query time; another will store a deployment-level fingerprint and fail startup. Without specifying the desired behavior (refuse-start vs. degrade-gracefully), the verification test can't be written.

▎ **Suggested resolution.** Add to llm.md §Embedding pipeline: "The system stores a model identifier (model name + version or content hash of the model file) in a singleton deployment row on first embedding. On restart, if the configured embedding model differs from the stored identifier, the Collector refuses to start until the operator either (a) reverts the model config, or (b) sets a `infochat.embedding.force-new-model=true` flag acknowledging that existing vectors will be incomparable and new embeddings will use the new model. Existing vectors from the old model are retained but excluded from cross-source linking queries (the model identifier column in `post_embedding` gates the join)."

---

▎ **F12** Post body TTL mechanism is inconsistent between D33 and schema.md

▎ **Severity:** major
▎ **Category:** inconsistency
▎ **Location:** `docs/spec/decisions.md` D33; `docs/spec/schema.md` § Invariants
▎ **Confidence:** high

▎ **What the spec says.** D33: "Old post-derived rows expire by partition drop... Post bodies have a fixed retention horizon; saves are exempt via snapshot copy." Schema.md invariant 6: "TTL by partitioning. `post_reference`, `post_embedding`, and similar bulk-derived rows are partitioned and aged out by partition drop, not row delete." The `post` table itself is not listed under TTL by partitioning.

▎ **Why it's a problem.** D33 says "post bodies have a fixed retention horizon" and saved posts are exempt "via snapshot copy" — which only makes sense if the original `post` row can disappear. But schema.md invariant 6 only lists bulk-derived tables for partition drop, not the `post` table. Is the `post` table also partitioned and aged out? If so, schema.md invariant 6 is incomplete. If not, D33's claim about post body retention is misleading — there's no mechanism to enforce it. An implementer reading schema.md will not partition the `post` table; one reading D33 might.

▎ **Suggested resolution.** Align them. If posts do expire: add `post` to invariant 6 and specify the partition key (likely `fetched_at`) and retention horizon (profile-driven). If posts don't expire: correct D33 to say "Post-derived rows (post_reference, post_embedding) expire by partition drop. Post bodies themselves have no fixed retention horizon in v1; the `post` table is append-only." Given that `/save` snapshots posts "so retention TTL on the underlying post does not break the bookmark," the intent is clearly that posts expire. Schema.md needs to reflect this.

---

### MINOR

---

▎ **F13** `/add-source` URL validation flow is unspecified

▎ **Severity:** minor
▎ **Category:** gap
▎ **Location:** `docs/spec/commands.md` § Source management; `docs/spec/security.md` § SSRF
▎ **Confidence:** medium

▎ **What the spec says.** Commands.md lists `/add-source --type … --url … --tags …`. Security.md details SSRF guards for all outbound connections. But the spec never says whether `/add-source` actually fetches the URL to validate it's a real feed, or just inserts the row and lets the Collector's next tick discover problems.

▎ **Why it's a problem.** If the Provider validates the URL synchronously during `/add-source` (fetches it, checks it parses as RSS), a slow or malicious URL ties up a Provider thread and the user sees a hung command. If it doesn't validate, the user gets a success reply for a nonexistent feed and only discovers the problem later (or never, if they don't check). The spec should commit to one approach.

▎ **Suggested resolution.** State explicitly: "`/add-source` performs a lightweight validation fetch (HEAD request, check Content-Type, respect connect/read timeouts) before inserting the source row. If the fetch fails or times out, the command returns a friendly error and does not create the source. The SSRF allowlist applies to this fetch. The full content parse and first ingest happen on the Collector's next scheduler tick."

---

▎ **F14** Quarantine status flow after Stage 2 BENIGN verdict is implicit

▎ **Severity:** minor
▎ **Category:** ambiguity
▎ **Location:** `docs/spec/security.md` § Quarantine workflow
▎ **Confidence:** medium

▎ **What the spec says.** "Every Stage 1 or Stage 2 hit creates a quarantine row... Posts with PENDING quarantine entries can still be visible to users (with redactions in place). A Stage 2 INJECTION/MALWARE/UNKNOWN verdict hides the entire post."

▎ **Why it's a problem.** The text says Stage 1 hits → quarantine row with PENDING status → visible with redactions. Then Stage 2 verdict of INJECTION/MALWARE/UNKNOWN → hidden. But what happens when Stage 2 returns BENIGN? Does the quarantine row stay PENDING forever? Does it transition to RESOLVED? Is the redaction lifted (original span restored)? The spec implies posts are "visible with redactions in place" but doesn't say whether BENIGN clears those redactions. Two implementers will disagree: one will leave redactions forever, another will restore the original text.

▎ **Suggested resolution.** Add: "A Stage 2 BENIGN verdict transitions the quarantine row to RESOLVED and restores the original spans (lifts the redactions). The post is re-NOTIFY'd so live Providers pick up the un-redacted body. This is the only automated path that lifts redactions without admin review."

---

▎ **F15** Chat output sanitizer doesn't check for `[refused-action]` marker

▎ **Severity:** minor
▎ **Category:** gap / security
▎ **Location:** `docs/spec/security.md` § Prompt-injection defenses, § Chat output sanitizer
▎ **Confidence:** low

▎ **What the spec says.** The system prompt instructs the model to "refuse action requests with a `[refused-action]` marker." The chat output sanitizer "strips or refuses replies containing admin command strings." The sanitizer description doesn't mention checking for the `[refused-action]` marker.

▎ **Why it's a problem.** If the LLM correctly refuses an injected action request by emitting `[refused-action]`, the marker appears in the user-visible reply. This is a minor information leak (the user learns that an injection was attempted and detected). More importantly, if the LLM *incorrectly* emits `[refused-action]` in response to benign content, the user sees a confusing marker. The sanitizer should either strip the marker (keeping the refusal text) or handle it explicitly.

▎ **Suggested resolution.** Add to the chat output sanitizer: "Replies containing the `[refused-action]` marker have the marker stripped before delivery; the surrounding refusal text is preserved. The marker itself is an internal signal, not user-visible text."

---

▎ **F16** Onboarding path for banned→unbanned users is unspecified

▎ **Severity:** minor
▎ **Category:** gap
▎ **Location:** `docs/spec/commands.md` § Onboarding; `docs/spec/security.md` § User ban
▎ **Confidence:** low

▎ **What the spec says.** Onboarding "branches on three modes (DM-fresh, DM-returning, group-first-mention)." Ban says banned users get one fixed reply per inbound message. Unban restores the user.

▎ **Why it's a problem.** A user is banned, then unbanned. Their next message: is this "DM-fresh" (they might have been auto-registered on their first pre-ban message) or "DM-returning"? The onboarding welcome message branches, but neither branch says "you were banned and are now unbanned." This is a minor UX gap — the unban flow is admin-initiated and the unbanned user may not know they've been reinstated until they try to interact.

▎ **Suggested resolution.** Add to the onboarding description: "A previously-banned user whose ban has been lifted receives the DM-returning welcome (not DM-fresh), since their user row already exists. No special 'you were unbanned' message is sent proactively; the unban notification is an admin-side concern."

---

▎ **F17** Property key `infochat.profile=laptop|vps|pi|remote` in spec map

▎ **Severity:** minor
▎ **Category:** layering
▎ **Location:** `docs/SPEC.md` § Glossary (Hardware profile)
▎ **Confidence:** high

▎ **What the spec says.** "Hardware profile: named bundle of settings keyed by `infochat.profile=laptop|vps|pi|remote`."

▎ **Why it's a problem.** The spec/design split (stated in SPEC.md line 16–19) says property keys live in design notes. `infochat.profile` is a property key. It appears in the glossary of the spec map. The architecture.md and deployment.md correctly avoid naming the key, referring only to "a named profile." This single occurrence in SPEC.md is inconsistent with the stated layering rule.

▎ **Suggested resolution.** Change to: "Hardware profile: named bundle of settings (values: `laptop`, `vps`, `pi`, `remote`). Picks context-window size, default chat / embedding model, eval concurrency, vector index type. Configured via a single operator property (key in design notes)."

---

▎ **F18** Scope preferences "digest-related preferences" is vague

▎ **Severity:** minor
▎ **Category:** ambiguity
▎ **Location:** `docs/spec/schema.md` § Per-scope state
▎ **Confidence:** medium

▎ **What the spec says.** "Scope preferences. Per-(scope) language, subscription versions (counters used to invalidate cached digests on subscription changes), digest-related preferences."

▎ **Why it's a problem.** "Digest-related preferences" is an undefined bucket. Does it include: preferred digest length? Preferred time of day? On/off toggle for digests? The spec doesn't say, and there's no command to set any digest preference beyond `/follow-tag`/`/unfollow-tag`. An implementer will either leave the column flexible (no validation) or guess at what belongs there.

▎ **Suggested resolution.** Either enumerate the digest preferences that exist in v1 (if any beyond tag follows), or remove the phrase and say "future digest preferences will extend this row." If the only digest preferences in v1 are tag follows (which live in `scope_tag`), say so explicitly.

---

▎ **F19** Post UID uniqueness enforcement mechanism is unspecified

▎ **Severity:** minor
▎ **Category:** gap
▎ **Location:** `docs/SPEC.md` § Glossary (Post UID); `docs/spec/schema.md` § Posts and derivatives
▎ **Confidence:** low

▎ **What the spec says.** "Post UID: stable globally-unique ID for a fetched post. Returned in summaries; usable in `/save`, 'tell me more about UID X' chat queries, etc."

▎ **Why it's a problem.** The spec says UIDs are "stable globally-unique" but doesn't say how uniqueness is enforced. Is UID a hash of (source, url) or (source, published_at, title)? Is it a UUID generated on first sight? If two sources publish the same article (cross-source syndication), does each get a different UID? Are UIDs stable across Collector restarts? The spec implies stability ("stable") but the generation rule determines whether stability holds after a source reconfiguration or a data migration.

▎ **Suggested resolution.** Add to schema.md: "A post UID is derived from `(source_id, upstream_identifier)` where `upstream_identifier` is the RSS `<guid>`, the Nostr event `id`, or the source's native stable identifier. If no native identifier exists, the UID is a content hash of `(url, published_at, title)`. A unique index on `uid` enforces global uniqueness."

---

### NIT

---

▎ **F20** Asset commands not listed in 00-mvp.md deferred items

▎ **Severity:** nit
▎ **Category:** scope
▎ **Location:** `docs/00-mvp.md` §5 What is NOT in MVP
▎ **Confidence:** high

▎ **What the spec says.** 00-mvp.md §5 lists everything deferred from MVP. Asset commands (`/zcash`, `/monero`, `price_snapshot` table, `bootstrap-assets.json`) are not in §2 (MVP tables), §3 (MVP pipeline), or §4 (MVP commands), so they are implicitly deferred.

▎ **Why it's a problem.** A reader scanning the deferred list to confirm what's out of MVP won't find asset commands and may assume they were overlooked. The deferred list should be exhaustive; implicit deferral by omission is fragile.

▎ **Suggested resolution.** Add to §5: "### Asset commands — `/zcash`, `/monero`, `price_snapshot` table, `bootstrap-assets.json`, asset-specific fetcher scheduling."

---

▎ **F21** "Competent at" language in llm.md is subjective

▎ **Severity:** nit
▎ **Category:** ambiguity
▎ **Location:** `docs/spec/llm.md` § SPI shape
▎ **Confidence:** low

▎ **What the spec says.** "Tagger — competent at controlled-vocabulary classification." "Entity extractor — competent at structured output."

▎ **Why it's a problem.** "Competent" is not a testable property. The spec doesn't define what accuracy threshold makes a model "competent" for a task. This matters for profile defaults: if the `pi` profile picks a tiny model for tagging and it's wrong 40% of the time, is that a bug or a known limitation? The spec should either define minimums or explicitly state that model competence is an operator judgment.

▎ **Suggested resolution.** Change to: "Tagger — classifies against the controlled vocabulary. Accuracy depends on the chosen model; the profile defaults pick models that balance throughput against the operator's hardware. No minimum accuracy is guaranteed by the spec; operators tune by swapping the tagger model."

---

▎ **F22** Flyway race condition on dual-service startup

▎ **Severity:** nit
▎ **Category:** smell
▎ **Location:** `docs/spec/deployment.md` § Topology
▎ **Confidence:** low

▎ **What the spec says.** "Both services run Flyway on startup; the migration set is identical and idempotent on second-run."

▎ **Why it's a problem.** If Collector and Provider start simultaneously (e.g., `docker-compose up`), both will attempt Flyway migrations at the same time. Flyway locks the schema history table, so one service will wait for the lock and then find no pending migrations (the other already ran them). This is safe in practice but can produce a WARN-level log line that looks like an error to an operator. The spec doesn't need to solve this, but should acknowledge it so operators don't file bug reports.

▎ **Suggested resolution.** Add a note: "If both services start simultaneously, Flyway's schema-history lock serializes the migrations automatically; the second service will log a lock-wait followed by a no-op result. This is expected and harmless."

---

▎ **F23** Verification for "source bodies are never translated" is missing

▎ **Severity:** nit
▎ **Category:** verification
▎ **Location:** `docs/spec/verification.md`; `docs/spec/llm.md` § Translation flow
▎ **Confidence:** low

▎ **What the spec says.** llm.md: "Source post bodies are never translated. Embeddings, retrieval, and entity extraction always operate on the original language." Verification.md has no entry for this invariant.

▎ **Why it's a problem.** A future developer adding a "helpful" translation pass to the ingest pipeline (e.g., translating source bodies so the summarizer gets English input) would not be caught by tests. This is a cross-cutting invariant that the spec explicitly states but the test suite doesn't enforce.

▎ **Suggested resolution.** Add to verification.md LLM section: "Source body invariance: a fixture post with a non-English body passes through the full ingest pipeline and the body stored in `post.body` is byte-identical to the fetched original (modulo Stage 1 Unicode normalization). The translation provider is never invoked during ingest."

---

## 3. Cross-cutting observations

**Failure handling is detailed for the LLM path but silent for deterministic infrastructure.** Stage 2, tagger, embedding, and translation all have explicit failure policies (D22). But Stage 1 crashes, Fetcher failures, DB outages, and pgvector unavailability are either unmentioned or mentioned only as metrics. See F01, F02, F07. This asymmetry is understandable — LLM failures are the novel risk — but an operator running on a Pi with limited resources will hit infrastructure failures more often than LLM failures.

**The determinism boundary is stated clearly but its edges are unguarded.** D19 and architecture principle 1 are well-written. But the enforcement mechanisms are scattered: the tool allowlist is in security.md, the embedding model swap detection is underspecified (F11), and the chat output sanitizer doesn't handle the `[refused-action]` marker (F15). Each individual gap is minor, but collectively they suggest the determinism boundary is more aspirational than systematically enforced.

**Verification coverage is uneven.** The core command path, security pipeline, and messaging adapter are thoroughly covered. But the v1 features added in later decisions — `/stop` (D35), `/retry` (D36), asset commands (D39), periodic summaries (D17) — have no verification entries. This tracks the development history (the decisions were added as the spec grew) but means the verification suite doesn't yet reflect the full v1 scope. See F03.

**Per-scope isolation is a strong invariant with one acknowledged leak.** Schema invariant 1, architecture principle 4, and verification.md's 100-user fuzz test are excellent. The translation cache (F06) is the one place where cross-scope state sharing is proposed for performance. It's a small leak, but it establishes a precedent.

**The spec/design split is well-maintained with one slip.** Property keys, class names, and numeric values consistently live in design notes. The sole exception is `infochat.profile=laptop|vps|pi|remote` in SPEC.md's glossary (F17). The CLAUDE.md project instructions contain a few more property keys, but those are project instructions, not spec commitments.

---

## 4. Spec evaluation

### Completeness — Good, with gaps in failure modes and plumbing

The spec covers what v1 must do: commands, ingest pipeline, security model, messaging adapter contract, deployment topology. The decision log (D1–D39) is thorough and cross-references well. Missing pieces: Fetcher failure behavior, re-evaluation mechanism, group timezone setting, Provider-Collector data flow for assets, and `/add-source` validation. These are all "how does this actually work in production" questions that a build team will ask in the first two sprints.

### Consistency — Good, with one real conflict

D33 and schema.md invariant 6 disagree on whether the `post` table has a TTL (F12). Other than that, the cross-references hold up: decisions.md aligns with their section files, architecture.md doesn't contradict security.md, commands.md's catalogue matches decisions.md's permissions. The spec authors clearly cross-checked.

### Implementability — Mostly yes, with daily-clarification items

An engineer can build the MVP from 00-mvp.md without ambiguity. Building full v1 will require resolving the gaps listed above — particularly F02 (re-evaluation), F04 (group timezone), F07 (Fetcher failures), and F10 (asset data flow). These aren't deep architectural questions; they're spec omissions that a team lead would answer in a standup. But there are enough of them that the first v1 build will generate ongoing clarification requests.

### Testability — Strong for the core, weak at the edges

The verification strategy's four-layer approach is well-designed. The spec-level invariants it enumerates cover the determinism boundary, ingest security, and messaging adapter contract thoroughly. The gaps (F03) are concentrated in features added by later decisions, suggesting the verification.md wasn't updated when D35, D36, D39 were finalized.

### Evolvability — The spec/design split is sound

The layering rule (behavior in spec, values in design) is clear and consistently followed. The decision log is append-only with stable D-numbers, making it easy to trace why a choice was made. The per-section "What lives in design notes" trailers are an excellent practice. The most likely leak point: new features that touch both Collector and Provider (like asset commands) will need careful section placement to avoid the spec fragmenting.

---

## 5. Pros and cons of the current state

### Pros

- **The determinism boundary is the spec's strongest element.** SQL for retrieval, LLM for prose — clearly stated, consistently enforced across architecture, security, commands, and llm.md. A new developer can understand it from any entry point.
- **The security model has concrete trust boundaries with named actors.** Not "the system is secure" but "adapter asserts identity → ban check before parsing → permission in Java → LLM downstream." Every step names the data that crosses the boundary.
- **The decision log (D1–D39) is the right granularity.** Each decision is a direction, not a value. Cross-references to section files are bidirectional. The history-is-legible rule (don't delete rows) is stated.
- **Per-(user, scope) isolation is a schema-level invariant with a verification fuzz test.** Very few spec-first projects commit to proving isolation at this level.
- **The spec/design split with trailers is disciplined.** Every spec file ends with a concrete list of what lives in design notes. This prevents the common pattern of spec files accumulating implementation details over time.
- **MVP scope is clearly bounded with exit criteria.** 00-mvp.md defines exactly 8 pass/fail conditions. A contractor could be handed the MVP spec alone and deliver it.

### Cons

- **Failure handling is asymmetrical.** LLM path failures have rich policies; infrastructure failures (Stage 1 crash, DB down, pgvector missing, all fetchers failing) are barely mentioned. This is the spec's largest structural weakness.
- **Features added via later decisions weren't back-propagated to verification.md.** `/stop`, `/retry`, asset commands, Nostr ingestion details, and periodic summaries all landed in the spec but not in the test catalogue.
- **The inter-service data flow for non-post data is ambiguous.** How the Provider reads price snapshots, how the re-evaluation job works, and how Provider state high-water marks are initialized are all implied by the architecture but not explicitly traced.
- **A few security-adjacent details need tightening.** Translation cache side-channel (F06), Nostr kind filtering before sig verification (F05), and the `[refused-action]` marker handling (F15) are all small enough to fix in an afternoon but large enough to cause an incident if ignored.
- **Group lifecycle is underspecified.** Groups appear in the schema, commands, and security model, but: how is a group's timezone set? Who can remove a subscription? What happens when the last group admin leaves? These are edge cases that will surface in the first real group deployment.

---

## 6. Recommended next actions

Ordered by impact-per-effort:

1. **Add the re-evaluation mechanism (F02).** This is a dangling reference in both security.md and verification.md. Without it, Stage-2-failed posts are in a permanent limbo state. 30 minutes to specify, prevents a design argument during implementation.

2. **Add verification entries for `/stop`, `/retry`, asset commands, and periodic summaries (F03).** These are v1 commitments with no test coverage. Adding them to verification.md doesn't require writing the tests — just stating what must be tested. 45 minutes.

3. **Resolve the post TTL inconsistency between D33 and schema.md (F12).** Decide whether `post` rows expire and align both documents. This affects the schema design and the partition strategy. 15 minutes to decide, 15 minutes to update both files.

4. **Specify Fetcher failure behavior (F07).** The `StreamSource` has a degradation story; the `Fetcher` needs one. A short paragraph in architecture.md or security.md. 20 minutes.

5. **Resolve the group timezone setting path (F04).** Either add `/group-timezone` to the command catalogue or state that the operator sets it. This blocks any group digest feature work. 15 minutes.

6. **Specify the Provider-Collector data flow for asset commands (F10).** Provider queries `price_snapshot` directly, with a freshness contract. 20 minutes.

7. **Fix the Nostr kind-filter ordering (F05).** Swap "kind filter before sig verification" to "sig verification before kind filter" in security.md. 5 minutes in the spec; verify the design notes match.

8. **Address the translation cache side-channel (F06).** Either scope-key the cache or document the accepted risk. 15 minutes.

9. **Add the remaining minor gap-fills (F08, F09, F13, F14, F15, F16, F19).** These are each under 10 minutes and collectively close most of the implementability gap.

10. **Clean up the sole layering violation (F17)** and add asset commands to 00-mvp.md's deferred list (F20). Two one-line edits.

Total estimated time for all recommended changes: ~4 hours of focused spec work, well under the cost of the implementation arguments they prevent.
