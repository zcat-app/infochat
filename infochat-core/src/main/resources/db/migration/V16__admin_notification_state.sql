-- V16: admin_notification_state (decision D22, D42).
--
-- Backing store for the ThrottledAdminNotifier per
-- docs/spec/schema.md §Operational — "Admin notification state —
-- backing store for the throttled admin notifier (decision D22)".
-- The notifier is the operator-visible signal that a fetcher or
-- eval-pipeline component is failing (Stage1Pipeline regex
-- timeouts, Stage2 infrastructure failures, TaggerWorker /
-- EmbeddingWorker fallbacks, RssFetcher D42 per-source failure
-- counter, asset-fetcher consecutive failures). One row per
-- notification_key; the notifier UPSERTS with a conditional
-- WHERE so concurrent first-time callers for the same key
-- produce exactly one log emission and N-1 suppressed bumps.
--
-- Atomic Flyway migration: the whole file applies in one
-- transaction so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- admin_notification_state (D22, schema.md §Operational)
--
-- notification_key TEXT PRIMARY KEY: the caller-supplied
-- coalescing key (e.g. "stage1-regex-timeout",
-- "asset-source-failed:zcash:price", "tagger-fallback:<source-uuid>").
-- The key shape is a caller convention, not a schema constraint —
-- the spec leaves the namespace open so each consumer picks a key
-- that groups failures usefully without cardinality explosion.
--
-- error_class TEXT NOT NULL: the canonical error_class string the
-- pipeline already records elsewhere (e.g. Stage1Pipeline's
-- ERROR_CLASS_SANITIZER_EXCEPTION). Stored here for operator
-- visibility — a key may carry rotating error_class values as
-- the underlying failure mode shifts.
--
-- last_notified_at TIMESTAMPTZ: when the most recent log
-- emission fired for this key. The notifier's WHERE clause
-- gates the next emission on `last_notified_at + window <= now`.
--
-- notification_count BIGINT: total log emissions for this key
-- since first_seen_at. Monotonic; never reset by the notifier.
--
-- suppressed_count BIGINT: total within-window suppressions for
-- this key since first_seen_at. Monotonic; never reset by the
-- notifier. Operators read (notification_count, suppressed_count)
-- together to see how loud a real failure is under throttling.
--
-- first_seen_at TIMESTAMPTZ: when this key was first observed.
-- Never updated after row creation.
--
-- The table grows monotonically — the notifier never DELETEs.
-- Operator-side pruning is a DBA TRUNCATE if the table ever
-- accumulates too many distinct keys (which would itself be a
-- signal that callers are using high-cardinality keys; the v1
-- consumers all use low-cardinality keys).
-- ---------------------------------------------------------------------

CREATE TABLE admin_notification_state (
    notification_key   TEXT PRIMARY KEY,
    error_class        TEXT NOT NULL,
    last_notified_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notification_count BIGINT NOT NULL DEFAULT 1,
    suppressed_count   BIGINT NOT NULL DEFAULT 0,
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- Per-role GRANTs (aligned with docs/spec/security.md §DB roles and
-- V7's per-table GRANT-split convention).
--
-- admin_notification_state is written through the shared
-- ThrottledAdminNotifier (infochat-core), which UPSERTs on every
-- notifyOnce call. As of this migration every writing consumer
-- runs in the Collector, so only infochat_collector gets
-- INSERT/UPDATE here; Provider is read-only until V21 widens it
-- for the Provider-side notifier callers.
--
-- DELETE is NOT granted to either role. The table is operator-
-- managed (DBA TRUNCATE if pruning is ever required); the
-- monotonic-counter design means the application never has a
-- legitimate reason to delete rows.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON admin_notification_state TO infochat_collector;
GRANT SELECT                 ON admin_notification_state TO infochat_provider;
