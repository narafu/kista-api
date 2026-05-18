CREATE TYPE user_role AS ENUM ('USER', 'ADMIN');

ALTER TABLE users
  ADD COLUMN role user_role NOT NULL DEFAULT 'USER';

CREATE TABLE audit_logs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(64),
  target_id    UUID,
  payload      JSONB,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_admin_created ON audit_logs(admin_id, created_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs(target_type, target_id);
