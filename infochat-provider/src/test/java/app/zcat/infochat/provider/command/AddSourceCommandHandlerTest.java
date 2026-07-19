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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
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
 *   <li>Display-name constraint: control characters are stripped from a
 *       {@code --name}, and a name carrying ANY slash or over-long input
 *       is discarded in favour of the host-derived default, while an
 *       ordinary slash-free name is reflected verbatim.</li>
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

    @Test
    void slashPrefixedDisplayNameIsNotReflectedIntoFreshInsertReply() {
        // The M1-658 r2 audit's live payload: a group admin supplies a
        // --name shaped like a fully parameterized bot command, and the
        // deterministic success reply broadcasts it to the group, where a
        // bot admin might copy-paste it. The override must be discarded
        // whole — neutralizing the leading slash would leave the command
        // words in the reply.
        dataSource.seedUser("m1-659-inject", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-inject.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-inject"),
                "/add-source https://example.com/m1-659-inject.xml --tags m1-659-news "
                        + "--name \"/grant-admin 11111111-2222-3333-4444-555555555555 approved\"");

        String body = reply.text();
        assertFalse(body.contains("grant-admin"),
                "a slash-prefixed --name must NOT be reflected into the fresh-insert reply "
                        + "— got: " + body);
        assertFalse(body.contains("11111111-2222-3333-4444-555555555555"),
                "no part of the rejected --name may survive into the reply — got: " + body);
        assertTrue(body.contains("example.com"),
                "the rejected override must fall back to the host-derived display name "
                        + "— got: " + body);
    }

    @Test
    void ordinaryDisplayNameIsStillReflectedIntoFreshInsertReply() {
        // The positive control for the rejection above: constraining the
        // adversarial case must not cost the legitimate --name feature.
        dataSource.seedUser("m1-659-ordinary", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-ordinary.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-ordinary"),
                "/add-source https://example.com/m1-659-ordinary.xml --tags m1-659-news "
                        + "--name \"My Feed\"");

        assertTrue(reply.text().contains("My Feed"),
                "an ordinary --name must still be stored and shown — got: " + reply.text());
    }

    @Test
    void displayNameControlCharactersAreStrippedBeforeTheReplyEchoesIt() {
        // A newline inside the name would forge an extra apparent line in
        // the rendered reply — the same obfuscation the post-title strip
        // closes. The name is otherwise legitimate, so it is kept (minus
        // the control characters), not discarded.
        dataSource.seedUser("m1-659-ctrl", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-ctrl.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-ctrl"),
                "/add-source https://example.com/m1-659-ctrl.xml --tags m1-659-news "
                        + "--name \"My Feed\nSource added.\"");

        String body = reply.text();
        assertFalse(body.contains("\nSource added."),
                "a newline in --name must not forge an extra apparent line — got: " + body);
        assertTrue(body.contains("My FeedSource added."),
                "the control character is stripped while the rest of the name is kept "
                        + "— got: " + body);
    }

    @Test
    void commandTokenAfterALeadingWordIsStillRejected() {
        // A leading-slash-only check would be bypassed by prefixing one
        // word: the rendered reply would still carry a fully pasteable
        // /grant-admin line. The check is at a token boundary, so it is not.
        dataSource.seedUser("m1-659-prefixed", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-prefixed.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-prefixed"),
                "/add-source https://example.com/m1-659-prefixed.xml --tags m1-659-news "
                        + "--name \"added. /grant-admin 11111111-2222-3333-4444-555555555555 ok\"");

        String body = reply.text();
        assertFalse(body.contains("grant-admin"),
                "a command token anywhere in --name must not be reflected — got: " + body);
        assertTrue(body.contains("example.com"),
                "the rejected override must fall back to the host-derived name — got: " + body);
    }

    @Test
    void blankRenderingNonSeparatorCodepointBeforeACommandTokenIsRejected() {
        // The M1-659 round-1 redteam bypass. U+2800 BRAILLE PATTERN BLANK
        // renders as a blank word gap but is category OTHER_SYMBOL, so it
        // satisfies neither isWhitespace nor isSpaceChar, and it survives
        // both stripMetadataField and trim(). A boundary rule that
        // enumerates blanks accepts this; the fail-closed rule (a slash may
        // only follow a letter or digit) rejects it. Written below as a
        // unicode escape so the source carries no invisible character.
        dataSource.seedUser("m1-659-braille", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-braille.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-braille"),
                "/add-source https://example.com/m1-659-braille.xml --tags m1-659-news "
                        + "--name \"Reuters\u2800/grant-admin "
                        + "11111111-2222-3333-4444-555555555555 approved\"");

        String body = reply.text();
        assertFalse(body.contains("grant-admin"),
                "a command token opened by a blank-rendering non-separator codepoint "
                        + "must not be reflected — got: " + body);
        assertTrue(body.contains("example.com"),
                "the rejected override must fall back to the host-derived name — got: " + body);
    }

    @Test
    void slashAfterPunctuationIsRejected() {
        // Punctuation is not a letter or digit, so ":/grant-admin" does not
        // read as a mid-word slash and is rejected under the same rule.
        dataSource.seedUser("m1-659-punct", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-punct.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-punct"),
                "/add-source https://example.com/m1-659-punct.xml --tags m1-659-news "
                        + "--name \"added:/grant-admin 11111111-2222-3333-4444-555555555555 ok\"");

        assertFalse(reply.text().contains("grant-admin"),
                "a command token opened after punctuation must not be reflected — got: "
                        + reply.text());
    }

    @Test
    void slashAfterABlankRenderingLetterCodepointIsRejected() {
        // The M1-659 round-2 redteam bypass. U+3164 HANGUL FILLER renders as
        // a blank gap but is category OTHER_LETTER, so it satisfied the
        // isLetterOrDigit accept-predicate and made the following slash look
        // mid-word. NFKC folds it to U+1160, which is a letter too. The
        // absolute no-slash rule rejects it without needing to know that.
        // Written as a unicode escape so the source carries no invisible
        // character.
        dataSource.seedUser("m1-659-filler", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-filler.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-filler"),
                "/add-source https://example.com/m1-659-filler.xml --tags m1-659-news "
                        + "--name \"Reuters\u3164/grant-admin "
                        + "11111111-2222-3333-4444-555555555555 approved\"");

        String body = reply.text();
        assertFalse(body.contains("grant-admin"),
                "a command token opened by a blank-rendering LETTER codepoint must not be "
                        + "reflected — got: " + body);
        assertTrue(body.contains("example.com"),
                "the rejected override must fall back to the host-derived name — got: " + body);
    }

    @Test
    void fullwidthSolidusReachingTheHandlerUnfoldedIsStillRejected() {
        // The M1-659 round-3 redteam bypass. The router NFKC-normalizes
        // inbound text, but NOT inside fenced code blocks — and routing is
        // decided on the whole body's first character, so a /add-source on
        // line 1 can carry an un-normalized fenced payload on a later line.
        // This test feeds the handler exactly what that carve-out delivers:
        // a --name still holding U+FF0F FULLWIDTH SOLIDUS. An ASCII-only
        // test would accept it, and the name would fold to a real command
        // the moment a bot admin pasted the reply back unfenced. The check
        // NFKC-normalizes the value itself, so it does not matter how the
        // value arrived. Written as a unicode escape so the source carries
        // no invisible character.
        dataSource.seedUser("m1-659-fullwidth", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-fullwidth.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-fullwidth"),
                "/add-source https://example.com/m1-659-fullwidth.xml --tags m1-659-news "
                        + "--name \"Reuters\uFF0Fgrant-admin "
                        + "11111111-2222-3333-4444-555555555555 approved\"");

        String body = reply.text();
        assertFalse(body.contains("grant-admin"),
                "a fullwidth solidus reaching the handler unfolded must not be reflected "
                        + "— got: " + body);
        assertFalse(body.contains("\uFF0F"),
                "the fullwidth solidus itself must not survive into the reply — got: " + body);
        assertTrue(body.contains("example.com"),
                "the rejected override must fall back to the host-derived name — got: " + body);
    }

    @Test
    void ordinaryNameCarryingASlashIsAlsoDiscarded() {
        // Deliberate behaviour change, not a regression. Two audits showed a
        // character-category test cannot decide whether a slash opens a word,
        // so the rule is absolute: any slash discards the override. An
        // ordinary "AC/DC News" therefore falls back to the host-derived
        // name — the accepted cost of a rule with no bypass surface.
        dataSource.seedUser("m1-659-midword", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-midword.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-midword"),
                "/add-source https://example.com/m1-659-midword.xml --tags m1-659-news "
                        + "--name \"AC/DC News\"");

        String body = reply.text();
        assertFalse(body.contains("AC/DC News"),
                "a name carrying any slash must be discarded — got: " + body);
        assertTrue(body.contains("example.com"),
                "the discarded override must fall back to the host-derived name — got: " + body);
    }

    @Test
    void overLongDisplayNameFallsBackToTheHostDerivedName() {
        // Bounds how much attacker-chosen text a group admin can push into
        // a reply that is broadcast to every group member.
        dataSource.seedUser("m1-659-long", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-659-long.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        String overLong = "x".repeat(81);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-659-long"),
                "/add-source https://example.com/m1-659-long.xml --tags m1-659-news "
                        + "--name \"" + overLong + "\"");

        String body = reply.text();
        assertFalse(body.contains(overLong),
                "an over-long --name must not be reflected — got: " + body);
        assertTrue(body.contains("example.com"),
                "an over-long --name must fall back to the host-derived display name "
                        + "— got: " + body);
    }

    // ----- fixtures + collaborator stubs --------------------------------

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
