package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.SourceUpsertService;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit) test for {@link AddSourceCommandHandler}'s
 * ban-check ordering invariant per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>Pins the in-handler ban-check ordering: actor lookup AND the
 * {@code is_banned} check run BEFORE the scope discriminator (the
 * {@code if (scope instanceof ScopeRef.Group)} branch). Implements
 * the M1-039 remediation of M1-036's red-team finding 2 (medium
 * INFO-LEAK — group-scope banned-user reply leak).
 *
 * <p><b>Coverage at the current SPI shape.</b> Two scenarios are
 * testable here:
 * <ul>
 *   <li>{@link #bannedDmUserReceivesFixedBanReply()} — banned DM
 *       caller receives the {@code error.add_source.banned} bundle
 *       literal; the URL probe is never invoked.</li>
 *   <li>{@link #groupScopeNonAdminReceivesGroupAdminOnly()} —
 *       non-banned non-group-admin caller in group scope receives
 *       the {@code error.add_source.group_admin_only} bundle
 *       literal; the discriminator is preserved after the reorder.</li>
 * </ul>
 *
 * <p>Scenarios (b) banned-user-in-group and (d) group-admin-proceeds
 * are NOT covered here because {@link ScopeRef.Group} carries only
 * {@code adapterGroupId} (no contact id); {@code lookupActor}
 * returns {@link Optional#empty()} for group scope, so the
 * in-handler ban check is observably a no-op there and the
 * group-admin proceed path cannot be exercised without the
 * actor-seam SPI widening that T2-F lands.
 *
 * <p><b>After M1-044b lands the intake-step splice</b>, the ban
 * check moves into {@code InboundRouter} and this class becomes
 * redundant; the ordering moves to
 * {@code InboundRouterIntakeOrderingTest} scenario (f).
 */
class AddSourceBanCheckOrderingTest {

    private AddSourceCommandHandler handler;
    private RecordingUrlProbe urlProbe;
    private RecordingSourceUpsertService sourceUpsertService;
    private BundleLoader bundleLoader;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        urlProbe = new RecordingUrlProbe();
        sourceUpsertService = new RecordingSourceUpsertService();
        handler = new AddSourceCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.kindResolver = new KindResolver();
        handler.urlProbe = urlProbe;
        handler.sourceUpsertService = sourceUpsertService;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        handler.inboundContext = context;
    }

    @Test
    void bannedDmUserReceivesFixedBanReply() {
        StubUserDataSource bannedDataSource = new StubUserDataSource();
        bannedDataSource.seedUser("m1-039-banned-dm", /* isAdmin */ false, /* isBanned */ true);
        handler.dataSource = bannedDataSource;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-039-banned-dm"),
                "/add-source https://example.com/m1-039-banned.xml --tags m1-039-tag");

        assertTrue(reply.text().contains("not permitted"),
                "banned DM caller must see the error.add_source.banned bundle literal "
                        + "(\"You are not permitted to add sources.\") — got: " + reply.text());
        assertEquals(0, urlProbe.callCount(),
                "the in-handler ban check must short-circuit BEFORE UrlProbe is invoked");
        assertEquals(0, sourceUpsertService.callCount(),
                "the in-handler ban check must short-circuit BEFORE SourceUpsertService runs");
    }

    @Test
    void groupScopeNonAdminReceivesGroupAdminOnly() {
        // Group scope: contactIdOf(scope) returns null so lookupActor
        // never touches dataSource. Wire a NEVER stub so an accidental
        // SQL call would surface loudly.
        handler.dataSource = StubUserDataSource.neverCalled();

        UUID groupId = UUID.randomUUID();
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(groupId.toString()),
                "/add-source https://example.com/m1-039-group.xml --tags m1-039-tag");

        assertTrue(reply.text().contains("Only group admins"),
                "non-admin caller in group scope must see the "
                        + "error.add_source.group_admin_only bundle literal — got: "
                        + reply.text());
        assertEquals(0, urlProbe.callCount(),
                "the discriminator must short-circuit BEFORE UrlProbe is invoked");
        assertEquals(0, sourceUpsertService.callCount(),
                "the discriminator must short-circuit BEFORE SourceUpsertService runs");
    }

    // ----- collaborator stubs -------------------------------------------

    /**
     * Real {@link BundleLoader} constructed by hand. Without CDI the
     * {@code @PostConstruct load()} does not fire automatically, so the
     * test invokes it via reflection.
     */
    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    private static final class RecordingSourceUpsertService extends SourceUpsertService {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public UpsertResult upsert(UUID actorUserId,
                                   boolean actorIsBotAdmin,
                                   String scopeKind,
                                   UUID scopeId,
                                   KindResolver.SourceKind kind,
                                   String identifier,
                                   String displayName,
                                   String category,
                                   List<String> tags) {
            callCount.incrementAndGet();
            return new UpsertResult(Outcome.FRESH_INSERT, UUID.randomUUID(), displayName);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
