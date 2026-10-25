package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.notify.application.port.output.TradingNotifyPort;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.event.BatchInterruptedEvent;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.event.MarketClosedEvent;
import com.kista.tradingcore.trading.application.event.MarketCloseEvent;
import com.kista.tradingcore.trading.application.event.MarketOpenEvent;
import com.kista.tradingcore.trading.application.event.StrategyAutoPausedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// trading이 발행하는 관리자/사용자 알림 이벤트 8종을 구독해 TradingNotifyPort/TradingUserNotificationPort를 호출한다.
// trading의 12개 발행 지점 중 어느 하나도 클래스/메서드에 @Transactional이 없음을 확인했다 — phase=AFTER_COMMIT을
// 단독으로 쓰면 활성 트랜잭션이 없을 때 이벤트가 그냥 버려지므로 phase 미지정 + fallbackExecution=true로 트랜잭션이
// 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다.
// 8개 이벤트 전부 com.kista.shared.kernel 소유 — AccountPort 재조회 없이 event 필드만 소비.
// root의 동명 클래스를 이 모듈로 이관 — User/UserNotificationPort/NotifyPort 대신 trading-core 소유
// TradingUserProfile/TradingUserNotificationPort/TradingNotifyPort를 쓴다(프로세스 분리 후 알림이
// 발행자와 같은 프로세스에서 발송되도록 하기 위함).
@Component
@RequiredArgsConstructor
public class TradingAlertNotifier {

    private final TradingNotifyPort notifyPort;                       // 관리자 알림
    private final TradingUserNotificationPort userNotificationPort;   // 사용자 알림
    private final TradingUserProfilePort userProfilePort;             // onMarketOpen/onMarketClose/onBatchInterrupted용

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            TradingUserProfile profile = TradingUserProfiles.requireProfile(userProfilePort, event.userId());
            userNotificationPort.notifyError(profile, new RuntimeException(event.message()));
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyInsufficientBalance(event.holdings(), event.usdDeposit(), event.ticker());
        } else {
            TradingUserProfile profile = TradingUserProfiles.requireProfile(userProfilePort, event.userId());
            userNotificationPort.notifyInsufficientBalance(profile, event.accountNickname(), event.strategyType(), event.ticker(),
                    event.failedCount(), event.partiallyPlaced());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        userNotificationPort.notifyMarketOpen(TradingUserProfiles.requireProfile(userProfilePort, event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        userNotificationPort.notifyMarketClose(TradingUserProfiles.requireProfile(userProfilePort, event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onDcaRoundSkipped(DcaRoundSkippedEvent event) {
        userNotificationPort.notifyDcaRoundSkipped(TradingUserProfiles.requireProfile(userProfilePort, event.userId()), event);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        userNotificationPort.notifyBatchInterrupted(
                TradingUserProfiles.requireProfile(userProfilePort, event.userId()), event.accountNickname());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onStrategyAutoPaused(StrategyAutoPausedEvent event) {
        userNotificationPort.notifyStrategyAutoPaused(TradingUserProfiles.requireProfile(userProfilePort, event.userId()),
                event.accountNickname(), event.strategyType(), event.ticker(), event.seed(), event.price());
    }
}
