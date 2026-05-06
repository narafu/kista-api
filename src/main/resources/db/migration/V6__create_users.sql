CREATE TYPE user_status AS ENUM ('PENDING', 'ACTIVE', 'REJECTED');

CREATE TABLE users (
    id                 UUID        PRIMARY KEY,           -- Supabase Auth UID와 동기화
    kakao_id           VARCHAR(50) NOT NULL UNIQUE,
    nickname           VARCHAR(100),
    status             user_status NOT NULL DEFAULT 'PENDING',
    telegram_bot_token VARCHAR(255),                      -- AES-256 암호화
    telegram_chat_id   VARCHAR(50),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
