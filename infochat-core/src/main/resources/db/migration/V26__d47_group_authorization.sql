-- V26: D47 group authorization — additive columns on the groups table.
--
-- approval_status gates whether a group's messages are processed
-- (the gate itself lands in M1-112). activated_by records the user
-- who first vouched or first @mentioned the bot into an approved
-- group; it is nullable because that information is not recoverable
-- for groups that pre-date D47.
--
-- Additive only: no DROP, no CHECK alteration, no statement touches
-- the users table. The group_only→invited consolidation of
-- users.registration_state is the complementary removal and lands
-- separately in M1-111. Flyway wraps this script in a single
-- transaction on PostgreSQL, so the additive shape is atomic by
-- construction.

ALTER TABLE groups ADD COLUMN approval_status VARCHAR NOT NULL DEFAULT 'pending'
    CHECK (approval_status IN ('pending', 'approved', 'rejected'));

ALTER TABLE groups ADD COLUMN activated_by UUID REFERENCES users(id);

-- Grandfather every group that existed before D47 to 'approved'
-- (they were already being processed under the pre-D47 model).
-- activated_by is left NULL — the originating user is not
-- recoverable. New rows inserted after this migration take the
-- column DEFAULT of 'pending'.
UPDATE groups SET approval_status = 'approved';
