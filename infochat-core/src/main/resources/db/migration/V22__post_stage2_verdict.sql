-- V22: Add stage2_verdict column to the post table.
--
-- Stores the definitive Stage 2 verdict label ('BENIGN', 'INJECTION',
-- 'MALWARE', 'UNKNOWN') when stage2_done=true and stage2_failed=false.
-- NULL when Stage 2 hasn't run or had an infra failure.
-- Required so PerSourceUnknownTracker can filter specifically for
-- UNKNOWN verdicts without conflating INJECTION/MALWARE quarantines.

ALTER TABLE post ADD COLUMN stage2_verdict TEXT;
