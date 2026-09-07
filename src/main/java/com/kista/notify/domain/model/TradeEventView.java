package com.kista.notify.domain.model;

import java.time.Instant;

// trading.domain.model.TradeEvent의 notify 소유 own-type 투영 — SSE 알림 포트 시그니처가 trading 타입을 직접 참조하지 않도록 분리
public record TradeEventView(
    Kind kind,
    String ticker,
    Integer quantity,
    Double price,
    Double amount,
    Instant time,
    String accountNickname,
    String message
) {
    // 실시간 매매 알림 종류 — Jackson은 name()으로 직렬화 ("BUY"/"SELL"/"INFO"/"FAIL")
    public enum Kind { BUY, SELL, INFO, FAIL }

    // BUY 체결 이벤트 팩토리 메서드
    public static TradeEventView buy(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEventView(Kind.BUY, ticker, quantity, price, amount, Instant.now(), nickname, null);
    }

    // SELL 체결 이벤트 팩토리 메서드
    public static TradeEventView sell(String ticker, int quantity, double price, double amount, String nickname) {
        return new TradeEventView(Kind.SELL, ticker, quantity, price, amount, Instant.now(), nickname, null);
    }
}
