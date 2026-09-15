package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.CycleEndedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.NoSuchElementException;
import java.util.UUID;

// 관리자 수동 체결 보정으로 사이클이 종료됨을 트랜잭션 커밋 후 사용자에게 알림 (SSE/FCM/텔레그램 호출을 트랜잭션 밖으로 격리)
// root의 동명 클래스를 이관 — User/UserNotificationPort 대신 TradingUserProfile/TradingUserNotificationPort 사용
@Component
@RequiredArgsConstructor
public class CycleEndedNotifier {

    private final TradingUserNotificationPort userNotificationPort;
    private final TradingUserProfilePort userProfilePort;       // 이벤트 payload가 ID만 담아 실행 시점 재조회

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCycleEnded(CycleEndedEvent event) {
        TradingUserProfile profile = requireProfile(event.userId());
        userNotificationPort.notifyCycleCompleted(profile, event.accountNickname(),
                event.strategyType(), event.ticker(), event.cycleSeedType());
    }

    // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응) — root UserPort.findByIdOrThrow 대체
    private TradingUserProfile requireProfile(UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
