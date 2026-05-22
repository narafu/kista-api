-- notification_channel: 알림 수단 선택 (TELEGRAM / FCM / ALL)
ALTER TABLE users
    ADD COLUMN notification_channel VARCHAR(20) NOT NULL DEFAULT 'TELEGRAM';
