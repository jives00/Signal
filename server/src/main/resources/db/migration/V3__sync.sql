-- Settings sync (PLAN §5.5, §9). Every synced item, in every scope, with its fields as JSON. The
-- revision is global and set by the companion; deletes stay as tombstones for 90 days.
CREATE TABLE sync_item (
    type VARCHAR(40) NOT NULL,
    scope VARCHAR(10) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    id VARCHAR(255) NOT NULL,
    fields MEDIUMTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    rev BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (type, scope, owner_id, id),
    INDEX sync_item_rev (rev)
);

-- Profiles (PLAN §9.1). "Main" always exists, and every new device joins it.
CREATE TABLE profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL
);

INSERT INTO profile (id, name, created_at) VALUES ('main', 'Main', 0);

ALTER TABLE device
    ADD COLUMN profile_id VARCHAR(64) NOT NULL DEFAULT 'main',
    ADD COLUMN last_sync_at BIGINT NOT NULL DEFAULT 0;

-- Backups (PLAN §9.3): the whole bundle, every scope, as JSON. Passwords stay encrypted inside it.
CREATE TABLE snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at BIGINT NOT NULL,
    reason VARCHAR(100) NOT NULL,
    items INT NOT NULL,
    bundle LONGTEXT NOT NULL,
    INDEX snapshot_created (created_at)
);
