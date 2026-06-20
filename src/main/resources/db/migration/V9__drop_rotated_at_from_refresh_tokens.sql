-- refresh_tokens.rotated_at 컬럼 제거
-- 안정 RT + 슬라이딩 갱신 방식으로 전환: AT 갱신 시 RT를 회전하지 않고
-- expires_at만 슬라이딩 연장 → 드리프트 원천 불가, 강제 로그아웃 제거
ALTER TABLE refresh_tokens DROP COLUMN IF EXISTS rotated_at;
