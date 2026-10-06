package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ReorderBuyBudget;

import java.time.LocalDate;
import java.util.UUID;

public interface ReorderBuyBudgetQuery {
    // tradeDate null이면 원본 주문 거래일
    ReorderBuyBudget query(UUID orderId, LocalDate tradeDate);
}
