ALTER TABLE orders
    ADD COLUMN order_leg VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN';

CREATE INDEX idx_orders_cycle_date_status
    ON orders(strategy_cycle_id, trade_date, status);

CREATE INDEX idx_orders_cycle_date_timing_status
    ON orders(strategy_cycle_id, trade_date, timing, status);

CREATE INDEX idx_orders_account_date_status_direction
    ON orders(account_id, trade_date, status, direction);

CREATE INDEX idx_orders_account_date_ticker_direction_status
    ON orders(account_id, trade_date, ticker, direction, status);
