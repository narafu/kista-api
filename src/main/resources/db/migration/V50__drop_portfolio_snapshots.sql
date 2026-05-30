-- V50: portfolio_snapshots 테이블 제거
-- trading_cycle_history로 역할 통합 완료 (V49에서 current_price 컬럼 추가됨)
DROP TABLE IF EXISTS portfolio_snapshots;
