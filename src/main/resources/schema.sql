CREATE TABLE IF NOT EXISTS forum_users (
 id VARCHAR(36) PRIMARY KEY,
 username VARCHAR(32) NOT NULL,
 username_key VARCHAR(32) NOT NULL UNIQUE,
 password_hash VARCHAR(256) NOT NULL,
 nickname VARCHAR(32 CHAR) NOT NULL,
 role VARCHAR(8) NOT NULL CHECK (role IN ('USER','ADMIN')),
 created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS forum_posts (
 id VARCHAR(36) PRIMARY KEY,
 author_id VARCHAR(36) NOT NULL REFERENCES forum_users(id),
 title VARCHAR(100 CHAR) NOT NULL,
 body TEXT NOT NULL,
 status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','PUBLISHED','REJECTED')),
 revision INTEGER NOT NULL DEFAULT 1,
 submitted_at BIGINT NOT NULL,
 first_published_at BIGINT,
 rejection_reason VARCHAR(1000 CHAR),
 reviewed_by VARCHAR(36) REFERENCES forum_users(id),
 reviewed_at BIGINT
);
CREATE INDEX IF NOT EXISTS forum_posts_public ON forum_posts(status,first_published_at,id);
CREATE INDEX IF NOT EXISTS forum_posts_review ON forum_posts(status,submitted_at,id);
CREATE TABLE IF NOT EXISTS forum_interactions (
 user_id VARCHAR(36) NOT NULL REFERENCES forum_users(id),
 post_id VARCHAR(36) NOT NULL REFERENCES forum_posts(id),
 kind VARCHAR(12) NOT NULL CHECK(kind IN ('likes','favorites')),
 created_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,post_id,kind)
);
CREATE TABLE IF NOT EXISTS forum_comments (
 id VARCHAR(36) PRIMARY KEY,
 post_id VARCHAR(36) NOT NULL REFERENCES forum_posts(id),
 user_id VARCHAR(36) NOT NULL REFERENCES forum_users(id),
 root_id VARCHAR(36) REFERENCES forum_comments(id),
 reply_to_id VARCHAR(36) REFERENCES forum_comments(id),
 body TEXT NOT NULL,
 created_at BIGINT NOT NULL,
 deleted_at BIGINT
);
CREATE INDEX IF NOT EXISTS forum_comments_post ON forum_comments(post_id,root_id,created_at,id);
CREATE INDEX IF NOT EXISTS forum_comments_user ON forum_comments(user_id,created_at,id);
CREATE TABLE IF NOT EXISTS forum_resources (
 id VARCHAR(36) PRIMARY KEY,
 owner_id VARCHAR(36) NOT NULL REFERENCES forum_users(id),
 purpose VARCHAR(8) NOT NULL,
 kind VARCHAR(8) NOT NULL,
 state VARCHAR(12) NOT NULL,
 mime VARCHAR(80),
 size_bytes BIGINT NOT NULL,
 duration_seconds DOUBLE PRECISION,
 post_id VARCHAR(36) REFERENCES forum_posts(id),
 avatar_user_id VARCHAR(36) REFERENCES forum_users(id),
 ever_bound BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 expires_at BIGINT,
 retired_at BIGINT,
 chunk_size INTEGER,
 parts_total INTEGER,
 processing_claim VARCHAR(36),
 claim_until BIGINT,
 error_code VARCHAR(80)
);
CREATE INDEX IF NOT EXISTS forum_resources_post ON forum_resources(post_id);
CREATE TABLE IF NOT EXISTS forum_chunks (
 resource_id VARCHAR(36) NOT NULL REFERENCES forum_resources(id),
 part_index INTEGER NOT NULL,
 size_bytes INTEGER NOT NULL,
 sha256 VARCHAR(64) NOT NULL,
 PRIMARY KEY(resource_id,part_index)
);
