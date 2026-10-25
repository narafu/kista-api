package com.kista.tradingcore.matching.domain.strategy;

import com.kista.tradingcore.matching.domain.strategy.InfiniteStrategy;

import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.shared.kernel.OrderDirection.BUY;
import static com.kista.shared.kernel.OrderDirection.SELL;
import static com.kista.shared.kernel.OrderType.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InfiniteStrategy 매매 변수 계산 검증")
class InfiniteStrategyTypeTest {

    private final InfiniteStrategy strategy = new InfiniteStrategy();
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);

    // ── buildOrders() 시나리오 ──────────────────────────────────────────────

    @Test
    @DisplayName("buildOrders 전반 holdings>0: LOC매수①② + 보정3 + LOC매도 + 지정가매도 = 7건")
    void buildOrders_frontHalf_withQuantity() {
        // averagePrice=20, holdings=10, usdDeposit=1000 → totalAssets=1200, unitAmount=60, currentRound=3.33, priceOffsetRate=0.13 (전반)
        // referencePrice=20×1.13=22.60, targetPrice=20×1.20=24.00
        // BUY①: floor(60/2/20)=1, BUY②: floor((60−20×1)/22.60)=floor(1.77)=1
        // 보정: 60/3=20.00, 60/4=15.00, 60/5=12.00 (FLOOR)
        // LOC SELL: 10/4=2, LIMIT SELL: 10-2=8
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("1000"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("21"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(7);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.quantity() == 1 && o.price().compareTo(position.averagePrice()) == 0);
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.quantity() == 1 && o.price().compareTo(position.referencePrice()) == 0);
        assertThat(orders.get(2).price()).isEqualByComparingTo("20.00");
        assertThat(orders.get(3).price()).isEqualByComparingTo("15.00");
        assertThat(orders.get(4).price()).isEqualByComparingTo("12.00");
        assertThat(orders.get(5)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.quantity() == 2);
        assertThat(orders.get(6)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.quantity() == 8 && o.price().compareTo(position.targetPrice()) == 0);
        assertThat(orders).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                        "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03",
                        "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
        assertThat(orders).allMatch(o -> o.ticker() == StrategyTicker.SOXL && o.tradeDate().equals(TODAY));
    }

    @Test
    @DisplayName("buildOrders 후반 cap 없음: base 뒤 SELL 앞에 보정 3건이 붙고 각 보정은 unitAmount 이내")
    void buildOrders_backHalf_noCap_attachesCorrections() {
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"), new BigDecimal("100"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("4.9"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02",
                        "INFINITE_CORRECTION_03", "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
        int cumulative = orders.getFirst().quantity();
        for (int i = 1; i <= 3; i++) {
            assertThat(orders.get(i).price().multiply(BigDecimal.valueOf(cumulative + 1)))
                    .isLessThanOrEqualTo(position.unitAmount());
            cumulative += 1;
        }
    }

    @Test
    @DisplayName("buildOrders 0회차 소액 원장: base가 이미 원장 초과면 보정 전부 제거, base 유지")
    void buildOrders_zeroRound_smallDeposit_trimsAllCorrections() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("20"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("100"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).extracting(PlannedOrder::orderLeg).containsExactly("INFINITE_EARLY_AVG_BUY");
    }

    @Test
    @DisplayName("buildOrders 교착 시드: 뒤쪽 보정부터 제거되어 prefix만 남고 BUY 합이 예수금 이내")
    void buildOrders_deadlockSeed_trimsTrailingCorrections() {
        InfinitePosition position = deadlockSeedPosition();

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        List<PlannedOrder> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        List<String> corrections = buys.stream().map(PlannedOrder::orderLeg)
                .filter(l -> l.startsWith("INFINITE_CORRECTION_")).toList();
        assertThat(corrections).isNotEmpty().hasSizeLessThan(3);
        assertThat(corrections).isEqualTo(List.of("INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03")
                .subList(0, corrections.size()));
        assertThat(AccountBalance.buyTotal(buys)).isLessThanOrEqualTo(position.usdDeposit());
    }

    @Test
    @DisplayName("buildOrders 최종회차: 보정 주문 없음")
    void buildOrders_finalRound_noCorrections() {
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"), new BigDecimal("50"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("4.8"), 20);

        assertThat(strategy.buildOrders(position, TODAY)).extracting(PlannedOrder::orderLeg)
                .noneMatch(l -> l.startsWith("INFINITE_CORRECTION_"));
    }

    @Test
    @DisplayName("buildOrders 0회차: 최근 종가를 평단가 대용으로 사용 — LOC매수①② + 보정3 = 5건, SELL 없음")
    void buildOrders_zeroRound_usesPrevClose() {
        // 0회차: averagePrice=prevClose=22 (현재가 20과 무관), holdings=0, usdDeposit=1000 → totalAssets=1000, unitAmount=50
        // referencePrice=22×1.20=26.40
        // BUY①: floor(50/2/22)=1(비용=22), BUY②: floor((50-22)/26.40)=floor(1.06)=1
        // SELL: holdings/4=0 → 없음
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("22"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        // 보정 3건 상시 부착: 50/3=16.66, 50/4=12.50, 50/5=10.00 (FLOOR)
        assertThat(orders).hasSize(5);
        // 평단가 대용 = 최근 종가(22), 현재가(20)가 아님
        assertThat(position.averagePrice()).isEqualByComparingTo("22");
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.price().compareTo(position.averagePrice()) == 0); // 종가 기준
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.price().compareTo(position.referencePrice()) == 0); // 종가 기반 referencePrice
        assertThat(orders.subList(2, 5)).extracting(PlannedOrder::price)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("16.66"), new BigDecimal("12.50"), new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("buildOrders 후반 기준가 1주>원장: MOC 매도 1건만")
    void lateStageOneShareOverDepositEmitsMocSellOnly() {
        // unitAmount=22 <= usdDeposit=25 이지만 기준가(≈34) > 25 → 1주도 못 사므로 최종회차
        AccountBalance balance = new AccountBalance(10, new BigDecimal("41.5"), new BigDecimal("25"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("40"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).extracting(PlannedOrder::orderLeg).containsExactly("INFINITE_MOC_SELL");
        assertThat(orders.getFirst().quantity()).isEqualTo(2); // 10/4
    }

    @Test
    @DisplayName("buildOrders 후반 unitAmount>usdDeposit: MOC 매도 1건만")
    void buildOrders_backHalf_insufficientDeposit() {
        // averagePrice=5, holdings=200, usdDeposit=50 → totalAssets=1050, unitAmount=52.50, currentRound≈19.05 (후반), unitAmount(52.50)>usdDeposit(50)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("50"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("4.8"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == MOC && o.direction() == SELL && o.quantity() == 50); // 200/4
        assertThat(orders).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_MOC_SELL");
    }

    @Test
    @DisplayName("buildOrders 후반 unitAmount<=usdDeposit: LOC매수 + 보정3 + LOC매도 + 지정가매도 = 6건")
    void buildOrders_backHalf_sufficientDeposit() {
        // averagePrice=5, holdings=200, usdDeposit=100 → totalAssets=1100, unitAmount=55, currentRound≈18.18 (후반), unitAmount(55)<=usdDeposit(100)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("100"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("4.9"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(6); // 보정 3건 상시 부착
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY);
        assertThat(orders.get(4)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.quantity() == 50); // 200/4
        assertThat(orders.get(5)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.quantity() == 150); // 200-50
        assertThat(orders).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02",
                        "INFINITE_CORRECTION_03", "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
    }

    @Test
    @DisplayName("buildOrders 후반 소액 예수금: 1주 미만 계산이어도 LOC매수 1주 보장")
    void buildOrders_backHalf_smallDeposit_guaranteesMinimumOneShare() {
        // averagePrice=48.31, holdings=10, usdDeposit=455.90 → totalAssets=939.00, unitAmount=46.95, currentRound≈10.29 (후반)
        // referencePrice=48.31, late buy qty=floor(46.95/48.31)=0 이어도 최소 1주
        AccountBalance balance = new AccountBalance(10, new BigDecimal("48.31"),
                new BigDecimal("455.90"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.MAGX, new BigDecimal("48.00"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(6); // 보정 3건 상시 부착
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.quantity() == 1 && o.price().compareTo(new BigDecimal("48.31")) == 0);
        assertThat(orders.get(4)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.quantity() == 2);
        assertThat(orders.get(5)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.quantity() == 8
                && o.price().compareTo(new BigDecimal("55.56")) == 0);
    }

    @Test
    @DisplayName("buildOrders 0회차 + 소액 예수금: 매수①은 최소 1주, 매수①이 unitAmount를 다 쓰면 매수② 없음 = 1건")
    void buildOrders_zeroRound_smallDeposit_guaranteesMinimumOneShare() {
        // 0회차: averagePrice=prevClose=100, usdDeposit=20 → totalAssets=20, unitAmount=1.00
        // BUY①: floor((1.00/2)/100)=0 → 최소 1
        // BUY②: 잔여 1.00-100×1<0 → 0주(미생성)
        // SELL: holdings=0 → 없음
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("20"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("100"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY && o.quantity() == 1
                && o.price().compareTo(new BigDecimal("100")) == 0);
    }

    @Test
    @DisplayName("buildOrders TQQQ: ticker가 TQQQ로 설정됨")
    void buildOrders_tqqq_tickerSet() {
        // 0회차: averagePrice=prevClose=10 (현재가 9와 무관)
        // totalAssets=2000, unitAmount=100, referencePrice=10×1.15=11.50
        // BUY①: floor(100/2/10)=5, BUY②: floor(100/2/11.50)=4 → 주문 있음
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("2000"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.TQQQ, new BigDecimal("10"), 20);

        List<PlannedOrder> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).isNotEmpty();
        assertThat(orders).allMatch(o -> o.ticker() == StrategyTicker.TQQQ);
    }

    // ── buildCappedBuyOrders() 시나리오 ──────────────────────────────────────
    // holdings=0 → unitAmount = usdDeposit ÷ 20

    private InfinitePosition positionWithUnitAmount(String usdDeposit) {
        return new InfinitePosition(
                new AccountBalance(0, null, new BigDecimal(usdDeposit)),
                StrategyTicker.SOXL, new BigDecimal("10.00"), 20);
    }

    // 평단 50, 매입액=예수금(currentRound=10) → priceOffsetRate 0 = 후반
    private InfinitePosition lateStagePosition(int holdings, String usdDeposit) {
        return new InfinitePosition(new AccountBalance(holdings, new BigDecimal("50"), new BigDecimal(usdDeposit)),
                StrategyTicker.SOXL, new BigDecimal("50"), 20);
    }

    private PlannedOrder buy(String price, int quantity) {
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, quantity, new BigDecimal(price));
    }

    private PlannedOrder buy(String price, int quantity, String orderLeg) {
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, quantity, new BigDecimal(price), orderLeg);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 2건, 캡 후 가격 상이: ①②+보정 3건 = 5건")
    void buildCappedBuyOrders_twoBuys_distinctCaps_recalculatesBoth() {
        // cap=55, unitAmount=1000 / buy①=60(>cap→55), buy②=52(≤cap)
        // qty1 = floor((1000/2) / 55) = 9 / qty2 = floor((1000-55×9) / 52) = floor(9.71) = 9
        // 보정 3회: unitAmount/(18+1)=52.63, unitAmount/(19+1)=50.00, unitAmount/(20+1)=47.61 — 각 1주 LOC
        InfinitePosition position = positionWithUnitAmount("20000");
        List<PlannedOrder> buyOrders = List.of(
                buy("60.00", 1, "INFINITE_EARLY_AVG_BUY"),
                buy("52.00", 1, "INFINITE_EARLY_REF_BUY"));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        assertThat(result).hasSize(5);
        assertThat(result.getFirst().quantity()).isEqualTo(9);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).quantity()).isEqualTo(9);
        assertThat(result.get(1).price()).isEqualByComparingTo("52.00");
        assertThat(result.get(2).quantity()).isEqualTo(1);
        assertThat(result.get(2).price()).isEqualByComparingTo("52.63");
        assertThat(result.get(3).quantity()).isEqualTo(1);
        assertThat(result.get(3).price()).isEqualByComparingTo("50.00");
        assertThat(result.get(4).quantity()).isEqualTo(1);
        assertThat(result.get(4).price()).isEqualByComparingTo("47.61");
        assertThat(result).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                        "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
    }

    // 전반 포지션(평단 50, unitAmount 1000) — buildOrders의 base BUY(AVG 50×10, REF 60×8)를 (avg, ref) 순서로 반환
    private List<PlannedOrder> earlyBaseBuys() {
        InfinitePosition position = earlyPosition();
        List<PlannedOrder> base = strategy.buildOrders(position, TODAY).stream()
                .filter(o -> o.direction() == BUY && !o.orderLeg().startsWith("INFINITE_CORRECTION_")).toList();
        assertThat(base).extracting(PlannedOrder::orderLeg).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");
        return base;
    }

    private InfinitePosition earlyPosition() {
        return new InfinitePosition(new AccountBalance(0, null, new BigDecimal("20000")),
                StrategyTicker.SOXL, new BigDecimal("50"), 20);
    }

    private void assertEarlyCappedAt5250(List<PlannedOrder> result) {
        assertThat(result).extracting(PlannedOrder::orderLeg).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
        assertThat(result).extracting(PlannedOrder::quantity).containsExactly(10, 9, 1, 1, 1);
        assertThat(result.get(0).price()).isEqualByComparingTo("50");
        assertThat(result.get(1).price()).isEqualByComparingTo("52.50");
        assertThat(result.get(2).price()).isEqualByComparingTo("50.00");
        assertThat(result.get(3).price()).isEqualByComparingTo("47.61");
        assertThat(result.get(4).price()).isEqualByComparingTo("45.45");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반, 입력 순서가 뒤집혀도 leg로 ①②를 식별해 같은 결과")
    void buildCappedBuyOrders_earlyStage_reversedInputOrder_sameResult() {
        List<PlannedOrder> base = earlyBaseBuys();
        BigDecimal cap = new BigDecimal("52.50");

        List<PlannedOrder> normal = strategy.buildCappedBuyOrders(earlyPosition(), TODAY, base, cap);
        List<PlannedOrder> reversed = strategy.buildCappedBuyOrders(earlyPosition(), TODAY, List.of(base.get(1), base.get(0)), cap);

        assertEarlyCappedAt5250(normal);
        assertThat(reversed).isEqualTo(normal);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반, REF leg만 남아도 매수①을 평단가로 재구성해 같은 결과")
    void buildCappedBuyOrders_earlyStage_onlyRefLegRemains_rebuildsAvg() {
        List<PlannedOrder> base = earlyBaseBuys();

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(earlyPosition(), TODAY, List.of(base.get(1)), new BigDecimal("52.50"));

        assertEarlyCappedAt5250(result);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 REF leg + legacy UNKNOWN 혼합: UNKNOWN 행이 비어 있는 매수① 자리를 채운다")
    void buildCappedBuyOrders_earlyStage_refLegWithLegacyAvg_fillsAvgFromLegacy() {
        // REF leg가 먼저 와도 legacy(UNKNOWN_LEG) 평단가 행이 매수①로 쓰여 정상 입력과 같은 결과
        List<PlannedOrder> base = earlyBaseBuys();
        PlannedOrder legacyAvg = PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY,
                base.getFirst().quantity(), base.getFirst().price());

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(earlyPosition(), TODAY,
                List.of(base.get(1), legacyAvg), new BigDecimal("52.50"));

        assertThat(result.getFirst().orderLeg()).isEqualTo(PlannedOrder.UNKNOWN_LEG);
        assertThat(result).extracting(PlannedOrder::quantity).containsExactly(10, 9, 1, 1, 1);
        assertThat(result).extracting(PlannedOrder::price).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50"), new BigDecimal("52.50"), new BigDecimal("50.00"),
                        new BigDecimal("47.61"), new BigDecimal("45.45"));
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 MERGED leg + legacy UNKNOWN 혼합: legacy 행을 매수②로 쓰지 않아 MERGED 단독과 같은 결과")
    void buildCappedBuyOrders_earlyStage_mergedLegWithLegacy_ignoresLegacyAsBuy2() {
        PlannedOrder merged = PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, 18, new BigDecimal("50"), InfiniteStrategy.EARLY_MERGED_LEG);
        PlannedOrder legacy = PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, 3, new BigDecimal("40"));

        List<PlannedOrder> mergedOnly = strategy.buildCappedBuyOrders(earlyPosition(), TODAY, List.of(merged), new BigDecimal("52.50"));
        List<PlannedOrder> mixed = strategy.buildCappedBuyOrders(earlyPosition(), TODAY, List.of(legacy, merged), new BigDecimal("52.50"));

        assertThat(mixed).isEqualTo(mergedOnly);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 2건, 동일 캡으로 수렴: 병합 1건+보정 3건 = 4건")
    void buildCappedBuyOrders_twoBuys_convergeToSameCap_mergesIntoOne() {
        // cap=55, buy①=70·buy②=60 모두 55로 캡 → 동일 가격이면 병합
        // qty1 = floor((1000/2) / 55) = 9 / qty2 = floor((1000-55×9) / 55) = floor(9.18) = 9 / merged = 18
        InfinitePosition position = positionWithUnitAmount("20000");
        List<PlannedOrder> buyOrders = List.of(
                buy("70.00", 1, "INFINITE_EARLY_AVG_BUY"),
                buy("60.00", 1, "INFINITE_EARLY_REF_BUY"));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        // 병합 1건 + 보정 3회: unitAmount/(18+1)=52.63, unitAmount/(19+1)=50.00, unitAmount/(20+1)=47.61 (FLOOR) — 각 1주 LOC
        assertThat(result).hasSize(4);
        assertThat(result.getFirst().quantity()).isEqualTo(18);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("52.63");
        assertThat(result.get(2).price()).isEqualByComparingTo("50.00");
        assertThat(result.get(3).price()).isEqualByComparingTo("47.61");
        assertThat(result).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_EARLY_MERGED_BUY",
                        "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 후반 1건: 재산정 1건+보정 3건 = 4건")
    void buildCappedBuyOrders_singleBuy_capped_recalculatesQuantity() {
        // 후반 단일 LOC 매수 — cap=55, unitAmount=1000 / qty = floor(1000/55) = 18
        InfinitePosition position = lateStagePosition(200, "10000");
        List<PlannedOrder> buyOrders = List.of(buy("70.00", 1));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        // 재산정 1건 + 보정 3회: unitAmount/(18+1)=52.63, unitAmount/(19+1)=50.00, unitAmount/(20+1)=47.61 — 각 1주 LOC
        assertThat(result).hasSize(4);
        assertThat(result.getFirst().quantity()).isEqualTo(18);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("52.63");
        assertThat(result.get(2).price()).isEqualByComparingTo("50.00");
        assertThat(result.get(3).price()).isEqualByComparingTo("47.61");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 비0회차 전반(currentRound=9): buy② 수량이 priceOffsetRate 기준 — targetProfitRate 사용 시 수량 달라짐")
    void buildCappedBuyOrders_nonZeroRound_usesOffsetRateNotProfitRate() {
        // holdings=180, avgPrice=5, usdDeposit=1100 → totalAssets=2000, unitAmount=100
        // currentRound=9.00, priceOffsetRate=0.20×(1−0.9)=0.02, referencePrice=5.10, targetProfitRate=0.20, targetPrice=6.00
        // priceOffsetRate(0.02) ≠ targetProfitRate(0.20) → 수량 공식 rate 선택이 결과에 영향
        // BUY①: floor(50/5.00)=10, BUY②(priceOffsetRate): floor(50/5.10)=9 — targetProfitRate 사용 시: floor(50/6.00)=8
        AccountBalance balance = new AccountBalance(180, new BigDecimal("5"), new BigDecimal("1100"));
        InfinitePosition position = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("5"), 20);

        // 정상 경로로 원본 주문 생성 후 BUY만 추출
        List<PlannedOrder> allOrders = strategy.buildOrders(position, TODAY);
        List<PlannedOrder> buyOrders = allOrders.stream().filter(o -> o.direction() == BUY).toList();

        // cap=10.00 — referencePrice(5.10)보다 높아 실제 캡 없음: rate 선택만 검증
        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("10.00"));

        // buy①+buy②+보정3 = 5건
        assertThat(result).hasSize(5);
        assertThat(result.getFirst().quantity()).isEqualTo(10);
        assertThat(result.getFirst().price()).isEqualByComparingTo("5.00");
        // qty=9 (priceOffsetRate=0.02 기준), targetProfitRate(0.20) 사용 시 qty=8
        assertThat(result.get(1).quantity()).isEqualTo(9);
        assertThat(result.get(1).price()).isEqualByComparingTo("5.10");
        // 보정: unitAmount/(19+1)=5.00, /(20+1)=4.76, /(21+1)=4.54 — 각 1주 LOC
        assertThat(result.get(2).price()).isEqualByComparingTo("5.00");
        assertThat(result.get(3).price()).isEqualByComparingTo("4.76");
        assertThat(result.get(4).price()).isEqualByComparingTo("4.54");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 후반 1건, 캡 후 수량 0이어도 최소 1주+보정 3건")
    void buildCappedBuyOrders_singleBuy_cappedQuantityZero_guaranteesMinimumOneShare() {
        // cap=55, unitAmount=50 / qty = floor(50/55) = 0 이어도 최소 1주
        InfinitePosition position = lateStagePosition(10, "500"); // holdings=10·평단 50·usdDeposit=500 → 총자산 1000÷20 = unitAmount 50, 후반
        List<PlannedOrder> buyOrders = List.of(buy("200.00", 1));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        assertThat(result).hasSize(4);
        assertThat(result.getFirst().quantity()).isEqualTo(1);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("25.00");
        assertThat(result.get(2).price()).isEqualByComparingTo("16.66");
        assertThat(result.get(3).price()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("buildCappedBuyOrders base 없이 correction leg만 입력되면 재산정 없이 원본 그대로 반환(방어적 가드)")
    void buildCappedBuyOrders_onlyCorrectionLegsNoBase_returnsInputUnchangedWithoutCrashing() {
        // 부분 저장 실패 등으로 base 주문 없이 correction leg만 남은 비정상 상태 — IndexOutOfBounds 없이 안전하게 원본 유지
        InfinitePosition position = positionWithUnitAmount("20000");
        List<PlannedOrder> buyOrders = List.of(
                buy("52.63", 1, "INFINITE_CORRECTION_01"),
                buy("50.00", 1, "INFINITE_CORRECTION_02"),
                buy("47.62", 1, "INFINITE_CORRECTION_03"));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("40.00"));

        assertThat(result).isSameAs(buyOrders);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 재진입(입력에 correction leg 3건 혼입): base 1건만 재산정 대상, correction은 무시하고 새로 생성")
    void buildCappedBuyOrders_reentrantWithExistingCorrectionLegs_ignoresThemAndRecalculatesBaseOnly() {
        // 동일 close 배치 내에서 접수 직전 재조회 가격이 최초 계산 시점보다 한 번 더 하락해
        // 이미 캡+correction 4건이 저장된 상태로 capIfNeeded가 재호출되는 시나리오.
        // correction leg가 buy②(기준가)로 오인되면 안 되고, base 1건만 재산정 대상이어야 한다.
        InfinitePosition position = lateStagePosition(200, "10000");
        List<PlannedOrder> buyOrders = List.of(
                buy("55.00", 18, "INFINITE_LATE_REF_BUY"),   // 직전 캡으로 이미 재산정된 base
                buy("52.63", 1, "INFINITE_CORRECTION_01"),
                buy("50.00", 1, "INFINITE_CORRECTION_02"),
                buy("47.62", 1, "INFINITE_CORRECTION_03"));

        // 추가 하락으로 캡이 더 낮아짐 (55.00 → 40.00)
        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("40.00"));

        // base만 재산정(단일 LOC 취급) + 신규 보정 3건 = 4건 — correction leg가 buy②로 오인돼
        // computeEarlyBuys(2건 이상 취급)로 잘못 빠지지 않는다
        assertThat(result).hasSize(4);
        assertThat(result.getFirst().orderLeg()).isEqualTo("INFINITE_LATE_REF_BUY");
        assertThat(result.getFirst().price()).isEqualByComparingTo("40.00");
        assertThat(result.getFirst().quantity()).isEqualTo(25); // floor(1000/40)
        assertThat(result).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_LATE_REF_BUY",
                        "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
    }

    // ── 보정 주문 예산 트림 (후반 교착 회귀) ──────────────────────────────────

    private InfinitePosition deadlockSeedPosition() {
        // 4분할 TQQQ, holdings 8, avg 81.1111, 예수금 300 → unit 237.22, ref 76.24
        return new InfinitePosition(new AccountBalance(8, new BigDecimal("81.1111"), new BigDecimal("300")),
                StrategyTicker.TQQQ, new BigDecimal("70"), 4);
    }

    @Test
    @DisplayName("buildCappedBuyOrders 교착 시드: base+보정합이 예수금 초과면 뒤쪽 보정부터 제거해 [base, 보정1]")
    void buildCappedBuyOrders_deadlockSeed_trimsTrailingCorrections() {
        List<PlannedOrder> buyOrders = List.of(PlannedOrder.of(TODAY, StrategyTicker.TQQQ, LOC, BUY, 3,
                new BigDecimal("76.24"), "INFINITE_LATE_REF_BUY"));

        List<PlannedOrder> result = strategy.buildCappedBuyOrders(
                deadlockSeedPosition(), TODAY, buyOrders, new BigDecimal("73.50"));

        assertThat(result).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_CORRECTION_01");
        assertThat(result.getFirst().quantity()).isEqualTo(3);
        assertThat(result.getFirst().price()).isEqualByComparingTo("73.50");
        assertThat(result.get(1).price()).isEqualByComparingTo("59.30");
        assertThat(AccountBalance.buyTotal(result)).isEqualByComparingTo("279.80");
    }

    @Test
    @DisplayName("trimCorrectionsToBudget 예산 넉넉하면 보정 3건 그대로")
    void trimCorrectionsToBudget_ampleBudget_keepsAll() {
        List<PlannedOrder> buys = List.of(buy("73.50", 3, "INFINITE_LATE_REF_BUY"),
                buy("59.31", 1, "INFINITE_CORRECTION_01"), buy("47.44", 1, "INFINITE_CORRECTION_02"),
                buy("39.54", 1, "INFINITE_CORRECTION_03"));

        assertThat(InfiniteStrategy.trimCorrectionsToBudget(buys, new BigDecimal("400"))).isEqualTo(buys);
    }

    @Test
    @DisplayName("trimCorrectionsToBudget base만으로 초과여도 base는 유지")
    void trimCorrectionsToBudget_baseExceeds_keepsBaseOnly() {
        PlannedOrder base = buy("73.50", 3, "INFINITE_LATE_REF_BUY");
        List<PlannedOrder> buys = List.of(base, buy("59.31", 1, "INFINITE_CORRECTION_01"));

        assertThat(InfiniteStrategy.trimCorrectionsToBudget(buys, new BigDecimal("100"))).containsExactly(base);
    }

    @Test
    @DisplayName("buildOrders 0회차 매수①이 unitAmount 초과: 매수② 미생성")
    void buildOrders_zeroRound_firstBuyConsumesUnit_omitsBuy2() {
        // unitAmount=100/20=5.00, 매수① max(1,0)=1주×60 ≥ 5 → 매수② 0주
        InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("100")), StrategyTicker.SOXL, new BigDecimal("60"), 20);
        List<PlannedOrder> buys = strategy.buildOrders(position, TODAY).stream().filter(o -> o.direction() == BUY).toList();
        assertThat(buys).extracting(PlannedOrder::orderLeg).doesNotContain("INFINITE_EARLY_REF_BUY");
        assertThat(buys.getFirst()).matches(o -> o.quantity() == 1 && o.price().compareTo(new BigDecimal("60")) == 0
                && o.orderLeg().equals("INFINITE_EARLY_AVG_BUY"));
    }

    @Test
    @DisplayName("buildCappedBuyOrders 보정 가격은 FLOOR — (N+1)×가격 ≤ unitAmount")
    void buildCappedBuyOrders_correctionPriceFloors() {
        // 0회차 prevClose=50, usdDeposit=20000 → unitAmount=1000, cap=52.50(현재가 50)
        // base 50×10, 52.50×9 (N=19) → 보정 1000/20=50.00, 1000/21=47.61(HALF_UP이면 47.62), 1000/22=45.45
        InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("50"), 20);
        List<PlannedOrder> base = strategy.buildOrders(position, TODAY).stream()
                .filter(o -> o.direction() == BUY && !o.orderLeg().startsWith("INFINITE_CORRECTION_")).toList();
        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, base, new BigDecimal("52.50"));
        assertThat(result).filteredOn(o -> o.orderLeg().startsWith("INFINITE_CORRECTION_"))
                .extracting(PlannedOrder::price).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50.00"), new BigDecimal("47.61"), new BigDecimal("45.45"));
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 매수② 0주 + cap 발동: 후반 공식이 아닌 전반 공식으로 재산정")
    void capped_earlyStageWithoutBuy2_staysOnEarlyFormula() {
        // 0회차 prevClose=100, usdDeposit=2000 → unitAmount=100, 매수① 1주×100 ≥ 100 → base 1건(매수② 0주)
        // cap=84(현재가 80): 전반 공식 cappedAvg=cappedRef=84 → q1=1, 잔여 16>0 → q2=1 → 병합 84×2
        // (후반 공식이면 floor(100/84)=1주 — 이 단언이 분기 결함을 잡는다)
        InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("2000")), StrategyTicker.SOXL, new BigDecimal("100"), 20);
        List<PlannedOrder> base = List.of(PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, 1, new BigDecimal("100"), "INFINITE_EARLY_AVG_BUY"));
        List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, base, new BigDecimal("84.00"));
        assertThat(result.getFirst()).matches(o -> o.orderLeg().equals("INFINITE_EARLY_MERGED_BUY")
                && o.quantity() == 2 && o.price().compareTo(new BigDecimal("84.00")) == 0);
    }
}
