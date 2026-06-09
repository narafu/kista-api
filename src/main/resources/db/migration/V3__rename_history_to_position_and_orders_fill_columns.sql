-- V3: trading_cycle_history → trading_cycle_position rename
--     orders 테이블에 체결 컬럼(filled_quantity, filled_price) 추가 + status VARCHAR(10→20) 확장

-- ============================================================
-- 1. trading_cycle_history → trading_cycle_position
-- ============================================================
ALTER TABLE trading_cycle_history RENAME TO trading_cycle_position;
ALTER INDEX idx_trading_cycle_history_cycle_id RENAME TO idx_trading_cycle_position_cycle_id;

-- ============================================================
-- 2. orders 테이블 재생성
--    status VARCHAR(10) → VARCHAR(20), filled_quantity / filled_price 추가 (created_at 앞)
-- ============================================================
CREATE TABLE orders_new (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID          NOT NULL,
    trade_date      DATE          NOT NULL,
    ticker          VARCHAR(20)   NOT NULL,
    order_type      VARCHAR(10)   NOT NULL,
    direction       VARCHAR(5)    NOT NULL,
    price           NUMERIC(12,2) NOT NULL,
    quantity        INT           NOT NULL,
    status          VARCHAR(20)   NOT NULL,      -- PLANNED/PLACED/FILLED/PARTIALLY_FILLED/FAILED/CANCELLED
    kis_order_id    VARCHAR(30),
    filled_quantity INT,                          -- 체결 수량 (null=미체결)
    filled_price    NUMERIC(12,2),                -- 체결 가중평균가 (null=미체결)
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_new_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

INSERT INTO orders_new (id, account_id, trade_date, ticker, order_type, direction,
                         price, quantity, status, kis_order_id, created_at, updated_at)
SELECT id, account_id, trade_date, ticker, order_type, direction,
       price, quantity, status, kis_order_id, created_at, updated_at
FROM orders;

DROP TABLE orders;
ALTER TABLE orders_new RENAME TO orders;
ALTER TABLE orders RENAME CONSTRAINT orders_new_account_id_fkey TO orders_account_id_fkey;
