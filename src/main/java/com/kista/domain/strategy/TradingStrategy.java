package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.Order;
import com.kista.domain.model.Ticker;
import com.kista.domain.model.TradingVariables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TradingStrategy {
    // Ticker로 종목별 targetProfitRate 결정
    TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice, Ticker ticker);
    List<Order> buildOrders(TradingVariables vars, LocalDate tradeDate, Ticker ticker); // 주문 목록 생성 (포트 호출 없음)
}
