package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.CycleCompletedEvent;
import com.kista.sharedkernel.NewCycleStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.NoSuchElementException;
import java.util.UUID;

// 사이클 종료/신규 시작 알림을 채널(Telegram/FCM) 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
// root의 동명 클래스를 이관 — User/UserNotificationPort 대신 TradingUserProfile/TradingUserNotificationPort 사용
@Component
@RequiredArgsConstructor
class CycleLifecycleNotifier {

    private final TradingUserNotificationPort userNotificationPort;
    private final TradingUserProfilePort userProfilePort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onCycleCompleted(CycleCompletedEvent event) {
        TradingUserProfile profile = requireProfile(event.userId());
        userNotificationPort.notifyCycleCompleted(profile, event.accountNickname(),
                event.strategyType(), event.ticker(), event.cycleSeedType());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNewCycleStarted(NewCycleStartedEvent event) {
        TradingUserProfile profile = requireProfile(event.userId());
        userNotificationPort.notifyNewCycleStarted(profile, event.accountNickname(),
                event.strategyType(), event.ticker(), event.initialUsdDeposit());
    }

    // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응) — root UserPort.findByIdOrThrow 대체
    private TradingUserProfile requireProfile(UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
