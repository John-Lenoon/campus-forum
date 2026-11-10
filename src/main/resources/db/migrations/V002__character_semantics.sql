-- Existing early-development databases only; fresh schema.sql already uses CHAR semantics.
BEGIN;
ALTER TABLE forum_users ALTER COLUMN nickname TYPE VARCHAR(32 CHAR);
ALTER TABLE forum_posts ALTER COLUMN title TYPE VARCHAR(100 CHAR);
ALTER TABLE forum_posts ALTER COLUMN rejection_reason TYPE VARCHAR(1000 CHAR);
COMMIT;
