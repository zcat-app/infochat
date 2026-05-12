-- V1: bootstrap migration — scaffolding placeholder.
--
-- This migration exists so Flyway has a baseline to apply on a fresh
-- DB. It deliberately contains only the pgvector extension load; every
-- entity table (users, sources, posts, group memberships, audit log,
-- ...) lands in the M1-008 umbrella's per-entity migrations. Keeping V1
-- profile-independent and schema-independent lets the foundational
-- scaffolding land before any entity shape is committed.
--
-- pgvector ships with the pgvector/pgvector:pg16 image but is NOT
-- auto-enabled on a fresh database; this is the canonical place where
-- the extension gets created. Subsequent migrations may then freely
-- declare `vector(N)` columns.

CREATE EXTENSION IF NOT EXISTS vector;
