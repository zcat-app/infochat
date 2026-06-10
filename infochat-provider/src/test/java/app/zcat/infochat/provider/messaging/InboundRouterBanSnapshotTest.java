package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin T4 (M1-244): the intake step-4 ban check is served from the
 * step-1 {@link InboundRouter.UserSnapshot} ({@code isBanned}) rather
 * than a second live query. A banned user's inbound is rejected with
 * the fixed {@code error.ban.fixed} reply from a SINGLE users-row read
 * (one {@code lookupUser}), and no parse / dispatch follows.
 *
 * <p>Plain JUnit — {@code lookupUser} is overridden to return a banned
 * snapshot and count its own invocations. That override is the only
 * DataSource access in production, so a second SELECT (the former
 * {@code BanCheck.isBanned} query) would re-enter it and push the count
 * above one — the assertion that the count stays at one is the proof
 * that the ban is snapshot-served.</p>
 */
class InboundRouterBanSnapshotTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "banned-snapshot-test-contact";

    @Test
    void bannedSnapshotIsRejectedAtStep4FromASingleSnapshotRead() {
        CallLog log = new CallLog();
        AtomicInteger lookups = new AtomicInteger();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                lookups.incrementAndGet();
                return Optional.of(new UserSnapshot(UUID.randomUUID(), "vouched", true));
            }
        };
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = bundleLoader;
        // Wired but unreachable: the ban short-circuit at step 4 must
        // return before any parse/dispatch, so this handler stays untouched.
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        router.maxInboundBodyBytes = 65536;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(
                new InboundMessage(
                        new Identity(DM_CONTACT, "Alice", Instant.now()),
                        new ScopeRef.Dm(DM_CONTACT),
                        "/help",
                        Instant.now(),
                        "msg-1"),
                ADAPTER);

        assertEquals(1, target.captured.size(),
                "banned user must receive exactly one ban-fixed reply; got: " + target.captured);
        assertEquals(bundleLoader.get(BundleKeys.ERROR_BAN_FIXED),
                target.captured.get(0).text(),
                "the reply must be the error.ban.fixed bundle entry");
        assertEquals(1, lookups.get(),
                "the ban must be served from the single step-1 snapshot read — "
                        + "no second users-row SELECT; got lookups=" + lookups.get());
        assertTrue(log.calls.stream().noneMatch(call -> call.startsWith("handler.handle")),
                "a banned inbound must NOT reach parse/dispatch; got: " + log.calls);
    }
}
