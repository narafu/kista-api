-- accounts 테이블에서 계좌별 텔레그램 알림 컬럼 제거 (V7 생성, V15 길이 확장)
-- 텔레그램 알림은 users 테이블 단일 설정으로 일원화
ALTER TABLE accounts DROP COLUMN telegram_bot_token;
ALTER TABLE accounts DROP COLUMN telegram_chat_id;
