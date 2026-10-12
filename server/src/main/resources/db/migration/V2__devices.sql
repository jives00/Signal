-- Paired devices (PLAN §5.7, §4.3.18 → Devices & profiles). The id is the UUID the device made for
-- itself, so pairing the same install again replaces its key instead of adding a second row. Only
-- the key's SHA-256 is kept: the key itself goes to the device once and is never stored here.
CREATE TABLE device (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    app_version VARCHAR(30) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    paired_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    UNIQUE INDEX device_key_hash (key_hash)
);
