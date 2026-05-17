package io.infochat.provider.messaging;

import io.infochat.messaging.InboundMessage;
import io.infochat.messaging.MessagingAdapter;
import io.infochat.messaging.MessagingException;
import io.infochat.messaging.OutboundMessage;
import io.infochat.messaging.ScopeRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-side inbound dispatch. Registered as the
 * {@link MessagingAdapter.InboundHandler} on every activated adapter
 * by {@link AdapterRegistry}; the registry also tells the router
 * which adapter to send replies through via {@link #setReplyTarget}.
 *
 * <p><b>Normalization-first invariant.</b> The first thing
 * {@link #onMessage} does is run the Unicode normalization pass spec'd
 * in {@code docs/spec/security.md} §Authorization model step 1.7 and
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
 * future reader sees the seam. M1-035c adds the
 * {@code AutoRegisterService} at the same intake point.</p>
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
public class InboundRouter implements MessagingAdapter.InboundHandler {

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

    @Inject
    Instance<CommandHandler> commandHandlers;

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

    @Override
    public void onMessage(InboundMessage msg) {
        // T2-A wires the missing intake steps upstream of this point:
        // ban check (D11), invite-code gate (D44), slow-start probation (D45).
        String normalized = normalize(msg.text());
        if (normalized.isEmpty()) {
            return;
        }

        String body;
        try {
            body = normalized.startsWith("/")
                    ? handleSlash(msg.scope(), normalized)
                    : CHAT_MODE_REPLY;
        } catch (RuntimeException e) {
            log.error("InboundRouter dispatch failed for scope={}", msg.scope(), e);
            body = INTERNAL_ERROR_REPLY;
        }

        MessagingAdapter target = replyTarget;
        if (target == null) {
            // The AdapterRegistry calls setReplyTarget before
            // setInboundHandler at registration, so this is reachable
            // only if onMessage is invoked before the registry has
            // bound a reply target — i.e. a test that forgot to wire
            // the router. Log and drop; no defensive throw.
            log.error("InboundRouter has no replyTarget; dropping reply for scope={}",
                    msg.scope());
            return;
        }
        try {
            target.send(new OutboundMessage(
                    msg.scope(),
                    body,
                    Instant.now(),
                    UUID.randomUUID().toString()));
        } catch (MessagingException e) {
            // Outbound failure on the reply itself is not recoverable
            // on this code path — surface it in the log and move on.
            log.error("InboundRouter reply send failed for adapter={} scope={}",
                    target.name(), msg.scope(), e);
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
     * Same Unicode normalization pass spec'd in {@code security.md}
     * §Authorization model step 1.7: NFKC + bidi-control strip +
     * zero-width strip + leading/trailing whitespace trim.
     *
     * <p>Fenced code blocks would carve out per the spec's chat-input
     * parity rule, but every inbound to InboundRouter is either a
     * slash command (no fenced code possible at the start) or
     * chat-mode input (M1-035b stubs chat-mode with a deterministic
     * reply, so the carve-out has no functional consumer yet).
     * Whole-body NFKC is sufficient for MVP.</p>
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); ) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            if (isBidiControl(cp) || isZeroWidth(cp)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
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
}
