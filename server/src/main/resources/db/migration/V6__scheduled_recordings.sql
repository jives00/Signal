-- Scheduled recordings (.claude/plans/dvr.md). start_ms and stop_ms are what gets captured, padding
-- included; these are the program's own times, and how much "Keep recording" added.
ALTER TABLE recording
    ADD COLUMN program_start_ms BIGINT NULL,
    ADD COLUMN program_stop_ms BIGINT NULL,
    ADD COLUMN extra_ms BIGINT NOT NULL DEFAULT 0;
