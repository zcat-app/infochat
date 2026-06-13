package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.provider.bundle.BundleKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.UUID;

/**
 * D47 step 3.5 approval-decision service per docs/spec/security.md
 * §Authorization model (the step 3.5 body at lines 407–445) +
 * docs/spec/messaging.md §Identity and groups + docs/design/04-security.md
 * §4.9 (per-user activation cap + global max-groups).
 *
 * <h2>Decision flow</h2>
 *
 * <ol>
 *   <li>Look up the {@code groups} row by natural key
 *       {@code (adapter, upstream_group_id)}.</li>
 *   <li>Existing row → dispatch on {@code approval_status}:
 *       {@code approved} → {@link GroupApprovalCheck.Outcome.Approved};
 *       {@code pending} → fixed reply via
 *       {@link BundleKeys#GROUP_PENDING}; {@code rejected} → fixed
 *       reply via {@link BundleKeys#GROUP_REJECTED}. The caps are not
 *       consulted on this path — they bound CREATION, not subsequent
 *       @mentions on already-existing groups.</li>
 *   <li>Missing row → cap-check (per-user activation, then global
 *       max-groups). Exceeded caps return the corresponding fixed
 *       reply ({@link BundleKeys#GROUP_ACTIVATION_LIMIT} /
 *       {@link BundleKeys#GROUP_GLOBAL_LIMIT}); no row is created and
 *       no notification fires.</li>
 *   <li>Missing row + caps OK → INSERT…ON CONFLICT DO NOTHING with
 *       {@code approval_status='pending'} and the activating user
 *       recorded on {@code activated_by}. On INSERT success: emit a
 *       throttled admin notification with the new row's UUID
 *       interpolated into the {@code /approve-group <uuid>} hint, and
 *       return {@link BundleKeys#GROUP_PENDING}. On INSERT conflict
 *       (concurrent race loser): re-read the row and dispatch on the
 *       winner's {@code approval_status}.</li>
 * </ol>
 *
 * <h2>Per-(adapter, upstream_group_id) isolation</h2>
 *
 * <p>All SQL paths (row lookup, cap-counter reads, INSERT, race-loser
 * re-read) key on the natural key per schema.md §Identity and access.
 * Cross-adapter group collisions cannot happen by construction — the
 * V5 UNIQUE (adapter, upstream_group_id) constraint serializes the
 * INSERT, and the per-user activation cap is itself per-user, not
 * per-adapter.</p>
 *
 * <h2>Race window (R2 from the plan-writer outline)</h2>
 *
 * <p>The two cap reads are sequential SELECTs whose values are
 * point-in-time snapshots. Two concurrent activations from the same
 * user toward two distinct groups, with the user at cap minus one,
 * could both pass the per-user count and both INSERT — leaving the
 * cap exceeded by one. Spec §Rate limiting treats the caps as
 * flood-bounds (resource exhaustion defenses), not strict invariants.
 * A stricter form would require advisory-lock serialization, deferred
 * to a follow-up if redteam surfaces an exploit.</p>
 */
@ApplicationScoped
public class GroupApprovalService {

    @Inject
    GroupRepository groupRepository;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.groups.per-user-activation-cap", defaultValue = "3")
    int perUserActivationCap;

    @ConfigProperty(name = "infochat.groups.global-max-groups", defaultValue = "10")
    int globalMaxGroups;

    /**
     * Run the step 3.5 approval decision for one inbound group
     * @mention from a registered, non-preban user. Encapsulates the
     * approval_status check, the two cap enforcement paths, the
     * race-safe INSERT, and the throttled admin notification on the
     * creation branch.
     *
     * @param adapter                    inbound adapter name; bind FK to
     *                                   {@code groups.adapter}.
     * @param upstreamGroupId            adapter-side stable group id; bind
     *                                   FK to {@code groups.upstream_group_id}.
     * @param activatorUserId            resolved {@code users.id} of the
     *                                   inbound sender. Recorded on the
     *                                   {@code groups.activated_by} column
     *                                   on the creation branch.
     * @param activatorRedactedContactId activating user's contact id
     *                                   passed through
     *                                   {@link app.zcat.infochat.core.log.ContactIds#redact}
     *                                   for the admin-notification message
     *                                   payload. The ADMIN-NOTIFY log line
     *                                   MUST carry only the redacted form
     *                                   (spec §Secrets handling).
     * @return one of {@link GroupApprovalCheck.Outcome.Approved},
     *         {@link GroupApprovalCheck.Outcome.FixedReply} (with a key
     *         from the four GROUP_* keys), depending on the path.
     */
    public GroupApprovalCheck.Outcome evaluate(
            String adapter,
            String upstreamGroupId,
            UUID activatorUserId,
            String activatorRedactedContactId) {
        // Existing-row branch — caps are NOT consulted (they bound
        // creation, not subsequent @mentions on already-pending
        // groups). The dispatch returns the appropriate user-visible
        // outcome based on the row's approval_status.
        Optional<GroupRepository.GroupApprovalRow> existing =
                groupRepository.findApprovalRow(adapter, upstreamGroupId);
        if (existing.isPresent()) {
            return GroupApprovalCheck.dispatchByStatus(existing.get());
        }
        // Missing-row branch — cap-checks first, INSERT second.
        // Per-user cap rejects activate-spam from one user; the global
        // cap bounds aggregate deployment cost.
        if (groupRepository.countGroupsActivatedBy(activatorUserId) >= perUserActivationCap) {
            return new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_ACTIVATION_LIMIT);
        }
        if (groupRepository.countActiveGroups() >= globalMaxGroups) {
            return new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_GLOBAL_LIMIT);
        }
        Optional<UUID> insertedId =
                groupRepository.tryInsertPending(adapter, upstreamGroupId, activatorUserId);
        if (insertedId.isPresent()) {
            notifyAdmin(adapter, upstreamGroupId, insertedId.get(), activatorRedactedContactId);
            return new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_PENDING);
        }
        // Race loser: a concurrent winner already inserted the row
        // between this thread's findApprovalRow and tryInsertPending.
        // Re-read and dispatch on whatever approval_status the winner
        // left behind (almost always 'pending' — the row was just
        // created — but the schema permits the rare case of a quick
        // admin approve/reject between INSERT and this SELECT, so
        // dispatch defensively on the full status set).
        return groupRepository.findApprovalRow(adapter, upstreamGroupId)
                .map(GroupApprovalCheck::dispatchByStatus)
                .orElseThrow(() -> new IllegalStateException(
                        "GroupApprovalService.evaluate: tryInsertPending conflicted but no "
                                + "row found on re-read for adapter=" + adapter
                                + " upstreamGroupId=" + upstreamGroupId
                                + " — schema invariant violation"));
    }

    /**
     * Emit one ADMIN-NOTIFY entry for a freshly-pending group. Fires
     * INSIDE the INSERT-winning branch ONLY: the ON CONFLICT path
     * (race loser, or pending re-mention) emits no notification per
     * acceptance item 7 ("exactly once per group creation").
     *
     * <p>The notification key shape is per-(adapter, upstream_group_id)
     * so collisions across distinct groups are impossible by
     * construction. The notifier's own throttle window provides a
     * backstop against a caller that somehow re-enters this branch
     * for the same group; the spec contract is "exactly once per group
     * creation", which the INSERT…ON CONFLICT semantics already
     * guarantee on the caller side.</p>
     */
    private void notifyAdmin(String adapter,
                             String upstreamGroupId,
                             UUID groupId,
                             String activatorRedactedContactId) {
        // The dedup key uses upstreamGroupId verbatim — the SELECT/
        // INSERT use the same value, so the key inherits the UNIQUE
        // (adapter, upstream_group_id) constraint and cannot collide
        // across distinct rows.
        String key = "group-pending:" + adapter + ":" + upstreamGroupId;
        // Escape control characters in the displayed upstream_group_id
        // before interpolating into the admin-facing message body.
        // The spec does not constrain the adapter-asserted
        // upstream_group_id grapheme set; an adapter emitting newlines
        // or tabs in the id would otherwise let attacker-influenced
        // characters forge multi-line admin notifications (e.g. a fake
        // /approve-group <uuid> line). The escape applies to the
        // displayed value only; the underlying string identity used
        // for the dedup key and DB lookup is unchanged.
        String safeUpstreamGroupId = escapeControlChars(upstreamGroupId);
        String message = "Pending group activation. adapter=" + adapter
                + " upstream_group_id=" + safeUpstreamGroupId
                + " activated_by=" + activatorRedactedContactId
                + " approve_command=/approve-group " + groupId;
        throttledAdminNotifier.notifyOnce(key, "group-pending", message);
    }

    // Package-private for direct unit-test coverage; the escape is
    // applied to the displayed upstream_group_id only, so a regression
    // here would silently re-open the redteam Finding 2 surface.
    // ThrottledAdminNotifier.sanitize already collapses \r/\n/\0 to
    // spaces at its boundary; this escape converts them to backslash-
    // letter sequences BEFORE sanitize sees them, so the admin sees
    // the injected control characters rendered visibly (e.g. literal
    // "\n") rather than silently flattened into a single line that
    // could carry a forged `approve_command=` token.
    static String escapeControlChars(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
