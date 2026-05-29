-- privacy_trades_detail.price
ALTER TABLE privacy_trades_detail
    ALTER COLUMN price TYPE NUMERIC(12, 2) USING price::numeric(12, 2);

-- privacy_trades_master (현재사이클시작가, 실현손익, 평단가)
ALTER TABLE privacy_trades_master
    ALTER COLUMN current_cycle_start TYPE NUMERIC(12, 2) USING current_cycle_start::numeric(12, 2),
    ALTER COLUMN current_cycle_realized_pnl TYPE NUMERIC(12, 2) USING current_cycle_realized_pnl::numeric(12, 2),
    ALTER COLUMN avg_price TYPE NUMERIC(12, 2) USING avg_price::numeric(12, 2);

-- orders.price
ALTER TABLE orders
    ALTER COLUMN price TYPE NUMERIC(12, 2) USING price::numeric(12, 2);

-- portfolio_snapshots.avg_price
ALTER TABLE portfolio_snapshots
    ALTER COLUMN avg_price TYPE NUMERIC(12, 2) USING avg_price::numeric(12, 2);

-- trade_histories.price
ALTER TABLE trade_histories
    ALTER COLUMN price TYPE NUMERIC(12, 2) USING price::numeric(12, 2);

-- trading_cycle_history.avg_price (precision 20 유지)
ALTER TABLE trading_cycle_history
    ALTER COLUMN avg_price TYPE NUMERIC(20, 2) USING avg_price::numeric(20, 2);
