package com.kista.tradingcore.notify.application.port.output;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.TradingReport;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;

import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;

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
    // failedCount>0: 증권사 잔고 부족 거절 BUY 건수, 0: 전략 원장 상한 거절
    void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker,
                                   int failedCount, boolean partiallyPlaced);
    void notifyError(TradingUserProfile profile, Exception e);
    void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname);
    void notifyMarketOpen(TradingUserProfile profile);
    void notifyMarketClose(TradingUserProfile profile);
    // DCA 회차 미주문 사용자 알림
    void notifyDcaRoundSkipped(TradingUserProfile profile, DcaRoundSkippedEvent event);
    // 코인 주문 체결 사용자 알림
    void notifyCryptoTrade(TradingUserProfile profile, CryptoOrderFilledEvent event);
    // 업비트 API 키 만료 임박·당일 알림
    void notifyApiKeyExpiring(TradingUserProfile profile, ApiKeyExpiringEvent event);
}
