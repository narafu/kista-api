-- trading_cycle_history: 사이클별 일별 잔고 스냅샷 (trading_cycle 1:N)
CREATE TABLE trading_cycle_history (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trading_cycle_id  UUID          NOT NULL REFERENCES trading_cycle(id) ON DELETE CASCADE,
    trade_date        DATE          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    avg_price         NUMERIC(20,4) NOT NULL,
    holdings          NUMERIC(20,4) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_trading_cycle_history_cycle_date UNIQUE (trading_cycle_id, trade_date)
);

CREATE INDEX idx_trading_cycle_history_cycle_id ON trading_cycle_history(trading_cycle_id);
