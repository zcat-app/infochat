package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.SourceUpsertService;
import app.zcat.infochat.provider.source.SourceUpsertService.Outcome;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link AddSourceCommandHandler} per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>The handler's six {@code @Inject} collaborators are stubbed in
 * {@link #buildHandlerWithStubs()}: real {@link BundleLoader} (loaded
 * by hand), real {@link KindResolver} (pure function), programmable
 * {@link RecordingUrlProbe} per-URL probe outcomes, programmable
 * {@link StubSourceUpsertService} per-scenario outcomes, programmable
 * {@link StubUserDataSource} per-contact-id user rows, and a hand-
 * constructed {@link InboundContext} with {@code adapterName="inmemory"}.
 *
 * <p>Asserted invariants (one {@code @Test} per behavioral branch):
 * <ul>
 *   <li>Dispatch: one /add-source call exercises UrlProbe exactly
 *       once on the dispatch path.</li>
 *   <li>Permission gate: DM non-banned proceeds, DM banned rejects,
 *       group non-admin rejects.</li>
 *   <li>Ambiguous probe: {@code /about} URL → AMBIGUOUS reply
 *       without invoking the probe; an RSS-hinted URL contradicted
 *       by {@code text/html} → AMBIGUOUS reply.</li>
 *   <li>URL-visibility disclosure: present on Branch A reply,
 *       absent on Branch B / Branch C replies.</li>
 * </ul>
 */
class AddSourceCommandHandlerTest {

    private AddSourceCommandHandler handler;
    private RecordingUrlProbe urlProbe;
    private StubSourceUpsertService sourceUpsertService;
    private StubUserDataSource dataSource;
    private BundleLoader bundleLoader;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        urlProbe = new RecordingUrlProbe();
        sourceUpsertService = new StubSourceUpsertService();
        dataSource = new StubUserDataSource();
        handler = new AddSourceCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.kindResolver = new KindResolver();
        handler.urlProbe = urlProbe;
        handler.sourceUpsertService = sourceUpsertService;
        handler.dataSource = dataSource;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        handler.inboundContext = context;
    }

    @Test
    void inboundRouterDispatchesAddSourceToHandlerExactlyOnce() {
        dataSource.seedUser("m1-036h-disp", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-disp.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-disp"),
                "/add-source https://example.com/m1-036h-disp.xml --tags m1-036h-news");

        assertEquals(1, urlProbe.callCount(),
                "the handler must call UrlProbe.probe exactly once on the dispatch path");
        // One OutboundMessage returned by signature; assert the text is non-empty
        // so this scenario fails loud if the handler short-circuits to nothing.
        assertFalse(reply.text().isEmpty(),
                "dispatch must produce a non-empty reply");
    }

    @Test
    void dmNonBannedNonAdminProceedsAndProducesFreshInsertReply() {
        dataSource.seedUser("m1-036h-fresh-user", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-fresh.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-fresh-user"),
                "/add-source https://example.com/m1-036h-fresh.xml --tags m1-036h-news");

        String body = reply.text();
        // Branch A reply MUST contain the URL-visibility disclosure literal.
        assertTrue(body.contains("visible to bot admins"),
                "Branch A reply MUST include the URL-visibility disclosure literal "
                        + "(per spec §Source management) — got: " + body);
    }

    @Test
    void dmBannedUserRejectsBeforeProbe() {
        // No mock probe setup needed — the ban check fires BEFORE probe.
        dataSource.seedUser("m1-036h-banned", /* isAdmin */ false, /* isBanned */ true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-banned"),
                "/add-source https://example.com/m1-036h-banned.xml --tags m1-036h-news");

        String body = reply.text();
        // The bundle value for error.add_source.banned should be present.
        assertTrue(body.contains("not permitted"),
                "banned user must see the banned-friendly-error literal — got: " + body);
        assertEquals(0, urlProbe.callCount(),
                "ban check must short-circuit BEFORE UrlProbe is invoked");
    }

    @Test
    void groupScopeNonAdminCallerIsRejected() {
        UUID groupId = UUID.randomUUID();
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(groupId.toString()),
                "/add-source https://example.com/m1-036h-group.xml --tags m1-036h-news");

        assertTrue(reply.text().contains("Only group admins"),
                "non-admin in group scope must see the group-admin-only friendly error — got: "
                        + reply.text());
        assertEquals(0, urlProbe.callCount(),
                "group-scope rejection must short-circuit BEFORE UrlProbe is invoked");
    }

    // The "GROUP scope, group admin → handler proceeds" branch is NOT
    // covered here: the frozen CommandHandler SPI does not carry the
    // inbound actor's identity in group scope (ScopeRef.Group holds
    // only the adapter-side group id; the actor's contact id is not on
    // the SPI), so the handler cannot consult group_membership for the
    // caller. T2-F wires the actor seam + the group-admin proceed
    // path; the corresponding acceptance test lands then.

    @Test
    void ambiguousUrlWithHtmlContentTypeSurfacesAmbiguousFriendlyError() {
        // /about URL has no RSS path-hint; the resolver returns
        // AMBIGUOUS directly. The handler short-circuits to the
        // ambiguous_url bundle key BEFORE the probe is invoked (the
        // probe runs after kind resolution).
        dataSource.seedUser("m1-036h-amb", /* isAdmin */ false, /* isBanned */ false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-amb"),
                "/add-source https://example.com/m1-036h-about --tags m1-036h-news");

        String body = reply.text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "ambiguous-URL reply must surface the ambiguous_url friendly error literal — got: "
                        + body);
        assertEquals(0, urlProbe.callCount(),
                "ambiguous-URL short-circuit must run BEFORE UrlProbe is invoked");
    }

    @Test
    void rssPathUrlContradictedByHtmlContentTypeSurfacesAmbiguous() {
        // Path ends in /feed → resolver chooses RSS via the path
        // hint. The probe returns text/html — the confirm-or-contradict
        // check fires and the handler returns the ambiguous_url friendly
        // error.
        dataSource.seedUser("m1-036h-contradict-user", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-news/feed",
                ProbeResult.success(200, Optional.of("text/html")));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-contradict-user"),
                "/add-source https://example.com/m1-036h-news/feed --tags m1-036h-news");

        String body = reply.text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "RSS-hinted URL contradicted by text/html Content-Type must surface "
                        + "the ambiguous_url friendly error — got: " + body);
    }

    @Test
    void branchBSubscribedExistingReplyOmitsUrlVisibilityDisclosure() {
        dataSource.seedUser("m1-036h-non-admin-b", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-shared.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.SUBSCRIBED_EXISTING);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-non-admin-b"),
                "/add-source https://example.com/m1-036h-shared.xml --tags m1-036h-other");

        String body = reply.text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch B (subscribed-existing) reply MUST NOT include the URL-visibility "
                        + "disclosure — got: " + body);
        assertTrue(body.contains("Subscribed"),
                "Branch B reply must include the subscribed-existing bundle literal — got: "
                        + body);
    }

    @Test
    void branchCBotAdminTagReplacementReplyOmitsUrlVisibilityDisclosure() {
        // Bot-admin caller against a pre-existing source row routes to
        // the ADMIN_TAGS_REPLACED arm of buildReply, which emits the
        // admin_tags_replaced bundle value WITHOUT the URL-visibility
        // disclosure (gated to FRESH_INSERT only).
        dataSource.seedUser("m1-036h-bot-admin-c", /* isAdmin */ true, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-admin.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.ADMIN_TAGS_REPLACED);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-bot-admin-c"),
                "/add-source https://example.com/m1-036h-admin.xml --tags m1-036h-new");

        String body = reply.text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch C (admin-tag-replacement) reply MUST NOT include the "
                        + "URL-visibility disclosure — got: " + body);
        assertTrue(body.contains("bootstrap tags replaced"),
                "Branch C reply must include the admin-tags-replaced bundle literal "
                        + "— got: " + body);
    }

    @Test
    void addSourceTypeNitterCreatesNitterKindSource() {
        // Explicit --type nitter resolves NITTER and persists kind='nitter'.
        // The host is not a configured nitter-host here, so resolution comes
        // purely from the explicit override; the feed takes the existing HTTP
        // probe (NITTER != NOSTR), and the RSS confirm-or-contradict gate does
        // not apply (kind != RSS) (M1-456).
        dataSource.seedUser("m1-456-nitter", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://nitter.example/someuser/rss",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-456-nitter"),
                "/add-source https://nitter.example/someuser/rss --type nitter --tags m1-456-news");

        assertEquals(KindResolver.SourceKind.NITTER, sourceUpsertService.lastKind(),
                "explicit --type nitter must persist kind='nitter'");
        assertEquals(1, urlProbe.callCount(),
                "nitter takes the existing HTTP probe exactly once, not the Nostr relay probe");
        assertFalse(reply.text().isEmpty(),
                "a successful nitter add must produce a non-empty reply");
    }

    // ----- fixtures + collaborator stubs --------------------------------

    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    /**
     * Programmable {@link SourceUpsertService} stub: returns a canned
     * {@link UpsertResult} on every {@link #upsert} call. Tests
     * configure the outcome per scenario via {@link #setOutcome}.
     * The display name is echoed back verbatim so Branch A's
     * fresh-insert reply gets a usable interpolation argument.
     */
    private static final class StubSourceUpsertService extends SourceUpsertService {
        private Outcome outcome = Outcome.FRESH_INSERT;
        private KindResolver.SourceKind lastKind;

        void setOutcome(Outcome outcome) {
            this.outcome = outcome;
        }

        /** The {@code kind} the handler resolved and passed to the upsert. */
        KindResolver.SourceKind lastKind() {
            return lastKind;
        }

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
            this.lastKind = kind;
            return new UpsertResult(outcome, UUID.randomUUID(), displayName);
        }
    }
}
