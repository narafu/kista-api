-- 미국 시장 휴장일 캐시 테이블 (KIS CTOS5011R 대체, Alpaca Calendar API 배치 적재)
-- 존재 = 휴장; 해당 연도 행이 없으면 MarketCalendarRefreshScheduler가 자동 갱신
CREATE TABLE us_market_holidays
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date DATE        NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_us_market_holidays_date UNIQUE (trade_date)
);
