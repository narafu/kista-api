-- V31: VR gradient(G)·poolLimitRate 경과주수 기반 램프 도입
-- strategy_vr_version에 램프 파라미터 8개 컬럼 추가, strategy_cycle_vr는 고정 금액(pool_limit) → 고정 비율(pool_limit_rate)로 전환한다.
-- ADD COLUMN은 항상 맨 뒤에 붙으므로(PostgreSQL 제약), 이미 존재하는 두 테이블은 재생성 없이 컬럼 추가만 진행한다.

-- ============================================================
-- strategy_vr_version: 램프 파라미터 8개 컬럼 추가
-- ============================================================
ALTER TABLE strategy_vr_version
    ADD COLUMN initial_gradient          INTEGER,
    ADD COLUMN g_grace_weeks             INTEGER,
    ADD COLUMN g_step_weeks              INTEGER,
    ADD COLUMN g_max                     INTEGER,
    ADD COLUMN initial_pool_limit_rate   NUMERIC(6, 4),
    ADD COLUMN p_grace_weeks             INTEGER,
    ADD COLUMN p_step_weeks              INTEGER,
    ADD COLUMN pool_limit_floor          NUMERIC(6, 4);

-- backfill: 기존 gradient()/poolLimitRate() 파생값을 램프 시작값으로 채우고, grace/step은 기본 정책(52/26주)으로,
-- gMax/poolLimitFloor는 초기값 그대로(=변화 없음) 고정해 기존 계약을 그대로 유지한다.
UPDATE strategy_vr_version
SET initial_gradient        = CASE WHEN recurring_amount < 0 THEN 20 ELSE 10 END,
    g_grace_weeks           = 52,
    g_step_weeks            = 26,
    initial_pool_limit_rate = CASE
                                   WHEN recurring_amount > 0 THEN 0.75
                                   WHEN recurring_amount = 0 THEN 0.50
                                   ELSE 0.25
                               END,
    p_grace_weeks           = 52,
    p_step_weeks            = 26;

-- gMax/poolLimitFloor는 initial 값과 동일하게 고정 — 기존 사용자는 램프가 시작돼도 값이 변하지 않음
UPDATE strategy_vr_version
SET g_max          = initial_gradient,
    pool_limit_floor = initial_pool_limit_rate;

ALTER TABLE strategy_vr_version
    ALTER COLUMN initial_gradient          SET NOT NULL,
    ALTER COLUMN g_grace_weeks             SET NOT NULL,
    ALTER COLUMN g_step_weeks              SET NOT NULL,
    ALTER COLUMN g_max                     SET NOT NULL,
    ALTER COLUMN initial_pool_limit_rate   SET NOT NULL,
    ALTER COLUMN p_grace_weeks             SET NOT NULL,
    ALTER COLUMN p_step_weeks              SET NOT NULL,
    ALTER COLUMN pool_limit_floor          SET NOT NULL;

ALTER TABLE strategy_vr_version
    ADD CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK (g_step_weeks > 0),
    ADD CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK (p_step_weeks > 0),
    ADD CONSTRAINT strategy_vr_version_initial_gradient_check CHECK (initial_gradient > 0),
    ADD CONSTRAINT strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient),
    ADD CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK (pool_limit_floor > 0),
    ADD CONSTRAINT strategy_vr_version_pool_limit_floor_le_initial_check CHECK (pool_limit_floor <= initial_pool_limit_rate),
    ADD CONSTRAINT strategy_vr_version_initial_pool_limit_rate_check CHECK (initial_pool_limit_rate <= 1);

-- ============================================================
-- strategy_cycle_vr: pool_limit(고정 금액) → pool_limit_rate(고정 비율) 전환
-- ============================================================
ALTER TABLE strategy_cycle_vr
    ADD COLUMN pool_limit_rate NUMERIC(6, 4);

-- backfill: strategy_cycle.start_amount(사이클 시작 시드)로 나눠 비율을 역산한다.
-- start_amount가 0/NULL인 예외 케이스(청산 후 재등록 등)는 strategy_vr_version.recurring_amount 부호 파생 기본값으로 대체한다.
UPDATE strategy_cycle_vr scv
SET pool_limit_rate = CASE
                           WHEN sc.start_amount IS NULL OR sc.start_amount = 0 THEN
                               CASE
                                   WHEN svv.recurring_amount > 0 THEN 0.75
                                   WHEN svv.recurring_amount = 0 THEN 0.50
                                   ELSE 0.25
                               END
                           ELSE ROUND(scv.pool_limit / sc.start_amount, 4)
                       END
FROM strategy_cycle sc
JOIN strategy_vr_version svv ON svv.strategy_version_id = sc.strategy_version_id
WHERE sc.id = scv.strategy_cycle_id;

ALTER TABLE strategy_cycle_vr
    ALTER COLUMN pool_limit_rate SET NOT NULL;

ALTER TABLE strategy_cycle_vr
    ADD CONSTRAINT strategy_cycle_vr_pool_limit_rate_check CHECK (pool_limit_rate > 0 AND pool_limit_rate <= 1);

ALTER TABLE strategy_cycle_vr
    DROP COLUMN pool_limit;
