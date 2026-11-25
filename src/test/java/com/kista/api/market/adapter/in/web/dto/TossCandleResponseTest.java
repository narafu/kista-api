package com.kista.api.market.adapter.in.web.dto;

import com.kista.shared.contract.broker.DailyCandleResponse;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaIndicators;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.strategy.DcaConditions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TossCandleResponseTest {

    // 임의 종가 시계열 → 일봉 (300개: RSI 창 250 초과 구간 포함)
    private static List<DailyCandleResponse> candles(int n) {
        Random r = new Random(42);
        double price = 100;
        List<DailyCandleResponse> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            price = Math.max(1, price + r.nextGaussian() * 2);
            BigDecimal p = BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP);
            list.add(new DailyCandleResponse(LocalDate.of(2024, 1, 1).plusDays(i), p, p, p, p, 1000));
        }
        return list;
    }

    @Test
    void 각_캔들의_지표는_판정용_계산을_같은_창으로_부른_값과_같다() {
        List<DailyCandleResponse> candles = candles(300);
        List<BigDecimal> closes = candles.stream().map(DailyCandleResponse::close).toList();

        List<TossCandleResponse> res = TossCandleResponse.fromList(candles);

        assertThat(res).hasSize(300);
        for (int i = 0; i < res.size(); i++) {
            List<BigDecimal> upTo = closes.subList(0, i + 1);
            List<BigDecimal> window = closes.subList(Math.max(0, i + 1 - DcaIndicators.CLOSE_WINDOW), i + 1);
            TossCandleResponse c = res.get(i);
            assertThat(c.sma20()).isEqualTo(DcaIndicators.sma(upTo, 20).orElse(null));
            assertThat(c.sma50()).isEqualTo(DcaIndicators.sma(upTo, 50).orElse(null));
            assertThat(c.sma120()).isEqualTo(DcaIndicators.sma(upTo, 120).orElse(null));
            assertThat(c.sma200()).isEqualTo(DcaIndicators.sma(upTo, 200).orElse(null));
            assertThat(c.rsi14()).isEqualTo(DcaIndicators.rsiWilder(window, 14).orElse(null));
        }
        // 데이터 부족 구간은 null
        assertThat(res.get(18).sma20()).isNull();
        assertThat(res.get(19).sma20()).isNotNull();
        assertThat(res.get(13).rsi14()).isNull();
        assertThat(res.get(14).rsi14()).isNotNull();
        assertThat(DcaIndicator.SMA_PERIODS).containsExactly(20, 50, 120, 200);
    }

    @Test
    void 마지막_캔들의_지표는_DCA_판정_경로가_계산하는_값과_같다() {
        // DCA 판정은 최근 CLOSE_WINDOW개 종가를 DcaConditions에 넘긴다
        List<DailyCandleResponse> candles = candles(300);
        List<BigDecimal> all = candles.stream().map(DailyCandleResponse::close).toList();
        List<BigDecimal> dcaCloses = all.subList(all.size() - DcaIndicators.CLOSE_WINDOW, all.size());
        TossCandleResponse last = TossCandleResponse.fromList(candles).getLast();

        // 항상 미충족하는 조건으로 detail에 찍히는 판정용 지표값을 읽는다
        String rsiDetail = detail(new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, BigDecimal.ZERO), dcaCloses);
        assertThat(rsiDetail).startsWith("RSI(14) " + last.rsi14().toPlainString() + " ");
        for (int p : DcaIndicator.SMA_PERIODS) {
            BigDecimal sma = switch (p) { case 20 -> last.sma20(); case 50 -> last.sma50(); case 120 -> last.sma120(); default -> last.sma200(); };
            String d = detail(new DcaCondition(DcaIndicator.SMA, DcaOperator.LTE, p, null), dcaCloses);
            assertThat(d).contains("SMA(" + p + ") " + sma.toPlainString());
        }
    }

    private static DcaRule rule(DcaCondition cond) {
        return new DcaRule("k", OrderDirection.BUY, DcaFrequency.DAILY, null, null, OrderType.MOC,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal("300"), null), DcaShortfallPolicy.PARTIAL, List.of(cond));
    }

    private static String detail(DcaCondition cond, List<BigDecimal> closes) {
        var skipped = DcaConditions.check(rule(cond), Currency.USD, closes, null, null, closes.getLast());
        if (skipped.isPresent()) return skipped.get().detail();
        // 종가 <= SMA로 통과했으면 반대 방향 조건으로 다시 판정해 detail을 얻는다
        DcaCondition flipped = new DcaCondition(cond.indicator(), DcaOperator.GTE, cond.smaPeriod(), cond.threshold());
        return DcaConditions.check(rule(flipped), Currency.USD, closes, null, null, closes.getLast()).orElseThrow().detail();
    }
}
