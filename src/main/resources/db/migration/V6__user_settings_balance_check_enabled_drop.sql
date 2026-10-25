-- contract-ok: 전략별 자기 원장 상한 전환(0e8a6847)에서 코드 참조를 이미 제거했고 kista-api·kista-scheduler 두 role 모두 그 이후 이미지(eb8b11be)로 운영 중 — 직전 이미지도 이 컬럼을 읽지 않는다
-- expand/contract의 contract 단계: 잔고 확인 토글은 전략 원장 상한으로 대체돼 더 쓰지 않는다
ALTER TABLE public.user_settings DROP COLUMN IF EXISTS balance_check_enabled;
