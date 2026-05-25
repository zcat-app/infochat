package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@code /unsave <uid>} per {@code docs/spec/commands.md}
 * §Content. Removes a post from the calling user's saved-post library.
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>Parse positional {@code <uid>}. Missing → fall back to
 *       {@code error.unsave.unknown_uid} (the spec catalogue does not
 *       assign a separate "no UID supplied" reply for {@code /unsave}).</li>
 *   <li>Actor lookup. Missing actor → {@code error.unsave.unknown_uid}.</li>
 *   <li>{@code DELETE FROM saved_post WHERE user_id = ? AND post_uid = ?}.
 *       {@code affectedRows == 1} → {@code reply.unsave.success} with
 *       the UID interpolated; {@code affectedRows == 0} →
 *       {@code error.unsave.unknown_uid}. The V15
 *       {@code trg_saved_post_count_del} trigger decrements
 *       {@code users.save_count} automatically.</li>
 * </ol>
 *
 * <p>No confirm gate — spec §Content commits that {@code /unsave} has no
 * confirmation ("cheap to redo"). No audit row — not in the spec's
 * audit-logged verb set.</p>
 */
@ApplicationScoped
public class UnsaveCommandHandler implements CommandHandler {

    private static final String SELECT_ACTOR_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String DELETE_SAVED_POST_SQL =
            "DELETE FROM saved_post WHERE user_id = ? AND post_uid = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Override
    public String name() {
        return "unsave";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String uid = parseUid(rawText);
        if (uid == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNSAVE_UNKNOWN_UID));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = resolveContactId(scope);
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNSAVE_UNKNOWN_UID));
        }

        try (Connection conn = dataSource.getConnection()) {
            UUID userId = lookupActor(conn, adapter, callerContactId);
            if (userId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNSAVE_UNKNOWN_UID));
            }
            int deleted = deleteSavedPost(conn, userId, uid);
            if (deleted == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNSAVE_UNKNOWN_UID));
            }
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_UNSAVE_SUCCESS), uid);
            return reply(scope, body);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnsaveCommandHandler.handle failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(callerContactId), e);
        }
    }

    private UUID lookupActor(Connection conn, String adapter, String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private int deleteSavedPost(Connection conn, UUID userId, String postUid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SAVED_POST_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            return ps.executeUpdate();
        }
    }

    private String resolveContactId(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm
                ? dm.contactId()
                : inboundContext.senderContactId();
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static String parseUid(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        if (tokens.length < 2) {
            return null;
        }
        return tokens[1];
    }
}
