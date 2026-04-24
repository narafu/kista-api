package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingVariables;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class SoxlDivisionStrategy implements TradingStrategy {

    @Override
    public TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice) {
        BigDecimal a = balance.soxlQty() == 0 ? currentPrice : balance.avgPrice();
        int q = balance.soxlQty();
        BigDecimal m = a.multiply(BigDecimal.valueOf(q));
        BigDecimal d = balance.effectiveAmt();
        BigDecimal b = d.add(m);
        BigDecimal k = b.divide(BigDecimal.valueOf(20), 2, RoundingMode.HALF_UP);

        int t;
        if (q == 0) {
            t = 0;
        } else {
            t = m.divide(k, 0, RoundingMode.FLOOR).intValue();
        }

        BigDecimal s = BigDecimal.valueOf(20 - t * 2L)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal p = a.multiply(new BigDecimal("1.2")).setScale(2, RoundingMode.HALF_UP);

        return new TradingVariables(a, q, m, d, b, k, t, s, p, currentPrice);
    }
}
