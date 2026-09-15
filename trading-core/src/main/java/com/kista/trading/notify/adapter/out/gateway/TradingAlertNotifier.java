package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingNotifyPort;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.BatchInterruptedEvent;
import com.kista.sharedkernel.InsufficientBalanceEvent;
import com.kista.sharedkernel.MarketClosedEvent;
import com.kista.sharedkernel.MarketCloseEvent;
import com.kista.sharedkernel.MarketOpenEvent;
import com.kista.sharedkernel.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.NoSuchElementException;
import java.util.UUID;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종을 구독해 TradingNotifyPort/TradingUserNotificationPort를 호출한다.
// trading의 11개 발행 지점 중 어느 하나도 클래스/메서드에 @Transactional이 없음을 확인했다 — phase=AFTER_COMMIT을
// 단독으로 쓰면 활성 트랜잭션이 없을 때 이벤트가 그냥 버려지므로 phase 미지정 + fallbackExecution=true로 트랜잭션이
// 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다.
// 6개 이벤트 전부 com.kista.sharedkernel 소유 — AccountPort 재조회 없이 event 필드만 소비.
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
            TradingUserProfile profile = requireProfile(event.userId());
            userNotificationPort.notifyError(profile, new RuntimeException(event.message()));
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyInsufficientBalance(event.holdings(), event.usdDeposit(), event.ticker());
        } else {
            TradingUserProfile profile = requireProfile(event.userId());
            userNotificationPort.notifyInsufficientBalance(profile, event.accountNickname(), event.strategyType(), event.ticker());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        userNotificationPort.notifyMarketOpen(requireProfile(event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        userNotificationPort.notifyMarketClose(requireProfile(event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        userNotificationPort.notifyBatchInterrupted(requireProfile(event.userId()), event.accountNickname());
    }

    // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응) — root UserPort.findByIdOrThrow 대체
    private TradingUserProfile requireProfile(UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
