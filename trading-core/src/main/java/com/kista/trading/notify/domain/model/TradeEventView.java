package com.kista.trading.notify.domain.model;

import java.time.Instant;

// 실시간 매매 SSE 알림(TradingRealtimeNotificationPort.notifyTrade)의 wire DTO — TradingReportNotifier가
// 체결 결과를 이 타입으로 조립해 넘긴다. root com.kista.notify.domain.model.TradeEventView의 own-type
// 복제본(필드 그대로, 패키지만 변경) — SSE 레지스트리가 root에 있어 root판은 그대로 유지되고, trading-core는
// 자신의 포트 시그니처용으로 동일 shape을 소유한다.
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
