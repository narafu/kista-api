package com.kista.tradingcore.stats.domain.backtest;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.InfiniteStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyStrategy;
import com.kista.tradingcore.matching.domain.strategy.ReverseInfiniteStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrStrategy;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase.PrivacyTrade;
import com.kista.tradingcore.stats.domain.model.DailyCandle;
import com.kista.tradingcore.stats.domain.model.backtest.BacktestCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// 백테스트 특성화(골든) 테스트 — R1(holdings int→BigDecimal) 전환 전 결과를 고정해 리팩터링이 수치를 바꾸지 않음을 잠근다
// 골든 파일이 없으면 현재 결과를 써 넣고 실패시킨다(재실행하면 비교 모드)
class BacktestGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("trading-core/src/test/resources/backtest-golden");
    private static final LocalDate START = LocalDate.parse("2024-01-02");
    private static final int DAYS = 300;

    // 리버스모드 진입 여부를 검증하기 위해 plan() 호출 중 리버스모드 일수를 센다 — 실제 전략에 그대로 위임
    private static final class CountingInfinite extends InfiniteCycleOrderStrategy {
        int reverseDays;

        CountingInfinite() {
            super(new InfiniteStrategy(), new ReverseInfiniteStrategy());
        }

        @Override
        public Optional<OrderPlan> plan(PlanContext ctx) {
            if (ctx.infinite().isReverseMode()) reverseDays++;
            return super.plan(ctx);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"infinite", "infinite-reverse", "vr", "privacy"})
    void 백테스트_결과가_골든과_같다(String scenario) {
        CountingInfinite infinite = new CountingInfinite();
        BacktestEngine engine = new BacktestEngine(new CycleOrderStrategies(List.of(
                infinite, new VrCycleOrderStrategy(new VrStrategy()),
                new PrivacyCycleOrderStrategy(new PrivacyStrategy()))));
        List<DailyCandle> candles = candles(scenario);

        BacktestEngine.Output output = scenario.equals("privacy")
                ? engine.run(candles, command(scenario), privacyBases(candles))
                : engine.run(candles, command(scenario));

        // 리버스 시나리오가 실제로 리버스모드를 탔는지(그리고 일반 시나리오는 안 탔는지) 고정
        if (scenario.equals("infinite-reverse")) assertThat(infinite.reverseDays).as("리버스모드 일수").isPositive();
        if (scenario.equals("infinite")) assertThat(infinite.reverseDays).as("리버스모드 일수").isZero();
        String rendered = render(output);
        assertThat(rendered).isEqualTo(readGolden(scenario, rendered));
    }

    // --- 캔들·명령 생성 ---

    // seed 고정 랜덤워크 — 시나리오별 일 드리프트만 다르다(infinite=상승, reverse=초반 상승 후 장기 하락)
    private static List<DailyCandle> candles(String scenario) {
        Random random = new Random(42);
        List<DailyCandle> candles = new ArrayList<>();
        double price = 100;
        LocalDate date = START;
        while (candles.size() < DAYS) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                int i = candles.size();
                double drift = switch (scenario) {
                    case "infinite" -> 0.006;
                    case "infinite-reverse" -> i < 30 ? 0.006 : -0.010;
                    default -> 0.0;
                };
                double open = price;
                double close = Math.max(1, open * (1 + drift + (random.nextDouble() * 2 - 1) * 0.04));
                double high = Math.max(open, close) * (1 + random.nextDouble() * 0.01);
                double low = Math.min(open, close) * (1 - random.nextDouble() * 0.01);
                candles.add(new DailyCandle(date, bd(open), bd(high), bd(low), bd(close)));
                price = close;
            }
            date = date.plusDays(1);
        }
        return candles;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static BacktestCommand command(String scenario) {
        LocalDate to = START.plusDays(500);
        return switch (scenario) {
            case "infinite" -> new BacktestCommand(StrategyType.INFINITE, StrategyTicker.SOXL, START, to,
                    new BigDecimal("10000"), 40, null, null, 0, null);
            case "infinite-reverse" -> new BacktestCommand(StrategyType.INFINITE, StrategyTicker.SOXL, START, to,
                    new BigDecimal("10000"), 20, null, null, 0, null);
            case "vr" -> new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ, START, to,
                    new BigDecimal("2000"), null, new BigDecimal("15"), 2, 100, new BigDecimal("1000"));
            case "privacy" -> new BacktestCommand(StrategyType.PRIVACY, StrategyTicker.SOXL, START, to,
                    new BigDecimal("10000"), null, null, null, 0, null);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    // 고정 기준 매매표(currentCycleStart 10000 → 배수 1.00) — 3번째 세션마다 한 장씩(LOC 지정가는 당일 종가 ±2%라 매수·매도 모두 체결), base.holdings를 오르내려 보유 감소(매도 보정)를 포함시킨다
    private static Map<LocalDate, PrivacyTradeBase> privacyBases(List<DailyCandle> candles) {
        int[] holdings = {0, 20, 40, 25, 10, 30, 5, 35};
        Map<LocalDate, PrivacyTradeBase> bases = new LinkedHashMap<>();
        for (int i = 3, k = 0; i < candles.size(); i += 3, k++) {
            DailyCandle c = candles.get(i);
            BigDecimal close = c.close();
            List<PrivacyTrade> trades = List.of(
                    trade(c.date(), OrderDirection.BUY, 3 + k % 4, close.multiply(new BigDecimal("1.02"))),
                    trade(c.date(), OrderDirection.SELL, 2 + k % 3, close.multiply(new BigDecimal("0.98"))));
            bases.put(c.date(), new PrivacyTradeBase(null, close, holdings[k % holdings.length],
                    new BigDecimal("10000"), trades));
        }
        return bases;
    }

    private static PrivacyTrade trade(LocalDate date, OrderDirection direction, int quantity, BigDecimal price) {
        return new PrivacyTrade(date, StrategyTicker.SOXL, OrderType.LOC, direction, quantity,
                price.setScale(2, RoundingMode.HALF_UP));
    }

    // --- 렌더·골든 입출력 ---

    private static String render(BacktestEngine.Output output) {
        StringBuilder sb = new StringBuilder();
        output.points().forEach(p -> sb.append(p.date()).append('|')
                .append(p.totalAsset().setScale(2, RoundingMode.HALF_UP).toPlainString()).append('\n'));
        return sb.append("trades=").append(output.tradeCount()).append(" cycles=").append(output.cycleCount())
                .append(" warnings=").append(output.warnings()).append('\n').toString();
    }

    // 골든이 없으면 현재 결과를 써 넣고 실패 — 사람이 내용을 확인한 뒤 재실행해 비교 모드로 전환한다
    private static String readGolden(String scenario, String current) {
        Path file = GOLDEN_DIR.resolve(scenario + ".txt");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(file, current);
                return fail("골든 생성됨 — 재실행 필요: " + file);
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
