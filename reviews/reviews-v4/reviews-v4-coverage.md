# Reviews-v4 Fix Plan — Coverage Report

Cross-reference of every plan item against the spec edits actually
applied. One line per item: where the fix landed, plus a quick
"verified by grep" cell so each claim is independently checkable.

**Headline change**: A9 was rewritten in-place to keep Signal in v1
(operator directive). The plan's original "defer Signal" framing was
withdrawn; the alternate path (align deployment.md / 00-mvp.md to
SPEC.md/D32, add a per-adapter trust-level rubric to messaging.md)
is the one actually applied. See `reviews-v4-fix-plan.md` §A9 for
the rewritten resolution.

## Section A — Blockers

| # | Item | Landed in | Verified |
|---|------|-----------|----------|
| A1 | `/add-source` non-admin idempotent on existing rows | `commands.md` §Source management; `decisions.md` D14 | "tags unchanged on existing source" appears in commands.md |
| A2 | `NEEDS_REVIEW` post status | `schema.md` §Posts; `security.md` §Re-evaluation job; `verification.md` | `NEEDS_REVIEW` present in 4 places in schema.md, 3 in security.md |
| A3 | D23/D44 onboarding contradiction | `decisions.md` D23 rewritten; `00-mvp.md` notes legacy auto-register path | "DM access is invite-gated" in D23; "legacy auto-register-on-first-DM" in 00-mvp.md |
| A4 | SSRF shared library (`infochat-ssrf`) | `security.md` §SSRF | "infochat-ssrf" present in security.md |
| A5 | `audit_log_view` for Provider role | `security.md` §DB roles; `commands.md` `/audit` | "audit_log_view" appears in commands.md and security.md |
| A6 | Quarantine stored procedures (no raw SELECT) | `security.md` §Quarantine workflow + §DB roles; `commands.md` `/quarantine` | "approve_quarantine\|reject_quarantine" appears in security.md ×3, commands.md ×1 |
| A7 | `asset_config` entity for scheduling/status | `schema.md` §Operational; `architecture.md` Ingest SPIs; `security.md` §DB roles; `deployment.md` §Bootstrap; `commands.md` §Asset commands | "asset_config" in 5 spec files |
| A8 | `price_snapshot` entity defined | `schema.md` §Operational | "Price snapshot" header at line 256 |
| A9 | Signal stays in v1; align deployment + 00-mvp; add trust-level rubric | `decisions.md` D32 (kept); `messaging.md` §Per-adapter trust level and identity; `deployment.md` §Topology; `00-mvp.md`; `SPEC.md` (already correct) | "v1 ships SimpleX, Signal" in D32; per-adapter section in messaging.md |
| A10 | `/save` per-user-globally; Invariant 1 carve-out | `decisions.md` D13; `schema.md` invariant 1 + Per-user state; `commands.md` `/save` | "per-user-globally" in 4 files |
| A11 | `summary_anchor` entity | `schema.md` §Per-scope state; `commands.md` `/retry` references | "Summary anchor" header in schema.md |

## Section B — Majors

| # | Item | Landed in | Verified |
|---|------|-----------|----------|
| B1 | High-water mark `(ready_at, post_id)` cursor | `architecture.md` §Catch-up; `schema.md` §Provider state | "(ready_at, post_id)" in both files |
| B2 | `/compress` and auto-compress LLM-failure path | `llm.md` §Failure handling; `security.md` §Failure handling | "memory checkpoint pending" in both |
| B3 | Stage 1 / kind-filter ordering separated | `security.md` §Nostr | "Ordering at the StreamSource trust boundary" present |
| B4 | Stage 2 BENIGN re-eval keeps redactions | `security.md` §Quarantine workflow + §Re-evaluation job | "Redactions are lifted only by /quarantine approve" |
| B5 | `/retry` digest routing with `command_kind` | `commands.md` `/retry`; `schema.md` §Summary anchor | `command_kind` and `--digest` both present |
| B6 | Sanitizer/translation pipeline ordering | `llm.md` §Pipeline order | "Pipeline order (delivery direction)" header |
| B7 | `/forget` purge set | `commands.md` `/forget`; `verification.md` `/forget` purge | `chat_session`, `summary_anchor`, `chat_memory`, full `saved_post` library all listed |
| B8 | Slow-start allows `/forget` and `/lang` | `security.md` §Slow-start; `decisions.md` D45 | "user's privacy lever" in both |
| B9 | Nostr drain-on-shutdown contract | `architecture.md` `StreamSource` | "Drain on shutdown" header |
| B10 | UID derivation algorithm | `schema.md` §UID derivation | dedicated subsection |
| B11 | `provider_state` CAS, one row per channel | `schema.md` §Provider state; `architecture.md` §Catch-up | "compare-and-swap" in both |
| B12 | `/unfollow-source` v1: group/bot admin only | `commands.md` `/unfollow-source` | "group admin or bot admin only" |
| B13 | `scope_tag` `tag_mode ∈ {ALL, EXPLICIT}` | `commands.md` §Per-scope tag preferences; `schema.md` §Scope preferences | `tag_mode` in both |
| B14 | UNKNOWN → re-eval queue with separate cap | `security.md` §Re-evaluation job | "UNKNOWN-flooding" present |
| B15 | Source identity `(kind, identifier)` is unique key, `config` mutable | `architecture.md` Source identity; `decisions.md` D38 | "unique key is `(kind, identifier)`" |
| B16 | Asset commands enable/disable lifecycle | `commands.md` §Asset commands | "Enable / disable lifecycle" + "Soft-disable" |
| B17 | Re-eval cadence + per-post cap on deployment surface | `deployment.md` §Configuration surface | "re-evaluation cadence" present |
| B18 | Quarantine TTL exemption + admin-review TTL | `schema.md` invariant 6 | "Quarantine rows are exempt" |
| B19 | `invite_type ∈ {CONTACT_BOUND, OPEN_ADAPTER}` | `schema.md` §Identity; `decisions.md` D44 | both enum values present |
| B20 | Verification gaps closed | `verification.md` (multiple sections) | invite-lifecycle, slow-start, fetcher-ladder, drain, sanitizer, pruner, single-instance-lock, BENIGN-parity, UNKNOWN-reeval, kind-filter ordering all listed |
| B21 | Single-instance via `pg_advisory_lock` | `architecture.md` §Deployment topology | "pg_advisory_lock" present |
| B22 | `/source-enable` admin recovery command | `commands.md` §Source management | dedicated entry |
| B23 | Translation cache cross-scope side-channel documented | `security.md` §What's intentionally NOT in v1 | already-existing entry preserved |
| B24 | Bootstrap admin contact-id drift | `deployment.md` §Bootstrap | "Bootstrap admin drift" header |
| B25 | Permanent delivery failure cleanup | `messaging.md` §Failure handling | "Permanent delivery failure cleanup" |
| B26 | Bot removed from group | `messaging.md` §Failure handling | "Bot removed from group" |
| B27 | `/save` on QUARANTINED visibility | `commands.md` `/save` | "Visibility-of-target rules" |
| B28 | `/list-sources --all` URL visibility documented | `security.md` §Source URL visibility; `commands.md` `/list-sources` | "Source URL visibility" header |
| B29 | Group admin auto-promote when zero admins | `security.md` §Authorization model | "first non-banned, non-probation `@mention` wins" |
| B30 | Verification.md hardcoded values stripped | `verification.md` | "30-second timeout" replaced; "11th call" replaced |
| B31 | Decisions log re-sort + D-number policy | `decisions.md` (D43 moved between D42 and D44; policy block added) | D-number policy block; D43 sequencing |
| B32 | `category` field on sources informational | `commands.md` `/add-source` | "informational metadata in v1" |
| B33 | Folded into B22 | n/a | n/a |
| B34 | `/save` cap atomic | `commands.md` `/save` | "atomically" + `SELECT ... FOR UPDATE` |
| B35 | Slow-start lazy auto-promote | `security.md` §Slow-start; `decisions.md` D45 | "passive sweep" in both |
| B36 | invite_code drop EXPIRED status | `schema.md` §Identity; `decisions.md` D44 | "no stored EXPIRED" present |
| B37 | Invariant 5 wording (no intermediate evaluating state) | `schema.md` invariants | "stage-outcome flags ... durable cursor" |
| B38 | NEEDS_REVIEW notification storm throttle | `security.md` §Re-evaluation job | "Throttled NEEDS_REVIEW notifications" |
| B39 | `/stop` worker release: bound orphaned query | `commands.md` `/stop` | "pg_cancel_backend" |
| B40 | `/forget` keep hard purge | no spec change (operator note in design notes) | hard-purge wording preserved in `/forget` |

## Section C — Minors

| # | Item | Landed in | Verified |
|---|------|-----------|----------|
| C1 | `supportsCodeFormatting` rename + `supportsMarkdownLinks=false` | `messaging.md`; `CLAUDE.md` | both flags present |
| C2 | Structured refusal marker (no literal in spec) | `llm.md`; `security.md` | "structured refusal marker" present in both; design-notes trailer updated |
| C3 | Delete misleading "chat-output sanitizer for admin commands" | `decisions.md` D21 rewritten | "LLM-output sanitizer" |
| C4 | Progress notifier templating discipline | `messaging.md` §Progress notifications | "Free-form user-authored text ... never interpolated" |
| C5 | Translation sanity-check criteria | `llm.md` §Failure handling | "non-Latin target scripts" |
| C6 | Translation fallback note from bundle, not hardcoded English | `llm.md` §Failure handling | "fallback note itself is a localization-bundle string" |
| C7 | CoinGecko free public endpoint wording | `decisions.md` D39 | "CoinGecko free public endpoint" |
| C8 | `/get-sources` accepts same flags as `/list-sources` minus `--all` | `commands.md` Discovery | "accepting the same flags except `--all`" |
| C9 | `/summary` empty window friendly reply | `commands.md` `/summary` | "no posts yet" |
| C10 | Localization bundle completeness build-time check | `decisions.md` D43 | "Localization bundle completeness" |
| C12/C29 | `/invite revoke` requires confirm | `commands.md` `/invite revoke`; `decisions.md` D44; `security.md` | "requires confirm" present |
| C14 | `/clear` confirm rationale | `commands.md` `/clear` | "irrecoverable" |
| C15 | Peer-IP change at socket = hard close | `security.md` §SSRF | "peer-IP change observed at the socket layer is a hard close" |
| C16 | `/audit` unknown actor returns "no rows" | `commands.md` `/audit` | "Unknown actor id" |
| C17 | SPEC.md note: group admin can't `/kick` | `SPEC.md` §Deferred | "cannot kick" |
| C18 | Revoke `DELETE` on `source` from non-admin roles | `security.md` §DB roles | "Invariant 4 enforcement" + "DELETE on source ... revoked" |
| C19 | Pagination cap saturation metric | `architecture.md` Per-relay degradation block | "Pagination cap saturation" |
| C20 | `/unfollow-tag --all` bulk reset | `commands.md` §Per-scope tag preferences | "/unfollow-tag --all" |
| C21 | All-relays-bad recovery | `architecture.md` Per-relay degradation block | "All relays in cooldown" |
| C22 | `--include-deleted` flag for `/list-sources --all` | `commands.md` `/list-sources` | "include-deleted" |
| C23 | Message-handle prohibition wording | `messaging.md` §Message handles | "MUST NOT persist a handle to the database" |
| C24 | Provider's `price_snapshot` cache flushes on Postgres reconnect | `commands.md` §Asset commands | "flushed entirely on every Postgres reconnect" |
| C25 | Asset commands optionality cross-referenced | `commands.md` §Asset commands | "bootstrap-assets.json is configured" |
| C26 | Embedding batch retry: not split | `llm.md` Embedding pipeline | "the batch is not split" |
| C27 | At most one in-flight per (user, scope) | `commands.md` Surface conventions | "At most one in-flight" |
| C28 | Pre-banned contact + invite | `security.md` §Invite-code registration | "Pre-banned contact + invite" |
| C30 | 00-mvp.md design-value leaks fixed | `00-mvp.md` Stage 1 + auto-compress wording | concrete values replaced with design-notes pointers |
| C31 | 00-mvp.md broken links | `00-mvp.md` | links now resolve to spec/schema.md, spec/commands.md, design/02-schema.md, design/03-commands.md |
| C32 | Confirm timeouts: per-category split = v2 candidate | `commands.md` Surface conventions | "v2 candidate" |
| C33 | Folded into B2 | n/a | n/a |
| C34 | Soft-deleted source rows boundless growth accepted | `security.md` §What's NOT in v1 | "Boundless growth of soft-deleted source rows" |
| C35 | Folded into A1 | n/a | n/a |
| C36 | Invite brute-force rate limit | `security.md` §Invite-code registration | "Brute-force rate limit" |
| C37 | Folded into B10 | n/a | n/a |
| C38 | Folded into B28 | n/a | n/a |
| C39 | `/export` field-level positive list | `commands.md` `/export` | "field-level positive list" |
| C40 | Cross-scope chat memory invariant | `verification.md`; `schema.md` §Chat memory | "Cross-scope chat memory" / "Cross-scope isolation invariant" |
| C41 | LLM output sanitizer match-set derived from permission matrix | `security.md` §LLM output sanitizer | "Match-set derivation" |
| C42 | Periodic digest staggered window | `commands.md` §Periodic group summaries | "profile-driven window centered on the scope's configured local hour" |
| C43 | Rate-limit bucket groupings explicit | `security.md` §Rate limiting | "Parser-only + DB-read paginated commands" |
| C44 | Quarantine review status enum | `schema.md` §Posts (Quarantine entry); `commands.md` `/quarantine list` | `{PENDING, APPROVED, REJECTED}` |
| C45 | Probation matrix-test dimension | `verification.md` Permission matrix | "× {full-access, probation}" |
| C46 | Pruner registration verification | `verification.md` chat_memory pruner | "the pruner bean is registered at startup" |
| C47 | chat_memory TTL profile + per-property | `deployment.md` Configuration surface | "Memory retention" line |
| C48 | Folded into A7 | n/a | n/a |
| C49 | Operator note: group-admin race | `commands.md` §Operator note: group-admin race | dedicated section |
| C50 | `source_subscription` cascade rules | `commands.md` `/remove-source` | "cascade-deleted" |

## Items not making it into the spec (intentional)

- B40 — operator note about `/forget` latency stays in design notes only (no spec change).
- The "value choices" called out in the plan's §Phasing as "what this plan does NOT do" remain unchosen (quarantine-review-TTL value, auto-compress occupancy threshold). Spec commits to the existence; values live in design notes.
- The operator-secret SPI (mentioned around C7) is **not** specced; it remains a v2 decision.
