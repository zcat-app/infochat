package app.zcat.infochat.provider.group;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;

/**
 * D47 step 3.5 router-facing gate per docs/spec/security.md
 * §Authorization model (the step 3.5 body at lines 407–445) +
 * docs/spec/messaging.md §Identity and groups + docs/spec/security.md
 * §Rate limiting per-group reply rate. Invoked by {@code InboundRouter}
 * for group-scope inbound from registered (not preban) users — fires
 * BETWEEN the step-3 registered-user check and the step-4 ban check.
 *
 * <h2>Two-layer responsibility</h2>
 *
 * <p>The Check is intentionally thin: it adds the per-group reply
 * rate bucket on top of the {@link GroupApprovalService} approval
 * decision. The Service encapsulates the approval_status check, the
 * caps, the race-safe INSERT, and the admin notification; the Check
 * applies the bucket gate that bounds outbound adapter-send cost.</p>
 *
 * <h2>Bucket scope</h2>
 *
 * <p>The bucket is consulted ONLY for existing rows — first @mention
 * on a non-existent group has no UUID key for the bucket yet (the
 * Service mints it inside {@link GroupApprovalService#evaluate}). The
 * creation path is rate-bounded instead by the per-user activation
 * cap and the global max-groups cap; spec is consistent with the
 * bucket only protecting outbound cost on already-existing groups.</p>
 *
 * <h2>InboundRouter ordering invariant</h2>
 *
 * <p>Step 3.5 fires AFTER step 3 (registered/preban filter), so
 * {@link #check} is invoked with a guaranteed-registered
 * {@code activatorUserId}. Step 3.5 fires BEFORE step 4 (ban) and
 * step 4.1 (auto-promote), so a pending/rejected group does NOT
 * accumulate membership rows, auto-promote attempts, or ban-check
 * SQL until approval lifts the gate.</p>
 */
@ApplicationScoped
public class GroupApprovalCheck {

    /**
     * User-visible outcome of the step 3.5 check, consumed by
     * {@code InboundRouter}. Sealed so the router's {@code switch}
     * is exhaustive — adding a future outcome shape forces the call
     * site to handle it (or the compiler complains).
     *
     * <p>The {@link FixedReply} variant carries only a bundle key —
     * no MessageFormat arguments today. The four user-visible
     * fixed-reply paths ({@link BundleKeys#GROUP_PENDING},
     * {@link BundleKeys#GROUP_REJECTED},
     * {@link BundleKeys#GROUP_ACTIVATION_LIMIT},
     * {@link BundleKeys#GROUP_GLOBAL_LIMIT}) all carry self-contained
     * text with no caller-supplied tokens. A future widening that
     * needs tokens can add a second variant rather than retrofit
     * this one.</p>
     */
    public sealed interface Outcome {
        /** Approved group — InboundRouter falls through to step 4 (ban check). */
        record Approved() implements Outcome {}

        /** Fixed reply path — InboundRouter resolves the bundle key, sends the reply, stops. */
        record FixedReply(String bundleKey) implements Outcome {}

        /** Per-group reply bucket exhausted — InboundRouter sends nothing, stops. */
        record SilentDrop() implements Outcome {}
    }

    @Inject
    GroupRepository groupRepository;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    GroupApprovalService groupApprovalService;

    /**
     * Run the step 3.5 decision for one inbound group @mention from a
     * registered, non-preban user. Consults the per-group reply rate
     * bucket on the existing-row path; delegates approval-decision to
     * {@link GroupApprovalService#evaluate} on both paths.
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
     * @return one of {@link Outcome.Approved}, {@link Outcome.FixedReply},
     *         or {@link Outcome.SilentDrop}. InboundRouter dispatches
     *         the three shapes per its class-level Javadoc.
     */
    public Outcome check(String adapter,
                                  String upstreamGroupId,
                                  UUID activatorUserId,
                                  String activatorRedactedContactId) {
        // Bucket gate for existing rows. The lookup here is what lets
        // the bucket be keyed on the row's UUID; Service does its own
        // lookup so the dispatch result reflects the freshest
        // approval_status (the row could have been approved between
        // our lookup and Service's lookup — a TOCTOU benign because
        // the bucket has already committed a token to this reply).
        Optional<GroupRepository.GroupApprovalRow> existing =
                groupRepository.findApprovalRow(adapter, upstreamGroupId);
        if (existing.isPresent()) {
            if (!rateCapBucket.tryAcquireGroupReply(existing.get().id())) {
                return new Outcome.SilentDrop();
            }
        }
        // Delegate the approval decision. Service does its own row
        // lookup; on the missing-row path it runs cap-checks, INSERT,
        // and notify. The first @mention path consumes no bucket
        // token by design — creation is rate-bounded by the per-user
        // activation cap and the global max-groups cap instead.
        return groupApprovalService.evaluate(
                adapter, upstreamGroupId, activatorUserId, activatorRedactedContactId);
    }

    /**
     * Map a {@code groups.approval_status} CHECK-constrained value to
     * its user-visible {@link Outcome}. Package-private + {@code static}
     * so {@link GroupApprovalService} reuses the same dispatch table
     * for the existing-row branch and the race-loser branch.
     *
     * <p>The default branch throws on an unknown status — V26's CHECK
     * constraint guarantees the column carries only the three valid
     * values, so reaching the default branch is a schema invariant
     * violation worth crashing on (not a defensive fall-back).</p>
     */
    static Outcome dispatchByStatus(String approvalStatus) {
        return switch (approvalStatus) {
            case "approved" -> new Outcome.Approved();
            case "pending" -> new Outcome.FixedReply(BundleKeys.GROUP_PENDING);
            case "rejected" -> new Outcome.FixedReply(BundleKeys.GROUP_REJECTED);
            default -> throw new IllegalStateException(
                    "Unknown groups.approval_status value '" + approvalStatus
                            + "' — V26 CHECK constraint was bypassed");
        };
    }
}
