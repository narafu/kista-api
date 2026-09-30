package com.kista.contract.notify;

import java.time.Instant;

// 실시간 매매 알림 — Redis Pub/Sub(trade.event 채널) payload이자 SSE 이벤트 body.
// 발행: trading-core RedisTradeEventPublisher, 구독: root RedisTradeEventSubscriber → TradeSseEmitterRegistry(kista-ui 소비)
public record TradeEventMessage(
        Kind kind,              // 알림 종류
        String ticker,          // 거래 종목
        Integer quantity,       // 체결 수량
        Double price,           // 체결 가격
        Double amount,          // 체결 금액
        Instant time,           // 발생 시각
        String accountNickname, // 계좌 별칭
        String message          // 부가 메시지 (INFO/FAIL)
) {
    // 실시간 매매 알림 종류 — Jackson은 name()으로 직렬화 ("BUY"/"SELL"/"INFO"/"FAIL")
    public enum Kind { BUY, SELL, INFO, FAIL }

    // BUY 체결 이벤트 팩토리 메서드
    public static TradeEventMessage buy(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEventMessage(Kind.BUY, ticker, quantity, price, amount, Instant.now(), nickname, null);
    }

    // SELL 체결 이벤트 팩토리 메서드
    public static TradeEventMessage sell(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEventMessage(Kind.SELL, ticker, quantity, price, amount, Instant.now(), nickname, null);
    }
}
