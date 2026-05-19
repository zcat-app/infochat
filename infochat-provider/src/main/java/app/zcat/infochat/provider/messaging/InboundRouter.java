package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider-side inbound dispatch. {@link AdapterRegistry} wraps the
 * router with a per-adapter {@link MessagingAdapter.InboundHandler}
 * lambda that captures the source adapter's {@code name()} and calls
 * {@link #onMessage(InboundMessage, String)}; the registry also tells
 * the router which adapter to send replies through via
 * {@link #setReplyTarget}. The adapter-name plumbing keeps the
 * messaging-adapter SPI ({@link app.zcat.infochat.messaging.InboundMessage})
 * free of an adapter-identity field — the registry knows which adapter
 * it just wired, so the name reaches the router through the wiring
 * surface, not the payload.
 *
 * <p><b>Body-size cap (defense in depth).</b> Before invoking
 * {@link #normalize}, {@link #onMessage} drops any inbound whose UTF-8
 * byte length exceeds {@code infochat.router.max-inbound-body-bytes}
 * with the fixed {@link #MESSAGE_TOO_LARGE_REPLY} literal. The
 * adapter SPI declares its own {@code maxInboundMessageBytes} but is
 * honor-system; the router cap fires even when a misbehaving adapter
 * lets a larger payload through. The cap exists chiefly to bound
 * NFKC's amplification cost on adversarial inputs (Hangul jamo
 * expansion, deep combining-mark sequences) — see M1-035b red-team
 * Finding 6.</p>
 *
 * <p><b>Normalization-first invariant.</b> After the size cap,
 * {@link #onMessage} runs the Unicode normalization pass spec'd in
 * {@code docs/spec/security.md} §Authorization model step 1.7 and
 * {@code docs/spec/commands.md} §Surface conventions: NFKC,
 * bidi-control strip, zero-width strip, leading/trailing whitespace
 * trim. The normalized body REPLACES the raw body for every
 * downstream consumer; the raw body is discarded. This is what makes
 * the homoglyph-evasion claim of §Authorization model step 1.7 a real
 * defense — every command parser and chat-agent call site sees the
 * normalized form, never the raw inbound. Future command handlers
 * MUST stay downstream of this router so they inherit the same
 * invariant.</p>
 *
 * <p><b>Fenced-code carve-out.</b> Code points inside CommonMark
 * fenced code blocks are preserved byte-for-byte: no NFKC, no
 * bidi-strip, no zero-width-strip. A fenced block opens on a line of
 * 3 or more consecutive {@code `} or {@code ~} characters (with 0–3
 * leading spaces and an optional info string after the run) and
 * closes on the next line containing only the same fence character
 * at ≥ that count. The carve-out exists because {@code docs/spec/
 * security.md} §Authorization model step 1.7 promises chat-input
 * parity: code samples a user pastes must round-trip verbatim into
 * downstream prose and through the chat-agent. The single-line
 * inline-code surface (single-backtick pairs within a line) is NOT
 * fenced code — those characters remain subject to NFKC.</p>
 *
 * <p><b>Branches.</b> After normalization the dispatch table is:</p>
 * <ul>
 *   <li>Empty / whitespace-only / bidi-only / zero-width-only body →
 *       drop silently (no outbound, no exception).</li>
 *   <li>Body begins with {@code '/'} → resolve a {@link CommandHandler}
 *       via {@link Instance} lookup; if no handler binds to that name
 *       reply with the deterministic unknown-command literal.</li>
 *   <li>Otherwise → reply with the deterministic chat-mode-not-in-MVP
 *       literal. The chat-mode dispatcher proper lands in T2-D; this
 *       stub prevents a silent drop.</li>
 *   <li>Any uncaught exception in the dispatch branch → log at ERROR
 *       via raw SLF4J, reply with the fixed internal-error literal.
 *       The exception's {@code getMessage()} is NEVER interpolated
 *       into the user-visible body (M1-020's deferred sanitization
 *       concern — using a fixed literal sidesteps it for MVP).</li>
 * </ul>
 *
 * <p>v1 deferred work that future tickets wire upstream of dispatch:
 * <b>ban check</b> (T2-A / D11), <b>invite-code gating</b> (T2-A /
 * D44), <b>slow-start probation filter</b> (T2-A / D45). The one-line
 * comment in {@link #onMessage} below pins the deferred ordering so a
 * future reader sees the seam. M1-035d adds the
 * {@code AutoRegisterService} at the same intake point.</p>
 *
 * <p><b>Contact-id redaction in operator logs.</b> The three error-log
 * sites in {@link #onMessage} (dispatch failure, no-replyTarget, and
 * reply-send-failed) route the scope's id through
 * {@link ContactIds#redact} so a contact id never appears unredacted
 * in non-audit logs — see {@code docs/spec/security.md} §Secrets
 * handling and M1-035b red-team Finding 5.</p>
 *
 * <p><b>Multi-adapter reply.</b> {@link #replyTarget} is a single
 * volatile reference. {@link AdapterRegistry} sets it once per
 * activated adapter; in a multi-adapter MVP the last-registered
 * adapter wins as the reply target — an acceptable MVP limitation
 * because no MVP user-facing flow runs more than one adapter
 * simultaneously (D46 production-exclusion of {@code inmemory}
 * leaves SimpleX+Signal as the only multi-adapter shape, and SimpleX
 * + Signal are T3-A territory). A scope-to-adapter routing table
 * lands when the multi-adapter inbound→reply round-trip becomes a
 * real consumer.</p>
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
     * the normalization pass runs. Not exposed as a public API; the
     * sole reader is {@code InboundRouterNormalizeTest} in the same
     * package.
     */
    static final AtomicLong NORMALIZE_INVOCATIONS = new AtomicLong();

    @Inject
    Instance<CommandHandler> commandHandlers;

    @Inject
    AutoRegisterService autoRegisterService;

    @Inject
    InboundContext inboundContext;

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
     * {@link AdapterRegistry} once per activated adapter at startup;
     * in single-adapter MVP this is the one InMemoryAdapter, in
     * future multi-adapter deployments the last-bound adapter wins
     * until scope-to-adapter routing is wired.
     */
    void setReplyTarget(MessagingAdapter adapter) {
        this.replyTarget = adapter;
    }

    /**
     * Entry point for one inbound message routed from the named
     * source adapter. {@link AdapterRegistry} wires every activated
     * adapter to a lambda that captures {@code adapter.name()} and
     * calls this method, so {@code adapterName} reflects the real
     * source adapter even in multi-adapter deployments (T3-A).
     */
    @ActivateRequestContext
    public void onMessage(InboundMessage msg, String adapterName) {
        // @ActivateRequestContext gives this dispatch its own CDI
        // request-scope context so @RequestScoped collaborators
        // (InboundContext) resolve correctly when invoked from threads
        // that have no ambient request scope — adapter callbacks fire
        // off virtual / pool threads in production and on the test
        // thread in InMemoryAdapter-driven tests.
        // T2-A wires the missing intake steps upstream of this point:
        // ban check (D11), invite-code gate (D44), slow-start probation (D45).
        //
        // Set the per-request adapter name BEFORE any size-cap / normalize /
        // dispatch step runs. Handlers and downstream collaborators that
        // need to qualify a per-actor users lookup by (adapter, contact_id)
        // — required for the V5 UNIQUE constraint and for cross-adapter
        // isolation when D46's SimpleX+Signal lands — read it back via
        // @Inject InboundContext. The router is the single producer of
        // adapter-name truth for the dispatch (AdapterRegistry captures
        // adapter.name() into the lambda that invokes onMessage).
        inboundContext.setAdapterName(adapterName);

        String raw = msg.text();

        // Size cap fires BEFORE normalize so adversarial NFKC inputs
        // (Hangul jamo expansion, deep combining marks) cannot amplify
        // cost. The adapter SPI also declares maxInboundMessageBytes
        // but is honor-system — this is the centralized seam.
        if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes) {
            sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY);
            return;
        }

        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return;
        }

        // MVP-legacy auto-register-on-first-DM per docs/design/00-mvp.md §4.
        // Returns the users.id; MVP discards it (T2-A wires invite-gating
        // upstream and consumes the id at that seam). The call must be
        // upstream of the slash-vs-chat split so chat-mode inbound also
        // produces a users row.
        autoRegisterService.resolveOrRegister(msg.sender(), adapterName);

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
     * are treated as contact-id-shaped for redaction purposes — the
     * spec's "Contact IDs are logged in redacted form" rule
     * generalises to "stable adapter-side identifiers" in
     * non-audit logs.
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
     * applied per-line OUTSIDE CommonMark fenced code blocks. Lines
     * inside a fence (including the fence-delimiter lines themselves)
     * are preserved byte-for-byte so the spec's chat-input parity
     * promise holds for pasted code samples.
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
     * string. The info string contents are not validated — strict
     * CommonMark forbids backticks in a backtick-fence info string,
     * but this carve-out is about preserving content rather than
     * full-fidelity Markdown parsing.
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
     * line. The CommonMark rule forbids any non-whitespace content
     * after the closing fence run.
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
     * (no trim — whole-body trim runs at the end of {@link #normalize}
     * so leading/trailing whitespace on intermediate lines and
     * fenced-block trailing whitespace are not disturbed).
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
