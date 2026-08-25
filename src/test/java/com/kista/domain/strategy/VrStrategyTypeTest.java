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

    // VrPosition 생성 헬퍼 — poolUsed=0, recurringAmount=0 기본값
    private VrPosition pos(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth,
                           BigDecimal poolLimit) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("100") : null, pool);
        return new VrPosition(balance, value, bandWidth, poolLimit, BigDecimal.ZERO, 0);
    }

    private VrPosition pos(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth,
                           BigDecimal poolLimit, BigDecimal poolUsed) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("100") : null, pool);
        return new VrPosition(balance, value, bandWidth, poolLimit, poolUsed, 0);
    }

    // ── V=0 bootstrap ─────────────────────────────────────────────────────────
    // firstCycle 개념은 폐기됨 — V==0 여부만으로 게이팅하므로 몇 번째 사이클인지는 무관하다

    @Test
    @DisplayName("V=0, pool>0이면 poolLimit 중 남은 예산을 그날 캡 가격(×1.05)으로 전액 LOC 매수 시도한다")
    void valueZero_poolPositive_buysRemainingBudgetAtCap() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("7500.00"), BigDecimal.ZERO, 0);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);
        Order buy = orders.getFirst();

        assertThat(orders).hasSize(1);
        assertThat(buy.orderType()).isEqualTo(LOC);
        assertThat(buy.timing()).isEqualTo(AT_CLOSE);
        assertThat(buy.price()).isEqualByComparingTo("105.00"); // 100.00 × 1.05
        assertThat(buy.quantity()).isEqualTo(71); // floor(7500/105.00)
        assertThat(orders).extracting(Order::orderLeg)
                .containsExactly("VR_BUY_01");
    }

    @Test
    @DisplayName("V=0, pool>0이지만 이미 poolUsed로 일부 소진됐으면 잔여분만 매수한다")
    void valueZero_poolPositive_partiallyUsed_buysRemainingOnly() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("7500.00"), new BigDecimal("5000.00"), 0);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);
        Order buy = orders.getFirst();

        assertThat(buy.price()).isEqualByComparingTo("105.00");
        assertThat(buy.quantity()).isEqualTo(23); // floor((7500-5000)/105.00)
    }

    @Test
    @DisplayName("V=0, poolLimit이 소진되면(poolUsed>=poolLimit) 빈 주문을 반환한다")
    void valueZero_poolLimitExhausted_returnsEmpty() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("7500.00"), new BigDecimal("7500.00"), 0);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("V=0, referencePrice가 없으면 빈 주문을 반환한다")
    void valueZero_poolPositive_noReferencePrice_returnsEmpty() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("7500.00"), BigDecimal.ZERO, 0);

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, null, TODAY);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("V=0, pool=0이면 빈 주문을 반환한다 (0원 사다리 버그 회귀 방지)")
    void valueZero_poolZero_returnsEmpty() {
        AccountBalance balance = new AccountBalance(0, null, BigDecimal.ZERO);
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("7500.00"), BigDecimal.ZERO, 200);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("holdings=0, V>0이어도 사다리 첫 단(lowerBand)이 예산을 초과하면 bootstrap으로 진입한다 " +
            "(nextValue 공식이 holdings 무관하게 V를 키워 사다리가 영구히 막히는 상황 방지)")
    void holdingsZero_valuePositive_ladderUnaffordable_fallsBackToBootstrap() {
        // V=10000, bandWidth=15% → lowerBand=8500. poolLimit=pool=1000이라 사다리 m=2(8500)조차 불가능
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000.00"));
        VrPosition position = new VrPosition(
                balance, new BigDecimal("10000"), new BigDecimal("15.00"),
                new BigDecimal("1000.00"), BigDecimal.ZERO, 0);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);
        Order buy = orders.getFirst();

        // 사다리 가격(8500)이 아니라 bootstrap 캡 가격(100×1.05=105.00) — 예산(1000) 내에서 매수
        assertThat(orders).hasSize(1);
        assertThat(buy.orderType()).isEqualTo(LOC);
        assertThat(buy.timing()).isEqualTo(AT_CLOSE);
        assertThat(buy.price()).isEqualByComparingTo("105.00");
        assertThat(buy.quantity()).isEqualTo(9); // floor(1000/105.00)
    }

    @Test
    @DisplayName("holdings>0인데 사다리 첫 유효 단(buyPrice(1))조차 예산을 초과하면(V 드리프트) " +
            "매수만 bootstrap으로 대체하고 매도 사다리는 정상 유지한다 (운영 strategy_id=0fd7e8dc 재현)")
    void holdingsPositive_ladderFirstRungUnaffordable_bootstrapBuyKeepsSellLadder() {
        // V=174.05, bandWidth=15% → lowerBand=147.94, holdings=1 → buyPrice(1)=147.94/1=147.94
        // poolLimit=128.83(재설정 후 실제 pool 기준으로 재스냅샷됐다고 가정) → remainingBudget=128.83
        // 147.94 > 128.83 → 정상 사다리는 여전히 불가능 → bootstrap 매수로 대체
        // referencePrice=69.09 → cap=69.09×1.05=72.54, quantity=floor(128.83/72.54)=1
        VrPosition position = pos(1, new BigDecimal("128.83"), new BigDecimal("174.05"),
                new BigDecimal("15.00"), new BigDecimal("128.83"));

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("69.09"), null, TODAY);
        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        List<Order> sells = orders.stream().filter(o -> o.direction() == SELL).toList();

        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().orderType()).isEqualTo(LOC);
        assertThat(buys.getFirst().timing()).isEqualTo(AT_CLOSE);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("72.54"); // 69.09 × 1.05
        assertThat(buys.getFirst().quantity()).isEqualTo(1); // floor(128.83/72.54) = 1
        // holdings=1이므로 매도 사다리는 드리프트와 무관하게 정상 생성 (sellPrice(1) = upperBand/1)
        assertThat(sells).hasSize(1);
    }

    @Test
    @DisplayName("holdings>0인데 value=0이면(bootstrap 매수 체결 후 롤오버 전 V 미갱신 갭) 사다리 skip — " +
            "lowerBand/upperBand가 0이 되어 $0 가격 주문이 나가는 버그 회귀 방지")
    void holdingsPositive_valueZero_skipsLadder() {
        AccountBalance balance = new AccountBalance(2, new BigDecimal("71.05"), new BigDecimal("57.90"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                new BigDecimal("150.00"), BigDecimal.ZERO, 100);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("69.09"), null, TODAY);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("poolLimit=0(완전 무일푼으로 개장해 영구 고정)이어도 pool()>0이면 그 예수금을 예산으로 대신 쓴다")
    void poolLimitZero_fallsBackToLivePoolAsBudget() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("200.00"));
        VrPosition position = new VrPosition(
                balance, BigDecimal.ZERO, new BigDecimal("15.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, 200);

        List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), null, TODAY);
        Order buy = orders.getFirst();

        assertThat(orders).hasSize(1);
        assertThat(buy.price()).isEqualByComparingTo("105.00");
        assertThat(buy.quantity()).isEqualTo(1); // floor(200/105.00)
    }

    @Test
    @DisplayName("holdings>0(정상 사다리 경로)에서도 poolLimit=0(무일푼 개장 사이클, 영구 고정)이면 pool()을 예산으로 대신 쓴다")
    void poolLimitZero_ladderPhase_fallsBackToLivePoolAsBudget() {
        // holdings=1, V=1000, bandWidth=10% → lowerBand=900
        // buyPrice(1)=900/1=900, buyPrice(2)=900/2=450
        // poolLimit=0(영구 고정) → 폴백 시 pool(1000)을 예산으로 사용
        // m=1: cumBuy=900 ≤ pool(1000) OK / m=2: cumBuy=900+450=1350 > pool(1000) → break
        VrPosition position = pos(1, new BigDecimal("1000.00"), new BigDecimal("1000"),
                new BigDecimal("10.00"), BigDecimal.ZERO);

        List<Order> buys = strategy.buildOrders(position, TQQQ, null, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("900.00");
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
    @DisplayName("holdings=0이면 매도 주문 없음 (사다리 첫 단이 예산 내라 bootstrap이 아닌 정상 사다리로 진입)")
    void holdings0_noSellOrders() {
        // lowerBand=8500 ≤ remainingBudget(min(poolLimit=8500, pool=9000))=8500 → needsBootstrap=false
        VrPosition position = pos(0, new BigDecimal("9000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("8500.00"));

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
    @DisplayName("buildCappedBuyOrders도 poolLimit=0(무일푼 개장 사이클)이면 pool()로 폴백한다 " +
            "— 접수 전 재산정 경로(BuyOrderPriceCapper VR_POSITION)도 buildBuyLadder를 공유하므로 동일하게 적용됨")
    void buildCappedBuyOrders_poolLimitZero_fallsBackToLivePoolAsBudget() {
        // holdings=1, V=1000, bandWidth=10% → lowerBand=900
        // buyPrice(1)=900/1=900, buyPrice(2)=900/2=450, cap=1000(캡에 걸리지 않아 가격 변화 없음)
        // poolLimit=0(영구 고정) → 폴백 시 pool(1000)을 예산으로 사용
        // m=1: cumBuy=900 ≤ pool(1000) OK / m=2: cumBuy=900+450=1350 > pool(1000) → break
        VrPosition position = pos(1, new BigDecimal("1000.00"), new BigDecimal("1000"),
                new BigDecimal("10.00"), BigDecimal.ZERO);

        List<Order> buys = strategy.buildCappedBuyOrders(position, TQQQ, TODAY, new BigDecimal("1000.00"))
                .stream().filter(o -> o.direction() == BUY).toList();

        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("900.00");
        assertThat(buys.getFirst().quantity()).isEqualTo(1);
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
