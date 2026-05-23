-- V42: 컬럼 순서를 JPA Entity 필드 선언 순서에 맞춰 재정렬
-- 대상: users / accounts / kis_tokens / trading_cycle / privacy_trades_master
-- 방식: CREATE TABLE _new + INSERT SELECT + DROP + RENAME (V22/V36 패턴)
-- FK 처리 순서: users → accounts → kis_tokens / trading_cycle → privacy_trades_master

-- ============================================================
-- 1. users
--    현재: id, kakao_id, nickname, status, telegram_bot_token,
--           telegram_chat_id, created_at, updated_at,
--           last_reapplied_at, role, telegram_bot_username, notification_channel
--    목표: id, kakao_id, nickname, status, role,
--           telegram_bot_token, telegram_chat_id, telegram_bot_username,
--           last_reapplied_at, notification_channel, created_at, updated_at
-- ============================================================

ALTER TABLE accounts          DROP CONSTRAINT accounts_user_id_fkey;
ALTER TABLE audit_logs        DROP CONSTRAINT audit_logs_admin_id_fkey;
ALTER TABLE fcm_device_tokens DROP CONSTRAINT fcm_device_tokens_user_id_fkey;

ALTER TABLE users RENAME TO users_old;

CREATE TABLE users (
    id                    UUID        PRIMARY KEY,
    kakao_id              VARCHAR(50) NOT NULL UNIQUE,
    nickname              VARCHAR(100),
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    role                  VARCHAR(20) NOT NULL DEFAULT 'USER',
    telegram_bot_token    VARCHAR(512),
    telegram_chat_id      VARCHAR(50),
    telegram_bot_username VARCHAR(64),
    last_reapplied_at     TIMESTAMPTZ,
    notification_channel  VARCHAR(20) NOT NULL DEFAULT 'TELEGRAM',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO users (id, kakao_id, nickname, status, role, telegram_bot_token, telegram_chat_id,
                   telegram_bot_username, last_reapplied_at, notification_channel, created_at, updated_at)
SELECT             id, kakao_id, nickname, status, role, telegram_bot_token, telegram_chat_id,
                   telegram_bot_username, last_reapplied_at, notification_channel, created_at, updated_at
FROM users_old;

DROP TABLE users_old;

ALTER TABLE accounts          ADD CONSTRAINT accounts_user_id_fkey
    FOREIGN KEY (user_id)  REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE audit_logs        ADD CONSTRAINT audit_logs_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE fcm_device_tokens ADD CONSTRAINT fcm_device_tokens_user_id_fkey
    FOREIGN KEY (user_id)  REFERENCES users(id) ON DELETE CASCADE;

-- ============================================================
-- 2. accounts
--    현재: id, user_id, nickname, account_no, kis_app_key, kis_secret_key,
--           kis_account_type, created_at, updated_at, broker
--    목표: id, user_id, nickname, account_no, kis_app_key, kis_secret_key,
--           kis_account_type, broker, created_at, updated_at
-- ============================================================

ALTER TABLE kis_tokens          DROP CONSTRAINT kis_tokens_account_id_fkey;
ALTER TABLE trade_histories     DROP CONSTRAINT trade_histories_new_account_id_fkey;
ALTER TABLE portfolio_snapshots DROP CONSTRAINT portfolio_snapshots_account_id_fkey;
ALTER TABLE trading_cycle       RENAME CONSTRAINT strategies_account_id_fkey1 TO strategies_account_id_fkey;
ALTER TABLE trading_cycle       DROP CONSTRAINT strategies_account_id_fkey;
ALTER TABLE orders              DROP CONSTRAINT planned_orders_account_id_fkey;

ALTER TABLE accounts RENAME TO accounts_old;

CREATE TABLE accounts (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    nickname         VARCHAR(100) NOT NULL,
    account_no       VARCHAR(512) NOT NULL,
    kis_app_key      VARCHAR(512) NOT NULL,
    kis_secret_key   VARCHAR(512) NOT NULL,
    kis_account_type VARCHAR(10)  NOT NULL DEFAULT '01',
    broker           VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT INTO accounts (id, user_id, nickname, account_no, kis_app_key, kis_secret_key,
                      kis_account_type, broker, created_at, updated_at)
SELECT               id, user_id, nickname, account_no, kis_app_key, kis_secret_key,
                     kis_account_type, broker, created_at, updated_at
FROM accounts_old;

DROP TABLE accounts_old;

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

ALTER TABLE kis_tokens          ADD CONSTRAINT kis_tokens_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE trade_histories     ADD CONSTRAINT trade_histories_new_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE portfolio_snapshots ADD CONSTRAINT portfolio_snapshots_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE trading_cycle       ADD CONSTRAINT strategies_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE orders              ADD CONSTRAINT planned_orders_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

-- ============================================================
-- 3. kis_tokens
--    현재: access_token, expires_at, created_at, account_id(PK), updated_at
--    목표: account_id(PK), access_token, expires_at, created_at, updated_at
-- ============================================================

ALTER TABLE kis_tokens RENAME TO kis_tokens_old;

CREATE TABLE kis_tokens (
    account_id   UUID        PRIMARY KEY,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kis_tokens_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

INSERT INTO kis_tokens (account_id, access_token, expires_at, created_at, updated_at)
SELECT                  account_id, access_token, expires_at, created_at, updated_at
FROM kis_tokens_old;

DROP TABLE kis_tokens_old;

-- ============================================================
-- 4. trading_cycle
--    현재: id, account_id, type, ticker, status, created_at, updated_at,
--           multiple, initial_usd_deposit
--    목표: id, account_id, type, ticker, multiple, status, initial_usd_deposit,
--           created_at, updated_at
-- ============================================================

ALTER TABLE trading_cycle_history DROP CONSTRAINT trading_cycle_history_trading_cycle_id_fkey;

ALTER TABLE trading_cycle RENAME TO trading_cycle_old;

CREATE TABLE trading_cycle (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID          NOT NULL,
    type                VARCHAR(20)   NOT NULL,
    ticker              VARCHAR(20)   NOT NULL,
    multiple            NUMERIC(4, 1) NOT NULL DEFAULT 1.0,
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    initial_usd_deposit NUMERIC(20, 2),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT strategies_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

INSERT INTO trading_cycle (id, account_id, type, ticker, multiple, status,
                            initial_usd_deposit, created_at, updated_at)
SELECT                     id, account_id, type, ticker, multiple, status,
                           initial_usd_deposit, created_at, updated_at
FROM trading_cycle_old;

DROP TABLE trading_cycle_old;

CREATE INDEX idx_trading_cycle_account_id ON trading_cycle(account_id);

ALTER TABLE trading_cycle_history ADD CONSTRAINT trading_cycle_history_trading_cycle_id_fkey
    FOREIGN KEY (trading_cycle_id) REFERENCES trading_cycle(id) ON DELETE CASCADE;

-- ============================================================
-- 5. privacy_trades_master
--    현재: id, trade_date, ticker, current_cycle_start, avg_price,
--           holdings, created_at, current_cycle_realized_pnl
--    목표: id, trade_date, ticker, current_cycle_start, current_cycle_realized_pnl,
--           avg_price, holdings, created_at
-- ============================================================

ALTER TABLE privacy_trades_detail DROP CONSTRAINT privacy_trades_detail_privacy_trade_id_fkey;

ALTER TABLE privacy_trades_master RENAME TO privacy_trades_master_old;

-- 리네임 후에도 제약조건명은 스키마에 남아 새 테이블과 충돌하므로 먼저 제거
ALTER TABLE privacy_trades_master_old DROP CONSTRAINT uq_privacy_trades_master_date_ticker;

CREATE TABLE privacy_trades_master (
    id                         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date                 DATE           NOT NULL,
    ticker                     VARCHAR(20)    NOT NULL,
    current_cycle_start        NUMERIC(12, 4) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12, 4) NOT NULL DEFAULT 0,
    avg_price                  NUMERIC(12, 4),
    holdings                   INT            NOT NULL,
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trades_master_date_ticker UNIQUE (trade_date, ticker)
);

INSERT INTO privacy_trades_master (id, trade_date, ticker, current_cycle_start,
                                   current_cycle_realized_pnl, avg_price, holdings, created_at)
SELECT                             id, trade_date, ticker, current_cycle_start,
                                   current_cycle_realized_pnl, avg_price, holdings, created_at
FROM privacy_trades_master_old;

DROP TABLE privacy_trades_master_old;

ALTER TABLE privacy_trades_detail ADD CONSTRAINT privacy_trades_detail_privacy_trade_id_fkey
    FOREIGN KEY (privacy_trade_id) REFERENCES privacy_trades_master(id) ON DELETE CASCADE;
