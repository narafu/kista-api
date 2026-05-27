-- V47: trading_cycle에 cycle_seed_type 추가 (컬럼 순서 정합을 위해 테이블 재생성)
--      trading_cycle_history.holdings NUMERIC → INT 타입 변환
-- 컬럼 순서 목표: id, account_id, type, ticker, status, initial_usd_deposit,
--                 cycle_seed_type, created_at, updated_at, deleted_at

-- ============================================================
-- 1. trading_cycle 재생성
-- ============================================================

ALTER TABLE trading_cycle_history DROP CONSTRAINT trading_cycle_history_trading_cycle_id_fkey;

ALTER TABLE trading_cycle RENAME TO trading_cycle_old;

CREATE TABLE trading_cycle (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID         NOT NULL,
    type                VARCHAR(20)  NOT NULL,
    ticker              VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    initial_usd_deposit NUMERIC(20, 2),
    cycle_seed_type     VARCHAR(10)  NOT NULL DEFAULT 'NONE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT strategies_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

INSERT INTO trading_cycle (id, account_id, type, ticker, status,
                            initial_usd_deposit, cycle_seed_type,
                            created_at, updated_at, deleted_at)
SELECT                      id, account_id, type, ticker, status,
                            initial_usd_deposit, 'NONE',
                            created_at, updated_at, deleted_at
FROM trading_cycle_old;

DROP TABLE trading_cycle_old;

CREATE INDEX idx_trading_cycle_account_id ON trading_cycle(account_id);

ALTER TABLE trading_cycle_history ADD CONSTRAINT trading_cycle_history_trading_cycle_id_fkey
    FOREIGN KEY (trading_cycle_id) REFERENCES trading_cycle(id) ON DELETE CASCADE;

-- ============================================================
-- 2. trading_cycle_history.holdings NUMERIC → INT
-- ============================================================

ALTER TABLE trading_cycle_history
    ALTER COLUMN holdings TYPE INT USING holdings::int;
