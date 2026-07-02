CREATE TABLE strategy_vr_version (
    strategy_version_id UUID NOT NULL,
    interval_weeks INTEGER NOT NULL,
    band_width NUMERIC(20, 2) NOT NULL,
    recurring_amount INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT strategy_vr_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_vr_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_vr_version_interval_weeks_check CHECK (interval_weeks > 0)
);

CREATE TABLE strategy_cycle_vr (
    strategy_cycle_id UUID NOT NULL,
    value NUMERIC(20, 2) NOT NULL,
    gradient INTEGER NOT NULL,
    pool_limit NUMERIC(20, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT strategy_cycle_vr_pkey PRIMARY KEY (strategy_cycle_id),
    CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_vr_gradient_check CHECK (gradient > 0)
);
