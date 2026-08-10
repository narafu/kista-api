CREATE TABLE housing_price_indices (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    source              VARCHAR(20)     NOT NULL,
    metric_code         VARCHAR(40)     NOT NULL,
    region_code         VARCHAR(20)     NOT NULL,
    region_name         VARCHAR(50)     NOT NULL,
    base_date           DATE            NOT NULL,
    index_value         NUMERIC(18, 12) NOT NULL,
    source_updated_date DATE,
    fetched_at          TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_housing_price_indices_source_metric_region_date
        UNIQUE (source, metric_code, region_code, base_date)
);

CREATE INDEX idx_housing_price_indices_metric_region_date
    ON housing_price_indices(metric_code, region_code, base_date);
