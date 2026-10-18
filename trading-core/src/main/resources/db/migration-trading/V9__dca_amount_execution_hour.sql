-- DCA 금액을 계좌 통화 단위(amount)로 일반화하고 코인 실행 시각(KST 시)을 추가한다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖(kista-trading stop-first 단일 인스턴스 — 이름 변경과 이미지 교체가 겹치지 않는다).
-- 주의: V8처럼 이 마이그레이션 이후 구 이미지(amount_usd 엔티티)는 ddl-auto validate에서 기동 실패 — 이미지 롤백 불가.
ALTER TABLE trading.strategy_dca_version RENAME COLUMN amount_usd TO amount;
ALTER TABLE trading.strategy_dca_version RENAME CONSTRAINT strategy_dca_version_amount_usd_check TO strategy_dca_version_amount_check;
ALTER TABLE trading.strategy_dca_version ADD COLUMN execution_hour smallint;
ALTER TABLE trading.strategy_dca_version ADD CONSTRAINT strategy_dca_version_execution_hour_check
    CHECK (execution_hour BETWEEN 0 AND 23);
COMMENT ON COLUMN trading.strategy_dca_version.execution_hour IS '코인 DCA 실행 시각(KST 시 0~23) — 주식은 NULL';
