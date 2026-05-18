package io.infochat.provider.command;

import io.infochat.messaging.OutboundMessage;
import io.infochat.messaging.ScopeRef;
import io.infochat.provider.bundle.BundleKeys;
import io.infochat.provider.bundle.BundleLoader;
import io.infochat.provider.command.AddSourceArgs.Failure;
import io.infochat.provider.command.AddSourceArgs.ParseResult;
import io.infochat.provider.command.AddSourceArgs.Success;
import io.infochat.provider.messaging.CommandHandler;
import io.infochat.provider.source.KindResolver;
import io.infochat.provider.source.KindResolver.Resolution;
import io.infochat.provider.source.SourceUpsertService;
import io.infochat.provider.source.SourceUpsertService.Outcome;
import io.infochat.provider.source.SourceUpsertService.UpsertResult;
import io.infochat.provider.source.UrlProbe;
import io.infochat.provider.source.UrlProbe.ProbeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
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
 *   <li>Ban check — defense-in-depth; the upstream T2-A ban gate
 *       is not yet wired so the handler reads the flag itself.</li>
 *   <li>Permission gate per scope: DM is any non-banned caller;
 *       Group is group-admin-only.</li>
 *   <li>Kind resolution via {@link KindResolver}; AMBIGUOUS short-
 *       circuits to the friendly error.</li>
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
 * <p><b>Adapter-name absence in the SPI.</b> The frozen
 * {@code CommandHandler.handle(ScopeRef, String)} does not carry the
 * inbound adapter name, so the {@code (adapter, contact_id) UNIQUE}
 * on {@code users} cannot be qualified here. MVP runs a single
 * active adapter ({@code inmemory} only per D46), so a
 * {@code contact_id}-only lookup is unambiguous. T3-A (SimpleX +
 * Signal) is the right time to widen the SPI or add a thread-local
 * adapter context; the lookup below MUST be revisited then.</p>
 */
@ApplicationScoped
public class AddSourceCommandHandler implements CommandHandler {

    private static final String SELECT_USER_FLAGS_FOR_DM_SQL =
            "SELECT id, is_admin, is_banned FROM users WHERE contact_id = ?";

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

        // Group scope: MVP rejects unconditionally with group_admin_only.
        // The frozen CommandHandler SPI does not carry the inbound
        // actor's identity in group scope (ScopeRef.Group holds only
        // the adapter-side group id), so the handler cannot consult
        // group_membership for the caller. The ticket's out_of_scope
        // commits to "the rejection branch falls through the auth
        // check WITHOUT requiring group-membership infrastructure";
        // T2-F wires the real actor seam + group-admin proceed path.
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY));
        }

        // DM scope: resolve actor + permission gate.
        Optional<UserRow> actor = lookupActor(scope);
        if (actor.isEmpty()) {
            // Shouldn't happen — AutoRegisterService runs upstream of
            // every dispatch. Surface as a generic friendly error.
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
        }
        UserRow user = actor.get();
        if (user.isBanned) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_BANNED));
        }

        // Kind resolution.
        Resolution resolution = kindResolver.resolve(args.url(), args.typeOverride());
        if (resolution.isAmbiguous()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_AMBIGUOUS_URL));
        }
        KindResolver.SourceKind kind = resolution.kind().orElseThrow();

        // URL probe.
        ProbeResult probe = urlProbe.probe(args.url());
        if (!probe.ok()) {
            return reply(scope, bundleLoader.get(probe.failureBundleKey()));
        }

        // Confirm-or-contradict: if no explicit --type was supplied
        // AND the resolver chose RSS via the path-pattern hint AND
        // the probe's Content-Type contradicts (text/html instead of
        // an RSS-shaped MIME), surface AMBIGUOUS per the spec.
        if (args.typeOverride().isEmpty()
                && kind == KindResolver.SourceKind.RSS
                && resolverHintedRssByPath(args.url())
                && contradictsRss(probe.contentType())) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_AMBIGUOUS_URL));
        }

        // Upsert + reply. DM scope only at this point — group scope
        // short-circuited above to the group_admin_only reply.
        String displayName = SourceUpsertService.defaultDisplayName(
                args.url().toString(), args.displayNameOverride());

        UpsertResult result = sourceUpsertService.upsert(
                user.id, user.isAdmin, "dm", user.id,
                kind, args.url().toString(),
                displayName, args.category(), args.tags());

        return reply(scope, buildReply(result));
    }

    private String buildReply(UpsertResult result) {
        return switch (result.outcome()) {
            case FRESH_INSERT -> {
                String fresh = format(BundleKeys.REPLY_ADD_SOURCE_FRESH_INSERT,
                        result.displayName());
                yield fresh + "\n"
                        + bundleLoader.get(BundleKeys.REPLY_ADD_SOURCE_URL_VISIBILITY_DISCLOSURE);
            }
            case SUBSCRIBED_EXISTING -> bundleLoader.get(
                    BundleKeys.REPLY_ADD_SOURCE_SUBSCRIBED_EXISTING);
            case ADMIN_TAGS_REPLACED -> bundleLoader.get(
                    BundleKeys.REPLY_ADD_SOURCE_ADMIN_TAGS_REPLACED);
        };
    }

    private String format(String bundleKey, Object... args) {
        String template = bundleLoader.get(bundleKey);
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private Optional<UserRow> lookupActor(ScopeRef scope) {
        String contactId = contactIdOf(scope);
        if (contactId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_FLAGS_FOR_DM_SQL)) {
            ps.setString(1, contactId);
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
                    "AddSourceCommandHandler.lookupActor failed for contact_id=" + contactId, e);
        }
    }

    /**
     * Extract the DM contact id from a {@link ScopeRef}. The handler
     * is only reached with DM scope (group scope short-circuits
     * upstream to the group_admin_only reply); a non-DM scope here
     * is an upstream bug surfaced as {@link Optional#empty()}.
     */
    private static String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
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

    /** Minimal in-memory representation of the actor row we need. */
    private record UserRow(UUID id, boolean isAdmin, boolean isBanned) {}
}
