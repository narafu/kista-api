package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    // account_id + trade_date + status = PLANNED 조건으로 실행 대상 조회
    List<OrderEntity> findByAccountIdAndTradeDateAndStatus(
            UUID accountId, LocalDate tradeDate, Order.OrderStatus status);

    // KIS 접수 실패 시 누적 PLANNED 주문 정리
    void deleteAllByAccountIdAndTradeDateAndStatus(
            UUID accountId, LocalDate tradeDate, Order.OrderStatus status);

    // 기간+종목 필터 (대시보드용)
    List<OrderEntity> findByTradeDateBetweenAndTicker(LocalDate from, LocalDate to, Ticker ticker);

    // 기간 전체 (관리자용)
    List<OrderEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
}
