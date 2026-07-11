package com.kista.domain.port.out;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderPort {
    // 계획 주문 일괄 저장 (신규 PLANNED 상태)
    void saveAll(List<Order> orders);

    // 특정 사이클·날짜의 PLANNED 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<Order> findPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 특정 사이클·날짜의 PLACED 주문 조회 (수동 실행 감지 및 이중 실행 방지용)
    List<Order> findPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 증권사 주문 접수 완료 후 PLACED 상태 + externalOrderId 기록
    void markPlaced(UUID orderId, String externalOrderId);

    // 사용자 스코프 기간+종목 조회 (대시보드 — 본인 데이터만)
    List<Order> findByUser(UUID userId, LocalDate from, LocalDate to, Ticker ticker);

    // 기간 내 전체 계좌 조회 ticker 필터 없음 (관리자·이상징후 감지용)
    List<Order> findAll(LocalDate from, LocalDate to);

    // 단건 조회 (취소 전 상태 확인용)
    Optional<Order> findById(UUID orderId);

    // 증권사 접수 실패 시 누적된 PLANNED 주문 일괄 삭제 (재시도 시 중복 접수 방지)
    void deletePlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 오늘 PLANNED 또는 PLACED 주문 조회 (스케쥴러 재계산 skip 판정용)
    List<Order> findPlannedOrPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 취소 완료 또는 미체결 → CANCELLED 상태로 변경
    void markCancelled(UUID orderId);

    // 증권사 접수 실패 → FAILED 상태로 변경
    void markFailed(UUID orderId);

    // 체결 완료 → FILLED 또는 PARTIALLY_FILLED 상태 + 체결 수량·가중평균가 기록
    void markFilled(UUID orderId, int filledQuantity, BigDecimal filledPrice, Order.OrderStatus status);

    // 계좌 기준 당일 PLANNED BUY 주문 합계 (수동 실행 예수금 체크 — 타 전략 점유분 차감용)
    BigDecimal sumPlannedBuyByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // 계좌 기준 FILLED/PARTIALLY_FILLED 주문 조회 (일별 거래내역 달력용)
    List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to);

    // 전략 기준 기간 내 주문 전체 조회 (사용자 주문내역 탭용) — strategy_cycle을 통한 JOIN 필요
    List<Order> findByStrategyId(UUID strategyId, LocalDate from, LocalDate to);

    // 전략 기준 distinct 거래일 목록 조회 (관리자 주문 보정 거래일 드롭다운용)
    List<LocalDate> findTradeDatesByStrategyId(UUID strategyId);

    // AT_OPEN + PLANNED 주문 조회 (개장 스케쥴러·수동 실행 선접수용)
    List<Order> findAtOpenPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 사이클 기준 FILLED/PARTIALLY_FILLED BUY 체결금액 합계 (VR 전략 누적 매수금 계산용)
    BigDecimal sumFilledBuyAmountByCycleId(UUID strategyCycleId);
}
