package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ReorderBuyBudget;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReorderBuyBudgetQuery {
    // orderIds는 같은 계좌 소속 1~50건, tradeDate null이면 첫 원본 주문 거래일
    ReorderBuyBudget query(List<UUID> orderIds, LocalDate tradeDate);
}
