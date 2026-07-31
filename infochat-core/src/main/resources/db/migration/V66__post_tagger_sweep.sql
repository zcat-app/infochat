-- V66: tagger re-evaluation sweep bookkeeping (M1-736).
--
-- M1-726 made tags='{}' a terminal first-pass outcome, but "untaggable" is a
-- function of two inputs that change over time: the controlled vocabulary
-- (TagVocabulary's refresh path) and the tagger model. The sweep re-runs the
-- tagger over posts whose first pass produced tags='{}' once per INPUT
-- GENERATION, with hard caps so it can never become a silent LLM-burning
-- loop. Two post columns carry the per-post bookkeeping; a singleton state
-- table carries the current generation marker and the fingerprint of the
-- inputs it was derived from.
--
-- tagger_swept_generation  The generation the post was last swept at. A post
--                          is sweep-eligible only while this is behind the
--                          marker's current generation. DEFAULT 0 means
--                          "never swept": pre-V66 rows become eligible on the
--                          FIRST generation bump (the first input change
--                          after deploy), not at deploy — the baseline
--                          fingerprint is recorded as generation 0 on the
--                          first sweep-capable tick, so a deploy alone never
--                          triggers a backlog sweep.
-- tagger_sweep_attempts    Total sweep passes across ALL generations; the
--                          per-post attempt cap
--                          (infochat.llm.tagger.sweep.max-attempts) reads
--                          this so a post is re-evaluated at most K times
--                          ever, then left alone even when the generation
--                          keeps bumping.
--
-- post is PARTITION BY RANGE (fetched_at); ALTER TABLE on the parent
-- propagates the columns to every child partition (same mechanism as V21's
-- re_eval_attempts and V52's last_reeval_at). No new GRANTs for the columns:
-- they ride the post table's existing per-role grants. Atomic Flyway
-- migration: both ALTERs, the CREATE TABLE and the grants apply in one
-- transaction so a partial failure rolls back cleanly.

ALTER TABLE post ADD COLUMN tagger_swept_generation INT NOT NULL DEFAULT 0;
ALTER TABLE post ADD COLUMN tagger_sweep_attempts    INT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------
-- tagger_sweep_state — singleton generation marker, bootstrap_meta shape
-- (V8): id SMALLINT CHECK (id = 1) forces the canonical row; the Collector
-- inserts it on first use and UPDATEs it in place on every bump, never
-- deletes (REVOKE DELETE is the defense-in-depth complement of the CHECK).
--
-- generation         Current input generation, bumped by 1 whenever the
--                    fingerprint below changes. Starts at 0 (baseline).
-- input_fingerprint  SHA-256 hex over the sorted normalized vocabulary
--                    names plus the configured tagger model string — the
--                    two cheaply-identifiable tagger inputs. An operator
--                    swapping what answers behind the same endpoint URL is
--                    NOT detectable and deliberately out of scope (M1-736
--                    Notes).
-- updated_at         Record-only stamp of the last init/bump.
-- ---------------------------------------------------------------------

CREATE TABLE tagger_sweep_state (
    id                SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    generation        INT         NOT NULL,
    input_fingerprint TEXT        NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- Collector-owned: TaggerWorker's sweep tail reads and bumps the marker on
-- ticks with spare batch capacity. The Provider is granted NOTHING — no
-- provider-side reader exists (the V61 narrow-grant posture).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON tagger_sweep_state TO infochat_collector;

REVOKE DELETE ON tagger_sweep_state FROM infochat_collector, infochat_provider, PUBLIC;
