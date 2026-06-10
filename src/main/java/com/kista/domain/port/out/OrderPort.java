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

    // kisOrderPort.place() 완료 후 PLACED 상태 + kisOrderId 기록
    void markPlaced(UUID orderId, String kisOrderId);

    // 기간+종목 필터 조회 (대시보드·텔레그램 이력 조회용)
    List<Order> findBy(LocalDate from, LocalDate to, Ticker ticker);

    // 기간 내 전체 계좌 조회 ticker 필터 없음 (관리자·이상징후 감지용)
    List<Order> findAll(LocalDate from, LocalDate to);

    // 단건 조회 (취소 전 상태 확인용)
    Optional<Order> findById(UUID orderId);

    // KIS 접수 실패 시 누적된 PLANNED 주문 일괄 삭제 (재시도 시 중복 접수 방지)
    void deletePlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // BUY PLANNED만 삭제 (KIS 접수 전 가격 보정 시 재저장 준비)
    void deletePlannedBuyByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 오늘 PLANNED 또는 PLACED 주문 조회 (스케줄러 재계산 skip 판정용)
    List<Order> findPlannedOrPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 취소 완료 → CANCELLED 상태로 변경
    void markCancelled(UUID orderId);

    // 체결 완료 → FILLED 또는 PARTIALLY_FILLED 상태 + 체결 수량·가중평균가 기록
    void markFilled(UUID orderId, int filledQuantity, BigDecimal filledPrice, Order.OrderStatus status);
}
