package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.DailyTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyTransactionResponseTest {

    private static DailyTransaction tx(String date, OrderDirection dir, String amount, Currency cur) {
        return new DailyTransaction(date, dir, StrategyTicker.BITO, "BITO", BigDecimal.ONE, new BigDecimal(amount),
                new BigDecimal(amount), cur);
    }

    @Test
    void 빈_목록은_빈_byDate() {
        assertThat(DailyTransactionResponse.summarize(List.of())).isEmpty();
    }

    @Test
    void 같은_날_두_통화는_KRW_USD_순으로_따로_합산한다() {
        var days = DailyTransactionResponse.summarize(List.of(
                tx("2024-01-05", OrderDirection.BUY, "10.50", Currency.USD),
                tx("2024-01-05", OrderDirection.BUY, "1000", Currency.KRW)));

        assertThat(days).hasSize(1);
        assertThat(days.get(0).tradeCount()).isEqualTo(2);
        assertThat(days.get(0).byCurrency()).extracting(DailyTransactionResponse.CurrencyDto::currency)
                .containsExactly(Currency.KRW, Currency.USD);
    }

    @Test
    void 매수_매도_섞이면_방향별_합계와_건수를_내고_날짜는_오름차순() {
        var days = DailyTransactionResponse.summarize(List.of(
                tx("2024-01-06", OrderDirection.SELL, "5", Currency.USD),
                tx("2024-01-05", OrderDirection.BUY, "10.10", Currency.USD),
                tx("2024-01-05", OrderDirection.BUY, "20.20", Currency.USD),
                tx("2024-01-05", OrderDirection.SELL, "7.00", Currency.USD)));

        assertThat(days).extracting(DailyTransactionResponse.DayDto::date).containsExactly("2024-01-05", "2024-01-06");
        var usd = days.get(0).byCurrency().get(0);
        assertThat(usd.buyAmount()).isEqualByComparingTo("30.30");
        assertThat(usd.buyCount()).isEqualTo(2);
        assertThat(usd.sellAmount()).isEqualByComparingTo("7.00");
        assertThat(usd.sellCount()).isEqualTo(1);
        assertThat(days.get(0).tradeCount()).isEqualTo(3);
    }
}
