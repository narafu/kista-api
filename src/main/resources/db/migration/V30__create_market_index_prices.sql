-- V30: 벤치마크 ETF(SPY/QQQ/QLD/IBIT) 지수 종가 테이블 재생성
-- V25에서 생성 후 V27에서 제거되었던 테이블을 벤치마크 비교 확장을 위해 다시 생성한다.
-- 매일 06:00 KST 스케줄러가 선동기화하며, 비교 요청 읽기 경로는 이 테이블만 조회한다.

CREATE TABLE market_index_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(10) NOT NULL,
    trade_date DATE NOT NULL,
    close_price NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_index_prices_symbol_date UNIQUE (symbol, trade_date)
);
