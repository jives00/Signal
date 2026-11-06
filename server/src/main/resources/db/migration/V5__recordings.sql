-- Recording (.claude/plans/dvr.md). One row per recording; the files are on the recordings share.
CREATE TABLE recording (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    profile_id VARCHAR(64) NOT NULL,
    stream_id VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    channel_name VARCHAR(500) NOT NULL,
    start_ms BIGINT NOT NULL,
    stop_ms BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    login_name VARCHAR(255) NULL,
    ended_at BIGINT NULL,
    updated_at BIGINT NOT NULL,
    bytes BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    gaps INT NOT NULL DEFAULT 0,
    gap_ms BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(500) NULL,
    expires_at BIGINT NULL,
    INDEX recording_profile (profile_id, start_ms),
    INDEX recording_state (state)
);
