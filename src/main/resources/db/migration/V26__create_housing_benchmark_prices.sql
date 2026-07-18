-- V26: KB Land 주택 벤치마크 월별 분위 가격 저장 테이블 생성
-- 자연키: source + metric_code + region_code + base_month

CREATE TABLE housing_benchmark_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(20) NOT NULL,
    metric_code VARCHAR(40) NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    region_name VARCHAR(50) NOT NULL,
    base_month DATE NOT NULL,
    first_quintile_price NUMERIC(18, 6) NOT NULL,
    second_quintile_price NUMERIC(18, 6) NOT NULL,
    third_quintile_price NUMERIC(18, 6) NOT NULL,
    fourth_quintile_price NUMERIC(18, 6) NOT NULL,
    fifth_quintile_price NUMERIC(18, 6) NOT NULL,
    fifth_quintile_ratio NUMERIC(18, 12) NOT NULL,
    source_updated_date DATE,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_housing_benchmark_prices_source_metric_region_month
        UNIQUE (source, metric_code, region_code, base_month)
);

CREATE INDEX idx_housing_benchmark_prices_metric_region_month
    ON housing_benchmark_prices (metric_code, region_code, base_month);
