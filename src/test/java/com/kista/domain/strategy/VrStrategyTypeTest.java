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
import static com.kista.domain.model.order.Order.OrderTiming.AT_OPEN;
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
        return new VrPosition(balance, value, bandWidth, poolLimit, BigDecimal.ZERO);
    }

    private VrPosition pos(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth,
                           BigDecimal poolLimit, BigDecimal poolUsed) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("100") : null, pool);
        return new VrPosition(balance, value, bandWidth, poolLimit, poolUsed);
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

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, TODAY);

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

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, TODAY);

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

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, TODAY);

        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("1700.00");
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

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, TODAY);

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

        List<Order> orders = strategy.buildOrders(position, TQQQ, null, TODAY);

        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        List<Order> sells = orders.stream().filter(o -> o.direction() == SELL).toList();

        // 매도는 정확히 holdings(3)개 단
        assertThat(sells).hasSize(3);
        assertThat(sells.get(0).price()).isEqualByComparingTo("366.67");
        assertThat(sells.get(1).price()).isEqualByComparingTo("550.00");
        assertThat(sells.get(2).price()).isEqualByComparingTo("1100.00");
        assertThat(sells).allMatch(o -> o.quantity() == 1);

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

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, TODAY)
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

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, TODAY)
                .stream().filter(o -> o.direction() == SELL).toList();

        assertThat(sells).hasSize(20);
        assertThat(sells).allMatch(o -> o.quantity() == 1); // 모두 1주
    }

    // ── 가격 캡 시나리오 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("가격 캡: rung 단가 > currentPrice×1.10 시 cap으로 교체")
    void priceCap_clampsToCap() {
        // holdings=1, V=10000, bandWidth=15%
        // lowerBand=8500, buyPrice(1)=8500/1=8500, buyPrice(2)=8500/2=4250
        // currentPrice=500 → cap=500×1.10=550.00
        // m=1: 8500 > cap(550) → price=550
        // m=2: 4250 > cap(550) → price=550 (같은 가격 → 병합)
        // ... 계속 캡 적용 후 poolLimit/pool 제한
        // poolLimit=1200, pool=1200
        // m=1: price=550, cumBuy=550 ≤1200 OK
        // m=2: price=550, cumBuy=1100 ≤1200 OK
        // m=3: price=550(or less?), cumBuy=1650 > poolLimit(1200) → break
        // Actually buyPrice(3) = 8500/(1+3-1) = 8500/3 = 2833.33 > cap(550) → price=550
        // cumBuy after m=2 = 1100, add 550 → 1650 > 1200 → break
        // → 2개 rung, 같은 가격 550 → 병합 → 1건(qty=2)
        VrPosition position = pos(1, new BigDecimal("1200"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("1200.00"));

        List<Order> buys = strategy.buildOrders(position, TQQQ, new BigDecimal("500.00"), TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // 2개 rung이 같은 cap 가격 → 병합 → 1건, qty=2
        assertThat(buys).hasSize(1);
        assertThat(buys.getFirst().price()).isEqualByComparingTo("550.00");
        assertThat(buys.getFirst().quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("가격 캡 후 이종 가격 연속 시 병합 안 됨")
    void priceCap_differentPrices_notMerged() {
        // holdings=10, V=10000, bandWidth=15%
        // lowerBand=8500, buyPrice(m=1)=8500/10=850, buyPrice(m=2)=8500/11=772.73, ...
        // currentPrice=1000 → cap=1100
        // m=1: 850 ≤ cap → price=850 (캡 미적용)
        // m=2: 772.73 ≤ cap → price=772.73
        // → 서로 다른 가격 → 병합 안 됨
        VrPosition position = pos(10, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"));

        List<Order> buys = strategy.buildOrders(position, TQQQ, new BigDecimal("1000.00"), TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // 각 rung 가격이 다르므로 병합 없음 — 첫 두 단 가격 확인
        assertThat(buys.getFirst().price()).isEqualByComparingTo("850.00");
        assertThat(buys.get(1).price()).isEqualByComparingTo("772.73");
        assertThat(buys).allMatch(o -> o.quantity() == 1); // 병합 없으므로 각 1주
    }

    @Test
    @DisplayName("currentPrice=null이면 가격 캡 미적용")
    void priceCap_nullCurrentPrice_noCap() {
        // holdings=1, V=10000, bandWidth=15%, buyPrice(1)=8500
        // currentPrice=null → 캡 없음 → 원래 가격 그대로
        VrPosition position = pos(1, new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("10000.00"));

        List<Order> buys = strategy.buildOrders(position, TQQQ, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // buyPrice(1) = 8500/1 = 8500.00 (캡 미적용)
        assertThat(buys.getFirst().price()).isEqualByComparingTo("8500.00");
    }

    // ── 매도 없음 시나리오 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("holdings=0이면 매도 주문 없음")
    void holdings0_noSellOrders() {
        VrPosition position = pos(0, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("15.00"), new BigDecimal("5000.00"));

        List<Order> sells = strategy.buildOrders(position, TQQQ, null, TODAY)
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

        List<Order> fullBuys = strategy.buildOrders(full, TQQQ, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();
        List<Order> partialBuys = strategy.buildOrders(partial, TQQQ, null, TODAY)
                .stream().filter(o -> o.direction() == BUY).toList();

        // poolUsed가 클수록 사용 가능 예산이 줄어 매수 단 수 감소
        assertThat(partialBuys.size()).isLessThan(fullBuys.size());
    }
}
