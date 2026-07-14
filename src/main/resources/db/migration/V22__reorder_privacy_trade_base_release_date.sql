ALTER TABLE privacy_trade_base_orders
    DROP CONSTRAINT IF EXISTS privacy_trade_base_orders_privacy_trade_id_fkey;

ALTER TABLE privacy_trade_bases
    DROP CONSTRAINT IF EXISTS uq_privacy_trade_bases_release_date_ticker;

ALTER TABLE privacy_trade_bases
    RENAME TO privacy_trade_bases_old;

ALTER INDEX privacy_trade_bases_pkey
    RENAME TO privacy_trade_bases_old_pkey;

CREATE TABLE privacy_trade_bases (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    release_date               DATE          NOT NULL,
    ticker                     VARCHAR(20)   NOT NULL,
    current_cycle_start        NUMERIC(12,2) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12,2) NOT NULL,
    avg_price                  NUMERIC(12,2),
    holdings                   INTEGER       NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trade_bases_release_date_ticker UNIQUE (release_date, ticker)
);

INSERT INTO privacy_trade_bases (
    id,
    release_date,
    ticker,
    current_cycle_start,
    current_cycle_realized_pnl,
    avg_price,
    holdings,
    created_at
)
SELECT
    id,
    release_date,
    ticker,
    current_cycle_start,
    current_cycle_realized_pnl,
    avg_price,
    holdings,
    created_at
FROM privacy_trade_bases_old;

DROP TABLE privacy_trade_bases_old;

ALTER TABLE privacy_trade_base_orders
    ADD CONSTRAINT privacy_trade_base_orders_privacy_trade_id_fkey
        FOREIGN KEY (privacy_trade_id) REFERENCES privacy_trade_bases(id) ON DELETE CASCADE;
