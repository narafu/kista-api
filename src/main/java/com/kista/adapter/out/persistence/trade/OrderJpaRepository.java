package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    // strategy_cycle_id + trade_date + status = PLANNED 조건으로 실행 대상 조회
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndStatus(
            UUID strategyCycleId, LocalDate tradeDate, Order.OrderStatus status);

    // 증권사 접수 실패 시 누적 PLANNED 주문 정리
    void deleteAllByStrategyCycleIdAndTradeDateAndStatus(
            UUID strategyCycleId, LocalDate tradeDate, Order.OrderStatus status);

    // PLANNED 또는 PLACED 조회 (스케쥴러 재계산 skip 판정)
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndStatusIn(
            UUID strategyCycleId, LocalDate tradeDate, List<Order.OrderStatus> statuses);

    // 기간+종목 필터 (대시보드용)
    List<OrderEntity> findByTradeDateBetweenAndTicker(LocalDate from, LocalDate to, Ticker ticker);

    // 관리자 거래내역 — 최신순
    List<OrderEntity> findByTradeDateBetweenOrderByTradeDateDesc(LocalDate from, LocalDate to);

    // 계좌·날짜 기준 PLANNED BUY 합계 (수동 실행 예수금 체크용)
    // 주의: 'PLANNED'/'BUY'는 Order.OrderStatus/OrderDirection enum name() 값 — enum 이름 변경 시 이 쿼리도 수정 필요
    @Query(value = """
            SELECT COALESCE(SUM(price * quantity), 0)
            FROM orders
            WHERE account_id = :accountId
              AND trade_date = :tradeDate
              AND status = 'PLANNED'
              AND direction = 'BUY'
            """, nativeQuery = true)
    BigDecimal sumPlannedBuyAmountByAccountIdAndTradeDate(
            @Param("accountId") UUID accountId,
            @Param("tradeDate") LocalDate tradeDate);

    // 사용자 기준 기간+종목 조회 (account 경유 JOIN — native)
    @Query(value = """
            SELECT o.* FROM orders o
            JOIN accounts a ON o.account_id = a.id
            WHERE a.user_id = :userId
              AND o.trade_date BETWEEN :from AND :to
              AND o.ticker = :ticker
              AND a.deleted_at IS NULL
            ORDER BY o.trade_date DESC
            """, nativeQuery = true)
    List<OrderEntity> findByUserIdAndTradeDateBetweenAndTicker(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("ticker") String ticker);

    // 계좌·날짜범위·상태 복합 조건 조회 (daily-trades DB 전환용)
    List<OrderEntity> findByAccountIdAndTradeDateBetweenAndStatusIn(
            UUID accountId, LocalDate from, LocalDate to,
            List<Order.OrderStatus> statuses);

    // 전략 사이클 기준 기간 내 주문 전체 조회 — 최신순
    List<OrderEntity> findByStrategyCycleIdAndTradeDateBetweenOrderByTradeDateDesc(
            UUID strategyCycleId, LocalDate from, LocalDate to);

    // 전략 기준 기간 내 주문 전체 조회 — strategy_cycle 경유 JOIN
    @Query(value = """
            SELECT o.* FROM orders o
            JOIN strategy_cycle sc ON o.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND o.trade_date BETWEEN :from AND :to
              AND sc.deleted_at IS NULL
            ORDER BY o.trade_date DESC
            """, nativeQuery = true)
    List<OrderEntity> findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
            @Param("strategyId") UUID strategyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // AT_OPEN 타이밍 PLANNED 주문 조회 (개장 스케쥴러·수동 실행 시 즉시 선접수용)
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndTimingAndStatus(
            UUID strategyCycleId, LocalDate tradeDate, Order.OrderTiming timing, Order.OrderStatus status);

    // 전략 기준 distinct 거래일 목록 조회 — 관리자 주문 보정 거래일 드롭다운용
    @Query(value = """
            SELECT DISTINCT o.trade_date FROM orders o
            JOIN strategy_cycle sc ON o.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND sc.deleted_at IS NULL
            ORDER BY o.trade_date DESC
            """, nativeQuery = true)
    List<LocalDate> findDistinctTradeDatesByStrategyId(@Param("strategyId") UUID strategyId);

    // 사이클 기준 FILLED/PARTIALLY_FILLED BUY 체결금액 합계 (VR 전략 누적 매수금 계산용)
    // orders 테이블은 soft-delete 없음 — deleted_at 조건 불필요
    @Query(value = """
            SELECT COALESCE(SUM(filled_quantity * filled_price), 0)
            FROM orders
            WHERE strategy_cycle_id = :strategyCycleId
              AND direction = 'BUY'
              AND status IN ('FILLED', 'PARTIALLY_FILLED')
            """, nativeQuery = true)
    BigDecimal sumFilledBuyAmountByCycleId(@Param("strategyCycleId") UUID strategyCycleId);
}
