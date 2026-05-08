# Reviews-v4 Fix Plan

**Status:** plan draft, not implemented
**Aim:** turn the v4 spec into a bullet-proof contract — deterministic
output, hardened security boundary, comfortable UX, predictable
performance — without taking shortcuts.

This plan was built by reading every line of all six review reports
(deepseek, gemini, gpt, kimik, mimo, opus) and verifying the
load-bearing claims against the live spec. The reports are **all six
distinct** (md5sums differ); a previous review-cycle assumed mimo and
gpt were byte-identical and that was wrong. Every finding in this plan
has been read in its native report and cross-checked against the spec
file it cites.

The reports collectively raised ~150 findings. After de-duplication and
verification: **47 distinct issues**, grouped here as **A** (blockers
that must land before any v1 build can start), **B** (majors that must
land before v1 ships), and **C** (minor / spec-hygiene). Each item
states the verified problem, the resolution, and — where relevant — why
a competing suggestion from one of the reports is the wrong fix.

---

## How findings cluster across reports

A few themes show up in 4+ reports independently. Treat these as
load-bearing, not as one reviewer's opinion:

| Theme | Reports | Section |
| --- | --- | --- |
| `/add-source` globally replaces `bootstrap_tags` for everyone | deepseek F1, mimo F02, gpt F03 | **A1** |
| MVP auto-registration contradicts v1 invite-code gate (D23 vs D44) | mimo F01+F10, gpt F04+F17, opus cross-cut, gemini AUTH-01 | **A3** |
| `NEEDS_REVIEW` referenced but missing from post status enum | kimik F01, gpt F09, mimo F13 | **A2** |
| Auto-`/compress` LLM-failure path is unspecified | gemini COMP-01, mimo F03+F07, kimik F16, opus F-12, deepseek F5, gpt F12 | **B2** |
| Nostr "events will reappear on next relay connection" is protocol-false | kimik F05, deepseek F11, gemini STATE-01 | **B9** |
| `/retry` anchor storage entity unspecified | deepseek F3, opus F-20 | **A11** |
| Asset Fetchers / `price_snapshot` / `asset_config` underspecified | deepseek F2+F4+F12+F21, kimik F08, opus F-02, gpt F13+F14+F24 | **A7+A8** |
| Verification has no invite/probation/Fetcher-failure/StreamSource-drain coverage | gemini AUTH-02+VER-01, mimo F17+F22, gpt F22+F24, opus F-25+F-26+F-27, deepseek F15 | **B20** |

If a finding shows up in only one report, that doesn't make it wrong
— but the cluster table tells you which fixes will be felt across the
spec.

---

## Reports that were misleading or that I declined to follow as written

These suggestions are real findings but the proposed fix is wrong; the
plan adopts a different resolution.

1. **Gemini PERF-01** suggests soft-deleting saves on `/forget` to
   avoid index churn. **Rejected.** D37 explicitly commits `/forget`
   to immediate purge; tombstoning would silently violate the privacy
   commitment. Keep hard delete; the per-user save cap (D13, profile
   driven) already bounds the worst case. If real index churn shows
   up in production, the v2 fix is a per-user saves partition, not a
   tombstone.
2. **Gemini ARCH-01** says `price_snapshot` needs a high-water mark
   like `new_post` does. **Rejected as framed; accepted in spirit.**
   Provider reads `price_snapshot` on demand (every user invocation),
   so a missed `NOTIFY` only stales the in-process cache. The minimal
   fix is to state explicitly that the Provider flushes its
   `price_snapshot` cache on every Postgres reconnect — covered in
   **C24**. Adding a high-water mark would buy nothing.
3. **Opus F-19 / GPT F06** say source identity should be `(kind,
   identifier, config)`. **Rejected the tuple form.** Keep `(kind,
   identifier)` as the unique key; `config` is a mutable value
   attached to the row. A `config_hash`-as-key approach would make
   bootstrap reload create a duplicate row every time the operator
   changes the relay list. (See **B15**.)
4. **Mimo F18** suggests splitting the embedding batch on retry.
   **Rejected.** One-retry-as-is is the spec's existing rule; adding
   a split path increases provider-side surface. State explicitly
   that retry is as-is and operators tune batch size if it correlates
   with failures. (See **C26**.)
5. **Gemini OP-01** suggests per-command-category confirm timeouts.
   **Rejected for v1.** One profile-tunable timeout is enough; the
   per-category split is real complexity that doesn't pay back at v1
   scale. Note as v2 candidate. Mimo F09 / GPT F23 only ask that
   verification.md not hardcode 30 s — **accepted**, see **B30**.
6. **Opus F-21** suggests adding `scope_id` to the translation cache
   key. **Rejected.** That kills the cache hit rate on the very
   case the cache exists for (one digest fanned out to ten group
   members, all in `cs`). Instead, document the cross-scope content-
   equivalence side-channel as an accepted trade-off. (See **B23**.)
7. **Opus F-31** suggests hiding source URLs from `/list-sources
   --all`. **Rejected.** D7 already commits the source row to global
   status; pretending URLs aren't visible to operators is dishonest.
   Document the visibility explicitly so users adding private feeds
   know the contract. (See **B28**.)
8. **Mimo F-15** asks whether missing localization keys fall back to
   English. **Adopted but stricter.** A missing key in a shipped
   bundle is a build failure, not a runtime fallback — see **C10**.
   Runtime fallback would erode determinism on translated surfaces.
9. **Kimik F-14** asks to rename `supportsMarkdownCode` to
   `supportsCodeFormatting` because the current name implies broader
   markdown. **Adopted with a tighter take.** Rename and add an
   explicit `supportsMarkdownLinks=false` invariant on every v1
   adapter, so a future adapter can't silently widen the surface.

Everything else from the reports is either adopted as-is or refined
in the resolution wording below.

---

## Section A — Blockers (must land before v1 build can start)

### A1. `/add-source` must not let any user replace global `bootstrap_tags`
**Verified.** `commands.md` L195–202 says explicitly "the new call's
`--tags` replace the existing `bootstrap_tags` for that source row,"
and source rows are global per D7. A non-admin DM user can therefore
silently rewrite the deterministic-tagger fallback for every other
subscriber. The bootstrap loader will then quietly revert this on the
next Collector restart, so the change is destructive *and* fragile.

**Resolution.** Existing-source path becomes idempotent on the source
row for non-admins:
- `(kind, identifier)` already exists, caller is not bot admin → upsert
  the caller's `source_subscription`, return "subscribed; tags
  unchanged on existing source." `bootstrap_tags` is not touched.
- Bot admin call may supply `--tags` to replace `bootstrap_tags`; this
  is the only path that mutates the global row's tags.
- Per-scope tag preferences continue to flow through `scope_tag`
  (`/follow-tag` / `/unfollow-tag`), not through `/add-source`.

Files: `commands.md` §Source management, `decisions.md` D14, audit-log
entry for tag replacement (admin-only).

### A2. Add `NEEDS_REVIEW` to the post status enum (or a separate review-state field)
**Verified.** `schema.md` L79–80 lists status as `RAW | READY |
QUARANTINED`. `security.md` L406 says re-evaluation exhaustion marks
the post `NEEDS_REVIEW`. An engineer reading schema.md alone will
build a check constraint that the security path violates.

**Resolution.** Two options on the table; pick (a):
- **(a, recommended)** Add `NEEDS_REVIEW` as a fourth status. Document
  transitions: `QUARANTINED → NEEDS_REVIEW` on retry-budget exhaustion;
  `NEEDS_REVIEW → READY` only via `/quarantine approve`; `NEEDS_REVIEW
  → QUARANTINED` only on a later non-`BENIGN` re-eval.
- **(b)** Keep three statuses, add an orthogonal `security_review_state
  ∈ {NULL, NEEDS_REVIEW}` flag. (GPT F09's preferred shape.)

(a) is simpler — one column, four values, end-to-end queries don't
need to combine fields. (b) keeps the visibility decision (`READY`)
separate from review state. The plan picks (a) because every reader
to date has assumed status is one column; widening it is the smaller
delta. If the team prefers (b), trade is that **B14** (UNKNOWN→re-eval)
and **B18** (quarantine TTL exemption) get easier.

Files: `schema.md` §Posts, `security.md` §Failure handling +
§Re-evaluation job, `verification.md`.

### A3. Resolve the MVP/v1 onboarding contradiction (D23 vs D44)
**Verified.** D23 in `decisions.md` still says "auto-register on first
message" verbatim; D44 introduces invite-gated DM access; `00-mvp.md`
auto-registers; `verification.md` only tests the auto-register path.
Six reports independently flag this.

**Resolution.** Replace D23 in place rather than deprecating it:
> **D23 — Onboarding (revised).** DM access is invite-gated (D44).
> Group access registers on first non-banned `@mention` (no invite
> required — group membership is the implicit vouch, per D44). All
> newly registered users enter slow-start (D45). Banned users blocked
> at intake.

Then:
- `00-mvp.md` §4 keeps the auto-register text but adds an explicit
  note: "MVP uses the legacy D23 auto-register path; v1 layers
  invite-gating and slow-start on top — see §5 for the deferred set."
  Add D44, D45, the `invite_code` table, the `probation_until` column,
  `/invite`, `/vouch`, and the slow-start permission matrix to the
  deferred list in §5.
- `verification.md` §Commands and chat removes the auto-register
  test as the only onboarding path; adds the v1 onboarding matrix
  (see **B20**).

Files: `decisions.md`, `00-mvp.md` §4 + §5, `verification.md`.

### A4. SSRF allowlist sharing between Collector and Provider
**Verified.** `commands.md` §Source management says the Provider does
a HEAD probe "through the Collector's SSRF allowlist" — but
`architecture.md` says the two services communicate **only** through
the database. Two readings: shared library, or Provider→Collector
RPC. Pick one explicitly.

**Resolution.** Shared library module — a small Maven module
(`infochat-ssrf`) containing the IP blocklist, DNS-rebind defense,
redirect cap, and timeout caps. Collector and Provider both depend on
it. No Provider→Collector RPC. The architecture's "DB-only" rule
applies to runtime data, not to compile-time code sharing.

Files: `architecture.md` §Inter-service communication, `commands.md`
§Source management, `security.md` §SSRF.

### A5. Provider DB role can read `audit_log` only via redacted view
**Verified.** Provider role has `INSERT`-only on `audit_log`, but
`/audit` is a Provider command that must `SELECT`. As written the
command can't run.

**Resolution.** Add a Postgres view `audit_log_view` that:
- exposes the same columns as `audit_log` minus any redacted fields
  (raw secrets, full contact ids — replaced with the redacted form
  per security.md §Secrets);
- has `SELECT` granted to the Provider role only;
- is the *only* path the Provider role uses to read audit data.

Update `security.md` §DB roles to enumerate the view; update
`commands.md` /audit to reference it. Verification entry: Provider
role `SELECT * FROM audit_log` fails; `SELECT * FROM audit_log_view`
succeeds and returns the redacted columns.

### A6. Provider DB role must be able to approve/reject quarantine without raw SELECT
**Verified.** Same shape as A5: command catalogue says Provider runs
`/quarantine approve`, but role spec says Provider has no read access
to raw original content.

**Resolution.** Two stored procedures:
- `approve_quarantine(quarantine_id, actor_id)` — sets review_status
  to APPROVED, restores the original span into the post, audit-logs
  the action, emits NOTIFY for the affected post.
- `reject_quarantine(quarantine_id, actor_id)` — sets review_status
  to REJECTED, leaves the placeholder permanent, audit-logs.

Provider role has `EXECUTE` on these procedures but no `SELECT` on the
raw-original columns of `quarantine`. The procedures internally read
the original from the privileged table to perform the restore — that
read happens with the procedure's elevated rights, not the caller's.

Files: `security.md` §Quarantine workflow + §DB roles, `schema.md`
§Quarantine, `verification.md`.

### A7. Asset Fetchers need `asset_config` for scheduling and status
**Verified.** `architecture.md` says `price_snapshot` is keyed by
`(asset, sub-verb)` and Asset Fetchers have no source row. D42's
`source.status='failed'` failure model has nothing to flip when
Kraken is down. `/list-sources --all` won't surface unhealthy asset
feeds. Provider has no table to read to know which assets/sub-verbs
are operator-enabled.

**Resolution.** Introduce an `asset_config` entity in `schema.md`:

> **Asset config.** One row per `(asset, sub_verb)` pair. Carries
> `enabled` flag, `default_quote_currency`, `attribution_url`,
> `consecutive_failures`, `last_success_at`, `last_failure_at`, and
> `status ∈ {active, failed, disabled}` (mirroring source.status).
> Bootstrap loader upserts entries from `bootstrap-assets.json` at
> Collector startup; absent entries are flagged disabled, never
> hard-deleted. The Collector's asset Fetcher schedules from this
> table; D42's failure-counter model applies. The Provider has
> `SELECT` on this table and uses it to (a) decide which asset
> commands appear in `/help`, (b) accept or reject sub-verb
> arguments, (c) surface stale-data warnings when `last_success_at`
> is too old.

Files: `schema.md` §Operational (or new §Asset data), `architecture.md`
§Ingest SPIs, `security.md` §DB roles, `commands.md` §Asset commands,
`deployment.md` §Bootstrap.

### A8. `price_snapshot` entity must be defined in `schema.md`
**Verified.** Referenced in 12 places, defined nowhere as a top-level
entity. Partition key, primary key, and INSERT-vs-UPSERT semantics
are all unstated.

**Resolution.** Add to `schema.md` §Operational (or new §Asset data):

> **Price snapshot.** One row per `(asset, sub_verb, captured_at)`.
> Columns: `asset` (FK to asset_config), `sub_verb`, `captured_at`,
> `price`, `currency`, `source_url`, `raw_payload` (JSONB, exactly
> the upstream response's relevant fragment for forensic replay).
> INSERT-only; no updates. Partitioned on `captured_at` and aged out
> by partition drop (Invariant 6) on a profile-driven horizon long
> enough that "the latest snapshot" is always present and short
> enough that the table doesn't grow unbounded. The "latest snapshot"
> query reads the row with the largest `captured_at` for the given
> `(asset, sub_verb)` — backed by an index on
> `(asset, sub_verb, captured_at DESC)`.

Provider role has `SELECT`-only as already specified. NOTIFY
`new_price_snapshot` is the latency optimization; the table read is
the correctness guarantee.

### A9. Reconcile Signal-in-v1 contradiction across SPEC, D32, deployment, and 00-mvp
**Verified.** `SPEC.md` L91 + D32 + `messaging.md` say v1 ships
SimpleX **and** Signal; `deployment.md` L19 says SimpleX-only;
`00-mvp.md` says SimpleX is deferred and only the in-memory adapter
ships. Three contradictory statements.

**Resolution (operator-confirmed direction).** Signal stays in v1.
Align the dissenting files to SPEC.md/D32 rather than narrowing the
v1 surface:

- `SPEC.md` §v1 scope: keep "SimpleX and Signal adapters plus an
  in-memory test adapter" — already correct.
- `D32`: keep "v1 ships SimpleX, Signal, and an in-memory test
  adapter" — already correct.
- `messaging.md` §Goals: keep the SimpleX-and-Signal commitment;
  add an explicit per-adapter trust-level/identity section so the
  Signal contact-id shape, identity assertion, and trust level are
  documented (was: implicit). The `messaging.md` design-notes
  trailer already references the Signal protocol shape — keep it,
  reinforce it.
- `deployment.md` §Topology: change "One messaging adapter backend
  (SimpleX in v1)" to enumerate **both** SimpleX and Signal as
  v1-supported backends; clarify that an operator picks one per
  deployment (not both at once) but either is a first-class v1
  target.
- `00-mvp.md`: keep the MVP slice on the in-memory adapter (the MVP
  is intentionally minimal), and replace any "SimpleX adapter is
  deferred" wording with "SimpleX and Signal adapters are deferred
  past the MVP, exercised in the v1 build" so MVP scope stays
  small without contradicting v1 commitments.
- `D44`: `--adapter <name>` flag stays mandatory; cross-adapter
  isolation clauses (invite codes scoped by `(adapter, contact_id)`)
  stay in place; `users.contact_id` shape remains multi-adapter.
- Add to the "Reports that were misleading" list: the prior plan's
  "defer Signal" framing was rejected by the operator — Signal is
  a firm v1 commitment.

The earlier "defer Signal" framing in this plan is **withdrawn**.
This entry replaces it.

### A10. `/save` scope model: declare per-user, exempt from Invariant 1
**Verified.** D13 says "/save semantics: Per-user only (private even
in groups)"; `schema.md` §Per-scope state describes saved_post under
the per-scope heading; Invariant 1 demands a scope discriminator on
all user-state rows; `/export` and `/forget` filter by `(user, scope)`.
Two engineers will build different things.

**Resolution.** Pick the simpler model: saves are per-user-globally.
- Move `saved_post` out from under §Per-scope state into §Per-user
  state (new heading) or call it out as the explicit exception to
  Invariant 1.
- Update Invariant 1: "Every row that holds user state carries a
  scope discriminator and a scope id, **except `saved_post`** which
  is per-user-globally (D13)."
- Update D13 wording: "/save is per-user across all scopes; a save
  made in DM is visible in every group the user is in, and vice
  versa. This is intentional: saves are personal bookmarks."
- `/forget` from any scope clears the user's entire save list (one
  user, one library).
- `/export` includes the user's full save list regardless of scope —
  document this exception explicitly.

This unifies F02 kimik, F-08 opus, F10 deepseek (chat_session purge —
treated separately in **B7**).

### A11. `/retry` anchor needs an explicit storage entity
**Verified.** `schema.md` says chat_session "carries the retry
anchor" but doesn't say what shape that takes. The anchor must store
the post UID list and clustering output of the original
summary-producing command so `/retry` can replay deterministically.
That payload is not "context window."

**Resolution.** Add a `summary_anchor` entity (or a column on
scope_preferences — they're equivalent at this scale; a dedicated
table is cleaner):

> **Summary anchor.** Per-(user, scope) row capturing the last
> summary-producing command's deterministic payload: command name,
> argument hash, post UIDs (ordered), cluster mapping, generated_at.
> Cleared by any non-/retry input from the same (user, scope) per
> D36. Survives Provider restart for the bounded retry window.

This is a small addition with one big payoff: `/retry` no longer
overloads chat_session, and chat_session's TTL/clear semantics can be
defined cleanly in **B7**.

---

## Section B — Majors (must land before v1 ships)

### B1. High-water mark must use `(ready_at, post_id)` cursor
**Verified.** `architecture.md` L42 says catch-up uses `>
last_ready_post_at`. Two posts with identical `ready_at` lose the
second one forever.

**Resolution.** Cursor is `(ready_at, post_id)`. Catch-up query:
`WHERE (ready_at, post_id) > (:last_ready_at, :last_post_id) ORDER BY
ready_at, post_id`. The high-water mark advances both fields in the
same transaction. `provider_state` carries both. Verification entry:
"two posts with identical `ready_at` are both processed by the
Provider on catch-up after a controlled restart."

### B2. Specify `/compress` and auto-compress LLM-failure path
**Verified by six reports.** Failure-handling tables list every other
LLM stage but compression is absent. Auto-compress is system-triggered
(no user retry possible).

**Resolution.** Add to both `security.md` §Failure handling and
`llm.md` §Failure handling (recap):

> **Compression (manual `/compress` or auto-compress).** LLM
> unreachable, timeout, or schema-violating reply after retry → the
> chat session is **held at the ceiling**: the user's next chat-mode
> message returns a localized friendly error ("memory checkpoint
> pending; please `/compress` manually or try again later"), and the
> session is never silently truncated. Manual `/compress` failure
> surfaces the same error and leaves the session unchanged. The
> escape hatch is `/clear` (which discards the live window — the
> user's choice, not the system's).

Plus, in `llm.md` (or `architecture.md`): pin the auto-compress
trigger category at spec level — "auto-compress fires when the chat
session occupies a profile-driven percentage of the context-window
ceiling, leaving headroom for the compress prompt and reply itself.
The exact percentage lives in design notes." This closes deepseek F5
and gemini COMP-01 in the same fix.

### B3. Stage 1 / kind-filter ordering: separate them cleanly
**Verified.** `security.md` L133–139 says "kind filter is part of
Stage 1" and "applies after the signature check and before any body
interpretation." Stage 1 begins after the post is in the outbox; kind
filter happens before the outbox write.

**Resolution.** Move the kind filter to the StreamSource trust
boundary, not Stage 1. Replace the section with:

> **Ordering at the StreamSource trust boundary.** signature
> verification → kind allowlist → outbox write. Stage 1 (HTML
> sanitization, regex, Unicode normalization) begins at outbox-write
> time and applies to the body of allowed kinds. The kind allowlist
> is **not** part of Stage 1 — it is a Nostr-specific protocol gate
> that prevents disallowed event types from reaching the pipeline at
> all.

### B4. Stage 2 BENIGN re-eval should keep redactions, not lift them
**Verified.** First-pass BENIGN keeps Stage 1 redactions; re-eval
BENIGN lifts them. Same verdict, opposite effect.

**Resolution.** Pick the safer rule: **BENIGN never auto-lifts
redactions, on first pass or re-eval.** Only `/quarantine approve`
lifts them. Update both passages in `security.md` and verification.md.

### B5. `/retry` for periodic group digest: anchor schema and routing
**Verified.** `/retry` is anchored to "the calling (user, scope)" but
periodic digests are system-generated, so a group admin's `/retry` has
no per-user anchor.

**Resolution.** The summary_anchor entity from **A11** carries
`command_kind`. In a group:
- regular member's `/retry` → matches the member's own most recent
  summary anchor in this scope, if it exists.
- group admin's `/retry`, no personal anchor → resolves to the
  group's cached periodic digest, if present and within the retry
  window.
- group admin's `/retry --digest` (new disambiguation flag) → always
  resolves to the digest, never the personal anchor.

Cached digest message handle is **not persisted** (per messaging.md);
after Provider restart `/retry` posts a *new* message noting "replaces
yesterday's cached digest for subsequent reads" — see **B25**.

### B6. Sanitizer/translation pipeline ordering
**Verified.** Multiple reports — order is implied but not explicit.

**Resolution.** Pin the pipeline in `llm.md`:

> **Order, in delivery direction:** LLM prose → output sanitizer →
> TranslationProvider (skipped if scope language is English) → output
> sanitizer (re-run on translated text) → translation cache (key:
> `(hash(English text), target_language)`, value: post-sanitizer
> translated text) → adapter delivery.

The double-sanitization is intentional: the translator is itself an
LLM and can introduce admin-command strings.

### B7. `/forget` purge: define the exact set
**Verified.** `/forget` purges chat_memory and saved_post. chat_session
(live history) is not on the list. saved_post being per-user-globally
(per **A10**) needs explicit handling.

**Resolution.** `/forget`, called from any scope, purges:
- `chat_memory` rows for `(caller, calling_scope)`;
- `chat_session` rows for `(caller, calling_scope)` (was missing);
- `saved_post` rows for the caller — globally, regardless of scope
  (per **A10**);
- `summary_anchor` rows for `(caller, calling_scope)` (defensive).

Audit-logged. Confirm-required. Verification entry covers all four
tables.

### B8. Slow-start tier: allow `/forget` and `/lang` during probation
**Verified.** D45 blocks `/forget` and `/lang`. `/forget` is the
user's privacy lever; blocking it during probation undermines D37.
`/lang` is a single-row UPDATE with no LLM cost; blocking it means a
non-English new user can't get help in their language during the
window when they most need it.

**Resolution.** Move both to the allowed list in D45 and `security.md`
§Slow-start tier. The remaining blocked set is still tight enough to
bound damage.

### B9. Nostr "events will reappear on reconnect" — replace with realistic contract
**Verified.** Nostr relays don't universally replay history. Three
reports flag this independently.

**Resolution.** Replace the line in `architecture.md` and add detail
in `security.md` §Nostr:

> **Drain on shutdown.** On graceful shutdown, the StreamSource
> implementation MUST aggressively flush in-flight events to the
> outbox before acknowledging the shutdown signal. Events not drained
> within a profile-driven hard timeout are dropped and **not
> guaranteed to reappear**. On reconnect, the implementation issues
> `since=last_persisted_event_at` per relay; relays that support
> `since` filters will replay missed events, relays that do not may
> produce permanent gaps. A per-relay "events lost on shutdown"
> counter is exposed for operator monitoring.
> **Non-graceful shutdown** (OOM, SIGKILL): same outcome — events
> in-flight at the SIGKILL moment are lost. The counter increments
> based on the gap between last-acknowledged and re-delivered.

This honesty matters: the previous wording invited an implementer
not to implement aggressive flushing.

### B10. UID content-hash fallback algorithm
**Verified.** kimik F03, gemini SEC-02. Without a normative algorithm,
two Collectors produce different UIDs for the same RSS item.

**Resolution.** Add to `schema.md` §Posts:

> **UID derivation.** Stable per-post UID is `sha256(source_id || '|'
> || canonical_body)` lower-case hex-encoded, when the source
> provides a stable upstream identifier the UID is `sha256(source_id
> || '|' || upstream_identifier)` instead. The canonical body is the
> Unicode-NFKC normalized text body, stripped of source-kind-specific
> volatile sections (per-kind normalization rules in design notes —
> e.g. for RSS strip ad-tracking query parameters and `<pubDate>`).
> The UID is stable globally across Collectors and across re-fetches;
> it is the dedup key.

This closes the brute-mutation evasion (gemini SEC-02) by canonicalizing
volatile fields out of the hash input.

### B11. provider_state — one row per channel, atomic CAS update
**Verified.** "Singleton(s)" with parenthetical `(s)` is ambiguous;
concurrent advance can roll back.

**Resolution.** One row per channel: `(channel, last_ready_at,
last_post_id, updated_at)`. Updates use compare-and-swap:
`UPDATE provider_state SET last_ready_at = :new_ready_at, last_post_id
= :new_post_id WHERE channel = :ch AND (last_ready_at, last_post_id)
< (:new_ready_at, :new_post_id)`. The CAS prevents a slow processor
from rolling back a fast one's mark.

### B12. `/unfollow-source` permission in groups: simplify, no per-adder column
**Verified.** Spec says "any group member may unfollow a subscription
they added," but `source_subscription` has no `added_by` column.

**Resolution.** Simplify the permission for v1: in groups,
`/unfollow-source` is **group admin or bot admin only**. The "added
by me" exception is removed. Rationale: per-contributor ownership
adds a column, a contributor sub-table, and "last-contributor leaves
the group with zero subscribers" edge cases — all for a feature no
report flags as necessary. Bring it back in v2 if user complaints
materialize.

### B13. `scope_tag` mode: introduce explicit `tag_mode = ALL | EXPLICIT`
**Verified.** Absence-of-rows = "all tags" is ambiguous: `/unfollow-tag`
on an empty set has no defined effect, and the digest query has no
clear source for "all tags."

**Resolution.** Add `scope_preferences.tag_mode ∈ {ALL, EXPLICIT}`
default `ALL`.
- In `ALL` mode: `/follow-tag` flips to `EXPLICIT` and seeds rows for
  the followed tag only (operator can choose this or seed all current
  tags then add — pick "seed only the followed tag," it matches the
  user's mental model).
- In `ALL` mode: `/unfollow-tag` flips to `EXPLICIT` and seeds rows
  for all currently subscribed-source `bootstrap_tags` minus the
  unfollowed tag.
- In `EXPLICIT` mode: `/follow-tag` and `/unfollow-tag` add/remove
  rows. When the row count drops to 0, mode flips back to `ALL`.

Digest query is then unambiguous: `ALL` mode = union of subscribed
sources' `bootstrap_tags`; `EXPLICIT` mode = intersect with `scope_tag`
rows.

### B14. UNKNOWN verdict → re-eval queue
**Verified.** UNKNOWN is permanent quarantine until manual review;
on weak hardware profiles this floods the queue.

**Resolution.** Add UNKNOWN to the re-evaluation job's target set
with a **separate, lower retry cap** (so an UNKNOWN-flooding model
exhausts attempts faster than infrastructure failures). After
exhaustion → `NEEDS_REVIEW` (per **A2**). Operator notification when
sustained UNKNOWN rate exceeds threshold (already alerts in
deployment.md, just confirm).

### B15. Source identity tuple: `(kind, identifier)` is the unique key, period
**Resolution** (already framed in §misleading-suggestions). Update
`schema.md`, `D38`, and `architecture.md` to use a single sentence
verbatim:

> **Source identity.** The unique key is `(kind, identifier)`. The
> per-kind `config` block is a mutable value attached to that key;
> bootstrap reload and bot-admin source maintenance update it in
> place. `config` is **not** part of the unique key.

### B16. Asset commands enable/disable lifecycle
**Verified.** Bootstrap-assets.json is optional; absent file disables
asset commands; the spec doesn't say where the enable/disable state
lives or how `/help` discovers it. Covered structurally by **A7**
(asset_config entity); this item is the surface-spec follow-up.

**Resolution.** In `commands.md` §Asset commands add:
> Asset commands are enabled only when `bootstrap-assets.json` is
> configured and contains the asset. When disabled, the command does
> not appear in `/help` and an attempted invocation returns the
> "not configured" friendly error. Soft-disable (asset present in
> bootstrap then later removed) sets `asset_config.enabled = false`
> on the next bootstrap reload; the row and historical
> `price_snapshot` data are preserved for audit.

### B17. Re-evaluation cadence + per-post attempt cap on the deployment surface
**Resolution.** Add to `deployment.md` §Configuration surface:
"Re-evaluation cadence and per-post attempt cap (profile-driven)."
One line; closes kimik F10.

### B18. Quarantine TTL exemption
**Verified.** Invariant 6 partitions `post` and derivatives; quarantine
rows live under "Posts and derivatives." A quarantine row aged out
before review is silent data loss.

**Resolution.** Add to Invariant 6:
> Quarantine rows are **exempt** from automatic TTL: they survive
> until explicitly approved or rejected by an admin. A separate,
> longer admin-review TTL applies (profile-driven; value in design
> notes) so an indefinitely-pending queue does not grow forever; rows
> aged past the admin-review TTL are not auto-released, they
> auto-`reject` and the placeholder becomes permanent.

### B19. `invite_code.invite_type`: model OPEN vs CONTACT_BOUND explicitly
**Resolution.** Add to `schema.md` §Identity:
> Invite codes carry `invite_type ∈ {CONTACT_BOUND, OPEN_ADAPTER}`,
> `adapter`, `expected_contact_id` (nullable), `expires_at`, `status
> ∈ {PENDING, USED, REVOKED}` (see **C36** — EXPIRED removed).
> CHECK constraint: `expected_contact_id IS NOT NULL` iff `invite_type
> = CONTACT_BOUND`. Single-use atomicity: state transitions to USED
> via `UPDATE invite_code SET status = 'USED' ... WHERE status =
> 'PENDING' AND code = $1 AND ... RETURNING ...` — a race-safe
> consume.

### B20. Verification gaps: invite/probation/Fetcher-failure/StreamSource-drain
**Resolution.** Add to `verification.md`:
- **Invite-code lifecycle:** create CONTACT_BOUND → wrong-contact
  reject → matching-contact accept; create OPEN_ADAPTER → cross-adapter
  reject → first unknown contact accept; expired code reject; revoked
  code reject; replayed USED code reject; concurrent-race on
  OPEN_ADAPTER produces exactly one USED transition and one new user
  row; pre-banned contact + invite path is rejected at intake.
- **Slow-start:** every write command and chat-mode rejected during
  probation with the localized probation reply; allowed list (after
  **B8** changes) is fully unblocked; `/vouch` immediately graduates;
  probation expiry (`probation_until < NOW()`) auto-promotes on next
  request without admin action.
- **Fetcher failure ladder:** N consecutive failures → status='failed',
  N-1 does not; admin notification fires throttled.
- **StreamSource drain:** graceful shutdown drains in-flight events;
  hard-killed test produces a counter increment; reconnect with
  `since=...` retrieves missed events from a replay-supporting fake
  relay.
- **Sanitizer set:** every command in the bot-admin and group-admin
  permission rows appears in the LLM output sanitizer match set
  (CI-derived, not hand-maintained — see **C41**).
- **Pruner:** chat_memory pruner bean is registered at startup; runs
  on the configured cadence; deletes rows older than the horizon.
- **Single-instance lock:** second Collector or Provider startup
  fails to acquire the advisory lock and exits non-zero (see **B21**).
- **Stage 2 re-eval BENIGN parity:** verdict on re-eval keeps
  redactions, matching first-pass behavior (per **B4**).

### B21. Single-instance topology: enforce via pg_advisory_lock
**Verified.** D41 says "running more than one instance is
unsupported" but provides no enforcement.

**Resolution.** Add to `architecture.md` §Deployment topology:
> Each service acquires a named `pg_advisory_lock` at startup
> (`infochat.collector` and `infochat.provider`). A second instance
> attempting to acquire the lock fails fast with a fatal log message
> pointing at the running instance's host id (recorded in a heartbeat
> row updated every N seconds). The lock is released on graceful
> shutdown; on hard kill the heartbeat staleness eventually
> invalidates the prior holder.

### B22. Source-status admin recovery command
**Verified.** Spec commits to "failed → active is set by an admin
recovery command" but no such command appears in the catalogue.

**Resolution.** Add to `commands.md` §Source management:
> `/source-enable <id>` — bot-admin only, transitions a `failed` or
> `disabled` source back to `active`. Emits a probe before the
> transition; probe failure leaves the source in its prior state with
> a friendly error. Audit-logged. Resets the consecutive-failure
> counter on success.

### B23. Translation cache: document content-equivalence side-channel
**Resolution.** Add to `security.md` §"What's intentionally NOT in v1"
trade-offs:
> The translation cache is keyed by `(hash(English text),
> target_language)` and is **not scoped per (user, scope)**. Two
> users in different scopes who happen to receive identical English
> prose will see a cache hit on the second request — content
> equivalence across scopes is therefore observable as a side-channel.
> This is acceptable in v1 because translated content is bot-authored
> prose (no per-scope private user data passes through the
> translator). Per-scope cache keys would kill the fan-out benefit
> the cache exists for (one digest fanning out to ten group members)
> and are not a v1 commitment.

### B24. Bootstrap admin contact-id change: clarify drift behavior
**Resolution.** Add to `deployment.md` §Bootstrap behavior:
> If the configured `infochat.admin.contact-id` does not match an
> existing `is_admin=true` row, Provider creates a new admin row
> (audit-logged) and leaves any prior admin rows in place. Pruning
> stale bootstrap admins is an operator action via `/revoke-admin`
> from the new admin's chat. Last-admin protection (Invariant 2)
> still applies: the prior admin row cannot be revoked until a second
> active admin exists, which the new bootstrap row provides.

### B25. Permanent messaging adapter delivery failure: state cleanup rules
**Resolution.** Add to `messaging.md` §Failure handling:
> Permanent delivery failures (user blocked the bot, group lost,
> credentials revoked) abort the reply without advancing chat session
> state — the context window remains as if the message was never
> generated, and `chat_memory` is not written. For periodic group
> digests, the failure is logged; the next slot retries; sustained
> permanent failure on a group triggers the bot-removed-from-group
> handler (see **B26**). The retry queue does not re-attempt
> permanent failures.

### B26. Bot removed from group: cleanup
**Resolution.** Add to `messaging.md` and `schema.md`:
> When the adapter detects the bot has been removed from a group
> (adapter-specific signal, OR repeated permanent send failures past
> a profile-driven threshold), Provider sets `groups.removed_at =
> NOW()` and cancels the periodic-digest scheduler entries for that
> group. The row is preserved for audit; on re-add the adapter signal
> clears `removed_at`. Group state (subscriptions, scope_tag,
> chat_memory) is **not** purged automatically — preserved against
> accidental remove/re-add cycles. Cleanup of long-removed groups is
> a v2 admin command.

### B27. `/save` on QUARANTINED post
**Resolution.** Add to `commands.md` §`/save`:
> `/save` on a `READY` post snapshots the visible body. `/save` on a
> `QUARANTINED` post with a `BENIGN` Stage 2 verdict (visible with
> Stage 1 redactions) snapshots the redacted body. `/save` on a
> `QUARANTINED` post with `INJECTION`, `MALWARE`, or `UNKNOWN`
> (hidden — invisible to the user) is treated as an unknown UID.
> `/save` on a `NEEDS_REVIEW` post follows the visibility of its
> stage-1 redactions (same as Stage 2 BENIGN).

### B28. `/list-sources --all` URL visibility — document it
**Resolution.** Add a one-line note in `commands.md` §`/list-sources`
and `security.md`:
> Source URLs are global state. Users adding private feeds via
> `/add-source` should treat the URL as visible to bot admins. This
> follows from D7's global source model — there is no per-user
> source row.

### B29. Group admin auto-promote when zero admins
**Resolution.** Replace the "first @mention in a new group" wording
in `security.md` with:
> The "first non-banned, non-probation `@mention` wins" auto-promote
> rule applies whenever the group has zero `is_group_admin` rows —
> covering both newly-created groups and groups left without an
> admin due to demotion or ban. Banned and probation users are
> ineligible (probation users cannot run admin commands by D45;
> giving them admin would be a footgun).

### B30. Verification.md: strip hardcoded values
**Resolution.** Replace:
- "30-second timeout rejects late confirms" → "a confirm arriving
  past the configured profile timeout is rejected."
- "rejects the 11th call in a window" → "rejects the call that
  exceeds the profile-configured cap."

### B31. Decisions log: re-sort and add policy
**Resolution.** Re-sort the decisions table by D-number (move D43 to
between D42 and D44 instead of after D45). Add a one-line policy
above the table:
> D-numbers are append-only. They are never reused, never reordered.
> Edits to an existing row require a sync-check pass against every
> spec section that cites it.

### B32. `category` field on sources
**Resolution.** Declare informational and add the flag:
> `category` is informational metadata on the `source` row (one of
> `news`, `blog`, `social`). It is not used for retrieval or
> filtering in v1. `/add-source` accepts `--category <name>` with a
> default of `news` for user-added sources. v2 may attach behavior
> to it (e.g., chat-agent tool filter); v1 commits to nothing.

### B33. (covered above as B22)

### B34. `/save` cap atomicity
**Resolution.** Add:
> The per-user save cap is enforced atomically. The implementation
> uses `SELECT ... FOR UPDATE` on the user's save-counter row, or
> equivalently a CHECK constraint on a derived counter, such that
> two concurrent `/save` calls at cap-1 admit exactly one. The
> verification entry asserts this race-free behaviour.

### B35. Slow-start auto-promote mechanism
**Resolution.** Make explicit in `security.md` §Slow-start tier:
> The permission step uses `probation_until IS NULL OR probation_until
> < NOW()`. The user is promoted at the instant `NOW() >
> probation_until`, regardless of whether the column has been nulled.
> A lazy sweep clears the column on the next request from a promoted
> user; no background job is required.

### B36. invite_code: drop `EXPIRED` as a stored status
**Resolution.** Status enum is `{PENDING, USED, REVOKED}`. The intake
path treats a row with `status='PENDING' AND expires_at < NOW()` as
"expired" — same friendly fixed reply as a missing code. No scheduled
sweep, no denormalization. (deepseek F22)

### B37. Invariant 5 wording: drop "intermediate evaluating state"
**Resolution.** Replace Invariant 5:
> **Outbox.** Posts are persisted before they are enqueued for
> evaluation. A startup rehydrator picks up any post left in `RAW`
> after a crash. Posts in `RAW` with one or more stage-outcome flags
> already set resume from the next uncompleted stage; the per-stage
> flags are the durable cursor.

### B38. NEEDS_REVIEW notification storm: throttle
**Resolution.** Add to `security.md` §Re-evaluation job:
> Admin notifications for `NEEDS_REVIEW` transitions are coalesced
> per `(channel, error_class)` over a profile-driven window — so a
> Stage-2 outage that exhausts retries on hundreds of posts produces
> one summary notification, not hundreds. (Mirrors the Stage-2
> infra-failure notification policy.)

### B39. `/stop` worker release: bound the orphaned DB query
**Resolution.** Add to `commands.md` §`/stop`:
> The released DB query is best-effort cancelled via
> `pg_cancel_backend(pid)` at the released connection so the
> connection pool is not drained by long-running orphans. A
> profile-driven `statement_timeout` is also applied to all
> interruptible read-only queries (chat-mode tool calls, on-demand
> `/summary`) as a hard ceiling.

### B40. `/forget` on large libraries: keep hard purge, document operator concern
**Resolution.** No spec change — keep hard purge. Add a one-line
note in design notes (not spec) flagging that operators with very
high save caps should monitor `/forget` latency. (Reject gemini
PERF-01's tombstone suggestion; see misleading-suggestions list.)

---

## Section C — Minors and spec hygiene

These are one-or-two-sentence fixes; collected here for the
implementation pass.

- **C1.** Rename `supportsMarkdownCode` to `supportsCodeFormatting`
  in `messaging.md` capability flags; add invariant
  `supportsMarkdownLinks=false` for every v1 adapter.
- **C2.** Replace `[refused-action]` literal in `llm.md` with
  "structured refusal marker (literal in design notes)."
- **C3.** Delete "chat-output sanitizer for admin commands;" from
  `security.md` — admin commands never pass through the LLM, so
  this phrase is misleading.
- **C4.** Progress notifier templating: "Stage strings are template-
  parameterized with deterministic, sanitized scalar values
  (post counts, controlled-vocabulary tag names) only. User-authored
  free text (custom personal tags, free-form chat) is never
  interpolated." (kimik F13)
- **C5.** Translation sanity-check criteria: "fails when (a) provider
  returns HTTP error, (b) output equals input, (c) output is empty
  or whitespace-only, (d) for non-Latin target scripts, output
  contains zero target-script characters; for Latin target scripts,
  output is byte-identical to input. The exact threshold for (d)
  lives in design notes."
- **C6.** Translation fallback note is a localization-bundle string
  (D43), not hardcoded English. (deepseek F25)
- **C7.** D39 wording for CoinGecko: "CoinGecko free public endpoint
  (no API key required); fall back to Kraken+Bitfinex if CoinGecko
  policy changes. Operator-provided API keys for free tiers are
  deferred to the operator-secret SPI in v2."
- **C8.** `/get-sources` accepts the same flags as `/list-sources`
  except `--all`. (kimik F26)
- **C9.** `/summary` with no eligible posts in window returns a
  friendly "no posts yet" reply, not an empty summary. (kimik F24)
- **C10.** Localization bundle completeness is enforced at build
  time: each shipped language bundle MUST contain every key in the
  registry; CI fails on a missing key. Missing keys in `en` are a
  startup error.
- **C11.** (folded into B38)
- **C12.** Update D44 to include "/invite revoke requires confirm"
  to match `commands.md`.
- **C13.** Adapter trust-level rubric in `messaging.md`:
  "SimpleX: HIGH (cryptographic queue address). InMemory:
  configurable, defaults LOW so tests opt into admin paths." Signal
  rubric deferred with the adapter (per **A9**).
- **C14.** `/clear` confirm rationale: "Confirm is required because
  the live window is the only bridging context for an evolving
  conversation; an accidental `/clear` is irrecoverable." (opus F-24)
- **C15.** SSRF section: "For long-lived StreamSource connections,
  the implementation MUST treat any peer-IP change observed at the
  socket layer as a hard close, not transparently accepted as a
  connection migration." (opus F-32)
- **C16.** `/audit --actor <id>` against an unknown id returns "no
  audit rows" — same response as a known id with no rows. The
  existence-vs-no-rows distinction is not exposed; bot admins
  enumerate via the existing audit history. v1 ships no `/list-users`
  command. (opus F-33)
- **C17.** SPEC.md §Deferred adds: "Group admins in v1 cannot kick a
  misbehaving member; escalate to a bot admin for `/ban`." (opus F-34)
- **C18.** Invariant 4 enforcement: revoke `DELETE` on `source` from
  Collector and Provider roles. Admin-role psql is the only path
  that can hard-delete (and that's a manual escape hatch). (opus F-28)
- **C19.** Pagination cap saturation metric: "fetcher pagination cap
  hit per source per tick" counter exposed; admin notification (one,
  throttled) when a source consistently saturates the cap.
- **C20.** Add `/unfollow-tag --all` (requires confirm) to handle
  bulk reset to ALL mode in v1. (deepseek F17)
- **C21.** All-relays-bad: "When all configured relays are in
  cooldown, the StreamSource waits until the earliest cooldown
  expires; admin notification fires once per all-relays-bad
  transition (throttled)."
- **C22.** Add `--include-deleted` (bot-admin only) to
  `/list-sources --all`. (deepseek F24)
- **C23.** Message-handle prohibition wording: "MUST NOT persist a
  handle to the database or pass it between service instances.
  Holding in memory for a single request's processing is the
  intended use." (deepseek F26)
- **C24.** Provider's `price_snapshot` cache flushes entirely on
  every Postgres reconnect; closes the gemini ARCH-01 concern
  without adding a high-water mark.
- **C25.** Asset commands optionality is referenced in
  `commands.md` §Asset commands ("enabled only when
  `bootstrap-assets.json` is configured; absent or empty file means
  no asset commands appear in `/help`"). Already cross-referenced
  to `deployment.md`.
- **C26.** Embedding batch retry policy: "On retry, the batch is
  not split; same batch resubmitted. If batch-size correlates with
  failures, operators reduce batch size in design notes." (mimo F18)
- **C27.** Concurrent requests per (user, scope): "At most one
  interruptible request in flight per (user, scope). A second
  request returns a localized 'request already in progress' reply."
  (mimo F20)
- **C28.** Pre-banned contact + invite: "/invite create --contact
  <id> against a banned user returns a friendly error pointing the
  admin at /unban; the invite is not created." (opus F-13)
- **C29.** D44 invite-revoke confirm: ensure D44 wording matches
  commands.md (folded into C12).
- **C30.** 00-mvp.md design-value leaks: replace concrete values
  ("RE2/J or 100 ms watchdog", "Auto-compress at 75%") with
  behavior-only references; or move them to `docs/design/00-mvp.md`.
- **C31.** 00-mvp.md broken links: update to `spec/schema.md` and
  `spec/commands.md` (or `design/02-schema.md` if the intent is the
  design file). Verified: current links resolve to non-existent
  paths.
- **C32.** Confirm timeouts stay one-per-profile in v1; record per-
  command-category split as a v2 candidate. (gemini OP-01)
- **C33.** (folded into B2)
- **C34.** Soft-deleted source rows are accepted as boundless growth
  in v1; explicit operator-only hard-delete via Admin-role psql is
  the cleanup path.
- **C35.** (folded into A1)
- **C36.** Invite brute-force rate limit: per-(adapter, contact_id)
  rate limit on invite-code attempts; failed attempts increment a
  counter, audit-logged when threshold exceeded. (gemini SEC-01)
- **C37.** (folded into B10)
- **C38.** (folded into B28)
- **C39.** `/export` field-level positive list: caller's `users` row
  in full **except** `is_admin`, `banned_by`, `ban_reason`,
  `banned_at`, `probation_until`. Caller's own `audit_log` rows
  (where `actor=self`) are **included**. (opus F-09)
- **C40.** Cross-scope chat memory invariant: explicit in `schema.md`
  §Chat memory: "Recall in scope S retrieves only rows whose scope
  key equals S. DM memory never surfaces in any group; one group's
  memory never surfaces in another." Verification entry. (opus F-10)
- **C41.** LLM output sanitizer match-set is **derived from the
  permission matrix** (every command in the bot-admin or group-admin
  rows is in the sanitizer set). CI fails on mismatch. (opus F-11)
- **C42.** Periodic digest staggered window: "Each digest fires
  within a profile-driven window centered on the scope's configured
  local hour; overload fallback fires when the window-end is reached
  without the digest having started." (opus F-15)
- **C43.** Rate-limit buckets: explicit groupings — "parser-only +
  DB-read paginated commands (`/audit`, `/export`); asset commands
  share the cache-hit bucket; LLM-triggering is its own bucket."
  (opus F-16)
- **C44.** Quarantine review status enum in `schema.md` §Quarantine:
  `{PENDING, APPROVED, REJECTED}`. `/quarantine list` shows PENDING
  by default; `--all` shows all (bot-admin only). (opus F-22)
- **C45.** Probation matrix-test dimension: verification asserts
  "every command × every actor type × {full-access, probation}";
  every write command appears in the probation-blocked list — the
  list is derived from the command registry, not hand-written. (opus
  F-26)
- **C46.** Pruner registration verification: "the pruner bean is
  registered at startup; the deletion test asserts the pruner has
  fired at least once during a deployment-N controlled run." (opus
  F-27, deepseek F14)
- **C47.** chat_memory TTL: profile-driven AND per-property
  overridable; document the property key in `deployment.md`
  §Configuration surface. (deepseek F14)
- **C48.** (folded into A7 — Provider has `SELECT` on `asset_config`)
- **C49.** Group-admin race operator note: in `deployment.md` /
  `commands.md`, "Operators adding the bot to large or public groups
  should `/promote` the intended admin immediately to avoid a
  first-mention race winner who is not the intended owner."
- **C50.** `source_subscription` lifecycle on soft-delete (mimo F05):
  spec the cascade explicitly — "/remove-source (bot admin) sets
  `source.deleted_at` and cascade-deletes all `source_subscription`
  rows for that source. `/unfollow-source` deletes only the caller's
  subscription; the source row stays. The 'no remaining subscribers'
  state does not auto-soft-delete the source — sources can exist
  without subscribers and be re-followed."

---

## Phasing

The fixes are independent enough that they can land in three passes:

**Phase 1 — Blockers (Section A).** ~1 working week of spec writing.
A1, A3, A9 unblock every implementer's first read. A2, A7, A8, A10,
A11 lift the schema commitments to "buildable." A4, A5, A6 close the
admin-command vs DB-role contradiction so migration files can be
written.

**Phase 2 — Majors (Section B).** ~2 working weeks of spec writing
plus verification.md additions. Most items are short — the bulk is
B20 (verification gap closure), which adds ~10 verification entries
to existing test-strategy fixtures.

**Phase 3 — Minors (Section C).** Done in passes alongside Phase 1
and 2 wherever a spec section is already open for editing.
Independently they are too small to schedule.

After Phase 1 the spec is buildable for v1 with no remaining
blockers. After Phase 2 the privacy story is externally defensible
and verification covers v1 (not just MVP). Phase 3 is hygiene.

---

## What this plan does NOT do

- **Pick a quarantine-review-TTL value** (B18). The plan commits to
  the existence of an admin-review TTL; the value is design-notes
  territory.
- **Pick the auto-compress occupancy threshold** (B2). Same — the
  plan adds the spec-level category, the value is profile-driven.
- **Decide whether to ship Signal in v1** (A9). The plan picks
  *defer* as the simpler reconciliation; if the team disagrees, swap
  A9 for the alternate write-up (update deployment.md and 00-mvp.md
  to enumerate both adapters; add Signal trust-level rubric).
- **Spec the operator-secret SPI** (related to C7). That is its own
  decision row; the plan assumes it stays out of v1.
- **Implement anything.** This is a spec fix plan; code changes
  follow once the spec lands.

---

## Confidence and risk

The Section A items have been verified against the spec text directly
— all six "blocker" claims are real, not reviewer overreach. Section B
items are mostly verified; the few medium-confidence items (B14
UNKNOWN re-eval, B18 quarantine TTL exemption value, B26 bot-removed
threshold) are flagged in their entries.

The principal residual risk is the volume of edits: the spec has 11
files and many of these changes touch 3–4 of them at once. The plan
deliberately groups by topic (not by file) so a single editing pass
hits every file consistently. Decisions log discipline (B31) is the
load-bearing change for keeping it that way long-term.
