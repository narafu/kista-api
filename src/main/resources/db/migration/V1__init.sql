-- V1: 전체 스키마 초기화 (모든 Entity 기준 최종 구조)
-- 컬럼 순서 규칙: pk → fk → 비즈니스 컬럼 → created_at → updated_at → deleted_at

-- ============================================================
-- users
--   id: 카카오 OAuth UID — DB가 아닌 앱에서 직접 할당 (gen_random_uuid() 없음)
-- ============================================================
CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    kakao_id              VARCHAR(50)  NOT NULL,
    nickname              VARCHAR(100),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',
    telegram_bot_token    VARCHAR(512),                           -- AES-256 암호화 저장
    telegram_chat_id      VARCHAR(50),
    telegram_bot_username VARCHAR(64),
    last_reapplied_at     TIMESTAMPTZ,
    notification_channel  VARCHAR(20)  NOT NULL DEFAULT 'TELEGRAM',
    balance_check_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT users_kakao_id_key UNIQUE (kakao_id)
);

-- ============================================================
-- accounts
-- ============================================================
CREATE TABLE accounts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    nickname            VARCHAR(100) NOT NULL,
    broker              VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    account_no          VARCHAR(512) NOT NULL,                    -- AES-256 암호화 저장 (KIS: "74420614-01" 포함)
    broker_account_code VARCHAR(10),                              -- 브로커 API 보조 식별자 (KIS: NULL, TOSS: accountSeq)
    app_key             VARCHAR(512) NOT NULL,                    -- AES-256 암호화 저장
    secret_key          VARCHAR(512) NOT NULL,                    -- AES-256 암호화 저장
    account_no_hash     VARCHAR(64),                              -- HMAC-SHA256 해시 (전역 중복 체크용)
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE UNIQUE INDEX uq_accounts_account_no_hash
    ON accounts (account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;

-- ============================================================
-- broker_tokens
--   account_id가 PK (계좌별 1개 토큰)
-- ============================================================
CREATE TABLE broker_tokens (
    account_id   UUID        NOT NULL,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT broker_tokens_pkey          PRIMARY KEY (account_id),
    CONSTRAINT broker_tokens_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- ============================================================
-- strategy
-- ============================================================
CREATE TABLE strategy (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID        NOT NULL,
    type            VARCHAR(20) NOT NULL,
    ticker          VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cycle_seed_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    division_count  INTEGER     NOT NULL DEFAULT 20,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_account_id ON strategy(account_id);

-- ============================================================
-- strategy_cycle
-- ============================================================
CREATE TABLE strategy_cycle (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id      UUID          NOT NULL,
    start_amount     NUMERIC(20,2) NOT NULL,
    end_amount       NUMERIC(20,2),
    start_date       DATE          NOT NULL,
    end_date         DATE,
    seed_resolved_by VARCHAR(20)   NOT NULL DEFAULT 'BROKER_VERIFIED',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_cycle_strategy_id ON strategy_cycle(strategy_id);

-- ============================================================
-- cycle_position
-- ============================================================
CREATE TABLE cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    closing_price     NUMERIC(12,2),
    avg_price         NUMERIC(20,2),
    holdings          INTEGER       NOT NULL,
    is_reverse_mode   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

CREATE INDEX idx_cycle_position_strategy_cycle_id ON cycle_position(strategy_cycle_id);

-- ============================================================
-- orders
-- ============================================================
CREATE TABLE orders (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID          NOT NULL,
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,                     -- DB는 UTC(=US 거래일) 저장 — TradeDateConverter 경유
    ticker            VARCHAR(20)   NOT NULL,
    order_type        VARCHAR(10)   NOT NULL,
    direction         VARCHAR(5)    NOT NULL,
    price             NUMERIC(12,2) NOT NULL,
    quantity          INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    external_order_id VARCHAR(50),
    filled_quantity   INTEGER,
    filled_price      NUMERIC(12,2),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_account_id_fkey        FOREIGN KEY (account_id)        REFERENCES accounts(id)       ON DELETE CASCADE,
    CONSTRAINT orders_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

-- ============================================================
-- audit_logs
-- ============================================================
CREATE TABLE audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID        NOT NULL,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id   UUID,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_admin_id_fkey FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
-- privacy_trade_bases  (전역 SSOT — account_id 없음)
-- ============================================================
CREATE TABLE privacy_trade_bases (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date                 DATE          NOT NULL,
    ticker                     VARCHAR(20)   NOT NULL,
    current_cycle_start        NUMERIC(12,2) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12,2) NOT NULL,
    avg_price                  NUMERIC(12,2),
    holdings                   INTEGER       NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trade_bases_date_ticker UNIQUE (trade_date, ticker)
);

-- ============================================================
-- privacy_trade_base_orders
-- ============================================================
CREATE TABLE privacy_trade_base_orders (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID          NOT NULL,
    direction        VARCHAR(5)    NOT NULL,
    order_type       VARCHAR(10)   NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    quantity         INTEGER,                                     -- FIDA 수신 시 수량 미확정 허용
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT privacy_trade_base_orders_privacy_trade_id_fkey
        FOREIGN KEY (privacy_trade_id) REFERENCES privacy_trade_bases(id) ON DELETE CASCADE
);

-- ============================================================
-- us_market_holidays
-- ============================================================
CREATE TABLE us_market_holidays (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT us_market_holidays_trade_date_key UNIQUE (trade_date)
);

-- ============================================================
-- fcm_device_tokens
-- ============================================================
CREATE TABLE fcm_device_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token      TEXT        NOT NULL,
    platform   VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fcm_device_tokens_token_key    UNIQUE (token),
    CONSTRAINT fcm_device_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
