package com.kista.notify.domain.model;

import java.time.Instant;

// 실시간 매매 SSE 알림(RealtimeNotificationPort.notifyTrade)의 wire DTO — RedisTradeEventSubscriber가
// trading-core의 Redis 발행분을 이 타입으로 역직렬화해 TradeSseEmitterRegistry에 전달한다.
// trading-core의 com.kista.trading.notify.domain.model.TradeEventView와 필드 shape byte-identical
// own-type 복제 — Gradle 컴파일 경계상 root가 trading-core 타입을 import할 수 없어(순환 불가피,
// constraints.md "모듈 경계 own-type" (a) 근거) 발행측·구독측이 각자 own-type을 들고 JSON 계약으로만
// 동기화한다. Task17 TradeLegSummary(sharedkernel, 이벤트 payload용 Execution narrowing)와는 별개 타입 —
// 혼동 금지
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
