package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code /lang <code>} per
 * {@code docs/spec/commands.md} §Conversation control and the
 * {@code docs/spec/llm.md} §Translation flow / §Pipeline order
 * commitments. Auto-discovered by {@code InboundRouter} via the CDI
 * {@code Instance<CommandHandler>} scan; no router-side edit needed.
 *
 * <p>Pipeline:
 * <ol>
 *   <li><b>Permission gate.</b> DM scope is the caller's own scope
 *       ({@code ScopeRef.Dm} carries the caller's own contact id —
 *       cross-DM mutation is structurally impossible). Group scope
 *       short-circuits to
 *       {@link BundleKeys#ERROR_LANG_GROUP_ADMIN_NOT_IN_V1} per the
 *       M1-054 FollowTagCommandHandler / UnfollowTagCommandHandler
 *       SPI-freeze precedent — the frozen
 *       {@code CommandHandler.handle(ScopeRef, String)} SPI does not
 *       carry the inbound caller's contact id in group scope, so the
 *       handler cannot consult {@code group_membership} to identify
 *       a group admin. T2-F lands the actor seam and the group-admin
 *       proceed path.</li>
 *   <li><b>Supported-code derivation + validation.</b> The supported
 *       set is {@link BundleLoader#supportedLanguages()} — derived
 *       from the loaded bundles, not hardcoded in the handler. An
 *       unsupported code returns
 *       {@link BundleKeys#ERROR_LANG_UNSUPPORTED_CODE} with the
 *       comma-separated supported list interpolated via
 *       {@link MessageFormat} — per spec §Conversation control:
 *       "An unsupported code produces a friendly error that lists the
 *       supported codes — never a silent no-op and never a fall-through
 *       to the default."</li>
 *   <li><b>UPSERT.</b> One transaction:
 *       {@code INSERT INTO scope_preferences (scope_kind, scope_id, language)
 *       VALUES ('dm', ?, ?) ON CONFLICT (scope_kind, scope_id)
 *       DO UPDATE SET language = EXCLUDED.language}. The V7 unique
 *       constraint on {@code (scope_kind, scope_id)} is the ON CONFLICT
 *       target. No {@code FOR UPDATE} — the single-column UPDATE is
 *       serializable under PostgreSQL's default isolation.</li>
 *   <li><b>Confirmation reply.</b> Resolved via the NEW 2-arg
 *       {@link BundleLoader#get(String, String)} accessor with
 *       {@code langCode = <newly-written code>}, so a {@code /lang cs}
 *       caller sees the Czech version of the confirmation immediately.
 *       Per decision D43 the deterministic reply comes from the bundle,
 *       not the translator.</li>
 * </ol>
 *
 * <p>The handler writes zero rows to {@code audit_log} — {@code /lang}
 * is a user-preference mutation, not a privileged action (spec
 * §Authorization model). The {@code langWritesZeroRowsToAuditLog}
 * scenario is the regression guard. {@code /lang} is also already in
 * the slow-start probation allowed set
 * ({@code CommandPermissions.ALLOWED}) per spec §Slow-start tier;
 * no {@code CommandPermissions} edit is required.</p>
 */
@ApplicationScoped
public class LangCommandHandler implements CommandHandler {

    /**
     * Per the V5 {@code UNIQUE (adapter, contact_id)} constraint on
     * {@code users}, the lookup MUST qualify on both columns — a
     * {@code contact_id}-only WHERE would cross-resolve across
     * adapters in a multi-adapter deployment (decision D46). Same
     * pattern as M1-054 FollowTagCommandHandler.
     */
    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String UPSERT_SCOPE_LANGUAGE_SQL =
            "INSERT INTO scope_preferences (scope_kind, scope_id, language) "
                    + "VALUES (?, ?, ?) "
                    + "ON CONFLICT (scope_kind, scope_id) "
                    + "DO UPDATE SET language = EXCLUDED.language";

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject GroupMembershipRepository groupMembershipRepository;

    @Override
    public String name() {
        return "lang";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        // Permission gate: group scope requires group-admin.
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            UUID actorId = lookupActorId(inboundContext.senderContactId());
            if (actorId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_LANG_GROUP_ADMIN_NOT_IN_V1));
            }
            UUID groupDbId = lookupGroupId(group.adapterGroupId());
            if (groupDbId == null
                    || (!isBotAdmin(actorId)
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actorId))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_LANG_GROUP_ADMIN_NOT_IN_V1));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            ScopeRef.Dm dm = (ScopeRef.Dm) scope;
            UUID actorId = lookupActorId(dm.contactId());
            if (actorId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
            }
            scopeKind = "dm";
            scopeId = actorId;
        }

        String suppliedCode = parsePositionalCode(rawText);
        Set<String> supported = bundleLoader.supportedLanguages();
        if (suppliedCode == null || !supported.contains(suppliedCode)) {
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_LANG_UNSUPPORTED_CODE),
                    sortedJoin(supported));
            return reply(scope, body);
        }

        upsertScopeLanguage(scopeKind, scopeId, suppliedCode);

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_LANG_SUCCESS, suppliedCode),
                suppliedCode);
        return reply(scope, body);
    }

    private void upsertScopeLanguage(String scopeKind, UUID scopeId, String langCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SCOPE_LANGUAGE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, langCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "LangCommandHandler.upsertScopeLanguage failed for contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
        }
    }

    private UUID lookupActorId(String contactId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "LangCommandHandler.lookupActorId failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private UUID lookupGroupId(String adapterGroupId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "LangCommandHandler.lookupGroupId failed", e);
        }
    }

    private boolean isBotAdmin(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "LangCommandHandler.isBotAdmin failed", e);
        }
    }

    /**
     * Extract the positional {@code <code>} from {@code rawText}.
     * Returns {@code null} when no positional arg appears, when the
     * arg is empty, or when extra tokens follow the code (e.g.
     * {@code /lang cs xx}). The supported-code check then surfaces
     * the unsupported-code error, which is the user-visible behavior
     * the spec requires for any malformed invocation that is not a
     * silent no-op.
     */
    private static String parsePositionalCode(String rawText) {
        String[] parts = rawText.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String remainder = parts[1].trim();
        if (remainder.isEmpty()) {
            return null;
        }
        return remainder.split("\\s+", 2)[0];
    }

    /** Comma-joined sorted list — deterministic across HashMap insertion order. */
    private static String sortedJoin(Set<String> codes) {
        List<String> sorted = new ArrayList<>(codes);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
