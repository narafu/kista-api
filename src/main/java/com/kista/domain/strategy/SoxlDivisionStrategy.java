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
        // 수량 없으면 현재가를 기준가로 사용 (첫 진입 시 avgPrice가 없음)
        BigDecimal a = balance.soxlQty() == 0 ? currentPrice : balance.avgPrice();
        int q = balance.soxlQty();
        // 보유 자산액 = 기준가 × 수량
        BigDecimal m = a.multiply(BigDecimal.valueOf(q));
        BigDecimal d = balance.usdDeposit();  // 예수금 (현금 잔고)
        // 총 자산액 = 예수금 + 보유자산(매입원가 기준)
        BigDecimal b = d.add(m);
        // 슬롯 크기 = 총자산을 20등분한 단위 금액
        BigDecimal k = b.divide(BigDecimal.valueOf(20), 2, RoundingMode.HALF_UP);

        // 현재 층수: 보유자산이 몇 슬롯인지 (수량 없으면 0)
        int t;
        if (q == 0) {
            t = 0;
        } else {
            t = m.divide(k, 0, RoundingMode.FLOOR).intValue();
        }

        // 매수 비중: 층이 높을수록 줄어듦 (익절 진행 시 매수 축소)
        BigDecimal s = BigDecimal.valueOf(20 - t * 2L)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        // 익절 목표가 = 기준가의 120%
        BigDecimal p = a.multiply(new BigDecimal("1.2")).setScale(2, RoundingMode.HALF_UP);

        return new TradingVariables(a, q, m, d, b, k, t, s, p, currentPrice);
    }
}
