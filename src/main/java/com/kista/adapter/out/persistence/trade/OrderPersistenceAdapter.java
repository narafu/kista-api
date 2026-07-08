package com.kista.adapter.out.persistence.trade;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // OrderJpaRepository가 package-private
public class OrderPersistenceAdapter implements OrderPort {

    private final OrderJpaRepository repository;

    @Override
    public void saveAll(List<Order> orders) {
        // Order → Entity 변환 후 일괄 저장 (id=null → Hibernate UUID 자동 생성)
        repository.saveAll(orders.stream().map(this::toEntity).toList());
    }

    @Override
    public List<Order> findPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // PLANNED 상태인 오늘 계획 주문만 조회
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLANNED));
    }

    @Override
    public List<Order> findPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 수동 실행 감지·이중 실행 방지용 — PLACED 주문 조회
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLACED));
    }

    @Override
    public void markPlaced(UUID orderId, String externalOrderId) {
        // 명시적 save로 dirty checking 의존 없이 PLACED + externalOrderId 기록
        mutate(orderId, e -> {
            e.setStatus(Order.OrderStatus.PLACED);
            e.setExternalOrderId(externalOrderId);
        });
    }

    @Override
    public List<Order> findByUser(UUID userId, LocalDate from, LocalDate to, Ticker ticker) {
        // native query는 enum을 name() 문자열로 전달 — DB VARCHAR 컬럼과 매칭
        return toDomainList(repository.findByUserIdAndTradeDateBetweenAndTicker(
                userId, TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to), ticker.name()));
    }

    @Override
    public List<Order> findAll(LocalDate from, LocalDate to) {
        return toDomainList(repository.findByTradeDateBetweenOrderByTradeDateDescCreatedAtDesc(
                TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to)));
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deletePlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 증권사 접수 실패 시 저장된 PLANNED 주문 정리 — PLACED는 건드리지 않음
        repository.deleteAllByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLANNED);
    }

    @Override
    public List<Order> findPlannedOrPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 스케쥴러 재계산 skip 판정 — PLANNED 또는 PLACED 중 하나라도 있으면 skip
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatusIn(
                strategyCycleId, TradeDateConverter.toUtc(tradeDate),
                List.of(Order.OrderStatus.PLANNED, Order.OrderStatus.PLACED)));
    }

    @Override
    public BigDecimal sumPlannedBuyByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // 계좌 기준 당일 PLANNED BUY 합계 — 타 전략 점유분 차감 계산에 사용
        return repository.sumPlannedBuyAmountByAccountIdAndTradeDate(accountId, TradeDateConverter.toUtc(tradeDate));
    }

    @Override
    public List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to) {
        return toDomainList(repository.findByAccountIdAndTradeDateBetweenAndStatusIn(
                accountId,
                TradeDateConverter.toUtc(from),
                TradeDateConverter.toUtc(to),
                List.of(Order.OrderStatus.FILLED.name(), Order.OrderStatus.PARTIALLY_FILLED.name())));
    }

    @Override
    public List<Order> findByStrategyId(UUID strategyId, LocalDate from, LocalDate to) {
        return toDomainList(repository.findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
                strategyId, TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to)));
    }

    @Override
    public List<LocalDate> findTradeDatesByStrategyId(UUID strategyId) {
        return repository.findDistinctTradeDatesByStrategyId(strategyId);
    }

    @Override
    public List<Order> findAtOpenPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // AT_OPEN + PLANNED 주문만 조회 — 개장 시 즉시 선접수 대상
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndTimingAndStatus(
                strategyCycleId, TradeDateConverter.toUtc(tradeDate), Order.OrderTiming.AT_OPEN, Order.OrderStatus.PLANNED));
    }

    @Override
    public BigDecimal sumFilledBuyAmountByCycleId(UUID strategyCycleId) {
        // FILLED/PARTIALLY_FILLED BUY 체결금액 합계 — 결과 없으면 ZERO (COALESCE 보장)
        BigDecimal result = repository.sumFilledBuyAmountByCycleId(strategyCycleId);
        return result != null ? result : BigDecimal.ZERO;
    }

    @Override
    public void updatePlannedOrder(UUID orderId, BigDecimal price, int quantity) {
        // PLANNED 상태 주문만 가격·수량 수정 허용
        mutate(orderId, e -> {
            if (e.getStatus() != Order.OrderStatus.PLANNED) {
                throw new IllegalStateException("PLANNED 주문만 수정할 수 있습니다: " + orderId);
            }
            e.setPrice(price);
            e.setQuantity(quantity);
        });
    }

    @Override
    public void markCancelled(UUID orderId) {
        // 명시적 save로 CANCELLED 상태 기록
        mutate(orderId, e -> e.setStatus(Order.OrderStatus.CANCELLED));
    }

    @Override
    public void markFailed(UUID orderId) {
        // 증권사 접수 실패 — FAILED 상태 기록
        mutate(orderId, e -> e.setStatus(Order.OrderStatus.FAILED));
    }

    @Override
    public void markFilled(UUID orderId, int filledQuantity, BigDecimal filledPrice, Order.OrderStatus status) {
        // 체결 완료: 상태 + 체결 수량/가중평균가 기록
        mutate(orderId, e -> {
            e.setStatus(status);
            e.setFilledQuantity(filledQuantity);
            e.setFilledPrice(filledPrice);
        });
    }

    // 엔티티 목록 → 도메인 목록 일괄 변환
    private List<Order> toDomainList(List<OrderEntity> entities) {
        return entities.stream().map(this::toDomain).toList();
    }

    // 주문 엔티티 조회 → 변경 → 명시적 save (dirty checking 대신 명시적 저장 유지)
    private void mutate(UUID orderId, Consumer<OrderEntity> change) {
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        change.accept(e);
        repository.save(e);
    }

    private OrderEntity toEntity(Order o) {
        OrderEntity e = new OrderEntity();
        e.setAccountId(o.accountId());
        e.setStrategyCycleId(o.strategyCycleId());
        e.setTradeDate(TradeDateConverter.toUtc(o.tradeDate())); // KST 도메인 → UTC DB
        e.setTicker(o.ticker());
        e.setOrderType(o.orderType());
        e.setTiming(o.timing());
        e.setDirection(o.direction());
        e.setQuantity(o.quantity()); // quantity는 모든 저장 경로에서 non-null 보장 (Order.planned/withPrice 팩토리 int 파라미터)
        e.setPrice(o.price());
        e.setStatus(o.status());
        e.setExternalOrderId(o.externalOrderId());
        e.setFilledQuantity(o.filledQuantity());
        e.setFilledPrice(o.filledPrice());
        return e;
    }

    private Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(), e.getAccountId(), e.getStrategyCycleId(), TradeDateConverter.toKst(e.getTradeDate()), e.getTicker(), // UTC DB → KST 도메인
                e.getOrderType(), e.getTiming(), e.getDirection(), e.getQuantity(), e.getPrice(),
                e.getStatus(), e.getExternalOrderId(), e.getFilledQuantity(), e.getFilledPrice()
        );
    }
}
