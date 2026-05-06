CREATE TYPE strategy_type   AS ENUM ('INFINITE', 'PRIVACY');
CREATE TYPE strategy_status AS ENUM ('ACTIVE', 'PAUSED');

CREATE TABLE accounts (
    id                 UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nickname           VARCHAR(100)    NOT NULL,
    account_no         VARCHAR(255)    NOT NULL,          -- AES-256 암호화
    kis_app_key        VARCHAR(255)    NOT NULL,          -- AES-256 암호화
    kis_secret_key     VARCHAR(255)    NOT NULL,          -- AES-256 암호화
    kis_account_type   VARCHAR(10)     NOT NULL DEFAULT '01',
    strategy           strategy_type   NOT NULL,
    strategy_status    strategy_status NOT NULL DEFAULT 'ACTIVE',
    telegram_bot_token VARCHAR(255),                      -- 계좌별 봇 (optional, AES-256)
    telegram_chat_id   VARCHAR(50),
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);
