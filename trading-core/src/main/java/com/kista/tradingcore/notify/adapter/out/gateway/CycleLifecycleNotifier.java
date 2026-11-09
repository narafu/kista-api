package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.event.CycleCompletedEvent;
import com.kista.tradingcore.trading.application.event.NewCycleStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 사이클 종료/신규 시작 알림을 채널(Telegram/FCM) 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
// root의 동명 클래스를 이관 — User/UserNotificationPort 대신 TradingUserProfile/TradingUserNotificationPort 사용
// 배치 정상 종료(CycleRotationService.rotate()가 CycleCompletedEvent 발행) — CyclePositionPersistor가 포지션 저장 후 트랜잭션 밖에서 호출하므로 fallbackExecution=true
// CycleEndedNotifier(관리자 수동 체결 보정)와 한 종료 흐름에서 동시 발행되지 않는다
@Component
@RequiredArgsConstructor
class CycleLifecycleNotifier {

    private final TradingUserNotificationPort userNotificationPort;
    private final TradingUserProfilePort userProfilePort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onCycleCompleted(CycleCompletedEvent event) {
        TradingUserProfile profile = TradingUserProfiles.requireProfile(userProfilePort, event.userId());
        userNotificationPort.notifyCycleCompleted(profile, event.accountNickname(),
                event.strategyType(), event.ticker(), event.cycleSeedType());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNewCycleStarted(NewCycleStartedEvent event) {
        TradingUserProfile profile = TradingUserProfiles.requireProfile(userProfilePort, event.userId());
        userNotificationPort.notifyNewCycleStarted(profile, event.accountNickname(),
                event.strategyType(), event.ticker(), event.initialDeposit());
    }
}
