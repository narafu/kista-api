-- trading_cycle_history: trade_date 컬럼 및 UNIQUE 제약 제거, avg_price nullable 허용
ALTER TABLE trading_cycle_history
    DROP CONSTRAINT uk_trading_cycle_history_cycle_date;

ALTER TABLE trading_cycle_history
    DROP COLUMN trade_date;

ALTER TABLE trading_cycle_history
    ALTER COLUMN avg_price DROP NOT NULL;
