-- 운영전략 추천 목록을 admin 전역 설정(admin_runtime_settings.assetFormOptions)에서
-- 유저별 설정(user_settings)으로 이관. 기존 admin_runtime_settings row의 assetFormOptions 키는
-- Jackson unknown-property 무시로 죽은 값으로 남는다 — 별도 정리 불필요.
ALTER TABLE user_settings ADD COLUMN strategy_suggestions jsonb;

-- admin이 과거 전역 목록을 기본값에서 바꿔둔 적이 있다면(assetFormOptions.strategySuggestions),
-- 기존 user_settings 행에 그 값을 그대로 백필한다 — 없으면(admin_runtime_settings 미생성 등) 아무 것도
-- 하지 않고 애플리케이션 기본값(UserSettings.DEFAULT_STRATEGY_SUGGESTIONS)에 맡긴다.
UPDATE user_settings
SET strategy_suggestions = admin_defaults.suggestions
FROM (
    SELECT setting_value -> 'assetFormOptions' -> 'strategySuggestions' AS suggestions
    FROM admin_runtime_settings
    WHERE setting_key = 'runtime'
) admin_defaults
WHERE admin_defaults.suggestions IS NOT NULL;
