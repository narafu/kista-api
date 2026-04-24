CREATE TABLE portfolio_snapshots (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_date    DATE           NOT NULL,
    symbol           VARCHAR(20)    NOT NULL,
    qty              INT            NOT NULL,
    avg_price        NUMERIC(12, 4) NOT NULL,
    current_price    NUMERIC(12, 4) NOT NULL,
    market_value_usd NUMERIC(12, 2) NOT NULL,
    usd_deposit      NUMERIC(12, 2) NOT NULL,
    total_asset_usd  NUMERIC(12, 2) NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
