package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.VrPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderTiming.AT_CLOSE;
import static com.kista.domain.model.order.Order.OrderTiming.AT_OPEN;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VrStrategy 매수·매도 사다리 시나리오 검증")
class VrStrategyTypeTest {

    private final VrStrategy strategy = new VrStrategy();
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);
    private static final Ticker TQQQ = Ticker.TQQQ;

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    // VrPosition 생성 헬퍼 — poolUsed=0 기본값
    private VrPosition pos(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth,
                           BigDecimal poolLimit) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("100") : null, pool);
        return new VrPosition(balance, value, bandWidth, poolLimit, BigDecimal.ZERO,
                false, false, 1, 0);
    }

    private VrPosition pos(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth,
                           BigDecimal poolLimit, BigDecimal poolUsed) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("100") : null, pool);
        return new VrPosition(balance, value, bandWidth, poolLimit, poolUsed,
                false, false, 1, 0);
    }

    // ── 첫 사이클 bootstrap ───────────────────────────────────────────────────

    @Test
    @DisplayName("첫 사이클 V만 있으면 poolLimit을 남은 거래일로 나눠 LOC 매도한다 (livePrice 필요)")
    void firstCycle_valueOnly_sellsPoolLimitAcrossRemainingDays() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("100"), BigDecimal.ZERO);
        VrPosition position = new VrPosition(
                balance, new BigDecimal("10000"), new BigDecimal("15.00"),
                new BigDecimal("5000.00"), BigDecimal.ZERO,
                true, false, 10, 0);

        // SELL bootstrap은 livePrice(실시간 현재가)만 사용 — referencePrice는 무시됨
        List<Order> orders = strategy.buildOrders(position, TQQQ, null, new BigDecimal("100.00"), TODAY);
        Order sell = orders.getFirst();

        assertThat(orders).hasSize(1);
        assertThat(sell.orderType()).isEqualTo(LOC);
        assertThat(sell.timing()).isEqualTo(AT_CLOSE);
        assertThat(sell.price()).isEqualByComparingTo("90.00");
        assertThat(sell.quantity()).isEqualTo(5);
        assertThat(orders).extracting(Order::orderLeg)
                .containsExactly("VR_SELL_01");
    }

    @Test
    @DisplayName("SELL bootstrap은 livePrice가 없으면 전일종가로 대체하지 않고 빈 주문을 반환한다 (갭 하락 과매도 방지)")
    void firstCycle_valueOnly_noLivePrice_returnsEmpty() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("100"), BigDecimal.ZERO);
        VrPosition position = new VrPosition(
                balance, new BigDecimal("10000"), new BigDecimal("15.00"),
                new BigDecimal("5000.00"), BigDecimal.ZERO,
                true, false, 10, 0);

        // referencePrice(전일종가 대체값)만 있고 livePrice(실시간)는 없는 preview/수동실행 상황 재현
        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("첫 사이클 시드만 있으면 poolLimit을 남은 거래일로 나눠 LOC 매수한다 (referencePrice로 전일종가 대체 가능)")
    void firstCycle_seedOnly_buysPoolLimitAcrossRemainingDays() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("5000.00"), BigDecimal.ZERO,
                true, false, 10, 0);

        // BUY bootstrap은 referencePrice만으로도 생성 가능 — livePrice=null(preview/수동실행)이어도 전일종가 대체값으로 진행
        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);
        Order buy = orders.getFirst();

        assertThat(orders).hasSize(1);
        assertThat(buy.orderType()).isEqualTo(LOC);
        assertThat(buy.timing()).isEqualTo(AT_CLOSE);
        assertThat(buy.price()).isEqualByComparingTo("110.00");
        assertThat(buy.quantity()).isEqualTo(4);
        assertThat(orders).extracting(Order::orderLeg)
                .containsExactly("VR_BUY_01");
    }

    @Test
    @DisplayName("적립식 첫 사이클은 due date 당일에 recurringAmount만큼 LOC 매수한다 (referencePrice 대체 가능)")
    void firstCycle_recurringOnly_dueDate_buysRecurringAmount() {
        AccountBalance balance = new AccountBalance(0, null, BigDecimal.ZERO);
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                true, true, 1, 200);

        List<Order> buys = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().orderType()).isEqualTo(LOC);
        assertThat(buys.getFirst().timing()).isEqualTo(AT_CLOSE);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("110.00");
        assertThat(buys.getFirst().quantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("적립식 첫 사이클은 due date 전에는 주문을 만들지 않는다")
    void firstCycle_recurringOnly_beforeDueDate_noOrders() {
        AccountBalance balance = new AccountBalance(0, null, BigDecimal.ZERO);
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                true, false, 5, 200);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);

        assertThat(orders).isEmpty();
    }

    // ── 전 주문 타입 검증 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("모든 주문은 LIMIT + AT_OPEN")
    void allOrders_areLimitAtOpen() {
        // holdings=5, pool=5000, V=10000, bandWidth=15%
        // lowerBand=8500, upperBand=11500
        // buyPrice(1)=8500/5=1700, buyPrice(2)=8500/6=1416.67, ...
        VrPosition position = pos(5, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        assertThat(orders).isNotEmpty();
        assertThat(orders).allMatch(o -> o.orderType() == LIMIT);
        assertThat(orders).allMatch(o -> o.timing() == AT_OPEN);
        assertThat(orders).allMatch(o -> o.ticker() == TQQQ);
        assertThat(orders).allMatch(o -> o.tradeDate().equals(TODAY));
    }

    // ── holdings=0 시나리오 ───────────────────────────────────────────────────

    @Test
    @DisplayName("holdings=0: m=1은 divisor=0으로 skip, m=2부터 시작 — 매도 없음")
    void holdings0_m1Skipped_noSells() {
        // holdings=0, lowerBand=8500
        // m=1: divisor=0+1-1=0 → skip
        // m=2: divisor=1 → price=8500/1=8500.00
        // m=3: divisor=2 → price=8500/2=4250.00 ...
        // poolLimit=8500: m=2가 8500 → cumBuy=8500 ≤ poolLimit(8500) OK
        // m=3: 8500+4250=12750 > poolLimit → break
        VrPosition position = pos(0, new BigDecimal("9000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("8500.00"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        // 매도 없음
        assertThat(orders).noneMatch(o -> o.direction() == SELL);
        // m=2 시작 (buyPrice(2) = 8500/1 = 8500)
        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        assertThat(buys).isNotEmpty();
        assertThat(buys.getFirst().price()).isEqualByComparingTo("8500.00");
        assertThat(buys.getFirst().quantity()).isEqualTo(1);
    }

    // ── poolLimit 초과 제외 시나리오 ───────────────────────────────────────────

    @Test
    @DisplayName("poolLimit 소진 시 이후 단 전량 제외")
    void poolLimit_exhausted_stopsLadder() {
        // holdings=5, lowerBand=8500
        // buyPrice(1)=8500/5=1700, buyPrice(2)=8500/6=1416.67
        // poolLimit=2000: m=1(1700) OK cumBuy=1700, m=2(1416.67) → 1700+1416.67=3116.67>2000 → break
        VrPosition position = pos(5, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("2000.00"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("1700.00");
    }

    @Test
    @DisplayName("매수 사다리의 병합 주문에는 생성 순서 leg를 부여한다")
    void buyLadder_assignsSequentialLegs() {
        VrPosition position = pos(5, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("3200.00"));

        List<Order> buyOrders = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        assertThat(buyOrders).extracting(Order::orderLeg)
                .containsExactly("VR_BUY_01", "VR_BUY_02");
    }

    // ── pool(예수금) 잔액 부족 시나리오 ────────────────────────────────────────

    @Test
    @DisplayName("pool 잔액 부족 시 이후 단 전량 제외")
    void pool_insufficient_stopsLadder() {
        // holdings=5, lowerBand=8500
        // buyPrice(1)=1700, poolLimit=10000(넉넉), pool=1500 (예수금 부족)
        // m=1: 1700 > pool(1500) → break (바로 제외)
        VrPosition position = pos(5, new BigDecimal("1500"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("10000.00"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        // 첫 단가(1700) > pool(1500) → 매수 없음
        assertThat(buys).isEmpty();
    }

    // ── 정상 사다리 시나리오 (holdings < 20) ────────────────────────────────────

    @Test
    @DisplayName("holdings=3: 매수 사다리 m=1..N, 매도 사다리 s=1..3")
    void normalLadder_holdingsLessThan20() {
        // holdings=3, V=1000, bandWidth=10%
        // lowerBand=900, upperBand=1100, pool=5000, poolLimit=2000
        // buyPrice(1)=900/3=300, buyPrice(2)=900/4=225, buyPrice(3)=900/5=180, buyPrice(4)=900/6=150
        // cumBuy: 300→525→705→855 — 모두 ≤ poolLimit(2000), ≤ pool(5000)
        // buyPrice(5)=900/7=128.57 → 855+128.57=983.57 ≤ 2000 OK
        // 사실 상 poolLimit(2000)을 소진할 때까지 진행
        // sellPrice(1)=1100/3=366.67, sellPrice(2)=1100/2=550, sellPrice(3)=1100/1=1100
        VrPosition position = pos(3, new BigDecimal("5000"), new BigDecimal("1000"),
                new BigDecimal("10.00"), new BigDecimal("2000.00"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        List<Order> sells = orders.stream().filter(o -> o.direction() == SELL).toList();

        // 매도는 정확히 holdings(3)개 단
        assertThat(sells).hasSize(3);
        assertThat(sells.get(0).price()).isEqualByComparingTo("366.67");
        assertThat(sells.get(1).price()).isEqualByComparingTo("550.00");
        assertThat(sells.get(2).price()).isEqualByComparingTo("1100.00");
        assertThat(sells).allMatch(o -> o.quantity() == 1);
        assertThat(sells).extracting(Order::orderLeg)
                .containsExactly("VR_SELL_01", "VR_SELL_02", "VR_SELL_03");

        // 매수는 1주씩
        assertThat(buys).isNotEmpty();
        assertThat(buys.getFirst().price()).isEqualByComparingTo("300.00");
        assertThat(buys).allMatch(o -> o.direction() == BUY && o.quantity() >= 1);
    }

    // ── holdings > 20: 마지막 단 잔여 전량 ───────────────────────────────────

    @Test
    @DisplayName("holdings=25: 매도 20단, 20단째 수량 = holdings − 19 = 6")
    void holdings25_sell20Rungs_lastRungRemainder() {
        // holdings=25 → maxS = min(20, 25) = 20
        // 마지막 단(s=20): quantity = holdings - 19 = 6
        VrPosition position = pos(25, new BigDecimal("10000"), new BigDecimal("1000"),
                new BigDecimal("10.00"), new BigDecimal("10000.00"));

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == SELL).toList();

        assertThat(sells).hasSize(20);
        // s=1..19: 각 1주
        assertThat(sells.subList(0, 19)).allMatch(o -> o.quantity() == 1);
        // s=20: 잔여 = 25 - 19 = 6주
        assertThat(sells.get(19).quantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("holdings=20: 매도 20단, 마지막 단 수량=1 (holdigns=20이면 잔여=1)")
    void holdings20_sell20Rungs_lastRungIs1() {
        // holdings=20 → 20단, 20단째 수량 = holdings - 19 = 1 (잔여 전량 = 1주)
        VrPosition position = pos(20, new BigDecimal("10000"), new BigDecimal("1000"),
                new BigDecimal("10.00"), new BigDecimal("10000.00"));

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == SELL).toList();

        assertThat(sells).hasSize(20);
        assertThat(sells).allMatch(o -> o.quantity() == 1); // 모두 1주
    }

    // ── 생성 시점 가격 캡 미적용 검증 (Task 2) ──────────────────────────────────
    // BUY 사다리 생성(buildOrders/buildBuyOrders)은 더 이상 가격 캡을 적용하지 않는다.
    // 캡은 접수 직전 BuyOrderPriceCapper(VR_POSITION)가 buildCappedBuyOrders로 별도 재산정한다.

    @Test
    @DisplayName("buildOrders는 생성 시점에 가격 캡을 적용하지 않는다 — rung 단가가 원가 그대로 유지된다")
    void buildOrders_doesNotCapAtCreationTime() {
        // holdings=1, V=10000, bandWidth=15% → lowerBand=8500, buyPrice(1)=8500
        // pool·poolLimit을 충분히 크게 둬 예산 컷이 아닌 순수 cap 미적용 여부만 검증
        VrPosition position = pos(1, new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("100000.00"));

        List<Order> buys = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // 캡이 있었다면(예: currentPrice=500 → cap=525) 525로 클램프됐겠지만, 이제는 원가 8500 그대로 나온다
        assertThat(buys.getFirst().price()).isEqualByComparingTo("8500.00");
    }

    // ── 매도 없음 시나리오 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("holdings=0이면 매도 주문 없음")
    void holdings0_noSellOrders() {
        VrPosition position = pos(0, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"));

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == SELL).toList();

        assertThat(sells).isEmpty();
    }

    // ── poolUsed 반영 시나리오 ──────────────────────────────────────────────────

    @Test
    @DisplayName("poolUsed가 클수록 사용 가능 예산(poolLimit−poolUsed)이 줄어 매수 단 수 감소")
    void poolUsed_reducesAvailableBudget() {
        // holdings=5, lowerBand=8500(V=10000, bw=15%)
        // poolLimit=5000, poolUsed=0 → 예산=5000
        VrPosition full = pos(5, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO);

        // poolUsed=4000 → 예산=1000
        VrPosition partial = pos(5, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"), new BigDecimal("4000.00"));

        List<Order> fullBuys = strategy.buildOrders(full, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();
        List<Order> partialBuys = strategy.buildOrders(partial, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // poolUsed가 클수록 사용 가능 예산이 줄어 매수 단 수 감소
        assertThat(partialBuys.size()).isLessThan(fullBuys.size());
    }

    // ── 접수 전 가격 캡 재산정 (buildCappedBuyOrders — BuyOrderPriceCapper VR_POSITION 전용) ──────

    @Test
    @DisplayName("buildCappedBuyOrders: rung 단가 > cap 시 cap으로 교체")
    void buildCappedBuyOrders_clampsToCap() {
        // holdings=1, V=10000, bandWidth=15%
        // lowerBand=8500, buyPrice(1)=8500/1=8500, buyPrice(2)=8500/2=4250
        // cap=525.00 (currentPrice=500 × 1.05 가정)
        // m=1: 8500 > cap(525) → price=525
        // m=2: 4250 > cap(525) → price=525 (같은 가격 → 병합)
        // poolLimit=1200, pool=1200
        // m=1: price=525, cumBuy=525 ≤1200 OK
        // m=2: price=525, cumBuy=1050 ≤1200 OK
        // m=3: buyPrice(3)=8500/3=2833.33 > cap(525) → price=525, cumBuy=1575 > poolLimit(1200) → break
        // → 2개 rung, 같은 가격 525 → 병합 → 1건(qty=2)
        VrPosition position = pos(1, new BigDecimal("1200"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("1200.00"));

        List<Order> buys = strategy.buildCappedBuyOrders(position, TQQQ, TODAY, new BigDecimal("525.00"))
                .stream().filter(o -> o.direction() == BUY).toList();

        // 2개 rung이 같은 cap 가격 → 병합 → 1건, qty=2
        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("525.00");
        assertThat(buys.getFirst().quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("buildCappedBuyOrders: 캡 이하 이종 가격 연속 시 병합 안 됨")
    void buildCappedBuyOrders_differentPrices_notMerged() {
        // holdings=10, V=10000, bandWidth=15%
        // lowerBand=8500, buyPrice(m=1)=8500/10=850, buyPrice(m=2)=8500/11=772.73, ...
        // cap=1050.00 (currentPrice=1000 × 1.05 가정)
        // m=1: 850 ≤ cap → price=850 (캡 미적용)
        // m=2: 772.73 ≤ cap → price=772.73
        // → 서로 다른 가격 → 병합 안 됨
        VrPosition position = pos(10, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"));

        List<Order> buys = strategy.buildCappedBuyOrders(position, TQQQ, TODAY, new BigDecimal("1050.00"))
                .stream().filter(o -> o.direction() == BUY).toList();

        // 각 rung 가격이 다르므로 병합 없음 — 첫 두 단 가격 확인
        assertThat(buys.getFirst().price()).isEqualByComparingTo("850.00");
        assertThat(buys.get(1).price()).isEqualByComparingTo("772.73");
        assertThat(buys).allMatch(o -> o.quantity() == 1); // 병합 없으므로 각 1주
    }

    @Test
    @DisplayName("buildCappedBuyOrders: cap=null이면 buildOrders와 동일하게 캡 미적용")
    void buildCappedBuyOrders_nullCap_noCap() {
        // holdings=1, V=10000, bandWidth=15%, buyPrice(1)=8500
        VrPosition position = pos(1, new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("10000.00"));

        List<Order> buys = strategy.buildCappedBuyOrders(position, TQQQ, TODAY, null)
                .stream().filter(o -> o.direction() == BUY).toList();

        // buyPrice(1) = 8500/1 = 8500.00 (캡 미적용)
        assertThat(buys.getFirst().price()).isEqualByComparingTo("8500.00");
    }
}
