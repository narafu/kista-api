package com.kista.trading.notify.application.port.output;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

// 관리자 텔레그램 알림 — root NotifyPort의 trading-core 판. Gradle 컴파일 경계(root→trading-core만
// 단방향) 때문에 root 타입을 참조할 수 없어 trading-core가 자기 필요분만 좁혀 재정의한다.
// holdings는 root NotifyPort.notifyInsufficientBalance(int, BigDecimal, StrategyTicker)와 동일하게
// int로 둔다 — 브리핑 문서는 BigDecimal로 적었으나 실제 발행 이벤트(InsufficientBalanceEvent.holdings())가
// int라 시그니처를 맞추지 않으면 TradingAlertNotifier에서 컴파일 에러가 난다.
public interface TradingNotifyPort {
    void notifyError(Exception e);
    void notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker);
    void notifyMarketClosed();
}
