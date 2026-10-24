-- 잔고검증 토글 제거 — 등록·재등록 잔고 검증이 원장 기준으로 바뀌어 복제 필드가 불필요
ALTER TABLE user_notify_profile DROP COLUMN IF EXISTS balance_check_enabled;
