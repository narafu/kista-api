-- scheduler_locks: clustered scheduler execution guard
CREATE TABLE scheduler_locks (
    name       VARCHAR(100) PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

CREATE INDEX idx_scheduler_locks_lock_until ON scheduler_locks (lock_until);

-- Normalize existing FCM platform values before adding a per-platform uniqueness guard.
UPDATE fcm_device_tokens
   SET platform = upper(trim(platform));

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY user_id, platform
               ORDER BY created_at DESC, id DESC
           ) AS rn
      FROM fcm_device_tokens
)
DELETE FROM fcm_device_tokens t
 USING ranked r
 WHERE t.id = r.id
   AND r.rn > 1;

ALTER TABLE fcm_device_tokens
    ADD CONSTRAINT fcm_device_tokens_user_platform_key UNIQUE (user_id, platform);
