-- V25: 벤치마크 지수 종가 캐시 테이블 생성
-- 벤치마크 지수(SPY/QQQ) 일별 종가 — Alpaca Market Data lazy backfill

CREATE TABLE market_index_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(10) NOT NULL,
    trade_date DATE NOT NULL,
    close_price NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_index_prices_symbol_date UNIQUE (symbol, trade_date)
);
