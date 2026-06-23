package com.kista.domain.model.order;

import java.time.Instant;

// 실시간 매매 알림 이벤트 — SSE로 전달됨
public record TradeEvent(
    String kind,            // BUY | SELL | INFO | FAIL
    String ticker,
    Integer quantity,
    Double price,
    Double amount,
    Instant time,
    String accountNickname,
    String message
) {
    // BUY 이벤트 팩토리 메서드
    public static TradeEvent buy(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEvent("BUY", ticker, quantity, price, amount, Instant.now(), nickname, null);
    }

    // SELL 이벤트 팩토리 메서드
    public static TradeEvent sell(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEvent("SELL", ticker, quantity, price, amount, Instant.now(), nickname, null);
    }

    // 실패 이벤트 팩토리 메서드
    public static TradeEvent fail(String ticker, String message, String nickname) {
        return new TradeEvent("FAIL", ticker, null, null, null, Instant.now(), nickname, message);
    }
}
