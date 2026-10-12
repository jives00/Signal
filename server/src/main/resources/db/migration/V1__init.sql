-- The companion's own settings (PLAN §4.3.18 → Settings → Companion): the nightly run time and
-- time zone, retention periods and so on. Key/value, so a new setting needs no migration.
CREATE TABLE companion_setting (
    name VARCHAR(100) NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

-- Job history (PLAN §5.8): one row per step of a nightly or manual run.
CREATE TABLE job_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job VARCHAR(50) NOT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT,
    result VARCHAR(20),
    detail TEXT,
    INDEX job_run_started (started_at)
);
