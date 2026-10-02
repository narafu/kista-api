-- 매매 배치 재개 체크포인트 — kista-trading 재기동 시 TradingBatchResumer가 (job, KST 거래일) 단계로 재개 지점을 판정한다
CREATE TABLE trading.trading_batch_run (
    job_name   VARCHAR(50) NOT NULL,
    trade_date DATE        NOT NULL,
    phase      VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_trading_batch_run PRIMARY KEY (job_name, trade_date)
);

-- 전략별 당일 리포트 완료 마커 — 리포트 재개 시 cycle_position 중복 append·리포트 재발송 방지
-- 사이클이 아닌 전략 키: 청산 rotation이 새 사이클을 만들어도 재개 판정이 흔들리지 않도록
CREATE TABLE trading.trading_batch_report (
    trade_date  DATE        NOT NULL,
    strategy_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_trading_batch_report PRIMARY KEY (trade_date, strategy_id)
);
