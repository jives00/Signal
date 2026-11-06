-- How far a recording has been watched (.claude/plans/dvr.md). A recording belongs to one profile, so
-- this lives on its row. watched_at is when it was watched to the end, which shortens its expiry.
ALTER TABLE recording
    ADD COLUMN position_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN watched_at BIGINT NULL;
