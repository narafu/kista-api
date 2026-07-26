-- pool_limit_floor는 5%p(0.05) 단위로만 변하고 initial_pool_limit_rate 기본값도 0.75/0.50/0.25 — 소수 넷째자리 불필요
ALTER TABLE strategy_vr_version
    ALTER COLUMN initial_pool_limit_rate TYPE NUMERIC(6, 2) USING ROUND(initial_pool_limit_rate, 2),
    ALTER COLUMN pool_limit_floor TYPE NUMERIC(6, 2) USING ROUND(pool_limit_floor, 2);
