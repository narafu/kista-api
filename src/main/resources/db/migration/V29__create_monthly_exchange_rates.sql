CREATE TABLE monthly_exchange_rates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(20) NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    base_month DATE NOT NULL,
    exchange_rate_date DATE NOT NULL,
    mid_rate NUMERIC(18,6) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_monthly_exchange_rates_source_pair_month
        UNIQUE (source, base_currency, quote_currency, base_month)
);

CREATE INDEX idx_monthly_exchange_rates_pair_month
    ON monthly_exchange_rates (base_currency, quote_currency, base_month);
