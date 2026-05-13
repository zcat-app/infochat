-- V3: single-instance enforcement fingerprint — the `heartbeat` table.
--
-- docs/spec/architecture.md §Deployment topology (v1) commits the system
-- to exactly one Collector and one Provider per shared Postgres
-- (decision D41). docs/design/07-deployment.md §7.8.5 enforces that
-- invariant with a per-service `pg_advisory_lock` on
-- `hashtext('infochat.collector')` / `hashtext('infochat.provider')`. The
-- advisory lock is the integrity boundary; this table is the
-- operator-visible *fingerprint* that lets a rejected second instance
-- log a useful message naming the current holder.
--
-- One row per service, keyed by `service` text PRIMARY KEY (values
-- 'collector' / 'provider'). The lock-holder upserts this row on
-- acquisition and the `@Scheduled` heartbeat task refreshes
-- `last_seen_at` on every tick.
--
-- GRANTs: both application roles get SELECT/INSERT/UPDATE (each role
-- writes its own row on acquisition and on every heartbeat tick;
-- Provider must also SELECT to read Collector's row, and vice versa, so
-- a rejected acquirer can log the holder's identity). DELETE is
-- intentionally NOT granted to either application role — only
-- `infochat_admin` may delete heartbeat rows (operator path). This
-- guarantees that an application bug cannot wipe the contention
-- fingerprint and leave a rejected instance with no holder to name in
-- the fatal log line (docs/design/07-deployment.md §7.8.5).

CREATE TABLE heartbeat (
    service       text        PRIMARY KEY,
    host_id       text        NOT NULL,
    pid           integer     NOT NULL,
    last_seen_at  timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON heartbeat TO infochat_collector;
GRANT SELECT, INSERT, UPDATE ON heartbeat TO infochat_provider;
