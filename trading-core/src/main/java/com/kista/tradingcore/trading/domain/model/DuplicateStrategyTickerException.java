package com.kista.tradingcore.trading.domain.model;

// 같은 계좌에 같은 종목 전략이 이미 있음 — 전 전략 유형 공통(계좌+종목당 전략 1개), 409로 매핑(TradingExceptionHandler가 상위 IllegalStateException 400보다 먼저 처리)
public class DuplicateStrategyTickerException extends IllegalStateException {
    public DuplicateStrategyTickerException() {
        super("같은 계좌에 이미 해당 종목 전략이 있습니다.");
    }
}
