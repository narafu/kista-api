-- PRIVACY 전략 기준 매매표 마스터 — 모든 PRIVACY 계좌가 공유하는 전역 SSOT
CREATE TABLE privacy_trades_master (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date          DATE          NOT NULL,            -- 기준 매매표가 적용되는 거래일
    ticker              VARCHAR(20)   NOT NULL,            -- 대상 종목 (현재 PRIVACY는 SOXL 강제)
    current_cycle_start NUMERIC(12, 4) NOT NULL,           -- 현재 사이클 시작 시점의 기준 가격
    avg_price           NUMERIC(12, 4) NOT NULL,           -- 보유 평단가
    qty                 INT           NOT NULL,            -- 보유 수량
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trades_master_date_ticker UNIQUE (trade_date, ticker)
);

-- PRIVACY 전략 기준 매매표 디테일 — 마스터 1행에 대한 계획 주문 세트
CREATE TABLE privacy_trades_detail (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID          NOT NULL REFERENCES privacy_trades_master(id) ON DELETE CASCADE,
    direction        VARCHAR(5)    NOT NULL,               -- BUY / SELL
    order_type       VARCHAR(10)   NOT NULL,               -- LOC / MOC / LIMIT
    qty              INT           NOT NULL,
    price            NUMERIC(12, 4) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
