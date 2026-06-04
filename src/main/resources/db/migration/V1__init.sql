-- V1: 초기 스키마 (V1~V52 통합 베이스라인)
-- 컬럼 순서는 각 JPA Entity 필드 선언 순서를 따름

-- ============================================================
-- 1. users
--    app이 직접 UUID를 할당 (gen_random_uuid() 없음)
-- ============================================================
CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    kakao_id              VARCHAR(50)  NOT NULL UNIQUE,
    nickname              VARCHAR(100),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',
    telegram_bot_token    VARCHAR(512),              -- AES-256 암호화 저장
    telegram_chat_id      VARCHAR(50),
    telegram_bot_username VARCHAR(64),
    last_reapplied_at     TIMESTAMPTZ,
    notification_channel  VARCHAR(20)  NOT NULL DEFAULT 'TELEGRAM',
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- 2. accounts
-- ============================================================
CREATE TABLE accounts (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    nickname         VARCHAR(100) NOT NULL,
    account_no       VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    kis_app_key      VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    kis_secret_key   VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    kis_account_type VARCHAR(10)  NOT NULL DEFAULT '01',
    broker           VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

-- ============================================================
-- 3. kis_tokens  (account_id가 PK)
-- ============================================================
CREATE TABLE kis_tokens (
    account_id   UUID        PRIMARY KEY,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kis_tokens_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- ============================================================
-- 4. trading_cycle
-- ============================================================
CREATE TABLE trading_cycle (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID          NOT NULL,
    type                VARCHAR(20)   NOT NULL,
    ticker              VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    initial_usd_deposit NUMERIC(20,2),
    cycle_seed_type     VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT trading_cycle_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_trading_cycle_account_id ON trading_cycle(account_id);

-- ============================================================
-- 5. trading_cycle_history
-- ============================================================
CREATE TABLE trading_cycle_history (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trading_cycle_id UUID          NOT NULL,
    usd_deposit      NUMERIC(20,2) NOT NULL,
    current_price    NUMERIC(12,2),          -- 실행 시점 현재가 (PRIVACY·초기 등록 시 null)
    avg_price        NUMERIC(20,2),          -- 평균 매입 단가 (보유수량 0이면 null)
    holdings         INT           NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT trading_cycle_history_trading_cycle_id_fkey
        FOREIGN KEY (trading_cycle_id) REFERENCES trading_cycle(id) ON DELETE CASCADE
);

CREATE INDEX idx_trading_cycle_history_cycle_id ON trading_cycle_history(trading_cycle_id);

-- ============================================================
-- 6. orders
-- ============================================================
CREATE TABLE orders (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   UUID          NOT NULL,
    trade_date   DATE          NOT NULL,     -- UTC(=US 거래일) 저장, 코드는 KST — TradeDateConverter 경유
    ticker       VARCHAR(20)   NOT NULL,
    order_type   VARCHAR(10)   NOT NULL,
    direction    VARCHAR(5)    NOT NULL,
    price        NUMERIC(12,2) NOT NULL,
    quantity     INT           NOT NULL,
    status       VARCHAR(10)   NOT NULL,
    kis_order_id VARCHAR(30),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- ============================================================
-- 7. privacy_trades_master  (전역 SSOT — account_id 없음)
-- ============================================================
CREATE TABLE privacy_trades_master (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date                 DATE          NOT NULL,   -- UTC(=US 거래일) 저장 — TradeDateConverter 경유
    ticker                     VARCHAR(20)   NOT NULL,
    current_cycle_start        NUMERIC(12,2) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12,2) NOT NULL DEFAULT 0,
    avg_price                  NUMERIC(12,2),
    holdings                   INT           NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trades_master_date_ticker UNIQUE (trade_date, ticker)
);

-- ============================================================
-- 8. privacy_trades_detail
-- ============================================================
CREATE TABLE privacy_trades_detail (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID          NOT NULL,
    direction        VARCHAR(5)    NOT NULL,
    order_type       VARCHAR(10)   NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    quantity         INT,                    -- FIDA 수신 시 수량 미확정 허용
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT privacy_trades_detail_privacy_trade_id_fkey
        FOREIGN KEY (privacy_trade_id) REFERENCES privacy_trades_master(id) ON DELETE CASCADE
);

-- ============================================================
-- 9. fcm_device_tokens
-- ============================================================
CREATE TABLE fcm_device_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token      TEXT        NOT NULL UNIQUE,
    platform   VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT fcm_device_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_fcm_device_tokens_user_id ON fcm_device_tokens(user_id);

-- ============================================================
-- 10. audit_logs
-- ============================================================
CREATE TABLE audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID        NOT NULL,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id   UUID,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_admin_id_fkey
        FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_audit_logs_admin_created ON audit_logs(admin_id, created_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs(target_type, target_id);

-- ============================================================
-- 11. us_market_holidays
-- ============================================================
CREATE TABLE us_market_holidays (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_us_market_holidays_date UNIQUE (trade_date)
);
