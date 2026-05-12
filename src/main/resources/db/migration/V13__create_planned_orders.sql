-- planned_orders: 매매 전략 계산 결과를 저장하는 계획 주문 테이블
-- 전략 계산(plan)과 KIS 접수(execute)를 분리하기 위해 도입
CREATE TABLE planned_orders (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   UUID          NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    trade_date   DATE          NOT NULL,
    symbol       VARCHAR(20)   NOT NULL,
    order_type   VARCHAR(10)   NOT NULL,      -- LOC / MOC / LIMIT
    direction    VARCHAR(5)    NOT NULL,      -- BUY / SELL
    qty          INT           NOT NULL,
    price        NUMERIC(12,4) NOT NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'PENDING', -- PENDING / EXECUTED
    kis_order_id VARCHAR(30),                 -- kisOrderPort.place() 성공 후 설정
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_planned_orders_account_date_status
    ON planned_orders(account_id, trade_date, status);
