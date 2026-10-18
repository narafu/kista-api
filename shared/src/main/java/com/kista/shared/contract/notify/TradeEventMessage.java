package com.kista.shared.contract.notify;

import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// 실시간 매매 알림 — Redis Pub/Sub(trade.event 채널) payload이자 SSE 이벤트 body.
// 발행: trading-core RedisTradeEventPublisher, 구독: root RedisTradeEventSubscriber → TradeSseEmitterRegistry(kista-ui 소비)
public record TradeEventMessage(
        Kind kind,              // 알림 종류
        String ticker,          // 거래 종목
        @JsonSerialize(using = PlainDecimalSerializer.class)
        BigDecimal quantity,    // 체결 수량 (코인은 소수 가능, 구버전 정수 JSON도 수용)
        Double price,           // 체결 가격
        Double amount,          // 체결 금액
        Instant time,           // 발생 시각
        String accountNickname, // 계좌 별칭
        String message,         // 부가 메시지 (INFO/FAIL)
        UUID accountId,         // 이벤트 대상 계좌 (UI 캐시 선별 무효화용)
        UUID strategyId         // 이벤트 대상 전략 — 전략 무관 INFO/FAIL·구버전 EPR 재발행분은 null
) {
    // 실시간 매매 알림 종류 — Jackson은 name()으로 직렬화 ("BUY"/"SELL"/"INFO"/"FAIL")
    public enum Kind { BUY, SELL, INFO, FAIL }

    // BUY 체결 이벤트 팩토리 메서드
    public static TradeEventMessage buy(String ticker, BigDecimal quantity, double price, double amount, String nickname,
                                        UUID accountId, UUID strategyId) {
        return new TradeEventMessage(Kind.BUY, ticker, quantity, price, amount, Instant.now(), nickname, null, accountId, strategyId);
    }

    // SELL 체결 이벤트 팩토리 메서드
    public static TradeEventMessage sell(String ticker, BigDecimal quantity, double price, double amount, String nickname,
                                         UUID accountId, UUID strategyId) {
        return new TradeEventMessage(Kind.SELL, ticker, quantity, price, amount, Instant.now(), nickname, null, accountId, strategyId);
    }
}
