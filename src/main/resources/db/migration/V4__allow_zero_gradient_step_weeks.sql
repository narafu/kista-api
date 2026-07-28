-- g_step_weeks=0은 gradient 램프 비활성화(항상 initial_gradient 유지)를 의미 — g_max는 이때 무관해지므로 0 허용
ALTER TABLE strategy_vr_version
    DROP CONSTRAINT strategy_vr_version_g_step_weeks_check,
    DROP CONSTRAINT strategy_vr_version_g_max_check;

ALTER TABLE strategy_vr_version
    ADD CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK (g_step_weeks >= 0),
    ADD CONSTRAINT strategy_vr_version_g_max_check
        CHECK (g_step_weeks = 0 OR g_max >= initial_gradient);
