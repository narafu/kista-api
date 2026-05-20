package com.kista.domain.model.strategy;
import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TradingReport(
        LocalDate date,                  // 거래일
        TradingSnapshot snapshot,        // 당일 전략 계산 스냅샷
        List<Order> mainOrders,          // LOC 자동 주문 목록
        List<Order> correctionOrders,    // 미체결 보정 LIMIT 주문 목록
        BigDecimal totalBoughtUsd,       // 당일 총 매수 체결액 (USD)
        BigDecimal totalSoldUsd          // 당일 총 매도 체결액 (USD)
) {}
