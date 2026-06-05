package app.zcat.infochat.provider.digest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestPostCollector.CollectionResult;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Observes {@link DigestSlot} events fired by {@link DigestScheduler},
 * orchestrates the full digest pipeline: collect posts → render prose
 * (or degrade) → cache → deliver. Each slot is independent — a degraded
 * result does not affect subsequent slots.
 */
@ApplicationScoped
public class DigestWorker {

    private static final Logger LOG = Logger.getLogger(DigestWorker.class);

    private static final ExecutorService RENDER_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    DigestPostCollector postCollector;

    @Inject
    DigestRenderer digestRenderer;

    @Inject
    DegradedDigestRenderer degradedRenderer;

    @Inject
    SummaryCacheRepository cacheRepository;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    DataSource dataSource;

    // In-flight guard (ConcurrentHashMap-backed, keyed groupId+slotKind): a
    // scheduler tick overrun re-fires a slot whose previous execution is
    // still running; overlapping same-group processing would double-deliver.
    private final Set<String> inFlightSlots = ConcurrentHashMap.newKeySet();

    public void execute(@Observes @NonNull DigestSlot slot) {
        String inFlightKey = slot.groupId() + ":" + slot.slotKind();
        if (!inFlightSlots.add(inFlightKey)) {
            LOG.warnf("Digest already in flight for group %s slot %s — skipping overlapping execution",
                    slot.groupId(), slot.slotKind());
            return;
        }
        try {
            executeSlot(slot);
        } catch (SQLException | MessagingException e) {
            // Expected operational failures only — programming errors propagate
            LOG.errorf(e, "Digest failed for group %s slot %s", slot.groupId(), slot.slotKind());
        } finally {
            inFlightSlots.remove(inFlightKey);
        }
    }

    private void executeSlot(DigestSlot slot) throws SQLException, MessagingException {
        CollectionResult collection =
                postCollector.collectForGroup(slot.groupId(), slot.windowStart());
        GroupMetadata meta = readGroupMetadata(slot.groupId());

        String content;
        boolean isDegraded = false;

        if (collection.posts().isEmpty()) {
            content = bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, meta.language());
        } else {
            Duration remaining = Duration.between(Instant.now(), slot.windowEnd());
            if (remaining.isNegative() || remaining.isZero()) {
                content = degradedRenderer.render(collection.posts());
                isDegraded = true;
            } else {
                try {
                    content = CompletableFuture
                            .supplyAsync(
                                    () -> digestRenderer.render(collection.posts(), meta.language()),
                                    RENDER_EXECUTOR)
                            .get(remaining.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    content = degradedRenderer.render(collection.posts());
                    isDegraded = true;
                }
            }
        }

        cacheRepository.insert(
                slot.groupId(),
                slot.slotKind(),
                slot.windowStart(),
                collection.tagSubscriptionVersion(),
                collection.sourceSubscriptionVersion(),
                content,
                isDegraded,
                slot.windowEnd());

        MessagingAdapter adapter = findAdapter(meta.adapterName());
        if (adapter == null) {
            LOG.warnf("No activated adapter '%s' for group %s — digest cached but not delivered",
                    meta.adapterName(), slot.groupId());
            return;
        }

        String correlationId = "digest-" + slot.groupId() + "-" + slot.windowStart();
        OutboundMessage msg = new OutboundMessage(
                new ScopeRef.Group(meta.upstreamGroupId()),
                content,
                Instant.now(),
                correlationId);
        adapter.send(msg);
    }

    record GroupMetadata(@NonNull String adapterName,
                         @NonNull String upstreamGroupId,
                         @NonNull String language) {}

    GroupMetadata readGroupMetadata(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(GROUP_META_SQL)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Group not found: " + groupId);
                }
                return new GroupMetadata(
                        rs.getString("adapter"),
                        rs.getString("upstream_group_id"),
                        rs.getString("language"));
            }
        }
    }

    private @Nullable MessagingAdapter findAdapter(String adapterName) {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        return null;
    }

    private static final String GROUP_META_SQL = """
            SELECT g.adapter, g.upstream_group_id,
                   COALESCE(sp.language, 'en') AS language
              FROM groups g
              LEFT JOIN scope_preferences sp
                ON sp.scope_kind = 'group' AND sp.scope_id = g.id
             WHERE g.id = ?""";
}
