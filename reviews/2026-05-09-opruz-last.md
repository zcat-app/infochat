● Architectural Review of docs/SPEC.md

I've evaluated the SPEC.md map document. Note: this is the map/index file only — it points at the authoritative spec under docs/spec/ (architecture, security, schema, commands, llm, messaging, deployment, verification) and the decisions log. My
review here is scoped to what SPEC.md itself commits to or implies; deep-diving each downstream spec file would be a separate pass. I'll flag where the map is the right place to clarify, vs. where the ambiguity must be resolved in a downstream
file.
  
---
1. Overall verdict

Quality: high. This is a well-disciplined, mature spec map. Strengths:

- Clear three-layer doctrine (spec / design / MVP), with explicit rules for what lives where and how to evolve.
- Reading order is prescribed and non-arbitrary (decisions → architecture → security → schema → …).
- v1 vs v2 boundary is concretely enumerated rather than hand-waved.
- Security posture is visible at the map level (invite codes D44, slow-start D45, layered ingest, two admin tiers, ban semantics, data minimization D37, Nostr forever-no key handling D38).
- Determinism boundary is named as a first-class concept ("Cluster", §5).
- The "evolve" section (§6) is the kind of governance most projects skip and regret.

However: there are several ambiguities and a handful of latent security issues that should be pinned down in writing before implementation begins. Most are not bugs in the architecture — they're places where the SPEC.md map either understates a
commitment that exists downstream, or silently delegates something load-bearing without saying which file owns it.

  ---
2. Findings — Ambiguities & open questions

F1. "Pluggable messaging adapter" — singular vs. plural mismatch

- §1 says: "Provider — handles user interaction through a pluggable messaging adapter." (singular)
- CLAUDE.md and decision D46 say one Provider runs a non-empty subset of adapters simultaneously.
- Risk: new contributors reading the map will design for single-adapter Provider and miss multiplexing concerns (per-adapter admin bootstrap, per-adapter contact-id parsing, last-admin counting across adapters).
- Fix: reword §1 to "one or more pluggable messaging adapters (decision D46)" and add a one-line forward reference.

F2. SPI nomenclature drift

The map uses three overlapping SPI names without disambiguating:
- §4 mentions Fetcher, StreamSource, TranslationProvider, MessagingAdapter.
- §5 glossary defines StreamSource and "Progress notifier" but not Fetcher, MessagingAdapter, or TranslationProvider.
- Fix: add glossary entries (or a single "SPIs" subsection) for all v1 SPIs. Right now a reader has to chase decisions D38/D39 to learn what Fetcher is.

F3. "Cluster ID" durability is honest but operationally underspecified

§5 admits: "Cluster IDs are best-effort breadcrumbs, not durable references." Good — but:
- Can users reference cluster IDs in commands (e.g. /save <cluster_id>)? If yes, what happens after recompute?
- The Post UID is durable; the cluster ID is not. This split should be called out where users see IDs.
- Fix: state explicitly "User-facing IDs in summaries are Post UIDs (durable) and Cluster IDs (best-effort, valid until cache expiry); only Post UIDs may be referenced in commands." Or push the rule down to commands.md with a forward pointer.

F4. /stop and /retry scope is partial

§4 says /stop cancels in-flight chat-mode replies or /summary. It does not say what happens to:
- A long-running asset-command refresh.
- A user-issued /export (which could touch large data sets).
- A /forget in flight (this should arguably be uninterruptible for integrity).
- Fix: either enumerate which commands honour /stop or state the rule as "/stop cancels any user-initiated request producing prose; mutating commands and bookkeeping commands are uninterruptible" — and then enforce that in commands.md.

F5. /retry determinism claim — "deterministic selection reused, LLM prose re-rolled"

This is a strong claim. Implications that aren't spelled out:
- Selection is reused from when? The original request, or recomputed at retry time? If posts have arrived since, what wins?
- Caching: must we persist the original cluster set per-(user, scope, requestId) to honour this, or does the periodic-digest cache cover it?
- Fix: state the rule precisely. Suggested: "/retry reuses the cluster set captured at the moment of the original request; new posts are not pulled in. The cluster set is held in the periodic-digest cache for digests and in a per-request retry
  cache (TTL = profile default) for ad-hoc summaries." This is a behavior commitment → belongs in spec, not design.

F6. Auto-compress threshold is a behavior, not just a value

§4: "Auto-compress near the profile-defined context window ceiling." "Near" is unspecified.
- Is the threshold deterministic or LLM-judged?
- Does the user see it happen, or is it silent?
- Does it run mid-reply or only between turns?
- Fix: the trigger and user-visibility are spec-level commitments; the exact percentage is a design value. Pin both: e.g. "When the conversation context exceeds the profile threshold, the next user turn is preceded by a deterministic compress
  step that produces one new memory entry; the user is notified by a one-line system message."

F7. /forget semantics are underspecified at the map

D37 promises "user-initiated purge" but the map doesn't say:
- Per-scope or global across scopes?
- Does it delete /saved posts, or only chat memory?
- Does it delete audit-log rows? (It should not — that's the whole point of an audit log.)
- Fix: state the boundary at the map level: "/forget purges the invoking user's chat memory and saves within the current scope; audit log entries are retained per the audit-log retention policy."

F8. /export format and PII surface

The map says /export exists but doesn't bound it.
- What does it include? Saves, memory, sent messages, received digests?
- Is it sent in-band over the messaging adapter (Signal attachment limits, SimpleX delivery?), or is there an out-of-band channel?
- This is a privacy-critical surface. Fix: spec the contents and delivery channel explicitly.

F9. Slow-start probation duration & write-block surface

D45 is referenced, but the map says "probation period" without saying:
- Is the duration profile-aware or fixed?
- Does a /vouch from any bot admin clear it, or only the issuer?
- Which commands are read-only vs. blocked? (Map says "restricted read-only command subset" — needs enumeration in commands.md.)
- Fix: the map can stay terse if it points at commands.md for the exact subset. Make sure that file actually enumerates it.

F10. Invite-code lifecycle (D44)

Map says invite codes are UUIDs bound to (contact_id, adapter). Open:
- Are they single-use? (They should be.)
- Do they expire? (They should — unbounded validity is a leak surface.)
- What happens on collision / re-issue?
- Are they rate-limited per admin?
- Audit logged on issue and on redemption?
- Fix: these are security commitments → must be pinned in spec/security.md, not deferred to design.

F11. Asset command "operator-configurable" conformance loophole

§4: "a v1 deployment is conformant whether or not bootstrap-assets.json is provided." Combined with: "asset commands are disabled and do not appear in /help."
- Good for optionality. But: does /zcash invoked when disabled return a "command not found" response, or a specific "not configured" reply? The first leaks no info; the second does. Pick one and pin it.
- The "rest of v1 ships as normal" phrasing is cute but if asset commands are disabled, the controlled vocabulary, rate-limit budget, and /help rendering all change. Make the diff explicit in design.

F12. Source kinds enumeration vs. extensibility

§5 lists rss, nitter, bluesky, odysee, youtube, reddit, nostr as v1 source kinds. But:
- Bootstrap-sources.json upserts by (kind, identifier) — what validates kind? An enum (closed), or an open string (rejected at SPI lookup)?
- "Extensibility" requires an answer. Closed enum is safer; open string with registry lookup is more flexible.
- Fix: state the model. Recommendation: closed enum at SPI level + clear "to add a kind, add a Fetcher/StreamSource impl + register it" line.

F13. "Bare URLs (no markdown link syntax)" — adapter assertion timing

CLAUDE.md says: "v1 adapters additionally assert supportsMarkdownLinks=false."
- Is this asserted at adapter registration (startup-fail-fast) or per-message (runtime check)? The former is safe; the latter creates a window.
- Fix: spec it as startup invariant.

  ---
3. Findings — Security issues to harden

S1. Last-admin protection across adapters: race condition surface

CLAUDE.md: "last-admin protection counts is_admin=true rows globally across adapters." Two concurrent /revoke-admin calls could both pass the precheck and leave zero admins.
- Fix: require the demote/revoke/ban path to use a SQL-level check (e.g. SELECT ... FOR UPDATE on the admin set, or a check constraint via trigger that counts after the update). Stating it at spec level: "Last-admin invariant is enforced as a
  serializable transaction; concurrent removals must not be able to violate it."

S2. Per-adapter admin threat surface

CLAUDE.md alludes to "Per-adapter admin threat profile for the SimpleX-vs-Signal threat surface." SimpleX queue addresses are reusable / forwardable in ways that Signal ACIs are not. The map should at minimum flag this: a stolen SimpleX queue
address impersonates the admin to the bot.
- Fix: either (a) require admin operations to come over the highest-trust adapter only, or (b) require a confirmation step for destructive admin commands (/ban, /revoke-admin, /grant-admin) — e.g. send a code, expect echo. Currently the spec is
  silent on whether a single message is sufficient authorization for /ban.

S3. LLM tool surface — admin operations excluded, but what about read tools?

CLAUDE.md asserts: "admin operations are NEVER exposed as LLM tools." Good. Open question:
- The chat agent has recallMemory (per §4 deferred-list comment). What other tools exist? Does recallMemory respect per-(user, scope) isolation when invoked by the LLM, or could a clever prompt extract another user's memory because the tool
  itself enforces scope from a context that the LLM controls?
- Fix: state at spec level: "All LLM tools are bound to the invoking (user, scope) at construction time; tool arguments cannot widen scope." This is the kind of invariant prompt-injection attacks try to break.

S4. Prompt injection from ingested posts

The pipeline runs LLM stages over fetched posts (security check, tagging, entity extraction). A post body is untrusted input. The map mentions "Layered ingest security" and "quarantine review" but doesn't say:
- Are post bodies ever concatenated into a prompt where an LLM also sees user prose? (If yes — e.g. summarization — that's a prompt-injection vector against the summarizer, not just the security check.)
- Are posts wrapped in a delimiter convention the model is trained/prompted to ignore?
- Fix: require spec/security.md to enumerate the trust boundary between system prompt, user prose, and ingested content at every LLM call site. The summarizer is the riskiest — a malicious RSS item could try to make the bot include
  attacker-controlled text in a digest.

S5. Nostr signature verification: timing

D38 says "per-event signature verification before Stage 1." Good. But:
- Is the verification done in the StreamSource impl (per relay) or post-relay-merge (once)? Verifying per-relay protects against a compromised relay injecting forged events; verifying after merge is cheaper but trusts every relay equally.
- Cross-relay dedup: dedup before or after verify? If after, a forged event from a bad relay can DoS the dedup table. If before, you risk discarding a valid event in favour of a forged duplicate.
- Fix: spec must say verify before dedup, per event from each relay.

S6. Audit log integrity & retention

D37 references a "log policy that keeps user-authored prose out of non-audit logs." Implies an audit log exists but the map says nothing about:
- Tamper resistance (append-only? signed?).
- Retention policy.
- Who can read it (no admin command for it is mentioned).
- Fix: even a one-paragraph commitment in spec/security.md would close this gap. At minimum: "Audit log is append-only at the database level; retention is operator-configured with a hard floor."

S7. Rate limiting — invisible at the map

The map mentions "rate-limit budget" only in passing under deferred items (re: /recall). v1 must have rate limits per (user, scope) for:
- LLM-touching commands (cost protection).
- /stop//retry flapping (prevents starvation).
- Failed invite-code attempts (prevents enumeration).
- Failed @mentions from unregistered group members (prevents log/quarantine flooding).
- Fix: the existence of rate limits at each surface is a behavior commitment → spec. The values are design.

S8. Translation as a leak surface

TranslationProvider SPI sends user messages and/or bot prose to a (potentially external) translator. D37 says user prose is kept out of non-audit logs — does that promise extend to what gets sent to TranslationProvider? An OpenAI-hosted
translator is a third party that sees plaintext.
- Fix: state at spec level whether the translator is a trust-equivalent of the chat LLM (likely yes) and whether the operator must declare the translator endpoint as a data processor.

S9. /export as exfiltration vector for a compromised account

If an attacker steals a Signal/SimpleX session, /export becomes a one-shot data dump command. Slow-start (D45) blocks new accounts but not stolen ones.
- Fix: consider a rate limit + audit-logged + delayed-delivery semantics for /export (e.g. "request acknowledged, export delivered in N minutes" — gives the legitimate user a window to notice).

S10. Bootstrap-sources.json as a privileged input

The loader is idempotent and seeds the controlled vocabulary. If an attacker can write to the file, they can:
- Add a malicious source whose posts go straight through Stage 1 unless the security check catches them.
- Inject tags into the controlled vocabulary that /add-source --tags users will later select.
- Fix: state at spec level that bootstrap files are operator-trust-equivalent (read at startup from a path the operator owns). Forbid runtime reload, or require admin-confirmed reload. Currently the map doesn't say either way.

  ---
4. Findings — Spec hygiene / map-level

H1. SPEC.md duplicates the v1 scope list that exists in decisions.md

§3 explicitly says "SPEC.md does not duplicate" the decision log — but §4 enumerates v1 features that come from decisions D37/D38/D39/D44/D45/D46. As decisions evolve, these two will drift.
- Fix: §4 should be a digest with decision IDs as anchors, not a re-statement. Already partially done (D38/D39/D44/D45 are cited) — finish the job for every bullet.

H2. "What lives in design notes" trailers are referenced but not present here

§6 mentions trailers in each spec section. SPEC.md itself doesn't have one — fine, since it's the map. But verify that every downstream spec file does. (Quick check needed against docs/spec/*.md.)

H3. The deferred section mixes "needs a new SPI" with "policy choice"

For example, "Auto-detect language" is a policy decision (no new SPI), while "Live ticker mode" needs TickerStream. Mixing them blurs effort estimates for v2 planning.
- Fix: group the deferred list by category (policy / new-SPI / new-adapter / new-data-source).

H4. No glossary entry for "outbox"

CLAUDE.md uses "Outbox pattern" prominently. SPEC.md §5 glossary doesn't define it. New contributors reading the map first will be confused.
- Fix: add a glossary entry pointing to architecture.md.

H5. "Periodic morning / evening digests" — the "12h cadence"

SPEC.md says "morning / evening" (per group, per timezone). The deferred IPFS bullet says "existing 12h cadence." These are not the same: morning + evening is 12h apart from one perspective, but "every 12h on a clock" is different. Pick one
phrasing.

  ---
5. Recommended next actions (ordered)

1. Resolve security commitments at spec level, in this order: S1 (last-admin race), S3 (LLM tool scope binding), S4 (prompt injection at summarizer), S5 (Nostr verify-before-dedup), S2 (admin command confirmation), S10 (bootstrap-file trust).
   These should land as edits in spec/security.md with one-line back-references from SPEC.md §4 if they're cross-cutting.
2. Pin invite-code lifecycle (F10/D44) in spec/security.md: single-use, expiring, audited on issue + redemption, rate-limited per issuer.
3. Pin /retry determinism (F5) — this is a user-visible behavior with non-trivial caching implications.
4. Pin /forget and /export boundaries (F7, F8, S9) — both are privacy surfaces and must not be left to design notes.
5. Add glossary entries (F2, H4) — Fetcher, MessagingAdapter, TranslationProvider, Outbox. Cheap, high-value for onboarding.
6. Fix F1 (singular adapter wording) immediately — this one-word change prevents a real implementation mistake.
7. Audit spec/decisions.md vs §4 for drift (H1).
8. Add a "Rate limits" subsection to security.md enumerating where they exist (S7).

  ---
6. Bottom-line evaluation

▎ The spec is implementation-ready in shape, but not yet implementation-ready in content.

The architecture, service split, trust boundaries, and v1/v2 line are sound. A senior engineer can begin scaffolding (Quarkus modules, Postgres schema, outbox, LISTEN/NOTIFY plumbing, SPI interfaces) without ambiguity. But several user-visible
behaviors (/stop, /retry, /forget, /export, slow-start, invite codes) and several security invariants (last-admin race, LLM tool scope, summarizer prompt-injection, audit-log integrity, rate limits) need to be pinned in writing before code lands
— otherwise the implementation will define them by accident, and they'll be expensive to walk back once users depend on the emergent behavior.

The doctrine in §6 ("How to evolve this spec") is exactly the right one to apply here: most of my findings are behavior commitments and therefore belong in docs/spec/, not docs/design/. I'd recommend a single PR pass that lands these
clarifications in the relevant spec/*.md files, then revisits SPEC.md to make sure the map still matches. After that, the project is ready to start the MVP slice (design/00-mvp.md).
