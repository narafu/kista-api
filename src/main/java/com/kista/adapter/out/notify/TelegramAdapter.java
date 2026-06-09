package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정

    @Override
    public void notifyReport(TradingReport r) {
        String text = String.format(
                "<b>매매 결산 [%s]</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.snapshot().holdings(), r.snapshot().averagePrice(),
                r.snapshot().priceOffsetRate(), r.snapshot().targetPrice());
        send(text);
    }

    @Override
    public void notifyMarketClosed() {
        send("오늘은 휴장일입니다. 매매를 건너뜁니다.");
    }

    @Override
    public void notifyInsufficientBalance(Account account, AccountBalance b, Strategy.Ticker ticker) {
        send(String.format("잔고 부족: %s %d주, 예수금 $%.2f. 매매를 건너뜁니다.",
                ticker.name(), b.holdings(), b.usdDeposit()));
    }

    @Override
    public void notifyError(Exception e) {
        send(String.format("<b>⚠️ 매매 오류 발생</b>%n%s", e.getMessage()));
    }

    // 관리자 봇 채팅방으로 단순 메시지 전송
    private void send(String text) {
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }
}
