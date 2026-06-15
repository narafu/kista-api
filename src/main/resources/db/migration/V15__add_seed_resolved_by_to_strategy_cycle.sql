-- 사이클 시드 결정 방식 audit 컬럼 추가
-- BROKER_VERIFIED: 잔고검증 ON → KIS 실잔고 조회 후 결정
-- LEDGER_ONLY: 잔고검증 OFF → 내부 원장 기준 결정
-- USER_INPUT: 전략 등록/수정 시 사용자 직접 입력
ALTER TABLE strategy_cycle
    ADD COLUMN seed_resolved_by VARCHAR(20) NOT NULL DEFAULT 'BROKER_VERIFIED';
