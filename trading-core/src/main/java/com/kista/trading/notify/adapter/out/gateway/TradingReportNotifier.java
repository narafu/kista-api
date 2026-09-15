package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.trading.notify.domain.model.TradeEventView;
import com.kista.sharedkernel.TradingReportReadyEvent;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.TradeLegSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.NoSuchElementException;
import java.util.UUID;

// 매매 리포트 알림(Telegram/FCM)과 체결 건별 SSE 알림을 채널 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
// root의 동명 클래스를 이관 — User/UserNotificationPort/RealtimeNotificationPort 대신 TradingUserProfile/
// TradingUserNotificationPort/TradingRealtimeNotificationPort 사용(구현체는 후속 태스크에서 채움)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReportNotifier {

    private final TradingUserNotificationPort userNotificationPort;         // 리포트 알림 발송
    private final TradingRealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final TradingUserProfilePort userProfilePort;                   // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응)

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingReportReady(TradingReportReadyEvent event) {
        TradingUserProfile profile = requireProfile(event.userId());

        // TRADING_ALERT 알림 활성 여부에 따라 리포트 발송 여부 결정 (기본값 true)
        if (event.reportEnabled()) {
            userNotificationPort.notifyTradingReport(profile, event.accountNickname(), event.report());
            log.info("[{}] 리포트 발송 완료", event.accountNickname());
        } else {
            log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", event.accountNickname());
        }

        // 체결 건별 SSE 실시간 알림 — 알림 설정과 무관하게 항상 발송
        for (TradeLegSummary e : event.executions()) {
            TradeEventView tradeEvent = e.direction() == OrderDirection.SELL
                    ? TradeEventView.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), event.accountNickname())
                    : TradeEventView.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), event.accountNickname());
            realtimeNotificationPort.notifyTrade(profile.userId(), tradeEvent);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", event.accountNickname(), event.executions().size());
    }

    // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응) — root UserPort.findByIdOrThrow 대체
    private TradingUserProfile requireProfile(UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
