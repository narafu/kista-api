-- ============================================================
-- strategy_cycle_vr.pool_limit_rate 소수점 둘째 자리까지 저장
-- 기존 값은 반올림해 NUMERIC(6, 2)로 축소한다.
-- ============================================================

ALTER TABLE strategy_cycle_vr
    ALTER COLUMN pool_limit_rate TYPE NUMERIC(6, 2)
    USING ROUND(pool_limit_rate, 2);
