CREATE TABLE trade_histories (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date   DATE             NOT NULL,
    symbol       VARCHAR(20)      NOT NULL,
    strategy     VARCHAR(50)      NOT NULL,
    order_type   VARCHAR(10)      NOT NULL,
    direction    VARCHAR(5)       NOT NULL,
    qty          INT              NOT NULL,
    price        NUMERIC(12, 4)   NOT NULL,
    amount_usd   NUMERIC(12, 2)   NOT NULL,
    status       VARCHAR(10)      NOT NULL,
    kis_order_id VARCHAR(30),
    phase        VARCHAR(20)      NOT NULL,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now()
);
