package app.zcat.infochat.core.audit;

import java.util.Locale;

/**
 * Closed-set enum of {@code audit_log.target_kind} values.
 *
 * <p>Unlike {@code action} (open-ended TEXT whose closure enforcer is
 * {@link AuditAction}), {@code target_kind} is pinned in the database
 * by the V5 §2.1.7 CHECK constraint
 * ({@code user|group|source|post|invite|quarantine|asset|memory|
 * system}). This enum mirrors that CHECK set exactly — a parity test
 * reads the V5 migration and fails the build if the two sets ever
 * drift. The DB column value is {@link #dbValue()}: the CHECK set is
 * lowercase, so the wire format is the lowercased enum identifier
 * (not {@link #name()} directly, which is how {@link AuditAction}
 * maps — its catalogue is uppercase in the DB).</p>
 */
public enum TargetKind {
    USER,
    GROUP,
    SOURCE,
    POST,
    INVITE,
    QUARANTINE,
    ASSET,
    MEMORY,
    SYSTEM;

    /**
     * The {@code audit_log.target_kind} column value: the lowercased
     * enum identifier, matching the V5 CHECK set.
     */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
