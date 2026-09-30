package com.kista.tradingnotify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.platform.telegram.TelegramProperties;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.tradingnotify.application.port.output.TradingNotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// 관리자 텔레그램 알림 — root TelegramAdapter(admin bot)와 동일 문구를 재사용한다.
// TelegramProperties는 root와 같은 TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID 환경변수를 읽는다(의도된 공유).
@Slf4j
@Component
@RequiredArgsConstructor
class TradingNotifyAdapter implements TradingNotifyPort {

    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties props; // 관리자 봇 설정 — botToken()/chatId()
    private final ApplicationEventPublisher eventPublisher; // 오류 보고 이벤트 발행 — root app_error_logs 저장 경로

    // root TelegramAdapter.notifyError와 동일하게 AppErrorRaisedEvent를 발행 — root는 admin AppErrorRaisedListener가 로컬로 저장하고,
    // trading-core는 AppErrorStreamPublisher가 Redis Stream으로 root에 보낸다

    @Override
    public void notifyError(Exception e) {
        // 보고 이벤트 발행 실패(리스너 예외 등)가 관리자 텔레그램 알림 자체를 막지 않도록 격리
        try {
            eventPublisher.publishEvent(AppErrorRaisedEvent.of(e, "TradingNotifyAdapter"));
        } catch (Exception publishEx) {
            log.warn("오류 보고 이벤트 발행 실패: {}", publishEx.getMessage());
        }
        send(String.format("<b>⚠️ 관리자 알림</b>%n%s", e.getMessage()));
    }

    @Override
    public void notifyInfo(String message) {
        send(message);
    }

    @Override
    public void notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker) {
        // userId=null 경로 전용(CycleRotationService.rotate()) — 사이클 재등록의 목표 시드가 최소금액에
        // 못 미쳐도 더는 등록을 막지 않고 그대로 진행하므로 "건너뜁니다"가 아닌 "축소 시작"으로 안내한다
        send(String.format("잔고 부족: %s %d주, 예수금 $%.2f — 축소된 배수로 사이클을 시작합니다.",
                ticker.name(), holdings, usdDeposit));
    }

    @Override
    public void notifyMarketClosed() {
        send("오늘은 휴장일입니다. 매매를 건너뜁니다.");
    }

    // 관리자 봇 채팅방으로 단순 메시지 전송
    private void send(String text) {
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }
}
