-- ============================================================
-- orders 테이블에 timing 컬럼 추가
-- 컬럼 순서 규칙(order_type 인접, created_at 앞)을 지키기 위해 테이블 재생성 패턴 사용
-- ============================================================

ALTER TABLE orders RENAME TO orders_old;

-- 기존 named FK 제약이 orders_old에 남아 새 테이블 CREATE 시 충돌하지 않도록 제거
ALTER TABLE orders_old DROP CONSTRAINT orders_account_id_fkey;
ALTER TABLE orders_old DROP CONSTRAINT orders_strategy_cycle_id_fkey;

CREATE TABLE orders (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID          NOT NULL,
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,                     -- DB는 UTC(=US 거래일) 저장 — TradeDateConverter 경유
    ticker            VARCHAR(20)   NOT NULL,
    order_type        VARCHAR(10)   NOT NULL,
    timing            VARCHAR(20)   NOT NULL DEFAULT 'AT_CLOSE', -- AT_CLOSE(마감 배치) / AT_OPEN(개장 선접수)
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

-- 기존 데이터 이전 (timing은 기본값 AT_CLOSE 백필)
INSERT INTO orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing,
                    direction, price, quantity, status, external_order_id,
                    filled_quantity, filled_price, created_at, updated_at)
SELECT id, account_id, strategy_cycle_id, trade_date, ticker, order_type, 'AT_CLOSE',
       direction, price, quantity, status, external_order_id,
       filled_quantity, filled_price, created_at, updated_at
FROM orders_old;

DROP TABLE orders_old;
