package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the step-5 slow-start probation gate (M1-451) to a fixed
 * {@link Clock} and assert the block-vs-allow decision is decided
 * against the <b>injected</b> instant — {@code clock.instant()}, not
 * inline {@code Instant.now()} — so it flips exactly at the
 * {@code probation_until} boundary and can never silently fall back to
 * the wall clock. This closes the app/DB skew engineering-rules §9
 * forbids on the authorization path: the gate's read of
 * {@code probation_until} now shares one clock with the app-side
 * writers (per spec {@code docs/spec/security.md} §Slow-start tier +
 * §Authorization model step 5).
 *
 * <p><b>Why a hand-constructed router, not
 * {@code QuarkusMock.installMockForType(Clock.class)}.</b> This file
 * follows the established plain-JUnit harness of
 * {@link InboundRouterProbationOrderingTest}: the router is built
 * directly (outside CDI) with the per-dispatch {@code UserSnapshot}
 * seeded in-test, so no DevServices Postgres is needed. A CDI mock
 * Clock would have no effect on a hand-constructed bean whose
 * {@code clock} field is not container-injected; pinning the field
 * directly is the equivalent deterministic mechanism for this harness
 * and matches the test_plan's "unit; no DB needed if the snapshot is
 * built in-test" directive.
 *
 * <p>The command is always blocked-during-probation (the
 * {@code commandPermissions} double returns {@code false}
 * unconditionally), so the ONLY input flipping block-vs-dispatch is
 * the gate's {@code probation_until} vs the injected instant.
 */
class InboundRouterProbationClockTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "probation-clock-test-contact";

    /**
     * Injected clock strictly before {@code probation_until}: the user
     * is still in probation, so the blocked command short-circuits with
     * {@code error.probation.blocked}.
     */
    @Test
    void blockedWhenInjectedClockBeforeProbationBoundary() {
        Instant probationUntil = Instant.parse("2026-07-01T00:00:00Z");
        Clock clock = Clock.fixed(probationUntil.minusMillis(1), ZoneOffset.UTC);
        CapturingAdapter target = runBlockedCommand(probationUntil, clock);

        assertEquals(1, target.captured.size(),
                "in-probation blocked command must produce exactly one reply; got: " + target.captured);
        assertTrue(target.captured.get(0).text()
                        .startsWith(FakeBundleLoader.stubFor(BundleKeys.ERROR_PROBATION_BLOCKED)),
                "clock 1ms before probation_until must be IN probation → error.probation.blocked; got: "
                        + target.captured.get(0).text());
    }

    /**
     * Injected clock exactly at {@code probation_until}: the predicate
     * {@code probationUntil.isAfter(now)} is false, so the user is no
     * longer in probation and the dispatch fires. The decision flips
     * exactly at the boundary.
     */
    @Test
    void dispatchesWhenInjectedClockAtProbationBoundary() {
        Instant probationUntil = Instant.parse("2026-07-01T00:00:00Z");
        Clock clock = Clock.fixed(probationUntil, ZoneOffset.UTC);
        CapturingAdapter target = runBlockedCommand(probationUntil, clock);

        assertEquals(1, target.captured.size(),
                "past-probation path must produce exactly one reply; got: " + target.captured);
        assertEquals("handler-reply:add-source", target.captured.get(0).text(),
                "clock AT probation_until must be PAST probation (isAfter is exclusive) → dispatch fires; got: "
                        + target.captured.get(0).text());
    }

    /**
     * The decisive injected-vs-wall-clock case: {@code probation_until}
     * is in the PAST relative to the real wall clock, but the injected
     * clock is pinned BEFORE it. The gate must read the injected clock
     * (→ still in probation → blocked); a wall-clock {@code Instant.now()}
     * would see the window long elapsed and wrongly dispatch.
     */
    @Test
    void gateFollowsInjectedClockNotWallClock() {
        Instant probationUntil = Instant.parse("2020-06-01T00:00:00Z");
        Clock clock = Clock.fixed(Instant.parse("2020-05-01T00:00:00Z"), ZoneOffset.UTC);
        CapturingAdapter target = runBlockedCommand(probationUntil, clock);

        assertEquals(1, target.captured.size(),
                "injected-clock-before-boundary must produce exactly one reply; got: " + target.captured);
        assertTrue(target.captured.get(0).text()
                        .startsWith(FakeBundleLoader.stubFor(BundleKeys.ERROR_PROBATION_BLOCKED)),
                "a probation_until in the wall-clock past must still block when the INJECTED clock is "
                        + "before it — proving the gate reads clock.instant(), not Instant.now(); got: "
                        + target.captured.get(0).text());
    }

    /**
     * The blocked reply's remaining-time token is rendered from the gate's
     * injected-clock instant (M1-471), not a re-read wall clock. The clock is
     * pinned 2h30m BEFORE {@code probation_until}, so the truncated-hours token
     * is deterministically {@code ~2h}; under {@code Instant.now()} this would
     * be non-deterministic and — with the 2020 expiry long past the real wall
     * clock — would render {@code <1m} instead, proving the formatter reads the
     * threaded gate instant.
     */
    @Test
    void blockedReplyRendersRemainingTimeTokenFromInjectedClock() {
        Instant pinnedNow = Instant.parse("2020-05-01T00:00:00Z");
        Instant probationUntil = pinnedNow.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(30));
        Clock clock = Clock.fixed(pinnedNow, ZoneOffset.UTC);

        UUID userId = UUID.randomUUID();
        InboundRouter router = newRouter(userId, probationUntil);
        router.clock = clock;
        // A {0}-bearing probation template so the interpolated unlock token
        // actually lands in the reply text (the default stub has no placeholder).
        router.bundleLoader = new TokenTemplateBundleLoader();
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(new CallLog(), "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "in-probation blocked command must produce exactly one reply; got: " + target.captured);
        assertEquals("unlock-in ~2h", target.captured.get(0).text(),
                "the remaining-time token must be computed from the injected clock instant "
                        + "(pinned 2h30m before probation_until → ~2h), not the wall clock; got: "
                        + target.captured.get(0).text());
    }

    // ----- harness ------------------------------------------------------------

    /**
     * Drive a DM {@code /add-source} (always blocked-during-probation)
     * through the router with {@code probation_until} seeded into the
     * snapshot and the given fixed {@link Clock} pinned on the gate.
     */
    private CapturingAdapter runBlockedCommand(Instant probationUntil, Clock clock) {
        UUID userId = UUID.randomUUID();
        InboundRouter router = newRouter(userId, probationUntil);
        router.clock = clock;
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(new CallLog(), "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/add-source https://example.org/feed --tags x"), ADAPTER);
        return target;
    }

    private InboundRouter newRouter(UUID userId, @Nullable Instant probationUntil) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(userId, "invited", false, probationUntil));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        CallLog log = new CallLog();
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        // The command is always blocked-during-probation, isolating the gate:
        // block-vs-dispatch is driven solely by probation_until vs the clock.
        router.commandPermissions = new CommandPermissions(new AssetCommandFamilyOracle()) {
            @Override
            public boolean allowedDuringProbation(String slashCommand) {
                return false;
            }
        };
        router.probationCheck = new NoopProbationCheck();
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId2, String scopeKind, UUID scopeId) {}
        };
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.assetCommandFamilyOracle = new AssetCommandFamilyOracle();
        CountingDispatchDataSource dispatchDataSource = new CountingDispatchDataSource(userId);
        router.dataSource = dispatchDataSource;
        router.groupAutoPromoteService = new NoopGroupAutoPromoteService(dispatchDataSource);
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        return router;
    }

    private static InboundMessage dmInbound(String body) {
        return new InboundMessage(
                new Identity(DM_CONTACT, "Alice", Instant.now()),
                new ScopeRef.Dm(DM_CONTACT),
                body,
                Instant.now(),
                "msg-1");
    }

    /** A {0}-bearing probation template so the rendered unlock token lands in the reply. */
    private static final class TokenTemplateBundleLoader extends BundleLoader {
        @Override
        public String get(String key) {
            return get(key, "en");
        }

        @Override
        public String get(String key, String langCode) {
            if (BundleKeys.ERROR_PROBATION_BLOCKED.equals(key)) {
                return "unlock-in {0}";
            }
            return FakeBundleLoader.stubFor(key);
        }
    }
}
