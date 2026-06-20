-- user_settings: users.balance_check_enabled 이전
CREATE TABLE user_settings (
    user_id               BIGINT PRIMARY KEY REFERENCES users(id),
    balance_check_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO user_settings (user_id, balance_check_enabled)
SELECT id, balance_check_enabled FROM users;

ALTER TABLE users DROP COLUMN balance_check_enabled;

-- user_notification_prefs: 알림 타입별 on/off
CREATE TABLE user_notification_prefs (
    user_id BIGINT      NOT NULL REFERENCES users(id),
    type    VARCHAR(50) NOT NULL,
    enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id, type)
);
