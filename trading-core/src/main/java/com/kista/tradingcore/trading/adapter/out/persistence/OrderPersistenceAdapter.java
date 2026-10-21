package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // OrderJpaRepository가 package-private
public class OrderPersistenceAdapter implements OrderPort {

    // 열린 주문 — 슬롯 점유·재계산 skip·수동 실행 이중 실행 판정 (SUBMITTING은 접수 결과 미확정이라 점유로 본다)
    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PLANNED, OrderStatus.SUBMITTING, OrderStatus.PLACED);

    private final OrderJpaRepository repository;
    private final EntityManager entityManager; // 상태 전이 시 행 잠금 + 최신 상태 refresh

    @Override
    public void saveAll(List<Order> orders) {
        // Order → Entity 변환 후 일괄 저장 (id=null → Hibernate UUID 자동 생성)
        repository.saveAll(orders.stream().map(this::toEntity).toList());
    }

    @Override
    public Order save(Order order) {
        // 단건 저장 — id=null이면 Hibernate가 UUID v7 생성, 채워진 id로 도메인 반환
        return toDomain(repository.save(toEntity(order)));
    }

    @Override
    @Transactional
    public void markSubmitting(UUID orderId) {
        // 행 잠금 후 PLANNED에서만 SUBMITTING 전이 — 그 사이 취소·삭제·재캡 취소됐으면 경합(호출자는 HTTP를 보내지 않는다)
        OrderEntity e = lockOrConflict(orderId);
        requireStatus(e, OrderStatus.PLANNED);
        e.setStatus(OrderStatus.SUBMITTING); // updated_at 갱신 = 선점 시각
        repository.save(e);
    }

    @Override
    public List<SubmittingOrder> findSubmittingByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 선점 시각은 updated_at — SUBMITTING 동안 다른 갱신 경로가 없다
        return repository.findByStrategyCycleIdAndTradeDateAndStatus(strategyCycleId, tradeDate, OrderStatus.SUBMITTING)
                .stream().map(e -> new SubmittingOrder(toDomain(e), e.getUpdatedAt())).toList();
    }

    @Override
    public List<Order> findPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // PLANNED 상태인 오늘 계획 주문만 조회
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, tradeDate, OrderStatus.PLANNED));
    }

    @Override
    public List<Order> findPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 수동 실행 감지·이중 실행 방지용 — PLACED 주문 조회
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, tradeDate, OrderStatus.PLACED));
    }

    @Override
    public List<Order> findCancelledByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, tradeDate, OrderStatus.CANCELLED));
    }

    @Override
    @Transactional
    public void markPlaced(UUID orderId, String externalOrderId) {
        // 행 잠금 후 SUBMITTING에서만 PLACED 전이 — 선점 뒤 다른 경로가 상태를 바꿨으면 경합으로 알린다
        OrderEntity e = lockOrConflict(orderId);
        // 1차 시도가 커밋된 뒤 예외로 재시도된 경우 — 같은 접수번호면 멱등 성공
        if (e.getStatus() == OrderStatus.PLACED && externalOrderId.equals(e.getExternalOrderId())) return;
        requireStatus(e, OrderStatus.SUBMITTING);
        e.setStatus(OrderStatus.PLACED);
        e.setExternalOrderId(externalOrderId);
        repository.save(e);
    }

    @Override
    @Transactional
    public void cancelPlanned(UUID orderId) {
        // 행 잠금 후 PLANNED에서만 CANCELLED 전이 — 배치가 먼저 접수(PLACED)했으면 경합으로 알린다
        OrderEntity e = lockOrConflict(orderId);
        requireStatus(e, OrderStatus.PLANNED);
        e.setStatus(OrderStatus.CANCELLED);
        repository.save(e);
    }

    @Override
    public boolean existsByStrategyCycleIdAndTradeDateAndExternalOrderId(UUID strategyCycleId, LocalDate tradeDate, String externalOrderId) {
        return repository.existsByStrategyCycleIdAndTradeDateAndExternalOrderId(strategyCycleId, tradeDate, externalOrderId);
    }

    // 행 잠금 + DB 최신 상태 재조회 — 같은 트랜잭션에서 먼저 읽어 둔 스냅샷(예: 재캡의 BUY 조회)은
    // 잠금 쿼리로도 갱신되지 않으므로 refresh로 그 사이 커밋된 취소·삭제를 반영한다
    private OrderEntity lockOrConflict(UUID orderId) {
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> deletedConflict(orderId));
        try {
            entityManager.flush(); // 같은 트랜잭션의 미반영 변경을 먼저 내려 refresh가 덮어쓰지 않게 한다
            entityManager.refresh(e, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException deleted) {
            throw deletedConflict(orderId);
        }
        return e;
    }

    private static OrderStateConflictException deletedConflict(UUID orderId) {
        return new OrderStateConflictException("주문이 삭제됨: orderId=" + orderId);
    }

    private static void requireStatus(OrderEntity e, OrderStatus expected) {
        if (e.getStatus() != expected) {
            throw new OrderStateConflictException("주문 상태 경합: orderId=" + e.getId()
                    + ", expected=" + expected + ", actual=" + e.getStatus());
        }
    }

    @Override
    public List<Order> findByUser(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker) {
        // native query는 enum을 name() 문자열로 전달 — DB VARCHAR 컬럼과 매칭
        return toDomainList(repository.findByUserIdAndTradeDateBetweenAndTicker(
                userId, from, to, ticker.name()));
    }

    @Override
    public List<Order> findAll(LocalDate from, LocalDate to) {
        return toDomainList(repository.findByTradeDateBetweenOrderByTradeDateDescCreatedAtDesc(from, to));
    }

    @Override
    public List<UUID> findDistinctAccountIdsByTradeDateBetween(LocalDate from, LocalDate to) {
        return repository.findDistinctAccountIdsByTradeDateBetween(from, to);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(this::toDomain);
    }

    @Override
    public List<Order> findOpenByAccountId(UUID accountId) {
        return repository.findByAccountIdAndStatusInOrderByTradeDateAscCreatedAtAsc(
                accountId, List.of(OrderStatus.PLANNED, OrderStatus.SUBMITTING, OrderStatus.PLACED)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<Order> findAllByIds(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public int deletePlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // PLANNED 주문만 정리 — 접수 중 PLACED로 바뀐 행은 건드리지 않음, 실제 삭제 건수 반환
        return repository.deleteAllByStrategyCycleIdAndTradeDateAndStatus(
                strategyCycleId, tradeDate, OrderStatus.PLANNED);
    }

    @Override
    public List<Order> findPlannedOrPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // 스케쥴러 재계산 skip 판정 — PLANNED 또는 PLACED 중 하나라도 있으면 skip
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndStatusIn(
                strategyCycleId, tradeDate,
                OPEN_STATUSES));
    }

    @Override
    public Map<UUID, List<Order>> findPlannedOrPlacedByCycleIdsAndDate(
            Collection<UUID> strategyCycleIds, LocalDate tradeDate) {
        // 미리보기 배치 조회 — 계좌 내 전략 N개의 사이클마다 개별 조회하던 것을 1회로 축소
        if (strategyCycleIds.isEmpty()) return Map.of();
        return toDomainList(repository.findByStrategyCycleIdInAndTradeDateAndStatusIn(
                strategyCycleIds, tradeDate,
                OPEN_STATUSES))
                .stream()
                .collect(Collectors.groupingBy(Order::strategyCycleId));
    }

    @Override
    public BigDecimal sumPlannedBuyByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // 계좌 기준 당일 PLANNED BUY 합계 — 타 전략 점유분 차감 계산에 사용
        return repository.sumPlannedBuyAmountByAccountIdAndTradeDate(accountId, tradeDate);
    }

    @Override
    public int sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
            UUID accountId, LocalDate tradeDate, StrategyTicker ticker) {
        // 같은 계좌·거래일(KST)·ticker의 미체결 SELL 예약 수량을 합산한다
        BigDecimal quantity = repository.sumPlannedOrPlacedSellQuantityByAccountIdAndTradeDateAndTicker(
                accountId, tradeDate, ticker.name());
        return quantity != null ? quantity.intValueExact() : 0; // 주식 SELL 예약 전용 — 종목 필터라 코인 소수 수량은 섞이지 않는다
    }

    @Override
    public List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to) {
        return toDomainList(repository.findByAccountIdAndTradeDateBetweenAndStatusIn(
                accountId,
                from,
                to,
                List.of(OrderStatus.FILLED.name(), OrderStatus.PARTIALLY_FILLED.name())));
    }

    @Override
    public List<Order> findFilledByUser(UUID userId, LocalDate from, LocalDate to) {
        return toDomainList(repository.findByUserIdAndTradeDateBetweenAndStatusIn(
                userId,
                from,
                to,
                List.of(OrderStatus.FILLED.name(), OrderStatus.PARTIALLY_FILLED.name())));
    }

    @Override
    public List<Order> findByStrategyId(UUID strategyId, LocalDate from, LocalDate to) {
        return toDomainList(repository.findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
                strategyId, from, to));
    }

    @Override
    public List<LocalDate> findTradeDatesByStrategyId(UUID strategyId) {
        return repository.findDistinctTradeDatesByStrategyId(strategyId);
    }

    @Override
    public List<Order> findAtOpenPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        // AT_OPEN + PLANNED 주문만 조회 — 개장 시 즉시 선접수 대상
        return toDomainList(repository.findByStrategyCycleIdAndTradeDateAndTimingAndStatus(
                strategyCycleId, tradeDate, OrderTiming.AT_OPEN, OrderStatus.PLANNED));
    }

    @Override
    public BigDecimal sumFilledBuyAmountByCycleId(UUID strategyCycleId) {
        // FILLED/PARTIALLY_FILLED BUY 체결금액 합계 — 결과 없으면 ZERO (COALESCE 보장)
        BigDecimal result = repository.sumFilledBuyAmountByCycleId(strategyCycleId);
        return result != null ? result : BigDecimal.ZERO;
    }

    @Override
    public Map<UUID, NavigableMap<LocalDate, BigDecimal>> findFilledNetInvestedByCycleIds(Collection<UUID> strategyCycleIds) {
        if (strategyCycleIds.isEmpty()) return Map.of();
        Map<UUID, NavigableMap<LocalDate, BigDecimal>> result = new java.util.HashMap<>();
        Map<UUID, BigDecimal> running = new java.util.HashMap<>(); // 사이클별 누적 (거래일 오름차순 행을 순회)
        for (var row : repository.findDailyNetInvestedByCycleIds(strategyCycleIds)) {
            BigDecimal cumulative = running.merge(row.getCycleId(), row.getNet(), BigDecimal::add);
            result.computeIfAbsent(row.getCycleId(), _ -> new TreeMap<>()).put(row.getTradeDate(), cumulative);
        }
        return result;
    }

    @Override
    public void markCancelled(UUID orderId) {
        // 명시적 save로 CANCELLED 상태 기록
        mutate(orderId, e -> e.setStatus(OrderStatus.CANCELLED));
    }

    @Override
    @Transactional
    public void markFailed(UUID orderId) {
        // 증권사 확정 거절 — SUBMITTING에서만 FAILED 전이 (결과 불명은 SUBMITTING 유지 — 호출자가 판정)
        OrderEntity e = lockOrConflict(orderId);
        requireStatus(e, OrderStatus.SUBMITTING);
        e.setStatus(OrderStatus.FAILED);
        repository.save(e);
    }

    @Override
    public void markFilled(UUID orderId, BigDecimal filledQuantity, BigDecimal filledPrice, OrderStatus status) {
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
        e.setTradeDate(o.tradeDate()); // KST 거래일 그대로 저장 (변환 없음)
        e.setTicker(o.ticker());
        e.setOrderType(o.orderType());
        e.setTiming(o.timing());
        e.setDirection(o.direction());
        e.setOrderLeg(o.orderLeg());
        e.setQuantity(o.quantity()); // quantity는 모든 저장 경로에서 non-null 보장 (Order.fromPlanned/withPrice 팩토리 int 파라미터)
        e.setPrice(o.price());
        e.setStatus(o.status());
        e.setExternalOrderId(o.externalOrderId());
        e.setFilledQuantity(o.filledQuantity());
        e.setFilledPrice(o.filledPrice());
        return e;
    }

    private Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(), e.getAccountId(), e.getStrategyCycleId(), e.getTradeDate(), e.getTicker(), // KST 거래일 그대로 복원
                e.getOrderType(), e.getTiming(), e.getDirection(), e.getOrderLeg(), e.getQuantity(), e.getPrice(),
                e.getStatus(), e.getExternalOrderId(), e.getFilledQuantity(), e.getFilledPrice()
        );
    }
}

