package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.notify.application.port.output.NotifyPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종을 구독해 기존 NotifyPort/UserNotificationPort 메서드를 그대로 호출한다.
// trading의 11개 발행 지점 중 어느 하나도 클래스/메서드에 @Transactional이 없음을 확인했다 — phase=AFTER_COMMIT을
// 단독으로 쓰면 활성 트랜잭션이 없을 때 이벤트가 그냥 버려지므로(TradingReportNotifier/CycleLifecycleNotifier와
// 동일한 이유로) phase 미지정 + fallbackExecution=true로 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다
@Component
@RequiredArgsConstructor
public class TradingAlertNotifier {

    private final NotifyPort notifyPort;                       // 관리자 알림
    private final UserNotificationPort userNotificationPort;   // 사용자 알림
    private final UserPort userPort;       // Task 4에서 신규 추가 — onMarketOpen/onMarketClose/onBatchInterrupted용
    private final AccountPort accountPort; // Task 4에서 신규 추가 — onBatchInterrupted용

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            User user = userPort.findByIdOrThrow(event.userId());
            userNotificationPort.notifyError(user, new RuntimeException(event.message()));
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        Account account = accountPort.findByIdOrThrow(event.accountId());
        if (event.userId() == null) {
            notifyPort.notifyInsufficientBalance(account, event.b(), event.ticker());
        } else {
            User user = userPort.findByIdOrThrow(event.userId());
            userNotificationPort.notifyInsufficientBalance(user, account, event.strategyType(), event.ticker());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        userNotificationPort.notifyMarketOpen(user);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        userNotificationPort.notifyMarketClose(user);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyBatchInterrupted(user, account);
    }
}
