package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingVariables;

import java.math.BigDecimal;

public interface TradingStrategy {
    TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice);
}
