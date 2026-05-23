package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider-side inbound dispatch. {@link AdapterRegistry} wraps the
 * router with a per-adapter {@link MessagingAdapter.InboundHandler}
 * lambda that captures the source adapter's {@code name()} and calls
 * {@link #onMessage(InboundMessage, String)}; the registry also tells
 * the router which adapter to send replies through via
 * {@link #setReplyTarget}.
 *
 * <p><b>Intake-step order (M1-044b splice).</b> {@link #onMessage}
 * runs the intake steps in the spec's exact order per
 * {@code docs/spec/security.md} §Authorization model:
 * <ol>
 *   <li>identity (resolved by {@link AdapterRegistry} into
 *       {@link InboundMessage#sender()});</li>
 *   <li>1.5 transport-level rate cap via {@link RateCapBucket};
 *       over-cap inbound is dropped silently with no outbound;</li>
 *   <li>1.7 Unicode normalize (NFKC + bidi-strip + zero-width-strip,
 *       per-line outside CommonMark fenced code);</li>
 *   <li>2 DM unknown contact → {@link InviteCodeConsumer#consume} with
 *       the normalized body. The consumer owns the UUID-parse: a
 *       non-UUID body and an invalid UUID share the same Rejected
 *       branch (both increment the brute-force counter — M1-044e
 *       AUDIT-EVASION fix). {@link InviteCodeConsumer.Accepted} sends
 *       the {@code reply.welcome.dm_fresh} bundle entry and stops;
 *       {@link InviteCodeConsumer.Rejected} and
 *       {@link InviteCodeConsumer.BruteForceThresholdBreached} both
 *       send the fixed {@code error.invite.required} reply per spec
 *       (the rate-limit state does not change the per-failure reply)
 *       and stop;</li>
 *   <li>3 Group unknown contact → {@link AutoRegisterService#resolveOrRegisterGroup}
 *       inserts a {@code registration_state='group_only'} row and
 *       continues to step 4;</li>
 *   <li>4 ban check via {@link BanCheck#isBanned}; a banned user
 *       receives the fixed {@code error.ban.fixed} reply and stops
 *       (spec §User ban: "one fixed reply per inbound message");</li>
 *   <li>5 reserved — M1-045 splices the probation check here;</li>
 *   <li>7 DM-gate carve-out (pre-dispatch): when the inbound is a DM
 *       scope AND the resolved user's {@code registration_state =
 *       'group_only'}, the router emits the fixed
 *       {@code error.invite.required} reply and stops BEFORE dispatch
 *       (spec §Invite-code registration: "rejected with the same fixed
 *       reply as step 2's invalid path"; §Authorization model:
 *       "blocked commands never reach execution"). M1-044e fix:
 *       moved from post-dispatch override to pre-dispatch short-
 *       circuit so handler side effects never land for a blocked DM
 *       (closes AUTH-BYPASS). Full step-7 permission matrix lands in
 *       M1-045;</li>
 *   <li>6 parse + dispatch (slash-command resolver or chat-mode
 *       fallback).</li>
 * </ol>
 *
 * <p><b>Single users-row SELECT per dispatch.</b> Steps 2 (emptiness
 * pre-condition), 3 (emptiness pre-condition), and 7 (DM-gate
 * {@code registration_state} predicate) all derive from the SAME
 * {@link UserSnapshot} read at the top of the splice. This eliminates
 * TOCTOU between the three checks and keeps the dispatch path at one
 * users-row SELECT per inbound. The step 4 ban predicate consults
 * {@link BanCheck#isBanned} directly per spec (a separate query that
 * sees the freshest {@code is_banned} state for a banned-mid-dispatch
 * race).</p>
 *
 * <p><b>Body-size cap (defense in depth, preserved from M1-038).</b>
 * After the rate-cap check, {@link #onMessage} drops any inbound
 * whose UTF-8 byte length exceeds
 * {@code infochat.router.max-inbound-body-bytes} with the fixed
 * {@link #MESSAGE_TOO_LARGE_REPLY} literal. Ordering rationale
 * (M1-044e): rate-cap fires FIRST so a hostile flood cannot drive
 * outbound cost via the size-cap reply path (closes the DOS
 * amplification surface — over-cap inbound never produces a friendly
 * error reply per spec §Authorization model step 1.5). The size-cap
 * still fires BEFORE normalize so NFKC amplification on adversarial
 * inputs (Hangul jamo expansion, deep combining-mark sequences)
 * cannot amplify cost. Bucket arithmetic is O(1) and cares nothing
 * about payload size, so reversing the order does not expose any
 * unbounded-input attack on the bucket.</p>
 *
 * <p><b>Normalization-first invariant (preserved from M1-035b).</b>
 * After rate cap, {@link #onMessage} runs the Unicode normalization
 * pass spec'd in {@code docs/spec/security.md} §Authorization model
 * step 1.7: NFKC, bidi-control strip, zero-width strip, leading/
 * trailing whitespace trim, applied per-line OUTSIDE CommonMark
 * fenced code blocks. The normalized body REPLACES the raw body for
 * every downstream consumer.</p>
 *
 * <p><b>Contact-id redaction in operator logs (preserved from
 * M1-038).</b> The three error-log sites in {@link #onMessage}
 * (dispatch failure, no-replyTarget, reply-send-failed) route the
 * scope's id through {@link ContactIds#redact} so a contact id never
 * appears unredacted in non-audit logs.</p>
 *
 * <p><b>Multi-adapter reply.</b> {@link #replyTarget} is a single
 * volatile reference. {@link AdapterRegistry} sets it once per
 * activated adapter; in a multi-adapter MVP the last-registered
 * adapter wins as the reply target — an acceptable MVP limitation
 * because no MVP user-facing flow runs more than one adapter
 * simultaneously.</p>
 */
@ApplicationScoped
public class InboundRouter {

    private static final Logger log = LoggerFactory.getLogger(InboundRouter.class);

    /** Deterministic English literal for chat-mode (non-slash) input until T2-D wires the real dispatcher. */
    static final String CHAT_MODE_REPLY =
            "Chat-mode replies are not in the MVP; try /help for the available commands.";

    /** Deterministic English literal for an unknown slash command until M1-035c's bundle infrastructure lands. */
    static final String UNKNOWN_COMMAND_REPLY =
            "Unknown command. Try /help for the available commands.";

    /**
     * Deterministic English literal for any uncaught dispatch exception.
     * The exception's own message is NEVER interpolated here — that
     * is M1-020's sanitization concern. A fixed literal sidesteps it
     * for MVP.
     */
    static final String INTERNAL_ERROR_REPLY =
            "Something went wrong handling that message. Please try again.";

    /**
     * Deterministic English literal for an oversize inbound. Sent
     * before the normalization pass runs (see body-size cap docs at
     * the class level).
     */
    static final String MESSAGE_TOO_LARGE_REPLY =
            "That message is too large for the bot to process. Please shorten it and resend.";

    /**
     * Test-only seam: incremented on entry to {@link #normalize}. The
     * size-cap test asserts this counter does not advance across an
     * oversize inbound, proving the cap-checked branch returns before
     * the normalization pass runs.
     */
    static final AtomicLong NORMALIZE_INVOCATIONS = new AtomicLong();

    /**
     * The {@code registration_state} string that causes the step 7
     * DM-gate carve-out to fire — a user auto-registered via group
     * {@code @mention} (M1-044a {@code AutoRegisterService}) is barred
     * from initiating a DM until a bot admin issues
     * {@code /invite create --contact} or {@code /vouch}. Other
     * states ({@code 'invited'}, {@code 'vouched'}, {@code 'preban'})
     * pass through.
     */
    private static final String REGISTRATION_STATE_GROUP_ONLY = "group_only";

    /** Single users-row lookup feeding steps 2, 3, and 7 from one SELECT. */
    private static final String USER_SNAPSHOT_SQL =
            "SELECT id, is_banned, registration_state FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    @Inject
    Instance<CommandHandler> commandHandlers;

    @Inject
    AutoRegisterService autoRegisterService;

    @Inject
    InboundContext inboundContext;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    InviteCodeConsumer inviteCodeConsumer;

    @Inject
    BanCheck banCheck;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    /**
     * Defense-in-depth body cap. The default is below the in-memory
     * adapter's declared {@code maxInboundMessageBytes} so a
     * misbehaving adapter that lets a larger payload through still
     * gets dropped here. The base default and the per-profile
     * overrides live in {@code application.properties}.
     */
    @ConfigProperty(name = "infochat.router.max-inbound-body-bytes", defaultValue = "65536")
    int maxInboundBodyBytes;

    private volatile MessagingAdapter replyTarget;

    /**
     * Bind the adapter used to send replies. Called by
     * {@link AdapterRegistry} once per activated adapter at startup.
     */
    void setReplyTarget(MessagingAdapter adapter) {
        this.replyTarget = adapter;
    }

    /**
     * Entry point for one inbound message routed from the named
     * source adapter. See the class-level Javadoc for the full
     * intake-step order.
     */
    @ActivateRequestContext
    public void onMessage(@NonNull InboundMessage msg, @NonNull String adapterName) {
        // @ActivateRequestContext gives this dispatch its own CDI
        // request-scope context so @RequestScoped collaborators
        // (InboundContext) resolve correctly when invoked from threads
        // that have no ambient request scope.
        //
        // Set the per-request adapter name BEFORE any size-cap / rate-cap
        // / normalize / dispatch step runs. Handlers and downstream
        // collaborators that need to qualify a per-actor users lookup by
        // (adapter, contact_id) — required for the V5 UNIQUE constraint
        // and cross-adapter isolation — read it back via @Inject
        // InboundContext.
        inboundContext.setAdapterName(adapterName);

        String raw = msg.text();
        String contactId = msg.sender().contactId();

        // Step 1.5 — transport-level rate cap. Fires FIRST per spec
        // §Authorization model step 1.5: over-cap inbound is dropped
        // silently with NO outbound (no ban reply, no invite-required
        // reply, no friendly error — including no MESSAGE_TOO_LARGE_REPLY).
        // A hostile flood that would otherwise drive the size-cap reply
        // path is short-circuited here, closing the DOS amplification
        // surface (M1-044e fix). Bucket arithmetic is O(1) and cares
        // nothing about payload size; the size-cap below still bounds
        // NFKC cost on the (rate-cap-passing) bodies that reach it.
        if (!rateCapBucket.tryAcquire(adapterName, contactId)) {
            return;
        }

        // Body-size cap (M1-038, preserved). Fires AFTER rate cap so a
        // flood cannot leak MESSAGE_TOO_LARGE_REPLY outbound, and BEFORE
        // normalize so NFKC amplification cannot drive cost on
        // adversarial inputs.
        if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes) {
            sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY);
            return;
        }

        // Step 1.7 — Unicode normalize (M1-035b, preserved).
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return;
        }

        // Single users-row SELECT feeds steps 2 (emptiness), 3
        // (emptiness), and 7 (DM-gate registration_state predicate).
        // Step 4 consults BanCheck.isBanned directly per spec — see
        // class-level Javadoc.
        Optional<UserSnapshot> snapshot = lookupUser(adapterName, contactId);

        // Step 2 — DM unknown contact + invite-code consume.
        // Per spec §Authorization model step 2: pass the normalized
        // body to InviteCodeConsumer.consume; the consumer owns the
        // UUID-parse + brute-force counter (M1-044e fix — closes
        // AUDIT-EVASION by ensuring non-UUID probes also increment
        // the counter). Accepted → welcome reply; Rejected and
        // BruteForceThresholdBreached → fixed error.invite.required
        // reply (the spec gives the same user-visible reply for
        // both — the rate-limit state does not change it).
        if (msg.scope() instanceof ScopeRef.Dm && snapshot.isEmpty()) {
            InviteCodeConsumer.Outcome outcome =
                    inviteCodeConsumer.consume(adapterName, contactId, normalized);
            switch (outcome) {
                case InviteCodeConsumer.Accepted a ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH));
                case InviteCodeConsumer.Rejected r ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
                case InviteCodeConsumer.BruteForceThresholdBreached b ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
            }
            return;
        }

        // Step 3 — Group unknown contact + auto-register. Per spec
        // §Authorization model step 3: insert a row under
        // registration_state='group_only' with probation_until = NOW()
        // + slow_start_window, then continue to step 4. The auto-
        // promote slot and group_membership row inserts are deferred
        // to T2-F. A freshly auto-registered user is never banned
        // (V5 default is_banned=FALSE), so step 4 below will see
        // is_banned=false either via BanCheck SQL or by the row that
        // was just written.
        if (msg.scope() instanceof ScopeRef.Group && snapshot.isEmpty()) {
            autoRegisterService.resolveOrRegisterGroup(msg.sender(), adapterName);
        }

        // Step 4 — ban check per spec §User ban + §Authorization model
        // step 4. The fixed error.ban.fixed reply is sent and dispatch
        // stops.
        if (banCheck.isBanned(adapterName, contactId)) {
            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_BAN_FIXED));
            return;
        }

        // Step 5 (reserved — M1-045 inserts the probation check here).

        // Step 7 — DM-gate carve-out (pre-dispatch). Per spec
        // §Invite-code registration + §Authorization model: a user
        // auto-registered via group @mention (registration_state=
        // 'group_only') is rejected from DM with the same fixed reply
        // as step 2's invalid path. The check fires BEFORE dispatch so
        // the handler's side effects (URL probe, LLM call, DB writes)
        // never land for a blocked DM — honors the spec's "blocked
        // commands never reach execution" promise (M1-044e fix closes
        // the AUTH-BYPASS redteam finding). Full step-7 permission
        // matrix is M1-045 territory.
        if (msg.scope() instanceof ScopeRef.Dm
                && snapshot.map(s -> REGISTRATION_STATE_GROUP_ONLY.equals(s.registrationState()))
                        .orElse(false)) {
            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
            return;
        }

        // Step 6 — Parse + dispatch.
        String body;
        try {
            body = normalized.startsWith("/")
                    ? handleSlash(msg.scope(), normalized)
                    : CHAT_MODE_REPLY;
        } catch (RuntimeException e) {
            log.error("InboundRouter dispatch failed for scope={}",
                    ContactIds.redact(scopeIdOf(msg.scope())), e);
            body = INTERNAL_ERROR_REPLY;
        }

        sendReply(msg.scope(), body);
    }

    /**
     * Resolve a {@link UserSnapshot} for {@code (adapter, contactId)} —
     * the V5 UNIQUE (adapter, contact_id) constraint guarantees
     * zero-or-one result. Used by steps 2 (DM emptiness), 3 (Group
     * emptiness), and 7 (DM-gate {@code registration_state} predicate)
     * — derive all three predicates from the SAME snapshot to
     * eliminate TOCTOU between them.
     *
     * <p>Package-private + non-final so plain-JUnit test helpers
     * (InboundRouterNormalizeTest, InboundRouterContactIdRedactionTest,
     * InboundRouterIntakeOrderingTest) can subclass {@link InboundRouter}
     * and override this method with a fixed-value response — the
     * helpers do not wire a {@link DataSource} fake, and the
     * size-cap / normalize / redaction code paths the tests exercise
     * do not depend on a real users row. Production callers consume
     * via the {@code @Inject} {@link DataSource} seam.</p>
     */
    Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(USER_SNAPSHOT_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("is_banned"),
                        rs.getString("registration_state")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.lookupUser failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }

    /**
     * Per-dispatch snapshot of one users-row's state. Captures only
     * the columns the splice needs: {@code id} (for downstream audit
     * hooks if any), {@code is_banned} (TOCTOU-paired with
     * {@link BanCheck#isBanned} at step 4), and
     * {@code registration_state} (step 7 DM-gate predicate).
     * Package-private so test subclasses can construct instances when
     * overriding {@link #lookupUser}.
     */
    record UserSnapshot(UUID id, boolean isBanned, String registrationState) {}

    private void sendReply(ScopeRef scope, String body) {
        MessagingAdapter target = replyTarget;
        if (target == null) {
            // The AdapterRegistry calls setReplyTarget before
            // setInboundHandler at registration, so this is reachable
            // only if onMessage is invoked before the registry has
            // bound a reply target — i.e. a test that forgot to wire
            // the router. Log and drop; no defensive throw.
            log.error("InboundRouter has no replyTarget; dropping reply for scope={}",
                    ContactIds.redact(scopeIdOf(scope)));
            return;
        }
        try {
            target.send(new OutboundMessage(
                    scope,
                    body,
                    Instant.now(),
                    UUID.randomUUID().toString()));
        } catch (MessagingException e) {
            // Outbound failure on the reply itself is not recoverable
            // on this code path — surface it in the log and move on.
            log.error("InboundRouter reply send failed for adapter={} scope={}",
                    target.name(), ContactIds.redact(scopeIdOf(scope)), e);
        }
    }

    private String handleSlash(ScopeRef scope, String normalized) {
        String firstToken = normalized.split("\\s+", 2)[0];
        String commandName = firstToken.substring(1);
        for (CommandHandler handler : commandHandlers) {
            if (handler.name().equals(commandName)) {
                return handler.handle(scope, normalized).text();
            }
        }
        return UNKNOWN_COMMAND_REPLY;
    }

    /**
     * Extract the id component of a {@link ScopeRef} for redacted
     * logging. DM scope carries the cryptographic {@code contactId};
     * group scope carries the adapter-defined stable group id. Both
     * are treated as contact-id-shaped for redaction purposes.
     */
    private static String scopeIdOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm dm -> dm.contactId();
            case ScopeRef.Group group -> group.adapterGroupId();
        };
    }

    /**
     * Unicode normalization pass per
     * {@code docs/spec/security.md} §Authorization model step 1.7:
     * NFKC + bidi-control strip + zero-width strip + whole-body trim,
     * applied per-line OUTSIDE CommonMark fenced code blocks.
     *
     * <p>Package-private + static so {@code InboundRouterNormalizeTest}
     * can exercise the function directly without booting Quarkus.</p>
     */
    static String normalize(String raw) {
        NORMALIZE_INVOCATIONS.incrementAndGet();
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder out = new StringBuilder(raw.length());
        boolean inFence = false;
        char fenceChar = '`';
        int fenceCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inFence) {
                FenceOpener opener = matchFenceOpener(line);
                if (opener != null) {
                    inFence = true;
                    fenceChar = opener.fenceChar;
                    fenceCount = opener.count;
                    // Fence-delimiter lines are preserved verbatim too
                    // — they carry literal fence characters whose
                    // exact byte sequence is the closing match key.
                    out.append(line);
                } else {
                    appendNormalized(out, line);
                }
            } else {
                if (matchFenceCloser(line, fenceChar, fenceCount)) {
                    inFence = false;
                }
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * Match a CommonMark fenced code-block opener per §4.5: 0–3
     * leading spaces, then a run of 3 or more {@code `} or {@code ~}
     * characters (all the same), optionally followed by an info
     * string.
     *
     * @return the opener descriptor on a match, {@code null} otherwise.
     */
    private static FenceOpener matchFenceOpener(String line) {
        int i = 0;
        int n = line.length();
        while (i < n && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        if (i >= n) {
            return null;
        }
        char c = line.charAt(i);
        if (c != '`' && c != '~') {
            return null;
        }
        int count = 0;
        while (i + count < n && line.charAt(i + count) == c) {
            count++;
        }
        if (count < 3) {
            return null;
        }
        return new FenceOpener(c, count);
    }

    /**
     * Match a CommonMark fenced code-block closer per §4.5: 0–3
     * leading spaces, then a run of the same {@code fenceChar} at
     * count ≥ {@code fenceCount}, then only whitespace until end of
     * line.
     */
    private static boolean matchFenceCloser(String line, char fenceChar, int fenceCount) {
        int i = 0;
        int n = line.length();
        while (i < n && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        int count = 0;
        while (i + count < n && line.charAt(i + count) == fenceChar) {
            count++;
        }
        if (count < fenceCount) {
            return false;
        }
        int rest = i + count;
        while (rest < n) {
            char ch = line.charAt(rest);
            if (ch != ' ' && ch != '\t') {
                return false;
            }
            rest++;
        }
        return true;
    }

    /**
     * Apply NFKC + bidi-control strip + zero-width strip to one line
     * (no trim — whole-body trim runs at the end of {@link #normalize}).
     */
    private static void appendNormalized(StringBuilder out, String line) {
        String nfkc = Normalizer.normalize(line, Normalizer.Form.NFKC);
        for (int i = 0; i < nfkc.length(); ) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            if (isBidiControl(cp) || isZeroWidth(cp)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
    }

    private static boolean isBidiControl(int cp) {
        return (cp >= 0x202A && cp <= 0x202E)   // LRE, RLE, PDF, LRO, RLO
                || (cp >= 0x2066 && cp <= 0x2069); // LRI, RLI, FSI, PDI
    }

    private static boolean isZeroWidth(int cp) {
        return cp == 0x200B    // ZERO WIDTH SPACE
                || cp == 0x200C // ZERO WIDTH NON-JOINER
                || cp == 0x200D // ZERO WIDTH JOINER
                || cp == 0xFEFF; // ZERO WIDTH NO-BREAK SPACE / BOM
    }

    /** Descriptor for a matched fence opener: which character (`` ` `` or {@code ~}) and how many. */
    private record FenceOpener(char fenceChar, int count) {}
}
