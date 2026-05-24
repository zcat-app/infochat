package app.zcat.infochat.provider.bundle;

/**
 * Compile-time constants for every deterministic-bundle key the Provider
 * looks up via {@link BundleLoader}. Per decision D43 and
 * {@code docs/design/03-commands.md} §3.4, every user-visible
 * deterministic string Provider emits — slash-command help text,
 * friendly errors, the chat-mode-not-in-MVP stub reply — flows through
 * the bundle so T2-C can drop {@code cs.properties} in without code
 * changes.
 *
 * <p>A typo in a key name fails at compile time at the call site
 * (the constant disappears); the bundle-completeness assertion in
 * {@code BundleLoaderTest} catches a typo at test time when the
 * constant resolves to a missing bundle entry. The assertion
 * iterates every {@code public static final String} on this class
 * via reflection, so adding a new constant here automatically
 * extends the CI guard at the next test run — no test edit
 * required.</p>
 */
public final class BundleKeys {

    /** Header line at the top of the {@code /help} reply for the DM-user actor tier. */
    public static final String HELP_HEADER_DM_USER = "help.header.dm-user";

    /** Short-help line for {@code /help} itself. */
    public static final String HELP_CMD_HELP_SHORT = "help.cmd.help.short";

    /** Short-help line for {@code /add-source}. Authored here so T1-F's implementation lands without bundle churn. */
    public static final String HELP_CMD_ADD_SOURCE_SHORT = "help.cmd.add-source.short";

    /** Short-help line for {@code /summary}. Authored here so T1-F's implementation lands without bundle churn. */
    public static final String HELP_CMD_SUMMARY_SHORT = "help.cmd.summary.short";

    /** Deterministic reply for an unknown slash command. InboundRouter still uses its own literal at M1-035b's commit; replacing the literal with this lookup is a post-umbrella follow-up. */
    public static final String ERROR_UNKNOWN_COMMAND = "error.unknown_command";

    /** Deterministic reply for any uncaught dispatch exception. Same M1-035b literal/bundle divergence note as {@link #ERROR_UNKNOWN_COMMAND}. */
    public static final String ERROR_INTERNAL = "error.internal";

    /** Deterministic reply for non-slash chat input until T2-D wires the chat dispatcher. Same M1-035b literal/bundle divergence note. */
    public static final String CHAT_MODE_NOT_IN_MVP = "chat_mode.not_in_mvp";

    // ----- /add-source friendly errors (M1-036) ---------------------------
    // Per docs/spec/commands.md §Source management. Every parse / probe
    // / permission rejection path the spec assigns a friendly error to
    // is keyed here so the handler never interpolates an exception
    // message into the user-visible body. {0}/{1} interpolation tokens
    // are filled by the caller via java.text.MessageFormat.

    /** {@code /add-source} called without {@code --tags}, or with empty {@code --tags=}. */
    public static final String ERROR_ADD_SOURCE_TAGS_REQUIRED = "error.add_source.tags_required";

    /** {@code /add-source --type=<unknown>} — value is not in the closed {@code source.kind} set. */
    public static final String ERROR_ADD_SOURCE_UNKNOWN_KIND = "error.add_source.unknown_kind";

    /** {@code /add-source --category=<unknown>} — value is not in {@code news|blog|social}. */
    public static final String ERROR_ADD_SOURCE_UNKNOWN_CATEGORY = "error.add_source.unknown_category";

    /** {@code /add-source <url>} where {@code <url>} lacks a scheme or host. */
    public static final String ERROR_ADD_SOURCE_MALFORMED_URL = "error.add_source.malformed_url";

    /** URL probe got 4xx/5xx or a connect failure that is not SSRF/timeout. */
    public static final String ERROR_ADD_SOURCE_URL_UNREACHABLE = "error.add_source.url_unreachable";

    /** SSRF guard rejected the probe (localhost / RFC1918 / metadata IP / blocked scheme). */
    public static final String ERROR_ADD_SOURCE_URL_BLOCKED_SSRF = "error.add_source.url_blocked_ssrf";

    /** Probe exceeded the per-read or total wall-clock deadline. */
    public static final String ERROR_ADD_SOURCE_URL_TIMEOUT = "error.add_source.url_timeout";

    /** Resolver could not pick a kind AND the probe's {@code Content-Type} contradicted the URL hint. */
    public static final String ERROR_ADD_SOURCE_AMBIGUOUS_URL = "error.add_source.ambiguous_url";

    /** Caller is banned (handler's own ban check; defense-in-depth — T2-A also gates upstream). */
    public static final String ERROR_ADD_SOURCE_BANNED = "error.add_source.banned";

    /** Caller invoked {@code /add-source} in a group scope without group-admin privilege. */
    public static final String ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY = "error.add_source.group_admin_only";

    // ----- /add-source successful replies (M1-036) ------------------------

    /** Branch A — fresh insert. {@code {0}} = source display name. */
    public static final String REPLY_ADD_SOURCE_FRESH_INSERT = "reply.add_source.fresh_insert";

    /** Branch B — non-admin caller subscribed to an existing source (tags ignored). */
    public static final String REPLY_ADD_SOURCE_SUBSCRIBED_EXISTING = "reply.add_source.subscribed_existing";

    /** Branch C — bot-admin caller replaced {@code bootstrap_tags} on an existing source. */
    public static final String REPLY_ADD_SOURCE_ADMIN_TAGS_REPLACED = "reply.add_source.admin_tags_replaced";

    /**
     * Branch A appendix — operator-visibility disclosure per
     * {@code docs/spec/security.md} §Source URL visibility. The literal
     * substring {@code visible to bot admins} is asserted by
     * {@code AddSourceCommandHandlerTest}.
     */
    public static final String REPLY_ADD_SOURCE_URL_VISIBILITY_DISCLOSURE =
            "reply.add_source.url_visibility_disclosure";

    // ----- /summary friendly errors (M1-037) ------------------------------
    // Per docs/spec/commands.md §Content (`/summary`) +
    // docs/design/03-commands.md §Time window flag. The eight keys below
    // mirror the friendly-error / reply paths the spec assigns to /summary.

    /** {@code /summary -w 5m}: the {@code m} suffix is intentionally rejected per design §Time window flag. */
    public static final String ERROR_SUMMARY_WINDOW_MINUTES_NOT_ACCEPTED =
            "error.summary.window_minutes_not_accepted";

    /** {@code /summary -w 200h}: window outside the 1h–168h / 1d–30d / 1w–4w range. */
    public static final String ERROR_SUMMARY_WINDOW_OUT_OF_RANGE =
            "error.summary.window_out_of_range";

    /** {@code /summary <tag>}: tag is not in the controlled vocabulary; bundle template surfaces a fuzzy-suggestion footer. */
    public static final String ERROR_SUMMARY_UNKNOWN_TAG = "error.summary.unknown_tag";

    /** {@code /summary <tag>}: tag failed the {@code ^[a-z0-9][a-z0-9-]{0,47}$} inline regex / length cap. */
    public static final String ERROR_SUMMARY_TAG_MALFORMED = "error.summary.tag_malformed";

    // ----- /summary successful / informational replies (M1-037) -----------

    /** Empty result — zero eligible posts in the window (or zero subscriptions). */
    public static final String REPLY_SUMMARY_NO_POSTS_YET = "reply.summary.no_posts_yet";

    /** LLM unreachable: reply prefix announcing the degraded-fallback form. */
    public static final String REPLY_SUMMARY_DEGRADED_NOTICE = "reply.summary.degraded_notice";

    /**
     * Cluster-cap excess prefix. Four interpolation tokens:
     * {@code {0}} = included count (= cap), {@code {1}} = total eligible
     * before cap, {@code {2}} = profile name (e.g. {@code laptop}),
     * {@code {3}} = excluded count.
     */
    public static final String REPLY_SUMMARY_CAP_EXCESS_NOTICE =
            "reply.summary.cap_excess_notice";

    /** {@code >5 followed tags} top-3 restriction prefix. {@code {0}} = N = followed-tag count. */
    public static final String REPLY_SUMMARY_TOP_3_OF_N_PREFIX =
            "reply.summary.top_3_of_n_prefix";

    // ----- Intake-step splice fixed replies (M1-044b) ---------------------
    // Per docs/spec/security.md §Authorization model + §User ban +
    // §Invite-code registration, and docs/design/03-commands.md §3.11
    // Welcome messages. These keys are looked up by InboundRouter at the
    // step 2 / step 4 / step 7-DM-gate branches of the intake splice.

    /**
     * Fixed reply for a DM that fails the step 2 invite-code consume
     * (Rejected or BruteForceThresholdBreached outcome), and for the
     * step 7 DM-gate override that rewrites a {@code group_only} user's
     * slash-command reply to the same literal. Per spec
     * §Invite-code registration: "rejected with the same fixed reply
     * as step 2's invalid path."
     */
    public static final String ERROR_INVITE_REQUIRED = "error.invite.required";

    /**
     * Fixed reply for the step 4 ban check. Per spec §User ban: "Banned
     * user receives one fixed reply per inbound message, regardless of
     * input." The exact English literal lives in
     * {@code docs/design/04-security.md} §Banning.
     */
    public static final String ERROR_BAN_FIXED = "error.ban.fixed";

    /**
     * Welcome reply sent after a successful step 2 invite-code consume
     * (DM, fresh user). Text from {@code docs/design/03-commands.md}
     * §3.11 Welcome messages, Mode 1.
     */
    public static final String REPLY_WELCOME_DM_FRESH = "reply.welcome.dm_fresh";

    /**
     * Welcome reply sent on a group's first non-banned {@code @mention}
     * after step 3 group auto-register. Text from
     * {@code docs/design/03-commands.md} §3.11 Welcome messages, Mode 3.
     * The intake-step splice (M1-044b) reserves this key for use by the
     * group auto-promote / first-mention reply path (M1-044c / T2-A).
     * M1-044b lands the key + bundle entry so the bundle stays in sync
     * with {@link BundleKeys} reflection.
     */
    public static final String REPLY_WELCOME_GROUP_FIRST_MENTION = "reply.welcome.group_first_mention";

    // ----- Admin command handler errors + replies (M1-044c) ---------------
    // Per docs/spec/security.md §User ban + §Invite-code registration +
    // §Authorization model, and docs/spec/commands.md §Admin (bot admin).
    // The three handlers (/ban, /unban, /invite create/list/revoke) look
    // these up; every user-visible reply path the spec assigns to those
    // commands is keyed here. Plain text only — single backticks for
    // inline literals, bare URLs, no markdown link syntax.

    /** Non-admin invoked an admin-tier command. */
    public static final String ERROR_ADMIN_ONLY = "error.admin_only";

    /** {@code /unban <contact>} against a contact with no {@code users} row at all (unknown-contact rule per {@code commands.md} §Admin). */
    public static final String ERROR_CONTACT_NOT_REGISTERED = "error.contact_not_registered";

    /** {@code /ban <self>}: actor and target are the same {@code users.id}. */
    public static final String ERROR_BAN_CANNOT_BAN_SELF = "error.ban.cannot_ban_self";

    /** {@code /ban} of the only remaining {@code is_admin=TRUE AND is_banned=FALSE} row — caught from the V5 {@code trg_last_admin_protection_update} trigger. */
    public static final String ERROR_BAN_LAST_ADMIN = "error.ban.last_admin";

    /** {@code /unban} success on the preban-deletion path; value MUST contain the literals {@code pre-ban-only row removed} and {@code fresh invite required} per spec §User ban. */
    public static final String REPLY_UNBAN_PREBAN_DELETED = "reply.unban.preban_deleted";

    /** {@code /unban} success on the non-preban path with {@code is_group_admin=TRUE} rows restored; value MUST contain {@code {0}} for the comma-joined group list and the literal {@code /demote} hint. */
    public static final String REPLY_UNBAN_GROUP_ADMINS_RESTORED = "reply.unban.group_admins_restored";

    /** {@code /unban} success on the non-preban path with zero group-admin rows. */
    public static final String REPLY_UNBAN_PLAIN = "reply.unban.plain";

    /** {@code /ban} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_BAN_SUCCESS = "reply.ban.success";

    /** {@code /invite} with no subcommand or an unrecognized one. */
    public static final String ERROR_INVITE_UNKNOWN_SUBCOMMAND = "error.invite.unknown_subcommand";

    /** {@code /invite create} with neither {@code --contact} nor {@code --open}; value lists both options per spec §Invite-code registration. */
    public static final String ERROR_INVITE_MISSING_FLAG = "error.invite.missing_flag";

    /** {@code /invite create} with both {@code --contact} and {@code --open}. */
    public static final String ERROR_INVITE_MUTUALLY_EXCLUSIVE = "error.invite.mutually_exclusive";

    /** {@code /invite create --adapter <name>} where {@code <name>} is not a currently-enabled adapter. {@code {0}} = the rejected name. */
    public static final String ERROR_INVITE_UNKNOWN_ADAPTER = "error.invite.unknown_adapter";

    /** {@code /invite create --contact <id>} against a {@code is_banned=TRUE} row; points the admin at {@code /unban}. */
    public static final String ERROR_INVITE_BANNED_TARGET = "error.invite.banned_target";

    /** {@code /invite create --open} when the per-adapter open cap is met; lists current open codes and a {@code /invite revoke} hint. */
    public static final String ERROR_INVITE_OPEN_CAP_MET = "error.invite.open_cap_met";

    /** {@code /invite create --contact} when the global contact-bound cap is met. */
    public static final String ERROR_INVITE_CONTACT_CAP_MET = "error.invite.contact_cap_met";

    /** {@code /invite revoke <code>} where {@code <code>} is absent, already {@code USED}, or already {@code REVOKED}. */
    public static final String ERROR_INVITE_REVOKE_NOT_PENDING = "error.invite.revoke_not_pending";

    /** {@code /invite create} success — the new code's UUID is interpolated as {@code {0}}. */
    public static final String REPLY_INVITE_CREATED = "reply.invite.created";

    /** {@code /invite list} header line printed before the rows. */
    public static final String REPLY_INVITE_LIST_HEADER = "reply.invite.list_header";

    /**
     * Per-row template for {@code /invite list} on CONTACT_BOUND rows. Tokens:
     * {@code {0}} = code prefix, {@code {1}} = adapter name, {@code {2}} = target
     * contact id, {@code {3}} = ISO expiry timestamp.
     */
    public static final String REPLY_INVITE_LIST_ENTRY = "reply.invite.list_entry";

    /**
     * Per-row template for {@code /invite list} on OPEN_ADAPTER rows; carries the
     * literal {@code OPEN} marker per spec §Invite-code registration ("the list
     * output must visually distinguish --open codes from --contact codes").
     * Tokens: {@code {0}} = code prefix, {@code {1}} = adapter name, {@code {2}}
     * = ISO expiry timestamp.
     */
    public static final String REPLY_INVITE_LIST_ENTRY_OPEN = "reply.invite.list_entry_open";

    /** {@code /invite revoke} success. */
    public static final String REPLY_INVITE_REVOKED = "reply.invite.revoked";

    // ----- Pre-dispatch confirm gate (M1-051) -----------------------------
    // Per docs/spec/commands.md §Surface conventions ("Confirmation for
    // destructive commands") + docs/spec/security.md §What's intentionally
    // NOT in v1 ("single-step confirm-within-window is enough for v1").
    // The five keys below back the prompt + cancellation + no-pending-error
    // replies for the three confirmable command surfaces (/ban,
    // /invite create --open, /invite revoke). Each prompt template
    // interpolates the timeout-in-seconds derived from
    // {@code infochat.confirm.timeout} so the user sees the same window
    // the service enforces.

    /** {@code /<cmd> confirm} arrived with no matching pending entry (no prior /<cmd> or window expired). */
    public static final String ERROR_CONFIRM_NO_PENDING = "error.confirm.no_pending";

    /**
     * Cancellation acknowledgement sent by the router's step 4.5 sweep
     * when any non-confirm-shape input arrives after a pending confirm
     * was registered. Token {@code {0}} = the display name of the
     * cancelled command (e.g. {@code "ban"}).
     */
    public static final String REPLY_CONFIRM_CANCELLED = "reply.confirm.cancelled";

    /**
     * First-call prompt template for {@code /ban}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = redacted
     * target contact id (per {@code ContactIds.redact}).
     */
    public static final String REPLY_CONFIRM_PROMPT_BAN = "reply.confirm.prompt.ban";

    /**
     * First-call prompt template for {@code /invite create --open}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = target adapter
     * name (validated against the currently-enabled set upstream).
     */
    public static final String REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN =
            "reply.confirm.prompt.invite_create_open";

    /**
     * First-call prompt template for {@code /invite revoke <code>}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = the
     * 8-char code prefix (full UUID is not echoed in case the prompt
     * is read by someone other than the issuing admin).
     */
    public static final String REPLY_CONFIRM_PROMPT_INVITE_REVOKE =
            "reply.confirm.prompt.invite_revoke";

    // ----- Slow-start probation + /vouch (M1-045) -------------------------
    // Per docs/spec/security.md §Slow-start tier ("Blocked operations
    // return a friendly reply stating when full access unlocks") +
    // docs/design/03-commands.md §3.3 (probation-aware reply shape).
    // The probation gate (InboundRouter step 5) emits
    // ERROR_PROBATION_BLOCKED when a probation user invokes a
    // non-allowed command; /vouch emits the success / no-op replies.

    /**
     * Probation gate rejection reply. Token {@code {0}} = the
     * approximate time until full access unlocks (formatted from
     * {@code probation_until - NOW()} by the caller). Per spec
     * §Slow-start tier, the reply never reaches the LLM or any
     * write path.
     */
    public static final String ERROR_PROBATION_BLOCKED = "error.probation.blocked";

    /**
     * {@code /vouch} success reply. Sent on the happy path where
     * the handler performed the two transitions
     * ({@code probation_until = NULL} and, when prior state was
     * {@code group_only}, {@code registration_state = 'vouched'})
     * in one transaction with one VOUCH audit row.
     */
    public static final String REPLY_VOUCH_SUCCESS = "reply.vouch.success";

    /**
     * {@code /vouch} no-op reply. Sent when the target row is
     * already past probation AND not {@code group_only} — the
     * UPDATE would change nothing. The handler short-circuits
     * BEFORE running the SQL and writes no audit row, matching
     * the M1-036 / {@code /unban} pattern for in-effect no-ops.
     */
    public static final String REPLY_VOUCH_NOOP = "reply.vouch.noop";

    /**
     * {@code /vouch} banned-target reply (M1-045 redteam-fix). Sent
     * when the target row has {@code is_banned = true}: an admin
     * cannot vouch a banned user past probation when intake step 4
     * still blocks them. The handler short-circuits BEFORE opening
     * the transaction so no audit row and no UPDATE land — the
     * banned row's {@code registration_state} and
     * {@code probation_until} columns are preserved verbatim, which
     * is the state {@code /unban} restores into.
     */
    public static final String ERROR_VOUCH_BANNED_TARGET = "error.vouch.banned_target";

    // ----- /grant-admin + /revoke-admin (M1-046) --------------------------
    // Per docs/spec/security.md §Authorization model
    // (last-admin protection + per-adapter scope) +
    // docs/spec/commands.md §Admin (bot admin). The two handlers mutate
    // users.is_admin and are inbound-adapter-scoped; the V5
    // trg_last_admin_protection_update trigger is the load-bearing
    // last-line defense the revoke handler catches via SQLException
    // substring match on `last_admin_protection`.

    /** {@code /grant-admin <contact>} against a {@code is_banned=TRUE} row — granting admin to a banned user is incoherent. */
    public static final String ERROR_GRANT_ADMIN_BANNED_TARGET = "error.grant_admin.banned_target";

    /** {@code /grant-admin <contact>} against a row that is already {@code is_admin=TRUE} (no-op friendly reply). */
    public static final String ERROR_GRANT_ADMIN_ALREADY_ADMIN = "error.grant_admin.already_admin";

    /** {@code /grant-admin} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_GRANT_ADMIN_SUCCESS = "reply.grant_admin.success";

    /** {@code /revoke-admin <self>}: actor and target are the same {@code users.id}. First-line UX guard; V5 trigger is the last-line defense. */
    public static final String ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF = "error.revoke_admin.cannot_revoke_self";

    /** {@code /revoke-admin <contact>} against a row that is already {@code is_admin=FALSE} (no-op friendly reply). */
    public static final String ERROR_REVOKE_ADMIN_NOT_ADMIN = "error.revoke_admin.not_admin";

    /** {@code /revoke-admin} of the only remaining {@code is_admin=TRUE AND is_banned=FALSE} row — caught from the V5 {@code trg_last_admin_protection_update} trigger. Mentions the global last-admin invariant + a hint that another admin must be granted first. */
    public static final String ERROR_REVOKE_ADMIN_LAST_ADMIN = "error.revoke_admin.last_admin";

    /** {@code /revoke-admin} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_REVOKE_ADMIN_SUCCESS = "reply.revoke_admin.success";

    /** Shared group-scope reject for /grant-admin and /revoke-admin: the ScopeRef.Group SPI does not carry the actor's contact id in v1, so the handler cannot verify bot-admin tier. T2-F lands the SPI widening. */
    public static final String ERROR_GROUP_ADMIN_NOT_IN_V1 = "error.group_admin_not_in_v1";

    // ----- /save + /saved + /unsave (M1-052) ------------------------------
    // Per docs/spec/commands.md §Content (/save, /saved, /unsave) +
    // docs/spec/schema.md §Per-user state + §Invariants (1 carve-out, 6
    // carve-out) + docs/design/02-schema.md §2.6.1 + docs/design/03-commands.md
    // §/save /§/saved /§/unsave. Saved-post library is per-user-globally
    // (decision D13); the /saved reply header MUST disclose this.
    //
    // The three _GROUP_NOT_IN_V1 keys cover the v1 group-scope short-
    // circuit: ScopeRef.Group carries adapterGroupId only (no actor
    // contact id), so the CommandHandler SPI cannot resolve the inbound
    // sender's identity in group scope (mirrors the AddSourceCommandHandler
    // / GrantAdminCommandHandler / RevokeAdminCommandHandler convention).
    // Each command gets its own key (rather than one shared key) so T2-F
    // can independently translate them when the SPI widens.

    /** {@code /save <uid>}: the post is missing, QUARANTINED, or NEEDS_REVIEW (visibility-of-target rule — non-READY posts are indistinguishable from a missing UID at the user surface). */
    public static final String ERROR_SAVE_UNKNOWN_UID = "error.save.unknown_uid";

    /** {@code /save <uid>} when the actor's {@code users.save_count} is already at the per-user cap; points the user at /unsave. */
    public static final String ERROR_SAVE_CAP_MET = "error.save.cap_met";

    /** {@code /save <uid>} on a post already in the actor's library — PK collision on (user_id, post_uid). */
    public static final String ERROR_SAVE_ALREADY_SAVED = "error.save.already_saved";

    /** {@code /save} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_SAVE_GROUP_NOT_IN_V1 = "error.save.group_not_in_v1";

    /** {@code /save} success reply. {@code {0}} = the post UID the caller saved. */
    public static final String REPLY_SAVE_SUCCESS = "reply.save.success";

    /** {@code /saved} reply header — MUST disclose per-user-global semantics. {@code {0}} = displayed-count, {@code {1}} = total-count, {@code {2}} = current page, {@code {3}} = total pages, {@code {4}} = optional filter clause. */
    public static final String REPLY_SAVED_HEADER_GLOBAL = "reply.saved.header.global";

    /** {@code /saved} per-row template. {@code {0}} = post UID, {@code {1}} = title, {@code {2}} = saved_at relative, {@code {3}} = tag list (comma-joined). */
    public static final String REPLY_SAVED_LINE = "reply.saved.line";

    /** {@code /saved} empty-library reply. */
    public static final String REPLY_SAVED_EMPTY = "reply.saved.empty";

    /** {@code /saved} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_SAVED_GROUP_NOT_IN_V1 = "error.saved.group_not_in_v1";

    /** {@code /unsave <uid>}: the actor has no saved_post row for that UID. */
    public static final String ERROR_UNSAVE_UNKNOWN_UID = "error.unsave.unknown_uid";

    /** {@code /unsave} success reply. {@code {0}} = the post UID the caller removed. */
    public static final String REPLY_UNSAVE_SUCCESS = "reply.unsave.success";

    /** {@code /unsave} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_UNSAVE_GROUP_NOT_IN_V1 = "error.unsave.group_not_in_v1";

    // ----- Source-management admin commands (M1-053) ----------------------
    // /list-sources, /remove-source, /source-enable, /source-disable per
    // docs/spec/commands.md §Source management + §Permission model +
    // docs/spec/security.md §Source URL visibility + §Authorization model.
    // The `--all` and `--include-deleted` flags on /list-sources are
    // bot-admin-only flag-as-identity (a non-admin caller never sees the
    // flag silently stripped). /remove-source and /source-enable
    // (soft-deleted path only) are confirm-gated via M1-051
    // ConfirmStateService; the two new PendingConfirm record types
    // (RemoveSourceConfirm, SourceEnableConfirm) live as top-level files
    // alongside this constant catalogue.

    /** {@code /list-sources --all} or {@code --include-deleted} from a non-admin caller — flag-as-identity rejection. */
    public static final String ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG = "error.list_sources.admin_only_flag";

    /** {@code /list-sources --include-deleted} without {@code --all} — even an admin must pair the two. */
    public static final String ERROR_LIST_SOURCES_INCLUDE_DELETED_REQUIRES_ALL =
            "error.list_sources.include_deleted_requires_all";

    /**
     * URL-visibility caveat appended to the header on the {@code --all}
     * paths per {@code docs/spec/security.md} §Source URL visibility.
     * The bundle text MUST contain the literal {@code visible to bot
     * admins} (asserted by {@code ListSourcesCommandHandlerTest}).
     */
    public static final String REPLY_LIST_SOURCES_URL_VISIBILITY_CAVEAT =
            "reply.list_sources.url_visibility_caveat";

    /** Header line printed before the per-source rows. */
    public static final String REPLY_LIST_SOURCES_HEADER = "reply.list_sources.header";

    /**
     * Per-row template. Tokens: {@code {0}} = display name, {@code {1}} = identifier (URL),
     * {@code {2}} = kind ({@code rss|bluesky|...}), {@code {3}} = status flag
     * ({@code active|failed|disabled|deleted}).
     */
    public static final String REPLY_LIST_SOURCES_LINE = "reply.list_sources.line";

    /** Reply when zero rows would be returned for the requested view. */
    public static final String REPLY_LIST_SOURCES_EMPTY = "reply.list_sources.empty";

    /** {@code /remove-source <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_REMOVE_SOURCE_UNKNOWN_ID = "error.remove_source.unknown_id";

    /** {@code /remove-source <id>}: target row is already soft-deleted ({@code deleted_at IS NOT NULL}). */
    public static final String ERROR_REMOVE_SOURCE_ALREADY_DELETED = "error.remove_source.already_deleted";

    /**
     * First-call prompt template for {@code /remove-source}. Tokens:
     * {@code {0}} = source display name, {@code {1}} = affected-subscriber count
     * (rows in {@code source_subscription} that will be cascade-deleted),
     * {@code {2}} = timeout in whole seconds.
     */
    public static final String REPLY_CONFIRM_PROMPT_REMOVE_SOURCE = "reply.confirm.prompt.remove_source";

    /**
     * {@code /remove-source confirm} success reply. Tokens: {@code {0}} =
     * source display name, {@code {1}} = cascade-deleted subscription count.
     */
    public static final String REPLY_REMOVE_SOURCE_SUCCESS = "reply.remove_source.success";

    /** {@code /source-enable <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_SOURCE_ENABLE_UNKNOWN_ID = "error.source_enable.unknown_id";

    /**
     * {@code /source-enable <id>}: probe failed (HTTP 4xx/5xx, SSRF block,
     * timeout, or unreachable). Single key collapses all failure shapes —
     * the source remains in its prior state regardless.
     */
    public static final String ERROR_SOURCE_ENABLE_PROBE_FAILED = "error.source_enable.probe_failed";

    /** {@code /source-enable <id>}: target row is already {@code status='active'} and not soft-deleted. */
    public static final String ERROR_SOURCE_ENABLE_ALREADY_ACTIVE = "error.source_enable.already_active";

    /**
     * {@code /source-enable <id>}: target row's {@code kind} is not in
     * the v1 HTTP-shaped probe-supported set (only {@code rss} qualifies
     * today; {@code nostr}/{@code bluesky}/etc. await {@code
     * StreamSourceSupervisor}).
     */
    public static final String ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1 =
            "error.source_enable.kind_not_supported_in_v1";

    /**
     * First-call prompt template for {@code /source-enable} against a
     * soft-deleted row. Tokens: {@code {0}} = source display name,
     * {@code {1}} = timeout in whole seconds. The bundle text MUST
     * include the literal {@code No subscriptions will be restored}
     * (asserted by {@code SourceEnableCommandHandlerTest}).
     */
    public static final String REPLY_CONFIRM_PROMPT_SOURCE_ENABLE_SOFT_DELETED =
            "reply.confirm.prompt.source_enable_soft_deleted";

    /**
     * {@code /source-enable} success reply on the {@code failed}/{@code disabled}
     * path (no soft-delete revival). Token {@code {0}} = source display name.
     */
    public static final String REPLY_SOURCE_ENABLE_SUCCESS = "reply.source_enable.success";

    /**
     * {@code /source-enable confirm} success reply on the soft-deleted
     * revival path. Token {@code {0}} = source display name. The bundle
     * text MUST include the {@link #REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED}
     * literal — the handler concatenates the two keys for one outbound.
     */
    public static final String REPLY_SOURCE_ENABLE_SUCCESS_FROM_SOFT_DELETED =
            "reply.source_enable.success.from_soft_deleted";

    /**
     * The required spec disclosure literal appended to the soft-deleted
     * revival reply. Per {@code docs/spec/commands.md} §Source management:
     * "No subscriptions were restored — affected scopes must /add-source
     * again to re-subscribe." Asserted as a literal substring.
     */
    public static final String REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED =
            "reply.source_enable.no_subscriptions_restored";

    /** {@code /source-disable <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_SOURCE_DISABLE_UNKNOWN_ID = "error.source_disable.unknown_id";

    /** {@code /source-disable <id>}: target row is not currently {@code status='active' AND deleted_at IS NULL}. */
    public static final String ERROR_SOURCE_DISABLE_NOT_ACTIVE = "error.source_disable.not_active";

    /** {@code /source-disable} success reply. Token {@code {0}} = source display name. */
    public static final String REPLY_SOURCE_DISABLE_SUCCESS = "reply.source_disable.success";

    private BundleKeys() {
        throw new AssertionError("BundleKeys is a constant holder and must not be instantiated");
    }
}
