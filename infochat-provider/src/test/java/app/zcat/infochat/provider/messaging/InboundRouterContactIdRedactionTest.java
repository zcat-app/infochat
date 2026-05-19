package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pin acceptance items 7 and 8 of M1-038: the three error-log sites in
 * {@link InboundRouter#onMessage} (dispatch failure, no-replyTarget,
 * reply-send-failed) interpolate the scope's id through
 * {@code ContactIds.redact} — so the unredacted contact-id literal
 * never reaches the captured SLF4J output.
 *
 * <p>The test is plain JUnit (no Quarkus boot). SLF4J in Quarkus
 * routes through the jboss-logmanager (a JUL implementation), so a
 * JUL {@link Handler} attached to {@code InboundRouter}'s named
 * logger captures the log records this test produces.</p>
 *
 * <p>The three scenarios are triggered via test doubles installed
 * directly on the {@link InboundRouter}'s package-private fields:</p>
 * <ul>
 *   <li>Dispatch-exception path — a {@link ThrowingCommandHandler}
 *       bound to {@code /boom} throws on invocation.</li>
 *   <li>No-replyTarget path — {@code replyTarget} is left null.</li>
 *   <li>Reply-send-failed path — a {@link FailingAdapter} whose
 *       {@link #send} throws {@link MessagingException} is bound as
 *       the reply target.</li>
 * </ul>
 */
class InboundRouterContactIdRedactionTest {

    /**
     * Long, distinctive contact-id literal that the redactor would
     * cut into prefix + ellipsis + suffix. Its leading 8 chars are
     * {@code "alice-co"}; the test asserts the FULL string never
     * reaches the log, not the redacted prefix (which is the intended
     * output and is fine to appear).
     */
    private static final String FULL_CONTACT_ID =
            "alice-contact-id-1234567890abcdef-fingerprint-very-long";

    private CapturingHandler logCapture;
    private org.jboss.logmanager.Logger jbossLogger;
    private Logger julLogger;

    @BeforeEach
    void attachLogHandler() {
        logCapture = new CapturingHandler();
        // SLF4J in this codebase routes through slf4j-jboss-logmanager,
        // which calls org.jboss.logmanager.Logger.getLogger(name). Attach
        // to BOTH the jboss-logmanager Logger and the JUL Logger so the
        // capture works regardless of whether jboss-logmanager is the
        // active java.util.logging.LogManager (which it is when Quarkus
        // boots, but not necessarily under plain surefire).
        jbossLogger = LogContext.getLogContext().getLogger(InboundRouter.class.getName());
        jbossLogger.addHandler(logCapture);
        julLogger = Logger.getLogger(InboundRouter.class.getName());
        julLogger.addHandler(logCapture);
    }

    @AfterEach
    void detachLogHandler() {
        jbossLogger.removeHandler(logCapture);
        julLogger.removeHandler(logCapture);
    }

    /**
     * Dispatch-exception path: a {@link ThrowingCommandHandler} bound
     * to {@code /boom} throws inside {@code handleSlash}; the router
     * catches and logs at ERROR. The captured log records must not
     * contain the full contact-id literal.
     */
    @Test
    void dispatchExceptionPathDoesNotLeakFullContactId() {
        InboundRouter router = newRouter();
        router.commandHandlers = new SingletonInstance<>(new ThrowingCommandHandler());
        router.setReplyTarget(new NoopAdapter());

        router.onMessage(inboundDm(FULL_CONTACT_ID, "/boom"), "inmemory");

        assertNoLeak("dispatch-exception path");
    }

    /**
     * No-replyTarget path: the router logs at ERROR and drops the
     * reply when {@code replyTarget} is null. The captured log
     * records must not contain the full contact-id literal.
     */
    @Test
    void noReplyTargetPathDoesNotLeakFullContactId() {
        InboundRouter router = newRouter();
        // replyTarget left intentionally null.

        router.onMessage(inboundDm(FULL_CONTACT_ID, "/xyz"), "inmemory");

        assertNoLeak("no-replyTarget path");
    }

    /**
     * Reply-send-failed path: the bound adapter throws
     * {@link MessagingException} from {@link MessagingAdapter#send};
     * the router catches and logs at ERROR. The captured log records
     * must not contain the full contact-id literal.
     */
    @Test
    void replySendFailedPathDoesNotLeakFullContactId() {
        InboundRouter router = newRouter();
        router.setReplyTarget(new FailingAdapter());

        router.onMessage(inboundDm(FULL_CONTACT_ID, "/xyz"), "inmemory");

        assertNoLeak("reply-send-failed path");
    }

    // ----- helpers ------------------------------------------------------

    private InboundRouter newRouter() {
        InboundRouter router = new InboundRouter();
        router.commandHandlers = new SingletonInstance<>();
        router.autoRegisterService = new NoopAutoRegisterService();
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    private static InboundMessage inboundDm(String contactId, String text) {
        return new InboundMessage(
                new Identity(contactId, "Alice", Instant.now()),
                new ScopeRef.Dm(contactId),
                text,
                Instant.now(),
                "msg-1");
    }

    private void assertNoLeak(String label) {
        String captured = logCapture.formatted();
        assertFalse(captured.isEmpty(),
                "the " + label + " must produce at least one captured log record; got: " + captured);
        assertFalse(captured.contains(FULL_CONTACT_ID),
                "the " + label + " must not leak the full contact-id literal `"
                        + FULL_CONTACT_ID + "` into operator logs; captured: " + captured);
        // Strict variant: also assert that the substring beyond the
        // redactor's prefix length is absent — catches a regression
        // where someone interpolates the raw id alongside the redacted
        // form.
        String tailBeyondPrefix =
                FULL_CONTACT_ID.substring(8); // chars 9..N — must not appear contiguously
        assertFalse(captured.contains(tailBeyondPrefix),
                "the " + label + " must not leak the post-prefix tail of the contact-id "
                        + "into operator logs; tail=`" + tailBeyondPrefix + "` captured: " + captured);
    }

    // ----- test doubles -------------------------------------------------

    /** Command handler bound to {@code /boom} that throws on dispatch. */
    private static final class ThrowingCommandHandler implements CommandHandler {
        @Override
        public String name() {
            return "boom";
        }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            throw new RuntimeException("dispatch blew up");
        }
    }

    /** Adapter that throws on send — drives the reply-send-failed log site. */
    private static final class FailingAdapter implements MessagingAdapter {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity assertIdentity(InboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) throws MessagingException {
            throw new MessagingException(FailureCategory.TRANSIENT, "send failure");
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    /** Adapter that swallows {@link #send} without throwing — used to isolate the dispatch-exception path. */
    private static final class NoopAdapter implements MessagingAdapter {
        @Override
        public String name() {
            return "noop";
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity assertIdentity(InboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return null;
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    /** Noop AutoRegisterService — the redaction tests are not about DB writes. */
    private static final class NoopAutoRegisterService extends AutoRegisterService {
        @Override
        public UUID resolveOrRegister(Identity sender, String adapterName) {
            return UUID.randomUUID();
        }
    }

    /**
     * Minimal {@link Instance} backed by a fixed list. The router only
     * iterates the instance; the other CDI accessors are unused and
     * throw {@link UnsupportedOperationException} to fail loudly if a
     * future change starts consuming them.
     */
    private static final class SingletonInstance<T> implements Instance<T> {
        private final List<T> items;

        @SafeVarargs
        SingletonInstance(T... items) {
            this.items = List.of(items);
        }

        @Override
        public Iterator<T> iterator() {
            return items.iterator();
        }

        @Override
        public T get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return items.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return items.size() > 1;
        }

        @Override
        public void destroy(T instance) {
            // no-op
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * JUL capturing handler — SLF4J in Quarkus routes through
     * jboss-logmanager, which IS a JUL implementation, so attaching
     * to the {@link InboundRouter} JUL logger captures the SLF4J
     * records the production code emits.
     */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final SimpleFormatter formatter = new SimpleFormatter();

        CapturingHandler() {
            setLevel(java.util.logging.Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String formatted() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord r : records) {
                sb.append(formatter.format(r));
                // Also append the raw parameters / thrown info — formatter
                // may not render parameter substitution; we want EVERY
                // byte the call site handed to SLF4J in the captured
                // surface.
                if (r.getParameters() != null) {
                    for (Object p : r.getParameters()) {
                        sb.append(" param=").append(p).append('\n');
                    }
                }
                if (r.getThrown() != null) {
                    sb.append(" thrown=").append(r.getThrown().toString()).append('\n');
                }
            }
            return sb.toString();
        }
    }
}
