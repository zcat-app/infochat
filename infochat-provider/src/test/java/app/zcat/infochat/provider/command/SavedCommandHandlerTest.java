package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SavedCommandHandler} against the
 * DevServices Postgres container (V15 saved_post). One {@code @Test}
 * per acceptance scenario in M1-052 acceptance item 5.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-052-saved-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix.</p>
 *
 * @implNote Shape B (Thin-SQL) per
 *     {@code docs/process/test-pyramid.md} §Handler unit tests —
 *     the handler's behavioral contract IS the SQL predicate
 *     (per-user-global with no scope discriminator clause; optional
 *     personal_tags filter; optional saved_at window;
 *     {@code ORDER BY saved_at DESC LIMIT/OFFSET} pagination), so
 *     observation against seeded rows is the only honest verification.
 */
@QuarkusTest
class SavedCommandHandlerTest {

    private static final String PREFIX = "m1-052-saved-";
    private static final String ADAPTER = "inmemory";

    @Inject SavedCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    // The test-classpath translator stub behind the REAL TranslationPipeline
    // bean — the spy that pins the en-scope no-op at the renderer level.
    @Inject TestLlmProvider mockLlm;
    // The same per-user LLM bucket the handler draws from — saturated in
    // the rate-cap test and refunded there so no budget leaks across tests.
    @Inject LlmRateCap llmRateCap;
    // The D47 aggregate group bucket, saturated in the group-backstop test.
    @Inject RateCapBucket rateCapBucket;

    @Inject
    @ConfigProperty(name = "infochat.chat.llm-rate-cap-per-minute", defaultValue = "10")
    int llmRateCapPerMinute;

    @Inject
    @ConfigProperty(name = "infochat.ratelimit.group-llm-per-15min", defaultValue = "5")
    int groupLlmCap;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM post WHERE uid LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void savedReturnsEmptyHeaderWhenLibraryEmpty() throws Exception {
        String contactId = PREFIX + "empty-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY), reply.text(),
                "empty library must surface reply.saved.empty");
    }

    @Test
    void savedReplyHeaderDisclosesGlobalScope() throws Exception {
        String contactId = PREFIX + "hdr-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "hdr-source");
        seedSavedPost(userId, sourceId, PREFIX + "hdr-uid-1", "Title", new String[] {}, new String[] {},
                Instant.now().minus(2, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // The disclosure-header letter — per spec §Content and
        // design 03 §/saved — MUST mention that saves are global
        // across DM and groups so the user is not surprised by DM-only
        // saves appearing in a group context.
        assertTrue(reply.text().contains("global across DM and groups"),
                "/saved reply header must disclose cross-scope visibility; got: " + reply.text());
    }

    @Test
    void savedListsAllRowsForActorRegardlessOfScopeOfOrigin() throws Exception {
        // Per spec §Per-user state (D13): saved_post carries user_id
        // only — no scope discriminator. Two saves under the same
        // actor with different snapshot_tags both appear under /saved
        // because the SQL predicate has no scope filter.
        String contactId = PREFIX + "noscope-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "noscope-source");
        seedSavedPost(userId, sourceId, PREFIX + "noscope-uid-a", "Title A",
                new String[] { "scope-a" }, new String[] {}, Instant.now().minus(2, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "noscope-uid-b", "Title B",
                new String[] { "scope-b" }, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // Both rows appear regardless of which scope they were saved
        // from — proves the SQL has no WHERE scope_kind = ? clause.
        assertTrue(reply.text().contains(PREFIX + "noscope-uid-a"),
                "row A must appear in /saved listing");
        assertTrue(reply.text().contains(PREFIX + "noscope-uid-b"),
                "row B must appear in /saved listing");
    }

    @Test
    void savedFiltersByPersonalTag() throws Exception {
        String contactId = PREFIX + "ptag-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "ptag-source");
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-ai-1", "AI 1",
                new String[] {}, new String[] { "ai" }, Instant.now().minus(3, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-ai-2", "AI 2",
                new String[] {}, new String[] { "ai", "read-later" }, Instant.now().minus(2, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-other", "Other",
                new String[] {}, new String[] { "read-later" }, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage filtered = handler.handle(new ScopeRef.Dm(contactId), "/saved ai");

        assertTrue(filtered.text().contains(PREFIX + "ptag-uid-ai-1"),
                "personal-tag-filtered listing must include ai-1");
        assertTrue(filtered.text().contains(PREFIX + "ptag-uid-ai-2"),
                "personal-tag-filtered listing must include ai-2");
        assertFalse(filtered.text().contains(PREFIX + "ptag-uid-other"),
                "personal-tag-filtered listing must NOT include rows lacking the tag");
    }

    @Test
    void savedFiltersByWindow() throws Exception {
        String contactId = PREFIX + "win-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "win-source");
        seedSavedPost(userId, sourceId, PREFIX + "win-uid-old", "Old",
                new String[] {}, new String[] {}, Instant.now().minus(30, ChronoUnit.DAYS));
        seedSavedPost(userId, sourceId, PREFIX + "win-uid-recent", "Recent",
                new String[] {}, new String[] {}, Instant.now().minus(2, ChronoUnit.DAYS));

        OutboundMessage windowed = handler.handle(new ScopeRef.Dm(contactId), "/saved -w 7d");

        assertTrue(windowed.text().contains(PREFIX + "win-uid-recent"),
                "window-filtered listing must include the recent save");
        assertFalse(windowed.text().contains(PREFIX + "win-uid-old"),
                "window-filtered listing must NOT include the old save");
    }

    @Test
    void savedPaginatesByPageFlag() throws Exception {
        String contactId = PREFIX + "page-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "page-source");
        // 25 saves so page 1 = 20 rows, page 2 = 5 rows (PAGE_SIZE=20).
        for (int i = 0; i < 25; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "page-uid-" + i, "P" + i,
                    new String[] {}, new String[] {},
                    Instant.now().minus(i, ChronoUnit.MINUTES));
        }

        OutboundMessage page1 = handler.handle(new ScopeRef.Dm(contactId), "/saved");
        OutboundMessage page2 = handler.handle(new ScopeRef.Dm(contactId), "/saved --page 2");

        // Page 1 shows the 20 most recent (i = 0..19); page 2 shows
        // the next 5 (i = 20..24).
        int onPage1 = countOccurrences(page1.text(), PREFIX + "page-uid-");
        int onPage2 = countOccurrences(page2.text(), PREFIX + "page-uid-");
        assertEquals(SavedCommandHandler.PAGE_SIZE, onPage1,
                "page 1 must contain exactly PAGE_SIZE rows");
        assertEquals(25 - SavedCommandHandler.PAGE_SIZE, onPage2,
                "page 2 must contain the remaining rows");
        // The newest (i=0) is on page 1; the oldest (i=24) is on page 2.
        assertTrue(page1.text().contains(PREFIX + "page-uid-0"),
                "newest save (i=0) must appear on page 1");
        assertTrue(page2.text().contains(PREFIX + "page-uid-24"),
                "oldest save (i=24) must appear on page 2");
    }

    @Test
    void saved_succeedsInGroupScope() throws Exception {
        String contactId = PREFIX + "group-actor";
        inboundContext.setSenderContactId(contactId);
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "group-source");
        seedSavedPost(userId, sourceId, PREFIX + "group-uid-1", "Title",
                new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/saved");

        assertTrue(reply.text().contains(PREFIX + "group-uid-1"),
                "/saved in group scope must list the actor's saved posts");
    }

    @Test
    void savedRedactsCommandShapedTitleAndTagInGroupBroadcast() throws Exception {
        // A pre-existing / upstream-controlled row (seeded via direct SQL,
        // bypassing the write-side reject) carries a command-shaped title AND
        // a command-shaped personal tag. /saved in a group broadcasts the
        // reply to every member, so both echoes must be redacted at render.
        // M1-675 F1 (title) + F2 (pre-existing tag row).
        String contactId = PREFIX + "redact-actor";
        inboundContext.setSenderContactId(contactId);
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "redact-source");
        seedSavedPost(userId, sourceId, PREFIX + "redact-uid-1",
                "/grant-admin 11111111-2222-3333-4444-555555555555",
                new String[] {}, new String[] { "/ban 22222222-3333-4444-5555-666666666666" },
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/saved");

        assertTrue(reply.text().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "command-shaped title/tag must be redacted in the broadcast reply; got: " + reply.text());
        assertFalse(reply.text().contains("/grant-admin"),
                "raw /grant-admin (title) must not survive into the reply; got: " + reply.text());
        assertFalse(reply.text().contains("/ban"),
                "raw /ban (personal tag) must not survive into the reply; got: " + reply.text());
    }

    @Test
    void savedPassesLegitSlashTitleByteIdentical() throws Exception {
        // A non-command slash (TCP/IP) is not a closed-list token, so the
        // title must render byte-identical — the sanitizer does not
        // over-redact ordinary content. M1-675.
        String contactId = PREFIX + "legit-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "legit-source");
        seedSavedPost(userId, sourceId, PREFIX + "legit-uid-1", "TCP/IP explained",
                new String[] {}, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("TCP/IP explained"),
                "legit-slash title must render byte-identical; got: " + reply.text());
        assertFalse(reply.text().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a non-command slash must not trigger redaction; got: " + reply.text());
    }

    @Test
    void savedRendersSavedBodyWhenTitleIsTheIngestSentinel() throws Exception {
        // A save taken from a titleless-by-design source (Bluesky, Nostr, a
        // Reddit item with no title) snapshots IngestTextNormalizer's
        // UNTITLED_TITLE placeholder into saved_post.title. Before M1-730 the
        // line printed that storage sentinel while the post's real text sat
        // unread in saved_post.body.
        String contactId = PREFIX + "sentinel-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "sentinel-source");
        seedSavedPost(userId, sourceId, PREFIX + "sentinel-uid-1", "untitled",
                "Zcash shielded pool crosses a million ZEC", new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("Zcash shielded pool crosses a million ZEC"),
                "the saved body must supply the headline for a sentinel title; got: " + reply.text());
        assertFalse(reply.text().contains("untitled"),
                "the ingest storage sentinel must never reach a reader; got: " + reply.text());
    }

    @Test
    void savedRedactsCommandShapedBodyPromotedToTheHeadline() throws Exception {
        // The newly reachable leg: before M1-730 the body could not reach this
        // line at all, so promoting it inherits the M1-675 threat the title
        // carries — /saved in a group broadcasts to every member, and the
        // headline renders at line start where a command-shaped echo is one
        // copy-paste from dispatch. The redaction must therefore cover the
        // body, and it must still operate on ONE author's field per call
        // (M1-697): the title is the sentinel, so the body alone is sanitized.
        String contactId = PREFIX + "bodyredact-actor";
        inboundContext.setSenderContactId(contactId);
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "bodyredact-source");
        seedSavedPost(userId, sourceId, PREFIX + "bodyredact-uid-1", "untitled",
                "/grant-admin 11111111-2222-3333-4444-555555555555",
                new String[] {}, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/saved");

        assertTrue(reply.text().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a command-shaped saved body promoted to the headline must be redacted; got: "
                        + reply.text());
        assertFalse(reply.text().contains("/grant-admin"),
                "raw /grant-admin (body) must not survive into the reply; got: " + reply.text());
    }

    @Test
    void savedRendersStableLineWhenNeitherTitleNorBodyHasText() throws Exception {
        // saved_post.title is NOT NULL and saved_post.body is nullable, so a
        // save with the sentinel title and no body is a real row shape.
        // DisplayHeadline yields nothing for it, and its contract is that the
        // caller drops the headline token TOGETHER with the separator that
        // would have followed it — the reply.saved.line.no-headline template.
        // Interpolating "" into the ordinary template would leave a doubled
        // separator where the headline was.
        String contactId = PREFIX + "nohl-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "nohl-source");
        seedSavedPost(userId, sourceId, PREFIX + "nohl-uid-1", "untitled",
                null, new String[] {}, new String[] { "zcash" },
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("[" + PREFIX + "nohl-uid-1" + "] — saved "),
                "the uid must be followed by a single separator, not a blank where the "
                        + "headline was; got: " + reply.text());
        assertFalse(reply.text().contains("untitled"),
                "the ingest storage sentinel must never reach a reader; got: " + reply.text());
        assertTrue(reply.text().contains("zcash"),
                "the rest of the line must still render; got: " + reply.text());
    }

    @Test
    void savedBoundsTheBodyInSqlWithoutChangingTheRenderedHeadline() throws Exception {
        // `saved_post.body` has no write-boundary cap, and DisplayHeadline's
        // BODY_SCAN_LIMIT guard runs only after the column has crossed JDBC —
        // for a 20-row page that is 20 unbounded columns materialised to
        // produce 20 bounded headlines. The SELECT therefore bounds it with
        // left(). The bound must be invisible: left() counts CODE POINTS while
        // Java counts UTF-16 units, so a truncated value always carries at
        // least as many Java chars as the helper's own cut consumes. The emoji
        // is what makes the two counts disagree, so it is load-bearing here,
        // not decoration. (Redteam 2026-07-30, medium/DOS.)
        String hugeBody = "Zcash shielded pool 😀 crosses a million ZEC "
                + "w".repeat(DisplayHeadline.BODY_SCAN_LIMIT * 3);
        String contactId = PREFIX + "sqlcap-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "sqlcap-source");
        seedSavedPost(userId, sourceId, PREFIX + "sqlcap-uid-1", "untitled",
                hugeBody, new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // The oracle is the derivation over the FULL body — what the handler
        // would have rendered with no SQL bound at all.
        String uncappedHeadline = DisplayHeadline.of(
                "untitled", hugeBody, SanitizerTestDoubles.noAuditSanitizer());
        assertTrue(reply.text().contains(uncappedHeadline),
                "the SQL bound must not change the headline; expected: " + uncappedHeadline
                        + "; got: " + reply.text());
    }

    @Test
    void enScopeRendersTheSnapshottedAnchorWithBracketedOriginalAndZeroTranslatorCalls()
            throws Exception {
        // M1-765, the whole point of snapshotting the anchor: an English
        // reader's Turkish bookmark now renders the same anchor-first block a
        // digest renders for the same post — English primary, publisher's own
        // words bracketed beneath — and it costs NO provider call, because
        // the anchor is a column read. The spy assertion is what makes that
        // claim testable rather than asserted: a render that reached for the
        // translator instead would still produce English text.
        String contactId = PREFIX + "anchor-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "anchor-source");
        seedAnchoredSavedPost(userId, sourceId, PREFIX + "anchor-uid-1",
                "Türkçe başlık", "Turkish headline", "tr",
                Instant.now().minus(1, ChronoUnit.HOURS));
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("would-be translation");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("Turkish headline"),
                "the snapshotted English anchor must occupy the primary slot; got: "
                        + reply.text());
        assertFalse(reply.text().contains("[Turkish headline]"),
                "the anchor IS in an en reader's language, so it must render unbracketed "
                        + "(D29 (c)); got: " + reply.text());
        assertTrue(reply.text().contains("\n[Türkçe başlık]"),
                "the publisher's own words must stay reachable on the bracketed subordinate "
                        + "line; got: " + reply.text());
        assertEquals(0, mockLlm.callCount(),
                "reading a snapshotted anchor is a column read — it must add ZERO "
                        + "translator calls");
        mockLlm.reset();
    }

    @Test
    void preAnchorSaveWithNullAnchorStillRendersTheBracketedOriginal() throws Exception {
        // Old saves still render, and render HONESTLY. A row written before
        // V78 carries NULL in both anchor columns, which is the accurate
        // value — no anchor was ever computed for it — and takes M1-759's
        // anchor-absent branch: the original in the primary slot, bracketed
        // because it is NOT in the reader's language. Not a blank headline
        // (the row has text), and not a bare line (that would tell an en
        // reader the Turkish words are English). The seed deliberately omits
        // the anchor columns entirely, so it writes the pre-migration shape.
        String contactId = PREFIX + "nullanchor-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "nullanchor-source");
        seedAnchoredSavedPost(userId, sourceId, PREFIX + "nullanchor-uid-1",
                "Türkçe başlık", null, "tr",
                Instant.now().minus(1, ChronoUnit.HOURS));
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("would-be translation");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("[Türkçe başlık]"),
                "a NULL anchor must render the original BRACKETED, since it is not in the "
                        + "reader's language; got: " + reply.text());
        assertEquals(1, countOccurrences(reply.text(), "Türkçe başlık"),
                "the anchor-absent branch renders the original once — primary and "
                        + "subordinate are the same string, so the subordinate is suppressed; "
                        + "got: " + reply.text());
        assertEquals(0, mockLlm.callCount(),
                "an en scope makes no translator call regardless of the anchor's presence");
        mockLlm.reset();
    }

    @Test
    void savedBoundsTheAnchorInSqlWithoutChangingTheRenderedHeadline() throws Exception {
        // Both anchor columns are LLM-authored and capped at NO write path
        // (IngestTranslationWorker stores the translator's reply as it
        // stands), so they carry the same unbounded-operand property that
        // earned `body` its left() — and /saved materialises 20 of them per
        // page. The bound must be invisible for the same reason it is on
        // `body`: left() counts CODE POINTS while Java counts UTF-16 units,
        // so the truncated value always carries at least as many Java chars
        // as DisplayHeadline's own cut consumes. The emoji is what makes the
        // two counts disagree, so it is load-bearing here, not decoration.
        String hugeAnchor = "Shielded pool 😀 crosses a million ZEC "
                + "w".repeat(DisplayHeadline.BODY_SCAN_LIMIT * 3);
        String contactId = PREFIX + "anchorcap-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "anchorcap-source");
        seedAnchoredSavedPost(userId, sourceId, PREFIX + "anchorcap-uid-1",
                "Türkçe başlık", hugeAnchor, "tr",
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // The oracle is the derivation over the FULL anchor — what the
        // handler would have rendered with no SQL bound at all.
        String uncappedAnchorLine = DisplayHeadline.anchorFirst(
                        "Türkçe başlık", null, hugeAnchor, null,
                        SanitizerTestDoubles.noAuditSanitizer())
                .readerLine();
        assertTrue(reply.text().contains(uncappedAnchorLine),
                "the SQL bound must not change the rendered anchor; expected: "
                        + uncappedAnchorLine + "; got: " + reply.text());
    }

    @Test
    void csScopeTranslatesHeadlineOfDifferingSourceLanguageRowWithBracketedOriginal() throws Exception {
        // M1-755 renderer-wiring pin (the M1-747 precedent): without this
        // case, deleting the runForDisplayHit call in buildReply would keep
        // every pipeline-level test green — those prove the leg works WHEN
        // called; only this proves the /saved renderer calls it. The saved
        // row's snapshot language ('en', the V76 default) differs from the
        // cs scope, so the headline routes through the translating leg and
        // renders the translated headline through the REAL pipeline (real
        // cache, real sanitizer-2), with the publisher's own words on the
        // bracketed line beneath it (M1-759).
        String contactId = PREFIX + "xlate-actor";
        inboundContext.setEffectiveLanguage("cs");
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "xlate-source");
        String uid = PREFIX + "xlate-uid-1";
        seedSavedPost(userId, sourceId, uid, "Original title",
                new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("Přeložený titulek");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("Přeložený titulek"),
                "cs-scope /saved must render the translated headline; got: " + reply.text());
        assertTrue(reply.text().contains("\n[Original title]"),
                "the publisher's own words must render on the bracketed subordinate line, so "
                        + "the original stays reachable (D29 (c)); got: " + reply.text());
        assertFalse(reply.text().contains("[Přeložený titulek]"),
                "a successful translation IS in the reader's language, so the primary line "
                        + "must stay unbracketed; got: " + reply.text());
        assertEquals(1, mockLlm.callCount(),
                "one row, one translator call — the cs-scope render must exercise the leg");
        mockLlm.reset();
    }

    @Test
    void enScopeRendersByteIdenticalWithZeroTranslatorCalls() throws Exception {
        // The en-scope no-op, pinned at the renderer level with the
        // TestLlmProvider spy: a byte-identical en render must not invoke
        // the translator once (the existing line-byte pins cover the
        // bytes; this covers the call count).
        String contactId = PREFIX + "enscope-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "enscope-source");
        String uid = PREFIX + "enscope-uid-1";
        seedSavedPost(userId, sourceId, uid, "En title",
                new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("would-be translation");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("En title"),
                "en-scope /saved must render the headline byte-identical; got: " + reply.text());
        assertFalse(reply.text().contains("would-be translation"),
                "the translator must never be invoked for an en scope; got: " + reply.text());
        assertEquals(0, mockLlm.callCount(),
                "an en-scope /saved page must make ZERO translator calls");
        mockLlm.reset();
    }

    @Test
    void llmBucketExhaustedRendersPageUntranslatedWithZeroTranslatorCalls() throws Exception {
        // The rate-cap control (redteam 2026-08-03, high/DOS refine): the
        // leg draws ONE per-user LlmRateCap token per translating
        // invocation; a rejected draw degrades the WHOLE page to
        // untranslated — the listing must still render (it stays in the
        // cheap class), just without the leg.
        String contactId = PREFIX + "llmcapx-actor";
        inboundContext.setEffectiveLanguage("cs");
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "llmcapx-source");
        seedSavedPost(userId, sourceId, PREFIX + "llmcapx-uid-1", "Cap title",
                new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));
        // Saturate the user's per-user LLM bucket (the bean the handler
        // draws from). The test profile caps it at 3/min
        // (%test.infochat.chat.llm-rate-cap-per-minute=3), so the
        // handler's draw after saturation must be rejected.
        for (int i = 0; i < llmRateCapPerMinute; i++) {
            assertTrue(llmRateCap.tryAcquire(userId), "saturation acquire must succeed at " + i);
        }
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("would-be translation");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains("Cap title"),
                "the listing must still render at bucket exhaustion; got: " + reply.text());
        assertFalse(reply.text().contains("would-be translation"),
                "no translator call may fire at bucket exhaustion; got: " + reply.text());
        assertEquals(0, mockLlm.callCount(),
                "an exhausted per-user LLM bucket must degrade the leg, not run it");

        // Restore the budget so the saturation never leaks across tests.
        for (int i = 0; i < llmRateCapPerMinute; i++) {
            llmRateCap.refund(userId);
        }
        mockLlm.reset();
    }

    @Test
    void perPageTranslationBudgetBoundsTranslatorCallsAndRendersConverge() throws Exception {
        // The per-page budget control (redteam 2026-08-03, high/DOS
        // refine): one more budget-eligible row than
        // infochat.save.translation-max-per-page (default 5). The first
        // render makes exactly `budget` translator calls and leaves the
        // overflow row untranslated; the second render translates the
        // remainder because cache hits cost nothing — the budget bounds
        // per-invocation calls, never the cache.
        String contactId = PREFIX + "budget-actor";
        inboundContext.setEffectiveLanguage("cs");
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "budget-source");
        for (int i = 0; i < 6; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "budget-uid-" + i, "Original " + i,
                    new String[] {}, new String[] {},
                    Instant.now().minus(i, ChronoUnit.HOURS));
        }
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("Přeložený titulek");

        OutboundMessage first = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertEquals(5, mockLlm.callCount(),
                "the first render must make exactly the per-page budget of translator calls; got: "
                        + first.text());
        assertEquals(5, countOccurrences(first.text(), "Přeložený titulek"),
                "exactly the budget rows render translated on the first render; got: "
                        + first.text());
        assertTrue(first.text().contains("Original 5"),
                "the row beyond the budget renders untranslated on the first render; got: "
                        + first.text());

        OutboundMessage second = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertEquals(6, mockLlm.callCount(),
                "the second render translates the remainder — cache hits cost nothing");
        assertEquals(6, countOccurrences(second.text(), "Přeložený titulek"),
                "after two renders every row is translated; got: " + second.text());

        // Converged-page no-draw pin (redteam refine, opencode finding):
        // a fully-cached page makes ZERO translator calls AND draws no
        // per-user token. The user has drawn exactly 2 tokens (one per
        // translating render) against the %test cap of 3 — if the
        // converged render drew a third, this acquire would fail.
        OutboundMessage third = handler.handle(new ScopeRef.Dm(contactId), "/saved");
        assertEquals(6, mockLlm.callCount(),
                "a converged page must make ZERO translator calls");
        assertEquals(6, countOccurrences(third.text(), "Přeložený titulek"),
                "the converged page renders every row translated; got: " + third.text());
        assertTrue(llmRateCap.tryAcquire(userId),
                "a converged render must not draw a per-user token — the user's "
                        + "last slot must still be free");
        for (int i = 0; i < llmRateCapPerMinute; i++) {
            llmRateCap.refund(userId);
        }
        mockLlm.reset();
    }

    @Test
    void groupBucketExhaustedDegradesTheLegAndRefundsThePerUserToken() throws Exception {
        // The D47 group backstop (redteam 2026-08-03, kimi finding): in
        // group scope the leg draws the aggregate group LLM bucket
        // alongside the per-user token, and a group reject REFUNDS the
        // per-user token (the RetryCommandHandler pattern) — otherwise
        // the backstop would drain every member's personal budget. The
        // per-user bucket is pre-filled to cap-1: if the refund is
        // missing, the post-render acquire below fails.
        String contactId = PREFIX + "grpbk-actor";
        inboundContext.setEffectiveLanguage("cs");
        inboundContext.setSenderContactId(contactId);
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "grpbk-source");
        UUID groupId = seedGroup(PREFIX + "grpbk-group");
        seedSavedPost(userId, sourceId, PREFIX + "grpbk-uid-1", "Group title",
                new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));
        // Saturate the group's D47 aggregate LLM bucket (the bean the
        // handler draws from). The %test profile caps it at 100 per
        // 15 min (%test.infochat.ratelimit.group-llm-per-15min=100), so
        // the acquire after saturation must be rejected.
        for (int i = 0; i < groupLlmCap; i++) {
            assertTrue(rateCapBucket.tryAcquireGroupLlm(groupId),
                    "group saturation acquire must succeed at " + i);
        }
        for (int i = 0; i < llmRateCapPerMinute - 1; i++) {
            assertTrue(llmRateCap.tryAcquire(userId), "user pre-fill must succeed at " + i);
        }
        mockLlm.reset();
        mockLlm.setTranslatorResponseText("would-be translation");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(PREFIX + "grpbk-group"), "/saved");

        assertTrue(reply.text().contains("Group title"),
                "the listing must still render at group-bucket exhaustion; got: "
                        + reply.text());
        assertFalse(reply.text().contains("would-be translation"),
                "no translator call may fire at group-bucket exhaustion; got: "
                        + reply.text());
        assertEquals(0, mockLlm.callCount(),
                "an exhausted group bucket must degrade the leg, not run it");
        assertTrue(llmRateCap.tryAcquire(userId),
                "the group reject must refund the per-user token — the pre-filled "
                        + "cap-1 user must still have its last slot free");

        for (int i = 0; i < llmRateCapPerMinute; i++) {
            llmRateCap.refund(userId);
        }
        mockLlm.reset();
    }

    @Test
    void savedHidesSaveWhosePostIsQuarantined() throws Exception {
        // The visibility interlock (M1-730; redteam 2026-07-30,
        // medium/INFO-LEAK): a post re-hidden to QUARANTINED after being
        // saved must not keep rendering — in group scope the reply is
        // broadcast to every member. The predicate is in BOTH listing
        // SELECTs, so the hidden row leaves the header's total too (a
        // rows-only predicate would print "1 of 2"). The probe reads
        // existence and status only: no post content column may cross
        // into the reply.
        String contactId = PREFIX + "hide-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "hide-source");
        seedPost(sourceId, PREFIX + "hide-uid-q", "QUARANTINED", Instant.parse("2026-05-15T00:00:00Z"));
        seedPost(sourceId, PREFIX + "hide-uid-r", "READY", Instant.parse("2026-05-15T00:00:00Z"));
        seedSavedPost(userId, sourceId, PREFIX + "hide-uid-q", "untitled",
                "hidden body text", new String[] {}, new String[] {},
                Instant.now().minus(2, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "hide-uid-r", "untitled",
                "visible body text", new String[] {}, new String[] {},
                Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertFalse(reply.text().contains(PREFIX + "hide-uid-q"),
                "a save whose post is QUARANTINED must not render; got: " + reply.text());
        assertFalse(reply.text().contains("hidden body text"),
                "a hidden post's snapshot body must not render either; got: " + reply.text());
        assertFalse(reply.text().contains("Post title"),
                "no post content column may cross into the reply — only the snapshot renders; got: "
                        + reply.text());
        assertTrue(reply.text().contains(PREFIX + "hide-uid-r"),
                "the READY post's save must render; got: " + reply.text());
        assertTrue(reply.text().contains("Saved posts (1 of 1 total"),
                "the count SELECT must apply the same predicate, keeping the header honest; got: "
                        + reply.text());
    }

    @Test
    void savedReappearsWhenHiddenPostReturnsToReady() throws Exception {
        // Reversibility: the interlock reads live post.status, so an admin
        // approve (or a BENIGN requeue) returning the post to READY makes
        // the bookmark reappear — the snapshot itself is never touched.
        // The hidden phase returning reply.saved.empty pins the COUNT
        // predicate: with its only row hidden the library reads as empty.
        String contactId = PREFIX + "rev-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "rev-source");
        String uid = PREFIX + "rev-uid-1";
        seedPost(sourceId, uid, "QUARANTINED", Instant.parse("2026-05-15T00:00:00Z"));
        seedSavedPost(userId, sourceId, uid, "untitled", "reversible body text",
                new String[] {}, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage hidden = handler.handle(new ScopeRef.Dm(contactId), "/saved");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY), hidden.text(),
                "with its only save hidden, /saved must read as an empty library; got: "
                        + hidden.text());

        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "UPDATE post SET status = 'READY' WHERE uid = ?", uid);
        }

        OutboundMessage shown = handler.handle(new ScopeRef.Dm(contactId), "/saved");
        assertTrue(shown.text().contains(uid),
                "the bookmark must reappear once the post is READY again; got: " + shown.text());
        assertTrue(shown.text().contains("reversible body text"),
                "the snapshot content must render unchanged; got: " + shown.text());
    }

    @Test
    void savedRendersWhenAnyWindowOfTheUidIsReady() throws Exception {
        // A re-fetched item lands in a later partition under the same uid
        // (UNIQUE(uid, fetched_at) is per-window dedup), so one uid can
        // carry rows in more than one status. The interlock's "any READY
        // window" rule matches how /summary, search and getPost decide
        // visibility — a save stays visible exactly while the post is
        // visible on those surfaces.
        String contactId = PREFIX + "multi-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "multi-source");
        String uid = PREFIX + "multi-uid-1";
        seedPost(sourceId, uid, "QUARANTINED", Instant.parse("2026-05-15T00:00:00Z"));
        seedPost(sourceId, uid, "READY", Instant.parse("2026-06-15T00:00:00Z"));
        seedSavedPost(userId, sourceId, uid, "untitled", "multi-window body text",
                new String[] {}, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertTrue(reply.text().contains(uid),
                "a save whose uid has a READY window must render; got: " + reply.text());
        assertTrue(reply.text().contains("multi-window body text"),
                "the snapshot content must render; got: " + reply.text());
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                             + "VALUES (?, ?, 'approved') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /** Seeds a save with no snapshot body — the shape every pre-M1-730 case uses. */
    private void seedSavedPost(UUID userId, UUID sourceId, String postUid, String title,
                               String[] snapshotTags, String[] personalTags,
                               Instant savedAt) throws Exception {
        seedSavedPost(userId, sourceId, postUid, title, null, snapshotTags, personalTags, savedAt);
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid, String title,
                               @Nullable String body,
                               String[] snapshotTags, String[] personalTags,
                               Instant savedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title, body, "
                             + "snapshot_tags, personal_tags, saved_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setString(5, body);
            ps.setArray(6, conn.createArrayOf("TEXT", snapshotTags));
            ps.setArray(7, conn.createArrayOf("TEXT", personalTags));
            ps.setObject(8, OffsetDateTime.ofInstant(savedAt, java.time.ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    /**
     * Seeds a save carrying the V78 English title anchor and a declared
     * source language. A null {@code titleEn} writes the PRE-MIGRATION shape
     * — the column is omitted from the INSERT entirely rather than bound to
     * NULL, so the row is byte-for-byte what a save taken before V78 left
     * behind, which is the state the anchor-absent case has to render.
     */
    private void seedAnchoredSavedPost(UUID userId, UUID sourceId, String postUid, String title,
                                       @Nullable String titleEn, String sourceLanguage,
                                       Instant savedAt) throws Exception {
        String columns = "user_id, post_uid, source_id, title, snapshot_tags, personal_tags, "
                + "saved_at, source_language" + (titleEn == null ? "" : ", title_en");
        String placeholders = "?, ?, ?, ?, ?, ?, ?, ?" + (titleEn == null ? "" : ", ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (" + columns + ") VALUES (" + placeholders + ")")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setArray(5, conn.createArrayOf("TEXT", new String[] {}));
            ps.setArray(6, conn.createArrayOf("TEXT", new String[] {}));
            ps.setObject(7, OffsetDateTime.ofInstant(savedAt, java.time.ZoneOffset.UTC));
            ps.setString(8, sourceLanguage);
            if (titleEn != null) {
                ps.setString(9, titleEn);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Seeds a minimal live {@code post} row for the visibility-interlock
     * tests (M1-730). {@code fetched_at} must land in an existing monthly
     * partition (V7's bootstrap plus V30 cover 2026-05 through 2026-07);
     * the caller picks the window — a second row with the same uid in a
     * later window exercises the multi-window branch.
     */
    private void seedPost(UUID sourceId, String uid, String status, Instant fetchedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, fetched_at, status, "
                             + "upstream_identifier) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setString(3, "Post title " + uid);
            ps.setObject(4, OffsetDateTime.ofInstant(fetchedAt, java.time.ZoneOffset.UTC));
            ps.setString(5, status);
            ps.setString(6, uid);
            ps.executeUpdate();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        assertNotNull(haystack);
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
