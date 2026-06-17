-- audit_logs.admin_id: ON DELETE CASCADE → SET NULL
-- 관리자 계정이 삭제돼도 감사 로그는 보존 (admin_id = NULL으로 유지)
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_admin_id_fkey;
ALTER TABLE audit_logs ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE SET NULL;
