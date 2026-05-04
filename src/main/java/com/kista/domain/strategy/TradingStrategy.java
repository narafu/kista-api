package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.Order;
import com.kista.domain.model.TradingVariables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TradingStrategy {
    TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice);
    List<Order> buildOrders(TradingVariables vars, LocalDate tradeDate, String symbol); // 주문 목록 생성 (포트 호출 없음)
}
