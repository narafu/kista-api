package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;

import java.math.BigDecimal;
import java.util.List;

public interface PrivacyTradingStrategy {
    List<Order> buildOrders(AccountBalance balance, BigDecimal multiple, PrivacyTradeBase privacyTradeBase);
}
