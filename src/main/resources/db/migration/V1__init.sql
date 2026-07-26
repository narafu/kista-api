-- V1: current schema baseline
-- Column order: pk -> fk -> business columns -> created_at -> updated_at -> deleted_at

CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    kakao_id              VARCHAR(50)  NOT NULL,
    nickname              VARCHAR(100),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',
    telegram_bot_token    VARCHAR(512),
    telegram_chat_id      VARCHAR(50),
    telegram_bot_username VARCHAR(64),
    reject_reason         TEXT,
    last_reapplied_at     TIMESTAMPTZ,
    notification_channel  VARCHAR(20)  NOT NULL DEFAULT 'TELEGRAM',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_kakao_id_active
    ON users(kakao_id)
    WHERE deleted_at IS NULL;

CREATE TABLE accounts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    nickname            VARCHAR(100) NOT NULL,
    broker              VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    account_no          VARCHAR(512) NOT NULL,
    broker_account_code VARCHAR(10),
    app_key             VARCHAR(512) NOT NULL,
    secret_key          VARCHAR(512) NOT NULL,
    account_no_hash     VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

CREATE UNIQUE INDEX uq_accounts_account_no_hash
    ON accounts(account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;

CREATE TABLE broker_tokens (
    account_id   UUID        NOT NULL,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT broker_tokens_pkey PRIMARY KEY (account_id),
    CONSTRAINT broker_tokens_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    user_agent VARCHAR(512),
    expires_at TIMESTAMPTZ  NOT NULL,
    rotated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

CREATE TABLE user_settings (
    user_id               UUID    NOT NULL,
    balance_check_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT user_settings_pkey PRIMARY KEY (user_id),
    CONSTRAINT user_settings_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE user_notification_prefs (
    user_id UUID        NOT NULL,
    type    VARCHAR(50) NOT NULL,
    enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT user_notification_prefs_pkey PRIMARY KEY (user_id, type),
    CONSTRAINT user_notification_prefs_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE strategy (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID        NOT NULL,
    type            VARCHAR(20) NOT NULL,
    ticker          VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cycle_seed_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_account_id ON strategy(account_id);

CREATE TABLE strategy_version (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID        NOT NULL,
    version_no  INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT strategy_version_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_strategy_version_strategy_version_no
    ON strategy_version(strategy_id, version_no);

CREATE TABLE strategy_infinite_version (
    strategy_version_id UUID        NOT NULL,
    division_count      INTEGER     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT strategy_infinite_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_infinite_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE
);

CREATE TABLE strategy_vr_version (
    strategy_version_id     UUID           NOT NULL,
    interval_weeks          INTEGER        NOT NULL,
    band_width              NUMERIC(20, 2) NOT NULL,
    recurring_amount        INTEGER        NOT NULL,
    initial_gradient        INTEGER        NOT NULL,
    g_grace_weeks           INTEGER        NOT NULL,
    g_step_weeks            INTEGER        NOT NULL,
    g_max                   INTEGER        NOT NULL,
    initial_pool_limit_rate NUMERIC(6, 4)  NOT NULL,
    p_grace_weeks           INTEGER        NOT NULL,
    p_step_weeks            INTEGER        NOT NULL,
    pool_limit_floor        NUMERIC(6, 4)  NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT strategy_vr_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_vr_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_vr_version_interval_weeks_check CHECK (interval_weeks > 0),
    CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK (g_step_weeks > 0),
    CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK (p_step_weeks > 0),
    CONSTRAINT strategy_vr_version_initial_gradient_check CHECK (initial_gradient > 0),
    CONSTRAINT strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient),
    CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK (pool_limit_floor > 0),
    CONSTRAINT strategy_vr_version_pool_limit_floor_le_initial_check CHECK (pool_limit_floor <= initial_pool_limit_rate),
    CONSTRAINT strategy_vr_version_initial_pool_limit_rate_check CHECK (initial_pool_limit_rate <= 1)
);

CREATE TABLE strategy_cycle (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id         UUID          NOT NULL,
    strategy_version_id UUID          NOT NULL,
    start_amount        NUMERIC(20,2) NOT NULL,
    end_amount          NUMERIC(20,2),
    start_date          DATE          NOT NULL,
    end_date            DATE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_cycle_strategy_id ON strategy_cycle(strategy_id);

CREATE TABLE strategy_cycle_vr (
    strategy_cycle_id UUID           NOT NULL,
    value             NUMERIC(20, 2) NOT NULL,
    gradient          INTEGER        NOT NULL,
    pool_limit_rate   NUMERIC(6, 2)  NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT strategy_cycle_vr_pkey PRIMARY KEY (strategy_cycle_id),
    CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_vr_gradient_check CHECK (gradient > 0),
    CONSTRAINT strategy_cycle_vr_pool_limit_rate_check CHECK (pool_limit_rate > 0 AND pool_limit_rate <= 1)
);

CREATE TABLE cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    closing_price     NUMERIC(12,2),
    avg_price         NUMERIC(20,2),
    holdings          INTEGER       NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

CREATE INDEX idx_cycle_position_strategy_cycle_id ON cycle_position(strategy_cycle_id);

CREATE TABLE cycle_position_infinite (
    cycle_position_id UUID        NOT NULL,
    is_reverse_mode   BOOLEAN     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_infinite_pkey PRIMARY KEY (cycle_position_id),
    CONSTRAINT cycle_position_infinite_cycle_position_id_fkey
        FOREIGN KEY (cycle_position_id) REFERENCES cycle_position(id) ON DELETE CASCADE
);

CREATE TABLE orders (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID          NOT NULL,
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,
    ticker            VARCHAR(20)   NOT NULL,
    order_type        VARCHAR(10)   NOT NULL,
    timing            VARCHAR(20)   NOT NULL DEFAULT 'AT_CLOSE',
    direction         VARCHAR(5)    NOT NULL,
    order_leg         VARCHAR(50)   NOT NULL DEFAULT 'UNKNOWN',
    price             NUMERIC(12,2) NOT NULL,
    quantity          INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    external_order_id VARCHAR(255),
    filled_quantity   INTEGER,
    filled_price      NUMERIC(12,2),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT orders_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

COMMENT ON COLUMN orders.trade_date IS 'KST 거래일 — 매매가 실행·정산되는 KST 아침이 속한 날';

CREATE INDEX idx_orders_cycle_date_status
    ON orders(strategy_cycle_id, trade_date, status);

CREATE INDEX idx_orders_cycle_date_timing_status
    ON orders(strategy_cycle_id, trade_date, timing, status);

CREATE INDEX idx_orders_account_date_status_direction
    ON orders(account_id, trade_date, status, direction);

CREATE INDEX idx_orders_account_date_ticker_direction_status
    ON orders(account_id, trade_date, ticker, direction, status);

CREATE TABLE audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id   UUID,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_admin_id_fkey FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE app_error_logs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    error_type  VARCHAR(255) NOT NULL,
    message     TEXT,
    stack_trace TEXT,
    context     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_app_error_logs_created_at ON app_error_logs(created_at DESC);

CREATE TABLE privacy_trade_bases (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    release_date               DATE          NOT NULL,
    ticker                     VARCHAR(20)   NOT NULL,
    current_cycle_start        NUMERIC(12,2) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12,2) NOT NULL,
    avg_price                  NUMERIC(12,2),
    holdings                   INTEGER       NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trade_bases_release_date_ticker UNIQUE (release_date, ticker)
);

CREATE TABLE privacy_trade_base_orders (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID          NOT NULL,
    direction        VARCHAR(5)    NOT NULL,
    order_type       VARCHAR(10)   NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    quantity         INTEGER,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT privacy_trade_base_orders_privacy_trade_id_fkey
        FOREIGN KEY (privacy_trade_id) REFERENCES privacy_trade_bases(id) ON DELETE CASCADE
);

CREATE TABLE us_market_holidays (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT us_market_holidays_trade_date_key UNIQUE (trade_date)
);

CREATE TABLE fcm_device_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token      TEXT        NOT NULL,
    platform   VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fcm_device_tokens_token_key UNIQUE (token),
    CONSTRAINT fcm_device_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fcm_device_tokens_user_platform_key UNIQUE (user_id, platform)
);

CREATE TABLE fear_greed_snapshots (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source        VARCHAR(20) NOT NULL,
    snapshot_date TIMESTAMPTZ NOT NULL,
    value         INTEGER     NOT NULL,
    rating        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fear_greed_source_date UNIQUE (source, snapshot_date)
);

CREATE TABLE scheduler_locks (
    name       VARCHAR(100) PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

CREATE INDEX idx_scheduler_locks_lock_until ON scheduler_locks(lock_until);

CREATE TABLE admin_runtime_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO admin_runtime_settings (setting_key, setting_value)
VALUES ('runtime', '{
  "approvalRequired": true,
  "brokers": {
    "KIS": {"enabled": true},
    "TOSS": {"enabled": true}
  },
  "strategies": {
    "INFINITE": {
      "enabled": true,
      "ticker": {"customizable": true, "allowedValues": ["MAGX", "USD", "TQQQ", "SOXL"], "defaultValue": "SOXL"},
      "divisionCount": {"customizable": true, "allowedValues": [20, 30, 40], "defaultValue": 20},
      "recurringMode": null,
      "bandWidth": null,
      "intervalWeeks": null
    },
    "PRIVACY": {
      "enabled": true,
      "ticker": {"customizable": false, "allowedValues": ["SOXL"], "defaultValue": "SOXL"},
      "divisionCount": null,
      "recurringMode": null,
      "bandWidth": null,
      "intervalWeeks": null
    },
    "VR": {
      "enabled": true,
      "ticker": {"customizable": false, "allowedValues": ["TQQQ"], "defaultValue": "TQQQ"},
      "divisionCount": null,
      "recurringMode": {"customizable": true, "allowedValues": ["DEPOSIT", "HOLD", "WITHDRAW"], "defaultValue": "HOLD"},
      "bandWidth": {"customizable": true, "allowedValues": [10, 15, 20], "defaultValue": 15},
      "intervalWeeks": {"customizable": true, "allowedValues": [1, 2, 4], "defaultValue": 2}
    }
  }
}'::jsonb);

CREATE TABLE housing_benchmark_prices (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    source                VARCHAR(20)    NOT NULL,
    metric_code           VARCHAR(40)    NOT NULL,
    region_code           VARCHAR(20)    NOT NULL,
    region_name           VARCHAR(50)    NOT NULL,
    base_month            DATE           NOT NULL,
    first_quintile_price  NUMERIC(18, 6) NOT NULL,
    second_quintile_price NUMERIC(18, 6) NOT NULL,
    third_quintile_price  NUMERIC(18, 6) NOT NULL,
    fourth_quintile_price NUMERIC(18, 6) NOT NULL,
    fifth_quintile_price  NUMERIC(18, 6) NOT NULL,
    fifth_quintile_ratio  NUMERIC(18, 12) NOT NULL,
    source_updated_date   DATE,
    fetched_at            TIMESTAMPTZ    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_housing_benchmark_prices_source_metric_region_month
        UNIQUE (source, metric_code, region_code, base_month)
);

CREATE INDEX idx_housing_benchmark_prices_metric_region_month
    ON housing_benchmark_prices(metric_code, region_code, base_month);

CREATE TABLE market_index_prices (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol      VARCHAR(10)   NOT NULL,
    trade_date  DATE          NOT NULL,
    close_price NUMERIC(12,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_index_prices_symbol_date UNIQUE (symbol, trade_date)
);
