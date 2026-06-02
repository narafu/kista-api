package com.kista.domain.port.out;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OrderPort {
    // 계획 주문 일괄 저장 (신규 PLANNED 상태)
    void saveAll(List<Order> orders);

    // 특정 계좌·날짜의 PLANNED 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<Order> findPlannedByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // 특정 계좌·날짜의 PLACED 주문 조회 (수동 실행 감지 및 이중 실행 방지용)
    List<Order> findPlacedByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // kisOrderPort.place() 완료 후 PLACED 상태 + kisOrderId 기록
    void markPlaced(UUID orderId, String kisOrderId);

    // 기간+종목 필터 조회 (대시보드·텔레그램 이력 조회용)
    List<Order> findBy(LocalDate from, LocalDate to, Ticker ticker);

    // 기간 내 전체 계좌 조회 ticker 필터 없음 (관리자·이상징후 감지용)
    List<Order> findAll(LocalDate from, LocalDate to);
}
