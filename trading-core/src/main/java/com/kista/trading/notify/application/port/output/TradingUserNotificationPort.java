package com.kista.trading.notify.application.port.output;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingReport;
import com.kista.trading.domain.model.TradingUserProfile;

import java.math.BigDecimal;

// root UserNotificationPort의 매매 관련 8개 메서드를 trading-core 소유로 재정의 — User 도메인 객체
// 대신 trading-core가 실제로 가진 TradingUserProfile을 받는다(실측: User는 root 전용, trading-core는
// 소유하지 않음). 구현체(TradingUserNotificationAdapter)는 후속 태스크가 채운다.
public interface TradingUserNotificationPort {
    void notifyTradingReport(TradingUserProfile profile, String accountNickname, TradingReport report);
    void notifyCycleCompleted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                               StrategyTicker ticker, StrategyCycleSeedType cycleSeedType);
    void notifyNewCycleStarted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                               StrategyTicker ticker, BigDecimal initialUsdDeposit);
    void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker);
    void notifyError(TradingUserProfile profile, Exception e);
    void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname);
    void notifyMarketOpen(TradingUserProfile profile);
    void notifyMarketClose(TradingUserProfile profile);
}
