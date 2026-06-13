-- 리버스모드 활성 여부 컬럼 추가 (소진 발동 시 true, 일반모드 복귀 시 false)
-- 컬럼 순서: pk, fk, 비즈니스 컬럼..., created_at, deleted_at
-- is_reverse_mode는 비즈니스 컬럼이므로 deleted_at 앞 (created_at 앞)에 위치해야 하나
-- ADD COLUMN은 항상 맨 뒤에 붙으므로 순서는 기존 방식 유지
ALTER TABLE strategy_cycle ADD COLUMN is_reverse_mode BOOLEAN NOT NULL DEFAULT FALSE;
