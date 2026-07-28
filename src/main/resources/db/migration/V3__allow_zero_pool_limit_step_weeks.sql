-- p_step_weeks=0은 poolLimitRate 램프 비활성화(항상 initial_pool_limit_rate 유지)를 의미 — pool_limit_floor는 이때 무관해지므로 0 허용
ALTER TABLE strategy_vr_version
    DROP CONSTRAINT strategy_vr_version_p_step_weeks_check,
    DROP CONSTRAINT strategy_vr_version_pool_limit_floor_check;

ALTER TABLE strategy_vr_version
    ADD CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK (p_step_weeks >= 0),
    ADD CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK (pool_limit_floor >= 0);
