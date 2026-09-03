package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.application.port.output.RealtimeNotificationPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 매매 리포트 알림(Telegram/FCM)과 체결 건별 SSE 알림을 채널 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReportNotifier {

    private final UserNotificationPort userNotificationPort;         // 리포트 알림 발송
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final UserPort userPort;                                 // ID → User 재조회 (EPR 역직렬화 대응)
    private final AccountPort accountPort;                           // ID → Account 재조회 (EPR 역직렬화 대응)

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingReportReady(TradingReportReadyEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());

        // TRADING_ALERT 알림 활성 여부에 따라 리포트 발송 여부 결정 (기본값 true)
        if (event.reportEnabled()) {
            userNotificationPort.notifyTradingReport(user, account, event.report());
            log.info("[{}] 리포트 발송 완료", account.nickname());
        } else {
            log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", account.nickname());
        }

        // 체결 건별 SSE 실시간 알림 — 알림 설정과 무관하게 항상 발송
        for (Execution e : event.executions()) {
            TradeEvent tradeEvent = e.direction() == Direction.SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), tradeEvent);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), event.executions().size());
    }
}
