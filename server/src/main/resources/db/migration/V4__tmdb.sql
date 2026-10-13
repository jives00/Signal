-- VOD enrichment (PLAN §4.3.12). The companion's own small TMDB cache, keyed by TMDB ID like Trakt's
-- so the future unified app can merge the two, but never shared with Trakt's database.
CREATE TABLE tmdb_title (
    kind VARCHAR(10) NOT NULL,
    tmdb_id BIGINT NOT NULL,
    body MEDIUMTEXT NOT NULL,
    fetched_at BIGINT NOT NULL,
    PRIMARY KEY (kind, tmdb_id)
);

-- Each source title's match: matched (with its TMDB ID), unmatched, or manual (fixed in the web admin,
-- never overwritten by the matcher). Kept across restarts, so the first pass resumes.
CREATE TABLE vod_match (
    vod_item_id VARCHAR(255) NOT NULL PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    tmdb_id BIGINT,
    status VARCHAR(12) NOT NULL,
    attempted_at BIGINT NOT NULL,
    INDEX vod_match_tmdb (kind, tmdb_id)
);

-- The nightly TMDB lists (Trending, Popular, New releases, Airing now, Top rated), in TMDB's order.
CREATE TABLE tmdb_list (
    kind VARCHAR(10) NOT NULL,
    list VARCHAR(20) NOT NULL,
    position INT NOT NULL,
    tmdb_id BIGINT NOT NULL,
    PRIMARY KEY (kind, list, position)
);
