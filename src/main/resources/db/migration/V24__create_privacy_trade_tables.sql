-- PRIVACY 전략 매매 세션 테이블
CREATE TABLE privacy_trade (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date       DATE        NOT NULL,
    ticker           VARCHAR(20) NOT NULL,
    current_cycle_start NUMERIC(12, 4) NOT NULL,
    avg_price        NUMERIC(12, 4) NOT NULL,
    qty              INT         NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- PRIVACY 전략 개별 주문 테이블
CREATE TABLE privacy_trade_order (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID        NOT NULL REFERENCES privacy_trade(id) ON DELETE CASCADE,
    direction        VARCHAR(5)  NOT NULL,
    order_type       VARCHAR(10) NOT NULL,
    qty              INT         NOT NULL,
    price            NUMERIC(12, 4) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
