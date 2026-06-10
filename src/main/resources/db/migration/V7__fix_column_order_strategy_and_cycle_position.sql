-- V7: strategy, cycle_position 테이블 컬럼 순서 규칙 준수
--     규칙: pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at
--     위반 현황:
--       strategy      — deleted_at 이 created_at/updated_at 앞에 위치
--       cycle_position — deleted_at 이 created_at 앞에 위치

-- ============================================================
-- 1. strategy 테이블 재생성 (deleted_at → created_at/updated_at 뒤로 이동)
-- ============================================================

-- strategy 를 참조하는 FK 먼저 제거
ALTER TABLE strategy_cycle DROP CONSTRAINT strategy_cycle_strategy_id_fkey;

ALTER TABLE strategy RENAME TO strategy_old;
ALTER INDEX strategy_pkey RENAME TO strategy_old_pkey;
DROP INDEX idx_strategy_account_id;

CREATE TABLE strategy (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    ticker          VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    cycle_seed_type VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at, deleted_at)
SELECT id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at, deleted_at
FROM strategy_old;

DROP TABLE strategy_old;

CREATE INDEX idx_strategy_account_id ON strategy(account_id);

ALTER TABLE strategy_cycle ADD CONSTRAINT strategy_cycle_strategy_id_fkey
    FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE;

-- ============================================================
-- 2. cycle_position 테이블 재생성 (deleted_at → created_at 뒤로 이동)
-- ============================================================

ALTER TABLE cycle_position DROP CONSTRAINT cycle_position_strategy_cycle_id_fkey;

ALTER TABLE cycle_position RENAME TO cycle_position_old;
ALTER INDEX cycle_position_pkey RENAME TO cycle_position_old_pkey;
DROP INDEX idx_cycle_position_strategy_cycle_id;

CREATE TABLE cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    closing_price     NUMERIC(12,2),
    avg_price         NUMERIC(20,2),
    holdings          INT           NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, closing_price, avg_price, holdings, created_at, deleted_at)
SELECT id, strategy_cycle_id, usd_deposit, closing_price, avg_price, holdings, created_at, deleted_at
FROM cycle_position_old;

DROP TABLE cycle_position_old;

CREATE INDEX idx_cycle_position_strategy_cycle_id ON cycle_position(strategy_cycle_id);
