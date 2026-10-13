package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminMarketPriceAdapter — 소스 체인")
class AdminMarketPriceAdapterTest {

    static final StrategyTicker A = StrategyTicker.SOXL;
    static final StrategyTicker B = StrategyTicker.TQQQ;

    // 가짜 소스 — 요청 종목 기록 + 응답 함수 주입
    static class FakeSource implements AdminPriceSource {
        final boolean enabled;
        final Function<List<StrategyTicker>, Map<StrategyTicker, BigDecimal>> response;
        final List<List<StrategyTicker>> calls = new ArrayList<>();

        FakeSource(boolean enabled, Function<List<StrategyTicker>, Map<StrategyTicker, BigDecimal>> response) {
            this.enabled = enabled;
            this.response = response;
        }

        public String name() { return "fake"; }
        public boolean enabled() { return enabled; }
        public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> t) { calls.add(t); return response.apply(t); }
        public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> t) { calls.add(t); return response.apply(t); }
        public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> t, LocalDate d) { calls.add(t); return response.apply(t); }
    }

    static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("1순위가 전부 성공하면 2순위는 호출하지 않는다")
    void firstSourceCoversAll() {
        var first = new FakeSource(true, t -> Map.of(A, bd("1"), B, bd("2")));
        var second = new FakeSource(true, t -> Map.of());

        var result = new AdminMarketPriceAdapter(List.of(first, second)).getPrices(List.of(A, B));

        assertThat(result).containsEntry(A, bd("1")).containsEntry(B, bd("2"));
        assertThat(second.calls).isEmpty();
    }

    @Test
    @DisplayName("1순위 누락분만 2순위에 전달한다")
    void onlyMissingGoesToNext() {
        var first = new FakeSource(true, t -> Map.of(A, bd("1")));
        var second = new FakeSource(true, t -> Map.of(B, bd("2")));

        var result = new AdminMarketPriceAdapter(List.of(first, second)).getPrevCloses(List.of(A, B));

        assertThat(second.calls).containsExactly(List.of(B));
        assertThat(result).containsEntry(A, bd("1")).containsEntry(B, bd("2"));
    }

    @Test
    @DisplayName("비활성 소스는 건너뛰고 누락은 결과에서 빠진다")
    void disabledSourceSkipped() {
        var first = new FakeSource(true, t -> Map.of(A, bd("1")));
        var second = new FakeSource(false, t -> Map.of(B, bd("2")));

        var result = new AdminMarketPriceAdapter(List.of(first, second)).getPrices(List.of(A, B));

        assertThat(second.calls).isEmpty();
        assertThat(result).containsOnlyKeys(A);
    }

    @Test
    @DisplayName("소스 예외는 삼키고 다음 소스로 진행한다")
    void exceptionFallsThrough() {
        var first = new FakeSource(true, t -> { throw new IllegalStateException("boom"); });
        var second = new FakeSource(true, t -> Map.of(A, bd("1"), B, bd("2")));

        var result = new AdminMarketPriceAdapter(List.of(first, second)).getClosingPrices(List.of(A, B), LocalDate.of(2026, 10, 14));

        assertThat(second.calls).containsExactly(List.of(A, B));
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("null 값은 누락으로 취급해 다음 소스로 넘긴다")
    void nullValueTreatedAsMissing() {
        Map<StrategyTicker, BigDecimal> withNull = new HashMap<>();
        withNull.put(A, null);
        withNull.put(B, bd("2"));
        var first = new FakeSource(true, t -> withNull);
        var second = new FakeSource(true, t -> Map.of(A, bd("1")));

        var result = new AdminMarketPriceAdapter(List.of(first, second)).getPrices(List.of(A, B));

        assertThat(second.calls).containsExactly(List.of(A));
        assertThat(result).containsEntry(A, bd("1")).containsEntry(B, bd("2"));
    }

    @Test
    @DisplayName("빈 요청은 소스 호출 없이 빈 Map")
    void emptyRequest() {
        var first = new FakeSource(true, t -> Map.of());

        assertThat(new AdminMarketPriceAdapter(List.of(first)).getPrices(List.of())).isEmpty();
        assertThat(first.calls).isEmpty();
    }
}
