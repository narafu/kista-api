package com.kista.adapter.out.notify;

import com.kista.application.event.TradingReportReadyEvent;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.kista.domain.model.order.Order.OrderDirection.SELL;

// 매매 리포트 알림(Telegram/FCM)과 체결 건별 SSE 알림을 채널 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReportNotifier {

    private final UserNotificationPort userNotificationPort;         // 리포트 알림 발송
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingReportReady(TradingReportReadyEvent event) {
        User user = event.user();
        // TRADING_ALERT 알림 활성 여부에 따라 리포트 발송 여부 결정 (기본값 true)
        if (event.reportEnabled()) {
            userNotificationPort.notifyTradingReport(user, event.account(), event.report());
            log.info("[{}] 리포트 발송 완료", event.account().nickname());
        } else {
            log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", event.account().nickname());
        }

        // 체결 건별 SSE 실시간 알림 — 알림 설정과 무관하게 항상 발송
        for (Execution e : event.executions()) {
            TradeEvent tradeEvent = e.direction() == SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), event.account().nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), event.account().nickname());
            realtimeNotificationPort.notifyTrade(user.id(), tradeEvent);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", event.account().nickname(), event.executions().size());
    }
}
