-- 텔레그램 봇 username 저장 (저장 시 getMe API 호출로 자동 취득, 평문 저장)
ALTER TABLE users ADD COLUMN telegram_bot_username VARCHAR(64);
