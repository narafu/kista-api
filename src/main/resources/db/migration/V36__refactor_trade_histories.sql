-- 1) V8 이전 하위호환 데이터 정리 (V35 TRUNCATE 이후엔 0건 기대)
DELETE FROM trade_histories WHERE account_id IS NULL;

-- 2) 새 구조 테이블 (Entity 필드 선언 순서: id, account_id, ...)
CREATE TABLE trade_histories_new (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    trade_date  DATE             NOT NULL,
    ticker      VARCHAR(20)      NOT NULL,
    strategy    VARCHAR(50)      NOT NULL,
    order_type  VARCHAR(10)      NOT NULL,
    direction   VARCHAR(5)       NOT NULL,
    price       NUMERIC(12, 4)   NOT NULL,
    quantity    INT              NOT NULL,
    amount_usd  NUMERIC(12, 2)   NOT NULL,
    status      VARCHAR(10)      NOT NULL,
    order_id    VARCHAR(30),
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- 3) 기존 데이터 이관 (kis_order_id → order_id)
INSERT INTO trade_histories_new
    (id, account_id, trade_date, ticker, strategy, order_type, direction,
     price, quantity, amount_usd, status, order_id, created_at)
SELECT
    id, account_id, trade_date, ticker, strategy, order_type, direction,
    price, quantity, amount_usd, status, kis_order_id, created_at
FROM trade_histories;

-- 4) 교체
DROP TABLE trade_histories;
ALTER TABLE trade_histories_new RENAME TO trade_histories;
