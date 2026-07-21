package app.zcat.infochat.provider.outbox;

import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.PGNotification;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live LISTEN/NOTIFY worker for the {@code new_post} channel
 * (docs/spec/architecture.md §Inter-service communication;
 * docs/design/02-schema.md §2.9.1). The reconnect-resilient worker
 * machinery (dedicated connection, virtual-thread loop, synchronized
 * lifecycle, post-reconnect catch-up) lives in {@link AbstractPgListener};
 * this class supplies the {@code new_post} channel, dispatch, and payload
 * parsing.
 *
 * <p><b>Bean ordering.</b> {@code @Startup} at {@code @Priority(260)} —
 * strictly greater than the {@link NewPostReconciler}'s 250 per the
 * Provider startup table (docs/design/01-architecture.md §1.4.3). The
 * priority ordering guarantees the reconciler's {@code @PostConstruct}
 * returns before this bean's begins, so any NOTIFY arriving mid-catch-up
 * is queued in the Postgres backend and delivered to this listener only
 * after the older READY rows have been replayed in order.
 *
 * <p><b>Payload format.</b> JSON object with exactly two fields per
 * docs/design/02-schema.md §2.9.1 ({@code new_post} cursor-only payload):
 * <pre>{@code {"ready_at":"<iso8601>","post_id":"<uuid>"}}</pre>
 * The format is the cross-service contract; the M1-028 outbox emit MUST
 * produce exactly this shape. Field order is not significant — the
 * parser locates each named field independently.
 *
 * <p><b>Idempotency.</b> Each notification dispatches to
 * {@link NewPostHandler#handle} which advances the cursor inside its
 * {@code @Transactional} boundary via the compare-and-swap UPDATE in
 * {@link ProviderStateDao}. A duplicate NOTIFY (the same
 * {@code (ready_at, post_id)} arriving twice) becomes a CAS no-op at the
 * cursor level and produces no additional handler side effect beyond a
 * log line — the idempotency promise from docs/spec/architecture.md
 * §Catch-up.
 */
@Startup
@Priority(260)
@ApplicationScoped
public class NewPostListener extends AbstractPgListener {

    private static final Pattern READY_AT_PATTERN =
        Pattern.compile("\"ready_at\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern POST_ID_PATTERN =
        Pattern.compile("\"post_id\"\\s*:\\s*\"([^\"]+)\"");

    private static final Logger LOG = Logger.getLogger(NewPostListener.class);

    private static final org.slf4j.Logger SAFE_LOG = LoggerFactory.getLogger(NewPostListener.class);

    @Inject
    NewPostHandler newPostHandler;

    @Inject
    NewPostReconciler newPostReconciler;

    @PostConstruct
    void onStartup() {
        start();
    }

    @PreDestroy
    void onShutdown() {
        stop();
    }

    @Override
    String channelName() {
        return "new_post";
    }

    @Override
    String workerThreadName() {
        return "new-post-listener";
    }

    @Override
    Logger log() {
        return LOG;
    }

    @Override
    void runCatchUp() throws SQLException {
        newPostReconciler.runCatchUp();
    }

    @Override
    void dispatch(PGNotification n) {
        if (!"new_post".equals(n.getName())) {
            return;
        }
        Payload payload;
        try {
            payload = parsePayload(n.getParameter());
        } catch (RuntimeException e) {
            // The exception carries a shape-only message (parsePayload no
            // longer echoes the raw payload); keep the raw NOTIFY bytes out
            // of the log too — info-leak hygiene on this boundary.
            SafeLog.error(SAFE_LOG, "NewPostListener: unparseable new_post payload (dropped)", e);
            return;
        }
        try {
            newPostHandler.handle(payload.postId(), payload.readyAt());
        } catch (SQLException e) {
            SafeLog.error(SAFE_LOG, "NewPostListener: handler failed for post_id=" + payload.postId(), e);
        }
    }

    /**
     * Parses a {@code new_post} NOTIFY payload per the format documented in
     * the class Javadoc. Visible for unit testing; not part of the public
     * API.
     *
     * @throws IllegalArgumentException if either required field is missing
     *     or the {@code ready_at} value is not an ISO-8601 instant or the
     *     {@code post_id} value is not a valid UUID.
     */
    static Payload parsePayload(String json) {
        Matcher readyAtMatcher = READY_AT_PATTERN.matcher(json);
        Matcher postIdMatcher = POST_ID_PATTERN.matcher(json);
        if (!readyAtMatcher.find() || !postIdMatcher.find()) {
            // Do NOT echo the raw payload into the exception message: this is
            // the NOTIFY-deserialization boundary and the unparseable bytes
            // flow into the dispatch log below. Info-leak hygiene over a
            // shape error needs no payload content to be actionable.
            throw new IllegalArgumentException(
                "new_post payload must contain both 'ready_at' and 'post_id' fields");
        }
        return new Payload(
            UUID.fromString(postIdMatcher.group(1)),
            Instant.parse(readyAtMatcher.group(1)));
    }

    /** Parsed payload tuple. */
    record Payload(UUID postId, Instant readyAt) {}
}
