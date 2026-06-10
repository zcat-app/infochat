package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.text.MessageFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * One named test per handler whose missing-required-argument path
 * previously fell back to a semantically wrong reply
 * ({@code error.admin_only} for the seven admin-tier handlers,
 * {@code error.internal} for {@code /unfollow-tag}): each bare command
 * must now return the shared {@code error.usage.missing_argument}
 * message carrying that command's usage string.
 *
 * <p>Each test also pins the reversal: the reply must NOT be the old
 * wrong bundle message, so a regression back to the fallback shape
 * fails with a precise diff rather than only an equality mismatch.</p>
 *
 * <p>{@code /ban}, {@code /unban}, and {@code /unfollow-tag} reach
 * their parse branch only past a users-table gate (admin gate for the
 * first two, registered-actor lookup for the third), so setup seeds
 * one admin row under the class prefix. The remaining handlers parse
 * before any DB read and need no seeding. No test writes audit rows:
 * every missing-argument refusal fires before the intent write.</p>
 */
@QuarkusTest
class MissingArgumentUsageReplyTest {

    private static final String PREFIX = "m1-280-usage-";
    private static final String ADAPTER = "inmemory";
    private static final String ADMIN_CONTACT = PREFIX + "admin";

    @Inject BanCommandHandler banHandler;
    @Inject UnbanCommandHandler unbanHandler;
    @Inject GrantAdminCommandHandler grantAdminHandler;
    @Inject RevokeAdminCommandHandler revokeAdminHandler;
    @Inject VouchCommandHandler vouchHandler;
    @Inject PromoteCommandHandler promoteHandler;
    @Inject DemoteCommandHandler demoteHandler;
    @Inject UnfollowTagCommandHandler unfollowTagHandler;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void seed() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        inboundContext.setSenderContactId(ADMIN_CONTACT);
        // Idempotent upsert; the row persists across tests (admin rows
        // are never deleted here — removing one trips the V5 last-admin
        // protection unless a guardian exists, and a stable extra admin
        // row matches the suite's established guardian-row precedent).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, TRUE, 'vouched') "
                             + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                             + "  SET is_admin = TRUE, is_banned = FALSE")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, ADMIN_CONTACT);
            ps.executeUpdate();
        }
    }

    private String usageReply(String usage) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT), usage);
    }

    private void assertUsageReply(OutboundMessage reply, String usage, String oldWrongKey) {
        assertEquals(usageReply(usage), reply.text(),
                "missing-argument reply must carry the usage string");
        assertNotEquals(bundleLoader.get(oldWrongKey), reply.text(),
                "missing-argument reply must no longer be the old fallback");
    }

    @Test
    void banMissingContactRepliesUsage() {
        OutboundMessage reply = banHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/ban");
        assertUsageReply(reply, "/ban <contact> [--reason \"...\"]",
                BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void unbanMissingContactRepliesUsage() {
        OutboundMessage reply = unbanHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/unban");
        assertUsageReply(reply, "/unban <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void grantAdminMissingContactRepliesUsage() {
        OutboundMessage reply = grantAdminHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/grant-admin");
        assertUsageReply(reply, "/grant-admin <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void revokeAdminMissingContactRepliesUsage() {
        OutboundMessage reply = revokeAdminHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/revoke-admin");
        assertUsageReply(reply, "/revoke-admin <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void vouchMissingContactRepliesUsage() {
        OutboundMessage reply = vouchHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/vouch");
        assertUsageReply(reply, "/vouch <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void promoteMissingContactRepliesUsage() {
        OutboundMessage reply = promoteHandler.handle(
                new ScopeRef.Group(PREFIX + "group"), "/promote");
        assertUsageReply(reply, "/promote <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void demoteMissingContactRepliesUsage() {
        OutboundMessage reply = demoteHandler.handle(
                new ScopeRef.Group(PREFIX + "group"), "/demote");
        assertUsageReply(reply, "/demote <contact>", BundleKeys.ERROR_ADMIN_ONLY);
    }

    @Test
    void unfollowTagMissingTagRepliesUsage() {
        OutboundMessage reply = unfollowTagHandler.handle(
                new ScopeRef.Dm(ADMIN_CONTACT), "/unfollow-tag");
        assertUsageReply(reply, "/unfollow-tag <tag>|--all", BundleKeys.ERROR_INTERNAL);
    }
}
