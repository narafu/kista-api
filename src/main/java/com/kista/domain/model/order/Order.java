package com.kista.domain.model.order;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Order(
        UUID id,                         // PK (null = 신규 PLANNED)
        UUID accountId,                  // FK → accounts.id
        UUID strategyCycleId,            // FK → strategy_cycle.id (멀티 전략 주문 격리)
        LocalDate tradeDate,             // 거래일
        Ticker ticker,                   // 거래 종목
        OrderType orderType,             // 주문 유형 (LOC/MOC/LIMIT) — 증권사 실행 지시
        OrderTiming timing,              // 접수 시점 (AT_OPEN=개장 선접수, AT_CLOSE=마감 배치)
        OrderDirection direction,        // 매수/매도 방향
        Integer quantity,                // 주문 수량(nullable)
        BigDecimal price,                // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,              // 주문 상태
        String externalOrderId,          // 증권사 부여 주문 번호 (KIS: ODNO, Toss: orderId), PLACED 이후 설정
        Integer filledQuantity,          // 체결 수량 (null=미확인, 0=미체결)
        BigDecimal filledPrice           // 체결 가중평균가 (null=미체결)
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문
    }

    public enum OrderTiming {
        AT_CLOSE,  // 마감 배치(04:30 KST)에 접수 — 기본값
        AT_OPEN    // 개장 시점(22:30 KST)에 선접수
    }

    public enum OrderDirection {
        BUY,
        SELL;

        // KIS SLL_TYPE 파라미터: 매도=00, 매수="" (빈 문자열)
        public String kisSllType() { return this == SELL ? "00" : ""; }
    }

    public enum OrderStatus {
        PLANNED,           // DB 저장, 증권사 접수 대기
        PLACED,            // 증권사 접수 완료
        FILLED,            // 전량 체결
        PARTIALLY_FILLED,  // 부분 체결 (filledQuantity < quantity)
        FAILED,            // 증권사 접수 실패
        CANCELLED          // 사용자 취소 또는 미체결로 취소 처리 완료
    }

    // 전략 계산 결과(template)를 특정 계좌·사이클의 PLANNED 주문으로 변환 (timing 전파)
    public static Order plan(Order template, UUID accountId, UUID strategyCycleId) {
        return new Order(null, accountId, strategyCycleId, template.tradeDate(), template.ticker(),
                template.orderType(), template.timing(), template.direction(), template.quantity(),
                template.price(), OrderStatus.PLANNED, null, null, null);

    }

    // 전략 계산용 PLANNED 템플릿 주문 — AT_CLOSE 기본값 (매수·PRIVACY 등 대부분)
    public static Order planned(LocalDate tradeDate, Ticker ticker, OrderType orderType,
                                 OrderDirection direction, int quantity, BigDecimal price) {
        return planned(tradeDate, ticker, orderType, direction, quantity, price, OrderTiming.AT_CLOSE);
    }

    // 전략 계산용 PLANNED 템플릿 주문 — 접수 시점 명시 (INFINITE SELL → AT_OPEN)
    public static Order planned(LocalDate tradeDate, Ticker ticker, OrderType orderType,
                                 OrderDirection direction, int quantity, BigDecimal price,
                                 OrderTiming timing) {
        return new Order(null, null, null, tradeDate, ticker, orderType, timing, direction,
                quantity, price, OrderStatus.PLANNED, null, null, null);
    }

    // 관리자 수동 보정용 FILLED 주문 — LIMIT 고정, 체결 수량·가격 즉시 기록
    public static Order filledManual(UUID accountId, UUID strategyCycleId, LocalDate tradeDate,
                                     Ticker ticker, OrderTiming timing, OrderDirection direction,
                                     int quantity, BigDecimal price, String externalOrderId) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker, OrderType.LIMIT,
                timing, direction, quantity, price, OrderStatus.FILLED, externalOrderId,
                quantity, price);
    }

    // 취소 후 재접수용 PLANNED 사본 — 거래일·수량·가격 교체, id·접수번호·체결 정보 초기화
    public Order replacementWith(LocalDate newTradeDate, int newQuantity, BigDecimal newPrice) {
        return new Order(null, accountId, strategyCycleId, newTradeDate, ticker, orderType,
                timing, direction, newQuantity, newPrice, OrderStatus.PLANNED, null, null, null);
    }

    // 증권사 접수 완료 표시 — externalOrderId 추가, 나머지 필드 유지
    public Order withPlaced(String externalOrderId) {
        return new Order(id, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, quantity, price,
                OrderStatus.PLACED, externalOrderId, null, null);
    }

    // 가격만 교체한 새 PLANNED 주문 — 보정 주문 재저장 시 기존 id·체결 정보 초기화
    public Order withPrice(BigDecimal newPrice) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, quantity, newPrice,
                OrderStatus.PLANNED, null, null, null);
    }
}
