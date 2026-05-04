package com.kista.adapter.out.notify;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingReport;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate telegramRestTemplate; // 빈 이름: telegramRestTemplate
    private final TelegramProperties props;

    @Override
    public void notifyReport(TradingReport r) {
        String text = String.format(
                "<b>매매 결산 [%s]</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.vars().quantity(), r.vars().averagePrice(),
                r.vars().priceOffsetRate(), r.vars().targetPrice());
        send(text);
    }

    @Override
    public void notifyMarketClosed() {
        send("오늘은 휴장일입니다. 매매를 건너뜁니다.");
    }

    @Override
    public void notifyInsufficientBalance(AccountBalance b) {
        send(String.format("잔고 부족: SOXL %d주, 예수금 $%.2f. 매매를 건너뜁니다.",
                b.quantity(), b.usdDeposit()));
    }

    @Override
    public void notifyError(Exception e) {
        send(String.format("<b>⚠️ 매매 오류 발생</b>%n%s", e.getMessage()));
    }

    private void send(String text) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram botToken 미설정 — 메시지 전송 생략: {}", text);
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            Map<String, String> body = Map.of(
                    "chat_id", props.chatId(),
                    "text", text,
                    "parse_mode", "HTML");
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
