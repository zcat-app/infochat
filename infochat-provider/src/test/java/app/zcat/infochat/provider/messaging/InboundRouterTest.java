package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link InboundRouter}'s entry-point dispatch.
 * Drives inbound messages through the real production wiring
 * (Quarkus boots the registry via {@link MessagingStartup}; the
 * single InMemoryAdapter is bound to the router) and asserts the
 * five M1-035b branches plus the M1-044b silent-rate-cap-drop:
 *
 * <ol>
 *   <li>Empty / whitespace / bidi-only / zero-width-only bodies →
 *       no outbound reply.</li>
 *   <li>Leading-whitespace {@code "  /help"} → same reply as
 *       {@code "/help"} (both produce the unknown-command literal
 *       since no {@code /help} handler is registered in this
 *       subticket).</li>
 *   <li>Non-slash body → chat-mode-not-in-MVP reply.</li>
 *   <li>Unknown slash command → unknown-command reply.</li>
 *   <li>Command-handler exception → internal-error reply that does
 *       NOT interpolate the exception's text.</li>
 *   <li>(M1-044b) Rate-cap silent drop — when
 *       {@link RateCapBucket#tryAcquire} returns false (bucket
 *       drained), no reply is sent and no downstream service runs.</li>
 * </ol>
 *
 * <p>The M1-035b methods are kept green by an @BeforeEach pre-seed
 * of an {@code alice} users row with
 * {@code registration_state='vouched'} — the post-M1-044b splice
 * routes unknown DM contacts through the invite gate (step 2), so
 * the deterministic UNKNOWN / CHAT_MODE / INTERNAL_ERROR replies the
 * five M1-035b methods assert only fire when the dispatch reaches
 * {@code handleSlash}. Pre-seeding the contact as a known
 * {@code 'vouched'} user makes step 2 (DM unknown) skip.</p>
 *
 * <p>M1-035d's three first-DM auto-register tests were REMOVED by
 * M1-044b: their premise (a DM from an unknown contact auto-creates
 * a users row) is spec-invalidated by the splice. Replacement
 * coverage lives in
 * {@link InboundRouterIntakeOrderingTest} scenarios (d) DM unknown +
 * valid invite → welcome and (e) DM unknown + invalid invite →
 * invite-required.</p>
 */
@QuarkusTest
@TestProfile(InboundRouterTest.Profile.class)
class InboundRouterTest {

    @Inject
    InMemoryAdapter inMemoryAdapter;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    RegisteredContactSet registeredContactSet;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    TestLlmProvider testLlmProvider;

    @Inject
    InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void resetAdapterState() throws Exception {
        inMemoryAdapter.reset();
        // Clean up any users rows this class touches before each test,
        // then pre-seed the alice contact as a vouched user so the
        // M1-044b splice's step 2 (DM unknown → invite gate) does not
        // short-circuit the deterministic UNKNOWN / CHAT_MODE /
        // INTERNAL_ERROR reply assertions in the M1-035b methods.
        // Clean chat_session rows first (FK from chat_session → users
        // blocks DELETE FROM users when chat-mode dispatch has persisted
        // session rows for these contacts).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement cleanSessions = conn.prepareStatement(
                     "DELETE FROM chat_session WHERE user_id IN ("
                             + "SELECT id FROM users WHERE adapter = 'inmemory' AND ("
                             + "contact_id = 'alice' "
                             + "OR contact_id LIKE 'rate-overflow-%'))")) {
            cleanSessions.executeUpdate();
        }
        // Delete rate-overflow contacts only; alice cannot be DELETEd
        // when audit_log rows reference her id (audit_log is append-only,
        // Invariant 10). The upsert below resets her state instead.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement clean = conn.prepareStatement(
                     "DELETE FROM users WHERE adapter = 'inmemory' "
                             + "AND contact_id LIKE 'rate-overflow-%'")) {
            clean.executeUpdate();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement seed = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, "
                             + "registration_state, probation_until) "
                             + "VALUES ('inmemory', 'alice', FALSE, 'vouched', ?) "
                             + "ON CONFLICT (adapter, contact_id) DO UPDATE SET "
                             + "registration_state = 'vouched', "
                             + "probation_until = EXCLUDED.probation_until, "
                             + "is_admin = FALSE, is_banned = FALSE")) {
            seed.setObject(1, OffsetDateTime.now().minusHours(24));
            seed.executeUpdate();
        }
        // M1-229: alice is seeded as a registered user via raw SQL,
        // bypassing the InviteCodeConsumer path that would normally call
        // RegisteredContactSet.markRegistered. Mirror that effect so the
        // router routes alice to her own per-id rate-cap bucket — without
        // this she would be treated as a stranger and share the per-
        // adapter stranger bucket, which rateCapOverflowDropsSilently...
        // deliberately drains (the test would then flake by JUnit method
        // order). The set is @ApplicationScoped; markRegistered is
        // idempotent across @BeforeEach runs.
        registeredContactSet.markRegistered("inmemory", "alice");
    }

    @Test
    void emptyAndWhitespaceAndInvisibleOnlyBodiesAreDropped() {
        inMemoryAdapter.deliverDm("alice", "");
        inMemoryAdapter.deliverDm("alice", "   ");
        inMemoryAdapter.deliverDm("alice", "​");    // zero-width space
        inMemoryAdapter.deliverDm("alice", "‮");    // right-to-left override

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertTrue(sent.isEmpty(),
                "empty / whitespace / bidi-only / zero-width-only bodies must produce no outbound, got: "
                        + sent);
    }

    @Test
    void leadingWhitespaceBeforeSlashCommandParsesAsTheCommand() {
        inMemoryAdapter.deliverDm("alice", "/help");
        List<OutboundMessage> baseline = inMemoryAdapter.sentMessages();
        assertEquals(1, baseline.size());
        String baselineReply = baseline.get(0).text();

        inMemoryAdapter.reset();
        inMemoryAdapter.deliverDm("alice", "  /help");
        List<OutboundMessage> indented = inMemoryAdapter.sentMessages();
        assertEquals(1, indented.size());
        assertEquals(baselineReply, indented.get(0).text(),
                "leading whitespace before /help must produce the same reply as /help");
    }

    @Test
    void chatModeBodyDispatchesToChatAgent() {
        inMemoryAdapter.deliverDm("alice", "hello there");

        // Chat dispatch is offloaded (M1-634): await the worker's full
        // completion, then the exactly-one assert (a negative bound) is
        // race-free — no further send can arrive from a drained pool.
        awaitDispatchIdle();
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "chat-mode body should produce exactly one outbound reply");
    }

    @Test
    void unknownCommandProducesFriendlyUnknownCommandReply() {
        inMemoryAdapter.deliverDm("alice", "/xyz");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size());
        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), sent.get(0).text());
    }

    @Test
    void commandHandlerExceptionProducesInternalErrorReplyWithoutLeakingMessage() {
        inMemoryAdapter.deliverDm("alice", "/boom");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "exception path must still produce one user-visible reply");
        String body = sent.get(0).text();
        assertEquals(bundleLoader.get(BundleKeys.ERROR_INTERNAL), body);
        assertFalse(body.contains(BoomHandler.SECRET_LEAK_TEXT),
                "exception's getMessage() must NOT be interpolated into the reply, got: " + body);
    }

    @Test
    void rateCapOverflowDropsSilentlyWithoutOutbound() {
        // Drain the SHARED per-adapter stranger bucket (M1-229) by calling
        // the 3-arg tryAcquire with registered=false until it returns
        // false (≤ infochat.rate-cap.inbound-per-minute iterations). The
        // overflow contact has no users row, so the router routes it to
        // this same stranger bucket: the router-driven deliverDm below
        // then finds it empty and exercises the spec §Authorization model
        // step 1.5 "drop silently" branch — no outbound, no downstream
        // service consulted. (Registered contacts in this class are
        // markRegistered, so they use isolated per-id buckets and are
        // unaffected by this drain.)
        String overflowContact = "rate-overflow-1";
        int safetyCap = 1000; // > any reasonable infochat.rate-cap.inbound-per-minute
        int i = 0;
        while (rateCapBucket.tryAcquire("inmemory", overflowContact, false) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquire should have returned false within " + safetyCap + " iterations");

        // Reset the adapter so any spurious previous outbound does not
        // mask a regression in the drop-silently branch.
        inMemoryAdapter.reset();

        inMemoryAdapter.deliverDm(overflowContact, "/help");

        assertTrue(inMemoryAdapter.sentMessages().isEmpty(),
                "over-rate-cap inbound must produce zero outbound; got: "
                        + inMemoryAdapter.sentMessages());
        // "No downstream service consulted" — observable proxy: no users
        // row was inserted (InviteCodeConsumer's invite_consume path
        // would have INSERTed even on a Rejected outcome via the
        // invite_code_attempt table; step 4 banCheck reads users; both
        // are silent if rate cap drops before them).
        assertEquals(0L, countUsersRows("inmemory", overflowContact),
                "rate-cap silent drop must not write any users row "
                        + "(no invite consume, no ban-check write side effect)");
    }

    @Test
    void llmRateCapEvictsIdleEntries() {
        UUID userId = UUID.randomUUID();
        int before = llmRateCap.entryCount();
        assertTrue(llmRateCap.tryAcquire(userId));
        assertEquals(before + 1, llmRateCap.entryCount());

        // Simulate time advancing past 2x the 60 s window so the
        // sweep prunes the timestamp and finds the deque empty.
        llmRateCap.evictIdleEntries(
                System.currentTimeMillis() + 200_000);
        assertEquals(0, llmRateCap.entryCount(),
                "All entries should be evicted after timestamps age past 2x the window");
    }

    @Test
    void bodySizeCheckDoesNotAllocateArray() {
        // The Provider body-size cap (onMessage) now tests the once-walked
        // length against maxInboundBodyBytes; the alloc-free early-exit
        // arithmetic is single-sourced in Utf8.exceedsByteLength.
        // ASCII: 1 byte per char
        assertFalse(Utf8.exceedsByteLength("hello", 5));
        assertTrue(Utf8.exceedsByteLength("hello", 4));

        // U+00E9 (é): 2 bytes in UTF-8
        assertFalse(Utf8.exceedsByteLength("é", 2));
        assertTrue(Utf8.exceedsByteLength("é", 1));

        // U+20AC (€): 3 bytes in UTF-8
        assertFalse(Utf8.exceedsByteLength("€", 3));
        assertTrue(Utf8.exceedsByteLength("€", 2));

        // U+1D11E (𝄞): 4 bytes in UTF-8 (surrogate pair in Java)
        assertFalse(Utf8.exceedsByteLength("𝄞", 4));
        assertTrue(Utf8.exceedsByteLength("𝄞", 3));
    }

    /**
     * Per docs/spec/security.md §Rate limiting "Per-group LLM rate
     * (D47)": the per-user LLM cap fires first; the per-group cap is
     * the backstop for groups with many active members. This test
     * issues a group chat-mode message that passes the per-user
     * {@link LlmRateCap} (one message, {@code %test} cap 3/min for a
     * fresh actor) but finds the per-group LLM bucket exhausted, and
     * asserts the fixed {@code group.llm_rate_limit} reply (design
     * §4.9 — NOT a silent drop) with no LLM invocation.
     *
     * <p>"ChatAgent.handle is NOT called" is pinned via two observable
     * proxies: the reply body equals the bundle entry (had handle run,
     * its return value — the TestLlmProvider text or a chat error key
     * — would be the reply instead), and {@link TestLlmProvider}'s
     * call counter does not advance across the dispatch.</p>
     */
    @Test
    void groupChatWithExhaustedGroupLlmBucketSendsFixedReplyWithoutLlmCall() throws Exception {
        String member = "m222-member";
        String upstreamGroup = "m222-group";
        seedVouchedUserPastProbation(member);
        UUID groupId = seedApprovedGroup(upstreamGroup);

        // Exhaust the per-group LLM bucket directly (mirrors the
        // rate-overflow drain above). The drain touches ONLY the
        // group-keyed bucket — the per-user LlmRateCap is untouched,
        // so the delivery below passes the per-user cap and is
        // rejected by the per-group backstop alone.
        int safetyCap = 1000; // > %test.infochat.ratelimit.group-llm-per-15min
        int i = 0;
        while (rateCapBucket.tryAcquireGroupLlm(groupId) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquireGroupLlm should have returned false within " + safetyCap + " iterations");

        inMemoryAdapter.reset();
        int llmCallsBefore = testLlmProvider.callCount();

        inMemoryAdapter.deliverGroupMention(upstreamGroup, member, "hello group chat");

        // The rate-cap rejection now ships from the M1-634 worker — drain
        // the pool so both the exactly-one and the no-LLM-call negative
        // asserts are race-free.
        awaitDispatchIdle();
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "group-LLM overflow must produce exactly one fixed reply, got: " + sent);
        assertEquals(bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT), sent.get(0).text(),
                "overflow reply must be the fixed group.llm_rate_limit bundle entry");
        assertEquals(llmCallsBefore, testLlmProvider.callCount(),
                "ChatAgent.handle must NOT be called when the per-group LLM bucket is exhausted "
                        + "— no LLM invocation may occur");
    }

    /**
     * Negative-space pin for spec §Rate limiting "Periodic digests do
     * NOT count against user-initiated per-group LLM budget": the
     * bucket is consulted ONLY on the chat-mode dispatch. With the
     * group-LLM bucket exhausted, a slash command in the same group
     * still dispatches normally — proving non-chat paths never consult
     * the bucket (the digest path is not modified by this ticket and
     * has no call site at all).
     */
    @Test
    void groupSlashDispatchDoesNotConsultGroupLlmBucket() throws Exception {
        String member = "m222-member";
        String upstreamGroup = "m222-group";
        seedVouchedUserPastProbation(member);
        UUID groupId = seedApprovedGroup(upstreamGroup);

        int safetyCap = 1000;
        int i = 0;
        while (rateCapBucket.tryAcquireGroupLlm(groupId) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquireGroupLlm should have returned false within " + safetyCap + " iterations");

        inMemoryAdapter.reset();

        inMemoryAdapter.deliverGroupMention(upstreamGroup, member, "/help");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "slash dispatch must still reply while the group-LLM bucket is exhausted, got: " + sent);
        assertNotEquals(bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT), sent.get(0).text(),
                "slash dispatch must not be gated by the per-group LLM bucket");
    }

    /**
     * Redteam M1-222 finding 3 (DOS-low): the spec-mandated check
     * order (per-user cap first, then the per-group backstop) consumes
     * a per-user token before the group bucket can reject — without a
     * refund, a group whose aggregate bucket is pinned empty would
     * drain every member's PERSONAL budget (actor-keyed, so DM chat
     * included) on fixed rate-limit replies. This test pins the
     * refund: after one group-cap rejection, the sender's per-user
     * budget is fully intact — {@code tryAcquire} succeeds the whole
     * {@code %test} cap (3/min). Without the refund the dispatch's own
     * acquire would leave only 2 of 3.
     *
     * <p>Dedicated contact + group (not the shared {@code m222-*}
     * seeds): the per-user probe below consumes real sliding-window
     * budget that would leak into sibling tests sharing the actor
     * within the 60 s window.</p>
     */
    @Test
    void groupLlmCapRejectionRefundsThePerUserToken() throws Exception {
        String member = "m222-refund-member";
        String upstreamGroup = "m222-refund-group";
        UUID userId = seedVouchedUserPastProbation(member);
        UUID groupId = seedApprovedGroup(upstreamGroup);

        int safetyCap = 1000;
        int i = 0;
        while (rateCapBucket.tryAcquireGroupLlm(groupId) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquireGroupLlm should have returned false within " + safetyCap + " iterations");

        inMemoryAdapter.reset();

        inMemoryAdapter.deliverGroupMention(upstreamGroup, member, "hello group chat");

        // Worker-side rejection (M1-634): drain before the exactly-one
        // assert; the refund precedes the reply send, so a drained pool
        // also guarantees the refund landed before the probe below.
        awaitDispatchIdle();
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "group-LLM overflow must produce exactly one fixed reply, got: " + sent);
        assertEquals(bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT), sent.get(0).text(),
                "precondition: the dispatch was rejected by the per-group backstop");

        // Per-user budget intact: the full %test cap of 3 acquires
        // succeeds, proving the rejected dispatch's token came back.
        for (int j = 0; j < 3; j++) {
            assertTrue(llmRateCap.tryAcquire(userId),
                    "per-user budget must be fully intact after a group-cap rejection (j=" + j + ")");
        }
    }

    /**
     * D47 per-group command rate cap (M1-222 redteam follow-up) per
     * spec §Rate limiting "Per-group command rate (D47)": with the
     * group-command bucket exhausted, a slash command in the group
     * produces the fixed {@code group.command_rate_limit} reply and
     * dispatch never reaches {@code handleSlash} (had it run, the
     * reply would be the unknown-command literal — no {@code /help}
     * handler is registered in this profile).
     *
     * <p>Dedicated contact + group (not the shared {@code m222-*}
     * seeds): the bucket drain below persists in the
     * application-scoped {@link RateCapBucket} across tests sharing
     * the group row's UUID.</p>
     */
    @Test
    void groupSlashWithExhaustedGroupCommandBucketSendsFixedReply() throws Exception {
        String member = "m222-cmd-member";
        String upstreamGroup = "m222-cmd-group";
        seedVouchedUserPastProbation(member);
        UUID groupId = seedApprovedGroup(upstreamGroup);

        // Exhaust the per-group command bucket directly. The drain
        // touches ONLY the command-keyed bucket — the reply and LLM
        // buckets are untouched, so the fixed reply below passes the
        // step-3.5 reply bucket and is produced by the command cap
        // alone.
        int safetyCap = 1000; // > %test.infochat.ratelimit.group-commands-per-15min
        int i = 0;
        while (rateCapBucket.tryAcquireGroupCommand(groupId) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquireGroupCommand should have returned false within " + safetyCap + " iterations");

        inMemoryAdapter.reset();

        inMemoryAdapter.deliverGroupMention(upstreamGroup, member, "/help");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "group-command overflow must produce exactly one fixed reply, got: " + sent);
        assertEquals(bundleLoader.get(BundleKeys.GROUP_COMMAND_RATE_LIMIT), sent.get(0).text(),
                "overflow reply must be the fixed group.command_rate_limit bundle entry");
    }

    /**
     * Negative-space pin: the group-command bucket is consulted ONLY
     * on the slash dispatch. With the command bucket exhausted, a
     * chat-mode message in the same group still dispatches normally —
     * proving the chat path never consults the command bucket (its
     * bound is the per-user + per-group LLM caps).
     */
    @Test
    void groupChatDispatchDoesNotConsultGroupCommandBucket() throws Exception {
        String member = "m222-cmdchat-member";
        String upstreamGroup = "m222-cmdchat-group";
        seedVouchedUserPastProbation(member);
        UUID groupId = seedApprovedGroup(upstreamGroup);

        int safetyCap = 1000;
        int i = 0;
        while (rateCapBucket.tryAcquireGroupCommand(groupId) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquireGroupCommand should have returned false within " + safetyCap + " iterations");

        inMemoryAdapter.reset();

        inMemoryAdapter.deliverGroupMention(upstreamGroup, member, "hello group chat");

        // Full chat turn on the M1-634 worker — drain before the
        // exactly-one negative bound.
        awaitDispatchIdle();
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "chat dispatch must still reply while the group-command bucket is exhausted, got: " + sent);
        assertNotEquals(bundleLoader.get(BundleKeys.GROUP_COMMAND_RATE_LIMIT), sent.get(0).text(),
                "chat dispatch must not be gated by the per-group command bucket");
    }

    /** Await M1-634 worker-pool quiescence so negative asserts are race-free. */
    private void awaitDispatchIdle() {
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");
    }

    /**
     * Upsert a registered {@code 'vouched'} user whose probation lies
     * in the past, mirroring the alice pre-seed: the M1-222 group-LLM
     * tests need the sender to clear steps 3 (registered), 4 (not
     * banned), and 5 (not in probation — chat mode is probation-
     * blocked) so dispatch reaches the chat-mode branch. Returns the
     * {@code users.id} — the key the per-user {@link LlmRateCap} is
     * bound to.
     */
    private UUID seedVouchedUserPastProbation(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement seed = conn.prepareStatement(
                    "INSERT INTO users (adapter, contact_id, is_admin, "
                            + "registration_state, probation_until) "
                            + "VALUES ('inmemory', ?, FALSE, 'vouched', ?) "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET "
                            + "registration_state = 'vouched', "
                            + "probation_until = EXCLUDED.probation_until, "
                            + "is_admin = FALSE, is_banned = FALSE")) {
                seed.setString(1, contactId);
                seed.setObject(2, OffsetDateTime.now().minusHours(24));
                seed.executeUpdate();
            }
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT id FROM users WHERE adapter = 'inmemory' "
                            + "AND contact_id = ?")) {
                select.setString(1, contactId);
                try (var rs = select.executeQuery()) {
                    assertTrue(rs.next(), "users row must exist after upsert");
                    // M1-229: this contact is seeded as registered via raw
                    // SQL, so mirror the InviteCodeConsumer.markRegistered
                    // effect — the router then routes its inbound to a
                    // per-id rate-cap bucket, isolated from the shared
                    // stranger bucket drained elsewhere in this class.
                    registeredContactSet.markRegistered("inmemory", contactId);
                    return rs.getObject("id", UUID.class);
                }
            }
        }
    }

    /**
     * Upsert an {@code approval_status='approved'} groups row for the
     * in-memory adapter and return its {@code groups.id} — the key the
     * per-group LLM bucket is bound to. Upsert (not delete-and-insert)
     * because audit/membership rows from prior runs may reference the
     * group id.
     */
    private UUID seedApprovedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                            + "VALUES ('inmemory', ?, 'approved') "
                            + "ON CONFLICT (adapter, upstream_group_id) DO UPDATE SET "
                            + "approval_status = 'approved', removed_at = NULL")) {
                upsert.setString(1, upstreamGroupId);
                upsert.executeUpdate();
            }
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT id FROM groups WHERE adapter = 'inmemory' "
                            + "AND upstream_group_id = ?")) {
                select.setString(1, upstreamGroupId);
                try (var rs = select.executeQuery()) {
                    assertTrue(rs.next(), "groups row must exist after upsert");
                    return rs.getObject("id", UUID.class);
                }
            }
        }
    }

    private long countUsersRows(String adapter, String contactId) throws RuntimeException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true"
            );
        }
    }

    /**
     * Test-only {@link CommandHandler} that throws on invocation so
     * the router's exception-handling path is exercised. The thrown
     * message includes a recognizable substring that the test
     * assertion confirms is NOT present in the user-visible reply.
     */
    @ApplicationScoped
    public static class BoomHandler implements CommandHandler {
        static final String SECRET_LEAK_TEXT = "SECRET_DB_PASSWORD=hunter2";

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            throw new RuntimeException(SECRET_LEAK_TEXT);
        }
    }
}
