package com.kista.adapter.out.notify;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingReport;
import com.kista.domain.port.out.NotifyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class TelegramAdapter implements NotifyPort {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate restTemplate;
    private final TelegramProperties props;

    public TelegramAdapter(RestTemplate telegramRestTemplate, TelegramProperties props) {
        this.restTemplate = telegramRestTemplate;
        this.props = props;
    }

    @Override
    public void notifyReport(TradingReport r) {
        String text = String.format(
                "<b>매매 결산 [%s]</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "비율: %.4f | 목표가: $%.2f",
                r.date(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.vars().q(), r.vars().a(),
                r.vars().s(), r.vars().p());
        send(text);
    }

    @Override
    public void notifyMarketClosed() {
        send("오늘은 휴장일입니다. 매매를 건너뜁니다.");
    }

    @Override
    public void notifyInsufficientBalance(AccountBalance b) {
        send(String.format("잔고 부족: SOXL %d주, 예수금 $%.2f. 매매를 건너뜁니다.",
                b.soxlQty(), b.effectiveAmt()));
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
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
