package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.notify.application.port.output.TradingNotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// 관리자 텔레그램 알림 — root TelegramAdapter(admin bot)와 동일 문구를 재사용한다.
// TelegramProperties는 root와 같은 TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID 환경변수를 읽는다(의도된 공유).
@Component
@RequiredArgsConstructor
class TradingNotifyAdapter implements TradingNotifyPort {

    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties props; // 관리자 봇 설정 — botToken()/chatId()

    @Override
    public void notifyError(Exception e) {
        send(String.format("<b>⚠️ 관리자 알림</b>%n%s", e.getMessage()));
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
