-- ============================================================
-- strategy_cycle / strategy_vr_version 컬럼 순서 재정렬
-- strategy_cycle:
--   id, strategy_id, strategy_version_id, start_amount, end_amount, start_date, end_date, created_at, deleted_at
-- strategy_vr_version:
--   strategy_version_id, interval_weeks, band_width, recurring_amount,
--   initial_gradient, g_grace_weeks, g_step_weeks, g_max,
--   initial_pool_limit_rate, p_grace_weeks, p_step_weeks, pool_limit_floor,
--   created_at, updated_at
-- ============================================================

-- strategy_cycle는 부모 테이블이므로, 참조하는 자식 FK를 먼저 제거한다.
ALTER TABLE cycle_position
    DROP CONSTRAINT IF EXISTS cycle_position_strategy_cycle_id_fkey;

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_strategy_cycle_id_fkey;

ALTER TABLE strategy_cycle_vr
    DROP CONSTRAINT IF EXISTS strategy_cycle_vr_strategy_cycle_id_fkey;

ALTER TABLE strategy_cycle
    RENAME TO strategy_cycle_old;

ALTER INDEX strategy_cycle_pkey
    RENAME TO strategy_cycle_old_pkey;

ALTER TABLE strategy_cycle_old
    RENAME CONSTRAINT strategy_cycle_strategy_id_fkey
        TO strategy_cycle_old_strategy_id_fkey;

ALTER TABLE strategy_cycle_old
    RENAME CONSTRAINT strategy_cycle_strategy_version_id_fkey
        TO strategy_cycle_old_strategy_version_id_fkey;

ALTER INDEX idx_strategy_cycle_strategy_id
    RENAME TO idx_strategy_cycle_old_strategy_id;

CREATE TABLE strategy_cycle (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id        UUID          NOT NULL,
    strategy_version_id UUID          NOT NULL,
    start_amount       NUMERIC(20,2) NOT NULL,
    end_amount         NUMERIC(20,2),
    start_date         DATE          NOT NULL,
    end_date           DATE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE
);

INSERT INTO strategy_cycle (
    id,
    strategy_id,
    strategy_version_id,
    start_amount,
    end_amount,
    start_date,
    end_date,
    created_at,
    deleted_at
)
SELECT
    id,
    strategy_id,
    strategy_version_id,
    start_amount,
    end_amount,
    start_date,
    end_date,
    created_at,
    deleted_at
FROM strategy_cycle_old;

CREATE INDEX idx_strategy_cycle_strategy_id
    ON strategy_cycle(strategy_id);

DROP TABLE strategy_cycle_old;

ALTER TABLE cycle_position
    ADD CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;

ALTER TABLE orders
    ADD CONSTRAINT orders_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;

ALTER TABLE strategy_cycle_vr
    ADD CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;

-- ============================================================
-- strategy_vr_version 재생성
-- ============================================================

ALTER TABLE strategy_vr_version
    RENAME TO strategy_vr_version_old;

ALTER INDEX strategy_vr_version_pkey
    RENAME TO strategy_vr_version_old_pkey;

ALTER TABLE strategy_vr_version_old
    RENAME CONSTRAINT strategy_vr_version_strategy_version_id_fkey
        TO strategy_vr_version_old_strategy_version_id_fkey;

CREATE TABLE strategy_vr_version (
    strategy_version_id        UUID          NOT NULL,
    interval_weeks             INTEGER       NOT NULL,
    band_width                 NUMERIC(20, 2) NOT NULL,
    recurring_amount           INTEGER       NOT NULL,
    initial_gradient           INTEGER       NOT NULL,
    g_grace_weeks              INTEGER       NOT NULL,
    g_step_weeks               INTEGER       NOT NULL,
    g_max                      INTEGER       NOT NULL,
    initial_pool_limit_rate    NUMERIC(6, 4) NOT NULL,
    p_grace_weeks              INTEGER       NOT NULL,
    p_step_weeks               INTEGER       NOT NULL,
    pool_limit_floor           NUMERIC(6, 4) NOT NULL,
    created_at                 TIMESTAMPTZ    NOT NULL,
    updated_at                 TIMESTAMPTZ    NOT NULL,
    CONSTRAINT strategy_vr_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_vr_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_vr_version_interval_weeks_check CHECK (interval_weeks > 0),
    CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK (g_step_weeks > 0),
    CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK (p_step_weeks > 0),
    CONSTRAINT strategy_vr_version_initial_gradient_check CHECK (initial_gradient > 0),
    CONSTRAINT strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient),
    CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK (pool_limit_floor > 0),
    CONSTRAINT strategy_vr_version_pool_limit_floor_le_initial_check CHECK (pool_limit_floor <= initial_pool_limit_rate),
    CONSTRAINT strategy_vr_version_initial_pool_limit_rate_check CHECK (initial_pool_limit_rate <= 1)
);

INSERT INTO strategy_vr_version (
    strategy_version_id,
    interval_weeks,
    band_width,
    recurring_amount,
    initial_gradient,
    g_grace_weeks,
    g_step_weeks,
    g_max,
    initial_pool_limit_rate,
    p_grace_weeks,
    p_step_weeks,
    pool_limit_floor,
    created_at,
    updated_at
)
SELECT
    strategy_version_id,
    interval_weeks,
    band_width,
    recurring_amount,
    initial_gradient,
    g_grace_weeks,
    g_step_weeks,
    g_max,
    initial_pool_limit_rate,
    p_grace_weeks,
    p_step_weeks,
    pool_limit_floor,
    created_at,
    updated_at
FROM strategy_vr_version_old;

DROP TABLE strategy_vr_version_old;
