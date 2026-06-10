package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code /get-tags} per {@code docs/spec/commands.md}
 * §Discovery + design notes {@code docs/design/03-commands.md}
 * §{@code /get-tags}: lists the controlled vocabulary (the {@code tag}
 * table) alphabetically, marking the tags the calling scope follows
 * with a leading {@code *}. Read-only and scope-filtered; available to
 * any non-banned user. No {@code audit_log} row, no state mutation —
 * the same read-only-doesn't-audit pattern as
 * {@link ListSourcesCommandHandler}'s unprivileged path.
 *
 * <p><b>Followed-set semantics</b> (V7 {@code scope_preferences.tag_mode},
 * default {@code 'ALL'}):
 * <ul>
 *   <li>{@code tag_mode='ALL'} — and the equivalent state where no
 *       {@code scope_preferences} row exists yet (the schema default is
 *       {@code 'ALL'}) — every vocabulary tag is followed.</li>
 *   <li>{@code tag_mode='EXPLICIT'} — only the tags present in
 *       {@code scope_tag} for this {@code (scope_kind, scope_id)}.</li>
 * </ul>
 * The marking mirrors how the digest narrows: {@code ALL} means "all
 * tags", an {@code EXPLICIT} set narrows to the listed rows
 * (V7 {@code joins_post} comment).</p>
 *
 * <p>Per-(user, scope) isolation: DM scope keys on the caller's own
 * {@code users.id}; group scope keys on the {@code groups.id} (D7 —
 * group preferences are shared across members), so a group member sees
 * the group's followed-tag marking, never another user's DM marking.</p>
 */
@ApplicationScoped
public class GetTagsCommandHandler implements CommandHandler {

    private static final String SELECT_VOCABULARY_SQL =
            "SELECT name FROM tag ORDER BY name ASC";

    private static final String SELECT_TAG_MODE_SQL =
            "SELECT tag_mode FROM scope_preferences "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    private static final String SELECT_FOLLOWED_TAG_NAMES_SQL =
            "SELECT t.name FROM scope_tag st JOIN tag t ON t.id = st.tag_id "
                    + "WHERE st.scope_kind = ? AND st.scope_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    /** Leading marker on a followed tag. */
    private static final String FOLLOWED_MARKER = "* ";

    /** Leading marker on an unfollowed tag; two spaces keep rows column-aligned under the {@code *}. */
    private static final String UNFOLLOWED_MARKER = "  ";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "get-tags";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        List<String> vocabulary = readVocabulary();
        if (vocabulary.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_GET_TAGS_EMPTY, inboundContext.effectiveLanguage()));
        }
        FollowedTags followed = resolveFollowedTags(scope);
        return reply(scope, render(vocabulary, followed));
    }

    private FollowedTags resolveFollowedTags(ScopeRef scope) {
        String adapter = inboundContext.adapterName();
        ScopeKey key = switch (scope) {
            case ScopeRef.Dm dm -> new ScopeKey("dm",
                    userRepository.findByAdapterAndContactId(adapter, dm.contactId())
                            .map(UserRepository.UserRow::id));
            case ScopeRef.Group group -> new ScopeKey("group",
                    lookupGroupId(adapter, group.adapterGroupId()));
        };
        if (key.scopeId().isEmpty()) {
            // No resolvable scope row → no scope_preferences row → the
            // schema default ('ALL') applies: every vocabulary tag is
            // followed. Inside the router trust boundary the row always
            // resolves (DM registration / group approval precede dispatch);
            // this is the natural no-preferences default, not a guard.
            return FollowedTags.all();
        }
        String mode = selectTagMode(key.scopeKind(), key.scopeId().get());
        if (!"EXPLICIT".equals(mode)) {
            // 'ALL', or no scope_preferences row yet (null) → all followed.
            return FollowedTags.all();
        }
        return FollowedTags.explicit(
                selectFollowedTagNames(key.scopeKind(), key.scopeId().get()));
    }

    private String render(List<String> vocabulary, FollowedTags followed) {
        StringBuilder sb = new StringBuilder();
        sb.append(bundleLoader.get(BundleKeys.REPLY_GET_TAGS_HEADER, inboundContext.effectiveLanguage()));
        for (String name : vocabulary) {
            sb.append('\n');
            sb.append(followed.isFollowed(name) ? FOLLOWED_MARKER : UNFOLLOWED_MARKER);
            sb.append(name);
        }
        return sb.toString();
    }

    private List<String> readVocabulary() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_VOCABULARY_SQL);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("GetTagsCommandHandler.readVocabulary failed", e);
        }
    }

    private @Nullable String selectTagMode(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_TAG_MODE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("tag_mode") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GetTagsCommandHandler.selectTagMode failed for scopeKind=" + scopeKind, e);
        }
    }

    private Set<String> selectFollowedTagNames(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_FOLLOWED_TAG_NAMES_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> out = new HashSet<>();
                while (rs.next()) {
                    out.add(rs.getString("name"));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GetTagsCommandHandler.selectFollowedTagNames failed for scopeKind="
                            + scopeKind, e);
        }
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GetTagsCommandHandler.lookupGroupId failed for adapter=" + adapter, e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /** Resolved scope coordinates: the {@code scope_kind} literal and the scope's row id (empty when unresolved). */
    private record ScopeKey(String scopeKind, Optional<UUID> scopeId) {}

    /**
     * The calling scope's followed-tag set. {@code followsAll} is the
     * {@code tag_mode='ALL'} state (every vocabulary tag followed);
     * otherwise {@code names} holds the {@code EXPLICIT} followed set.
     */
    private record FollowedTags(boolean followsAll, Set<String> names) {

        static FollowedTags all() {
            return new FollowedTags(true, Set.of());
        }

        static FollowedTags explicit(Set<String> names) {
            return new FollowedTags(false, names);
        }

        boolean isFollowed(String name) {
            return followsAll || names.contains(name);
        }
    }
}
