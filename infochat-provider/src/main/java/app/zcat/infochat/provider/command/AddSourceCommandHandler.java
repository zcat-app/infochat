package app.zcat.infochat.provider.command;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AddSourceArgs.Failure;
import app.zcat.infochat.provider.command.AddSourceArgs.ParseResult;
import app.zcat.infochat.provider.command.AddSourceArgs.Success;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.KindResolver.Resolution;
import app.zcat.infochat.provider.source.SourceUpsertService;
import app.zcat.infochat.provider.source.SourceUpsertService.Outcome;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /add-source} per {@code docs/spec/commands.md}
 * §Source management. Auto-discovered by
 * {@code InboundRouter} via the CDI {@code Instance<CommandHandler>}
 * scan; no router-side edit needed when this handler lands.
 *
 * <p>Flow per {@code docs/design/03-commands.md} §{@code /add-source}:
 * <ol>
 *   <li>Parse the inbound body via
 *       {@link AddSourceArgs#parse(String)}; a typed
 *       {@link Failure} short-circuits to the friendly error.</li>
 *   <li>Resolve the caller's {@code users.id} +
 *       {@code is_banned}/{@code is_admin} flags by
 *       {@code contact_id} alone (MVP single-adapter assumption;
 *       see the inline note below).</li>
 *   <li>Ban check — defense-in-depth. {@code InboundRouter} step 4
 *       (the {@code is_banned} flag folded into the step-1
 *       {@code UserSnapshot}) is authoritative and blocks
 *       banned senders before any handler runs; this re-check covers
 *       a ban that lands between that intake check and this handler's
 *       actor lookup within the same dispatch.</li>
 *   <li>Permission gate per scope: DM is any non-banned caller;
 *       Group is group-admin-only.</li>
 *   <li>Kind resolution via {@link KindResolver}; AMBIGUOUS short-
 *       circuits to the friendly error.</li>
 *   <li>Per-user hourly rate bucket (M1-705); over-cap short-circuits
 *       to the explanatory reject naming the retry delay.</li>
 *   <li>URL probe via {@link UrlProbe}; SSRF / unreachable / timeout
 *       short-circuits to the matching bundle key.</li>
 *   <li>Confirm-or-contradict: if the resolver chose RSS based on a
 *       path-pattern hint but the probe's {@code Content-Type} is
 *       {@code text/html} (not an RSS-shaped MIME), the result is
 *       AMBIGUOUS per spec.</li>
 *   <li>Upsert via {@link SourceUpsertService}; build the outbound
 *       reply from the returned {@link Outcome}.</li>
 * </ol>
 *
 * <p><b>Adapter-scoped actor lookup.</b> The frozen
 * {@code CommandHandler.handle(ScopeRef, String)} does not carry the
 * inbound adapter name as a parameter; the adapter reaches this
 * handler via the {@link InboundContext} CDI request-scope bean that
 * the {@link app.zcat.infochat.provider.messaging.InboundRouter}
 * populates on every inbound dispatch. The {@code users} SELECT MUST
 * qualify on {@code (adapter, contact_id)} per the V5 UNIQUE
 * constraint; a {@code contact_id}-only lookup would cross-resolve
 * to a different adapter's user row once D46's SimpleX+Signal
 * deployment shape comes online (two distinct users can share a
 * {@code contact_id} literal across adapters).</p>
 */
@ApplicationScoped
public class AddSourceCommandHandler implements CommandHandler {

    private static final String SELECT_USER_FLAGS_SQL =
            "SELECT id, is_admin, is_banned FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SELECT_VOCABULARY_SQL =
            "SELECT name FROM tag ORDER BY name ASC";

    private static final int FUZZY_SUGGESTION_MAX = 5;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    KindResolver kindResolver;

    @Inject
    UrlProbe urlProbe;

    @Inject
    SourceUpsertService sourceUpsertService;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    RateCapBucket rateCapBucket;

    @Override
    public String name() {
        return "add-source";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        ParseResult parsed = AddSourceArgs.parse(rawText);
        if (parsed instanceof Failure f) {
            return reply(scope, format(f.bundleKey(), f.interpolationArgs().toArray()));
        }
        AddSourceArgs args = ((Success) parsed).args();

        String contactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        Optional<UserRow> actor = lookupActor(contactId);
        if (actor.isPresent() && actor.get().isBanned) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_BANNED, inboundContext.effectiveLanguage()));
        }

        // Group scope: group-admin permission gate via
        // GroupMembershipRepository. Admin callers proceed to the
        // add-source logic; non-admin callers get the friendly error.
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            if (actor.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UUID groupDbId = lookupGroupId(group.adapterGroupId());
            if (groupDbId == null
                    || (!actor.get().isAdmin
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actor.get().id))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            if (actor.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actor.get().id;
        }
        UserRow user = actor.get();

        // Kind resolution.
        Resolution resolution = kindResolver.resolve(args.url(), args.typeOverride());
        if (resolution.isNitterHostTypeConflict()) {
            // Configured Nitter host forced to a non-nitter --type (M1-456):
            // name the host so the operator/user knows why the override was
            // refused.
            return reply(scope, format(BundleKeys.ERROR_ADD_SOURCE_NITTER_HOST_TYPE_CONFLICT,
                    resolution.nitterHostConflict().orElseThrow()));
        }
        if (resolution.isAmbiguous()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_AMBIGUOUS_URL, inboundContext.effectiveLanguage()));
        }
        KindResolver.SourceKind kind = resolution.kind().orElseThrow();

        // Per-user hourly /add-source bucket (M1-705; design §4.9's
        // 5/hour row) — the outbound-cost guard: every accepted command
        // below drives a UrlProbe fetch. Drawn AFTER the permission
        // gate and kind resolution (both cost-free) and BEFORE the
        // probe, so over-cap callers are rejected with the explanatory
        // reply (naming the retry delay and the bulk bootstrap-JSON
        // path) instead of silently dropping. Distinct from the
        // cheap-command bucket — a user who drains the hourly budget
        // keeps their cheap commands.
        if (!rateCapBucket.tryAcquireAddSource(user.id)) {
            return reply(scope, format(BundleKeys.ERROR_ADD_SOURCE_RATE_LIMIT,
                    Long.toString(rateCapBucket.addSourceRetryAfterSeconds(user.id))));
        }

        // URL probe. StreamSource-shaped kinds (Nostr in v1) get a
        // single relay connection attempt per spec §Source management;
        // the HTTP probe's scheme allowlist would reject wss/ws with a
        // misleading SSRF-blocked reply.
        ProbeResult probe = kind == KindResolver.SourceKind.NOSTR
                ? urlProbe.probeRelay(args.url())
                : urlProbe.probe(args.url());
        if (!probe.ok()) {
            return reply(scope, bundleLoader.get(probe.failureBundleKey(), inboundContext.effectiveLanguage()));
        }

        // Confirm-or-contradict: if no explicit --type was supplied
        // AND the resolver chose RSS via the path-pattern hint AND
        // the probe's Content-Type contradicts (text/html instead of
        // an RSS-shaped MIME), surface AMBIGUOUS per the spec.
        if (args.typeOverride().isEmpty()
                && kind == KindResolver.SourceKind.RSS
                && resolverHintedRssByPath(args.url())
                && contradictsRss(probe.contentType())) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_AMBIGUOUS_URL, inboundContext.effectiveLanguage()));
        }

        String displayName = SourceUpsertService.defaultDisplayName(
                args.url().toString(), args.displayNameOverride());

        UpsertResult result;
        try {
            result = sourceUpsertService.upsert(
                    user.id, user.isAdmin, scopeKind, scopeId,
                    kind, args.url().toString(),
                    displayName, args.category(), args.lang(), args.tags());
        } catch (SourceUpsertService.UnknownTagsException e) {
            // The v2 growth gate (M1-866): tags must name existing
            // tag-tree nodes. Friendly fuzzy-suggestion shape, never
            // echoing the supplied names (M1-656).
            return reply(scope, format(BundleKeys.ERROR_ADD_SOURCE_UNKNOWN_TAG,
                    String.join(", ", suggestionsFor(e.unknownNames().getFirst()))));
        }

        return reply(scope, buildReply(result));
    }

    private String buildReply(UpsertResult result) {
        return switch (result.outcome()) {
            case FRESH_INSERT -> {
                String fresh = format(BundleKeys.REPLY_ADD_SOURCE_FRESH_INSERT,
                        result.displayName());
                yield fresh + "\n"
                        + bundleLoader.get(BundleKeys.REPLY_ADD_SOURCE_URL_VISIBILITY_DISCLOSURE, inboundContext.effectiveLanguage());
            }
            case SUBSCRIBED_EXISTING -> bundleLoader.get(
                    BundleKeys.REPLY_ADD_SOURCE_SUBSCRIBED_EXISTING, inboundContext.effectiveLanguage());
            case ADMIN_TAGS_REPLACED -> bundleLoader.get(
                    BundleKeys.REPLY_ADD_SOURCE_ADMIN_TAGS_REPLACED, inboundContext.effectiveLanguage());
            // The source id, not the URL: /source-enable takes the id
            // /list-sources shows, and a removed source is hidden from
            // /list-sources — so naming the id here is the only way the
            // admin can reach the remedy from this reply (M1-669).
            case ADMIN_EXISTING_REMOVED -> format(
                    BundleKeys.REPLY_ADD_SOURCE_ADMIN_EXISTING_REMOVED,
                    result.displayName(), result.sourceId());
            case SUBSCRIBED_EXISTING_REMOVED -> format(
                    BundleKeys.REPLY_ADD_SOURCE_SUBSCRIBED_EXISTING_REMOVED,
                    result.displayName(), result.sourceId());
        };
    }

    private String format(String bundleKey, Object... args) {
        String template = bundleLoader.get(bundleKey, inboundContext.effectiveLanguage());
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private Optional<UserRow> lookupActor(String contactId) {
        if (contactId == null) {
            return Optional.empty();
        }
        String adapterName = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_FLAGS_SQL)) {
            ps.setString(1, adapterName);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = (UUID) rs.getObject("id");
                boolean isAdmin = rs.getBoolean("is_admin");
                boolean isBanned = rs.getBoolean("is_banned");
                return Optional.of(new UserRow(id, isAdmin, isBanned));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AddSourceCommandHandler.lookupActor failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapterGroupId) {
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
                    "AddSourceCommandHandler.lookupGroupId failed", e);
        }
    }

    /**
     * Did the resolver pick RSS *only* because the URL path looks like
     * an RSS feed (no host-table hit, no explicit {@code --type})? The
     * confirm-or-contradict check fires only in that case: an explicit
     * host-table match (e.g. bsky.app) does not depend on the
     * Content-Type for confirmation.
     */
    private static boolean resolverHintedRssByPath(java.net.URI url) {
        String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".xml") || path.endsWith(".rss") || path.contains("/feed");
    }

    /**
     * The probe's {@code Content-Type} contradicts an RSS hint when it
     * is plainly {@code text/html} (a browser page) and not one of the
     * RSS-shaped MIME types ({@code application/rss+xml},
     * {@code application/atom+xml}, {@code application/xml}).
     */
    private static boolean contradictsRss(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return false;
        }
        String lower = contentType.get().toLowerCase(Locale.ROOT);
        if (lower.contains("rss+xml") || lower.contains("atom+xml")
                || lower.contains("application/xml") || lower.contains("text/xml")) {
            return false;
        }
        return lower.contains("text/html");
    }

    /** Fuzzy-suggestion list over the tree-node vocabulary for the unknown-tag gate reply (the EligiblePostQuery shape). */
    private List<String> suggestionsFor(String supplied) {
        List<String> vocabulary = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_VOCABULARY_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                vocabulary.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AddSourceCommandHandler: failed to load tag vocabulary for suggestions", e);
        }
        return EligiblePostQuery.fuzzySuggest(supplied, vocabulary, FUZZY_SUGGESTION_MAX);
    }

    /** Minimal in-memory representation of the actor row we need. */
    private record UserRow(UUID id, boolean isAdmin, boolean isBanned) {}
}
