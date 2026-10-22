package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    // strategy_cycle_id + trade_date + status = PLANNED 조건으로 실행 대상 조회
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndStatus(
            UUID strategyCycleId, LocalDate tradeDate, OrderStatus status);

    // 계좌의 지정 상태 주문 — 거래일·생성 시각 오름차순
    List<OrderEntity> findByAccountIdAndStatusInOrderByTradeDateAscCreatedAtAsc(UUID accountId, List<OrderStatus> statuses);

    // 사이클·거래일 내 증권사 주문번호 존재 여부 (수동 체결 보정 중복 반영 방지 — 주문번호는 일자별 채번이라 거래일 포함)
    boolean existsByStrategyCycleIdAndTradeDateAndExternalOrderId(UUID strategyCycleId, LocalDate tradeDate, String externalOrderId);

    // 사이클·거래일의 PLANNED 주문 일괄 삭제 — 단일 DELETE문이라 행 잠금 대기 후 status를 재평가한다
    // (파생 delete는 잠금 없는 SELECT 후 id별 DELETE라 그 사이 PLACED로 바뀐 행까지 지워 증권사 주문을 고아로 만든다)
    @Modifying
    @Query("DELETE FROM OrderEntity o WHERE o.strategyCycleId = :strategyCycleId AND o.tradeDate = :tradeDate AND o.status = :status")
    int deleteAllByStrategyCycleIdAndTradeDateAndStatus(
            @Param("strategyCycleId") UUID strategyCycleId, @Param("tradeDate") LocalDate tradeDate,
            @Param("status") OrderStatus status);

    // PLANNED 또는 PLACED 조회 (스케쥴러 재계산 skip 판정)
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndStatusIn(
            UUID strategyCycleId, LocalDate tradeDate, List<OrderStatus> statuses);

    // 여러 사이클의 PLANNED 또는 PLACED 배치 조회 (미리보기 N+1 방지)
    List<OrderEntity> findByStrategyCycleIdInAndTradeDateAndStatusIn(
            Collection<UUID> strategyCycleIds, LocalDate tradeDate, List<OrderStatus> statuses);

    // 관리자 거래내역 — 최신순 (동일 trade_date 내 created_at DESC 2차 정렬)
    @Query(value = """
            SELECT * FROM orders
            WHERE trade_date BETWEEN :from AND :to
            ORDER BY trade_date DESC, created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findByTradeDateBetweenOrderByTradeDateDescCreatedAtDesc(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 기간 내 distinct accountId 목록 조회 (이상징후 감지용 N+1 방지)
    @Query(value = """
            SELECT DISTINCT account_id FROM orders
            WHERE trade_date BETWEEN :from AND :to
            """, nativeQuery = true)
    List<UUID> findDistinctAccountIdsByTradeDateBetween(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 계좌·날짜 기준 PLANNED·SUBMITTING BUY 합계 (예수금 체크용 — SUBMITTING은 증권사 차감 여부 불명이라 보수적으로 포함)
    // 주의: 'PLANNED'/'BUY'는 OrderStatus/OrderDirection enum name() 값 — enum 이름 변경 시 이 쿼리도 수정 필요
    @Query(value = """
            SELECT COALESCE(SUM(price * quantity), 0)
            FROM orders
            WHERE account_id = :accountId
              AND trade_date = :tradeDate
              AND status IN ('PLANNED', 'SUBMITTING')
              AND direction = 'BUY'
            """, nativeQuery = true)
    BigDecimal sumPlannedBuyAmountByAccountIdAndTradeDate(
            @Param("accountId") UUID accountId,
            @Param("tradeDate") LocalDate tradeDate);

    // 계좌·거래일·종목 기준 미접수/접수 완료 SELL 예약 수량 합계 (판매가능수량 중복 배정 방지용)
    // PostgreSQL SUM(numeric)은 numeric을 반환하므로 BigDecimal로 수신한다
    @Query(value = """
            SELECT COALESCE(SUM(quantity), 0)
            FROM orders
            WHERE account_id = :accountId
              AND trade_date = :tradeDate
              AND ticker = :ticker
              AND direction = 'SELL'
              AND status IN ('PLANNED', 'SUBMITTING', 'PLACED')
            """, nativeQuery = true)
    BigDecimal sumPlannedOrPlacedSellQuantityByAccountIdAndTradeDateAndTicker(
            @Param("accountId") UUID accountId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("ticker") String ticker);

    // 사용자 기준 기간+종목 조회 (account 경유 JOIN — native)
    @Query(value = """
            SELECT o.* FROM orders o
            JOIN accounts a ON o.account_id = a.id
            WHERE a.user_id = :userId
              AND o.trade_date BETWEEN :from AND :to
              AND o.ticker = :ticker
              AND a.deleted_at IS NULL
            ORDER BY o.trade_date DESC, o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findByUserIdAndTradeDateBetweenAndTicker(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("ticker") String ticker);

    // 유저 기준 기간 체결 주문 조회 — 체결 판정은 filled_quantity > 0 (취소 전 부분 체결된 CANCELLED 포함) (account 경유 JOIN — 대시보드 배치 조회용, 계좌 구분 없이 전체 합산)
    @Query(value = """
            SELECT o.* FROM orders o
            JOIN accounts a ON o.account_id = a.id
            WHERE a.user_id = :userId
              AND o.trade_date BETWEEN :from AND :to
              AND o.filled_quantity > 0
              AND a.deleted_at IS NULL
            ORDER BY o.trade_date DESC, o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findFilledByUserIdAndTradeDateBetween(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 계좌·날짜범위 체결 주문 조회 (daily-trades) — 최신순, 체결 판정은 filled_quantity > 0 (취소 전 부분 체결된 CANCELLED 포함)
    @Query(value = """
            SELECT * FROM orders
            WHERE account_id = :accountId
              AND trade_date BETWEEN :from AND :to
              AND filled_quantity > 0
            ORDER BY trade_date DESC, created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findFilledByAccountIdAndTradeDateBetween(
            @Param("accountId") UUID accountId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 전략 기준 기간 내 주문 전체 조회 — strategy_cycle 경유 JOIN
    @Query(value = """
            SELECT o.* FROM orders o
            JOIN strategy_cycle sc ON o.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND o.trade_date BETWEEN :from AND :to
              AND sc.deleted_at IS NULL
            ORDER BY o.trade_date DESC, o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
            @Param("strategyId") UUID strategyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // AT_OPEN 타이밍 PLANNED 주문 조회 (개장 스케쥴러·수동 실행 시 즉시 선접수용)
    List<OrderEntity> findByStrategyCycleIdAndTradeDateAndTimingAndStatus(
            UUID strategyCycleId, LocalDate tradeDate, OrderTiming timing, OrderStatus status);

    // 전략 기준 distinct 거래일 목록 조회 — 관리자 주문 보정 거래일 드롭다운용
    @Query(value = """
            SELECT DISTINCT o.trade_date FROM orders o
            JOIN strategy_cycle sc ON o.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND sc.deleted_at IS NULL
            ORDER BY o.trade_date DESC
            """, nativeQuery = true)
    List<LocalDate> findDistinctTradeDatesByStrategyId(@Param("strategyId") UUID strategyId);

    // 사이클 기준 BUY 체결금액 합계 (VR 전략 누적 매수금 계산용) — 체결 판정은 filled_quantity > 0 (취소 전 부분 체결된 CANCELLED 포함)
    // orders 테이블은 soft-delete 없음 — deleted_at 조건 불필요
    @Query(value = """
            SELECT COALESCE(SUM(filled_quantity * filled_price), 0)
            FROM orders
            WHERE strategy_cycle_id = :strategyCycleId
              AND direction = 'BUY'
              AND filled_quantity > 0
            """, nativeQuery = true)
    BigDecimal sumFilledBuyAmountByCycleId(@Param("strategyCycleId") UUID strategyCycleId);

    // 사이클·거래일별 순투입 체결금액(BUY +, SELL -) — 거래일 오름차순 (누적은 어댑터가 계산), 취소 전 부분 체결된 CANCELLED 포함
    @Query(value = """
            SELECT strategy_cycle_id AS cycleId, trade_date AS tradeDate,
                   SUM(CASE WHEN direction = 'BUY' THEN filled_quantity * filled_price
                            ELSE -filled_quantity * filled_price END) AS net
            FROM orders
            WHERE strategy_cycle_id IN (:cycleIds)
              AND filled_quantity > 0 AND filled_price IS NOT NULL
            GROUP BY strategy_cycle_id, trade_date
            ORDER BY trade_date
            """, nativeQuery = true)
    List<DailyNetInvested> findDailyNetInvestedByCycleIds(@Param("cycleIds") Collection<UUID> cycleIds);

    // findDailyNetInvestedByCycleIds 결과 행 투영
    interface DailyNetInvested {
        UUID getCycleId();

        LocalDate getTradeDate();

        BigDecimal getNet();
    }
}
