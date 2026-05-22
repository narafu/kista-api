-- strategies 테이블 → trading_cycle 리네임 + initial_usd_deposit 컬럼 추가
ALTER TABLE strategies RENAME TO trading_cycle;
ALTER INDEX IF EXISTS idx_strategies_account_id RENAME TO idx_trading_cycle_account_id;
ALTER TABLE trading_cycle ADD COLUMN initial_usd_deposit NUMERIC(20, 2);
