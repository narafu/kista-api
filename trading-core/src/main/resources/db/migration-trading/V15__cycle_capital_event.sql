CREATE TABLE trading.cycle_capital_event (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID NOT NULL,
    cycle_position_id UUID NOT NULL,
    kind              VARCHAR(20) NOT NULL,
    deposit_delta     NUMERIC(20,2) NOT NULL,
    holdings_delta    NUMERIC(20,8) NOT NULL,
    principal_delta   NUMERIC(20,2) NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cycle_capital_event_strategy_cycle FOREIGN KEY (strategy_cycle_id)
        REFERENCES trading.strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT fk_cycle_capital_event_cycle_position FOREIGN KEY (cycle_position_id)
        REFERENCES trading.cycle_position(id) ON DELETE CASCADE
);
CREATE INDEX idx_cycle_capital_event_strategy_cycle_id ON trading.cycle_capital_event (strategy_cycle_id);
