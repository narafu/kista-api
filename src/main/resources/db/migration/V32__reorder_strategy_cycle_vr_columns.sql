-- ============================================================
-- strategy_cycle_vr 컬럼 순서 재정렬
-- pool_limit_rate를 created_at/updated_at 앞에 두어 엔티티 필드 순서와 맞춘다.
-- ============================================================

ALTER TABLE strategy_cycle_vr
    RENAME TO strategy_cycle_vr_old;

ALTER TABLE strategy_cycle_vr_old
    RENAME CONSTRAINT strategy_cycle_vr_pkey TO strategy_cycle_vr_old_pkey;

ALTER TABLE strategy_cycle_vr_old
    RENAME CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        TO strategy_cycle_vr_old_strategy_cycle_id_fkey;

CREATE TABLE strategy_cycle_vr (
    strategy_cycle_id UUID NOT NULL,
    value NUMERIC(20, 2) NOT NULL,
    gradient INTEGER NOT NULL,
    pool_limit_rate NUMERIC(6, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT strategy_cycle_vr_pkey PRIMARY KEY (strategy_cycle_id),
    CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_vr_gradient_check CHECK (gradient > 0),
    CONSTRAINT strategy_cycle_vr_pool_limit_rate_check CHECK (pool_limit_rate > 0 AND pool_limit_rate <= 1)
);

INSERT INTO strategy_cycle_vr (
    strategy_cycle_id,
    value,
    gradient,
    pool_limit_rate,
    created_at,
    updated_at
)
SELECT
    strategy_cycle_id,
    value,
    gradient,
    pool_limit_rate,
    created_at,
    updated_at
FROM strategy_cycle_vr_old;

DROP TABLE strategy_cycle_vr_old;
