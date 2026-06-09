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
    public List<Order> findPlannedByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // PLANNED 상태인 오늘 계획 주문만 조회
        return repository
                .findByAccountIdAndTradeDateAndStatus(
                        accountId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLANNED)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Order> findPlacedByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // 수동 실행 감지·이중 실행 방지용 — PLACED 주문 조회
        return repository
                .findByAccountIdAndTradeDateAndStatus(
                        accountId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLACED)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markPlaced(UUID orderId, String kisOrderId) {
        // 명시적 save로 dirty checking 의존 없이 PLACED + kisOrderId 기록
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        e.setStatus(Order.OrderStatus.PLACED);
        e.setKisOrderId(kisOrderId);
        repository.save(e);
    }

    @Override
    public List<Order> findBy(LocalDate from, LocalDate to, Ticker ticker) {
        return repository
                .findByTradeDateBetweenAndTicker(
                        TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to), ticker)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Order> findAll(LocalDate from, LocalDate to) {
        return repository
                .findByTradeDateBetween(TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deletePlannedByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // KIS 접수 실패 시 저장된 PLANNED 주문 정리 — PLACED는 건드리지 않음
        repository.deleteAllByAccountIdAndTradeDateAndStatus(
                accountId, TradeDateConverter.toUtc(tradeDate), Order.OrderStatus.PLANNED);
    }

    @Override
    @Transactional
    public void deletePlannedBuyByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // 가격 보정 시 BUY PLANNED만 삭제 — SELL PLANNED와 PLACED는 보존
        repository.deleteAllByAccountIdAndTradeDateAndStatusAndDirection(
                accountId, TradeDateConverter.toUtc(tradeDate),
                Order.OrderStatus.PLANNED, Order.OrderDirection.BUY);
    }

    @Override
    public List<Order> findPlannedOrPlacedByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // 스케줄러 재계산 skip 판정 — PLANNED 또는 PLACED 중 하나라도 있으면 skip
        return repository
                .findByAccountIdAndTradeDateAndStatusIn(
                        accountId, TradeDateConverter.toUtc(tradeDate),
                        List.of(Order.OrderStatus.PLANNED, Order.OrderStatus.PLACED))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markCancelled(UUID orderId) {
        // 명시적 save로 CANCELLED 상태 기록
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        e.setStatus(Order.OrderStatus.CANCELLED);
        repository.save(e);
    }

    @Override
    public void markFilled(UUID orderId, int filledQuantity, BigDecimal filledPrice, Order.OrderStatus status) {
        // 체결 완료: 상태 + 체결 수량/가중평균가 기록
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        e.setStatus(status);
        e.setFilledQuantity(filledQuantity);
        e.setFilledPrice(filledPrice);
        repository.save(e);
    }

    private OrderEntity toEntity(Order o) {
        OrderEntity e = new OrderEntity();
        e.setAccountId(o.accountId());
        e.setTradeDate(TradeDateConverter.toUtc(o.tradeDate())); // KST 도메인 → UTC DB
        e.setTicker(o.ticker());
        e.setOrderType(o.orderType());
        e.setDirection(o.direction());
        e.setQuantity(o.quantity());
        e.setPrice(o.price());
        e.setStatus(o.status());
        e.setKisOrderId(o.kisOrderId());
        e.setFilledQuantity(o.filledQuantity());
        e.setFilledPrice(o.filledPrice());
        return e;
    }

    private Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(), e.getAccountId(), TradeDateConverter.toKst(e.getTradeDate()), e.getTicker(), // UTC DB → KST 도메인
                e.getOrderType(), e.getDirection(), e.getQuantity(), e.getPrice(),
                e.getStatus(), e.getKisOrderId(), e.getFilledQuantity(), e.getFilledPrice()
        );
    }
}
