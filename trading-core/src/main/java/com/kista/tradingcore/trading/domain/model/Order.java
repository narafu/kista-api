package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.Quantities;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Order(
        UUID id,                         // PK (null = 신규 PLANNED)
        UUID accountId,                  // FK → accounts.id
        UUID strategyCycleId,            // FK → strategy_cycle.id (멀티 전략 주문 격리)
        LocalDate tradeDate,             // 거래일
        StrategyTicker ticker,                   // 거래 종목
        OrderType orderType,             // 주문 유형 (LOC/MOC/LIMIT) — 증권사 실행 지시
        OrderTiming timing,              // 접수 시점 (AT_OPEN=개장 선접수, AT_CLOSE=마감 배치)
        OrderDirection direction,        // 매수/매도 방향
        String orderLeg,                 // 전략 주문 다리 식별자
        BigDecimal quantity,             // 주문 수량 — 주식은 정수 값, 코인은 소수 8자리
        BigDecimal price,                // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,              // 주문 상태
        String externalOrderId,          // 증권사 부여 주문 번호 (KIS: ODNO, Toss: orderId), PLACED 이후 설정
        BigDecimal filledQuantity,       // 체결 수량 (null=미확인, 0=미체결)
        BigDecimal filledPrice           // 체결 가중평균가 (null=미체결)
) {
    public static final String UNKNOWN_LEG = "UNKNOWN";

    public Order(UUID id, UUID accountId, UUID strategyCycleId, LocalDate tradeDate, StrategyTicker ticker,
                 OrderType orderType, OrderTiming timing, OrderDirection direction,
                 BigDecimal quantity, BigDecimal price, OrderStatus status, String externalOrderId,
                 BigDecimal filledQuantity, BigDecimal filledPrice) {
        this(id, accountId, strategyCycleId, tradeDate, ticker, orderType, timing, direction,
                UNKNOWN_LEG, quantity, price, status, externalOrderId, filledQuantity, filledPrice);
    }

    public Order {
        if (orderLeg == null || orderLeg.isBlank()) orderLeg = UNKNOWN_LEG;
        quantity = Quantities.normalize(quantity); // DB scale 8 → 정수 주식 수량 형태 유지
        filledQuantity = Quantities.normalize(filledQuantity);
    }

    // 커널 계획 주문(PlannedOrder) → 특정 계좌·사이클 PLANNED Order 승격 — 유일한 승격 지점(TradingOrderPlanner.savePlannedOrders)
    public static Order fromPlanned(PlannedOrder p, UUID accountId, UUID strategyCycleId) {
        return new Order(null, accountId, strategyCycleId, p.tradeDate(), p.ticker(), p.orderType(),
                p.timing(), p.direction(), p.orderLeg(), BigDecimal.valueOf(p.quantity()), p.price(),
                OrderStatus.PLANNED, null, null, null);
    }

    // 영속 Order → 계획 주문 강등 (사후 가격 캡 재산정·canSkipOrderComputation 판정 시 커널에 재입력)
    public PlannedOrder toPlanned() {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity.intValueExact(), price, true); // 축소 정책은 영속하지 않는다 — 영속 주문 재배정(재캡)은 DCA가 타지 않는다
    }

    // 관리자 수동 보정용 FILLED 주문 — LIMIT 고정, 체결 수량·가격 즉시 기록
    public static Order filledManual(UUID accountId, UUID strategyCycleId, LocalDate tradeDate,
                                     StrategyTicker ticker, OrderTiming timing, OrderDirection direction,
                                     BigDecimal quantity, BigDecimal price, String externalOrderId) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker, OrderType.LIMIT,
                timing, direction, UNKNOWN_LEG, quantity, price, OrderStatus.FILLED, externalOrderId,
                quantity, price);
    }

    // 코인 시장가 주문 — MARKET·PLANNED 고정, 수량은 소수 8자리 (매수는 price=주문 금액 기준 참고가)
    public static Order cryptoMarket(UUID accountId, UUID strategyCycleId, LocalDate tradeDate, StrategyTicker ticker,
                                     OrderDirection direction, String orderLeg, OrderTiming timing,
                                     BigDecimal quantity, BigDecimal price) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker, OrderType.MARKET, timing, direction,
                orderLeg, quantity, price, OrderStatus.PLANNED, null, null, null);
    }

    // 증권사 접수 완료 표시 — externalOrderId 추가, 나머지 필드 유지
    public Order withPlaced(String externalOrderId) {
        return new Order(id, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, orderLeg, quantity, price,
                OrderStatus.PLACED, externalOrderId, null, null);
    }

    // 가격만 교체한 새 PLANNED 주문 — 보정 주문 재저장 시 기존 id·체결 정보 초기화
    public Order withPrice(BigDecimal newPrice) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, orderLeg, quantity, newPrice,
                OrderStatus.PLANNED, null, null, null);
    }

    // 수량만 교체한 새 PLANNED 주문 — 보유수량 캡 보정 재계산 시 기존 id·체결 정보 초기화
    public Order withQuantity(int newQuantity) {
        return new Order(null, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, orderLeg, BigDecimal.valueOf(newQuantity), price,
                OrderStatus.PLANNED, null, null, null);
    }

    // 관리자 재주문용 PLANNED 신규 주문 — 원본에서 계좌·사이클·종목·주문유형 승계, direction/price/timing 명시 재정의
    public static Order reorder(Order source, LocalDate tradeDate, OrderDirection direction,
                                int quantity, BigDecimal price, OrderTiming timing) {
        return new Order(null, source.accountId(), source.strategyCycleId(), tradeDate,
                source.ticker(), source.orderType(), timing, direction, source.orderLeg(), BigDecimal.valueOf(quantity), price,
                OrderStatus.PLANNED, null, null, null);
    }

    // 전략 주문 다리 식별자만 교체한 새 주문
    public Order withLeg(String newLeg) {
        return new Order(id, accountId, strategyCycleId, tradeDate, ticker,
                orderType, timing, direction, newLeg, quantity, price, status,
                externalOrderId, filledQuantity, filledPrice);
    }

    public static String leg(String prefix, int index) {
        if (index < 1) throw new IllegalArgumentException("order leg index must be positive: " + index);
        return "%s_%02d".formatted(prefix, index);
    }
}
