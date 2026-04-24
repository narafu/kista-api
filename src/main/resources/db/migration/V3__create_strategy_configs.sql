CREATE TABLE strategy_configs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol     VARCHAR(20)  NOT NULL,
    strategy   VARCHAR(50)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT true,
    params     JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
