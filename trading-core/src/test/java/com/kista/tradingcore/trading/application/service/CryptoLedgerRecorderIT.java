package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 코인 체결 → 원장 반영의 멱등성(증분만 반영)을 실 DB로 검증 — 사이클 잠금·주문 재조회·cycle_position append 왕복
@Tag("integration")
@Import({CryptoLedgerRecorder.class, CryptoLedgerRecorderIT.Adapters.class})
@Execution(ExecutionMode.SAME_THREAD)
class CryptoLedgerRecorderIT extends DataJpaTestBase {

    // package-private 영속 어댑터 중 필요한 포트 구현만 스캔
    @TestConfiguration
    @ComponentScan(basePackages = "com.kista.tradingcore.trading.adapter.out.persistence", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {OrderPort.class, StrategyCyclePort.class, CyclePositionPort.class, StrategyPort.class}))
    static class Adapters {}

    static final BigDecimal PRICE = new BigDecimal("100000000"); // 1억 원

    @Autowired JdbcTemplate jdbc;
    @Autowired TestEntityManager em;
    @Autowired CryptoLedgerRecorder recorder;
    @Autowired CyclePositionPort cyclePositionPort;
    @Autowired OrderPort orderPort;

    BatchContext ctx;
    UUID cycleId;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        // FK 선행 행: accounts → strategy → strategy_version → strategy_cycle (초기 보유 없음 — cycle_position 행 없음)
        jdbc.update("INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "코인계좌", "UPBIT", "upbit", null, "key", "secret");
        jdbc.update("INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "DCA", "BTC", "ACTIVE", "NONE");
        jdbc.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbc.update("INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, BigDecimal.ZERO, LocalDate.now());
        TradingAccount account = TradingFixtures.tradingAccount(accountId, userId);
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.DCA, StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, versionId, BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(userId));
    }

    Order placedBuy() {
        Order saved = orderPort.save(Order.cryptoMarket(ctx.account().id(), cycleId, LocalDate.now(), StrategyTicker.BTC,
                OrderDirection.BUY, "DCA_11111111-1111-1111-1111-111111111111", OrderTiming.AT_CLOSE, new BigDecimal("0.001"), PRICE));
        orderPort.markSubmitting(saved.id());
        orderPort.markPlaced(saved.id(), "upbit-uuid");
        return saved;
    }

    // 사이클의 원장 행 수 — JDBC 직접 카운트(append 횟수 검증)
    int positionCount() {
        em.flush(); // JPA 쓰기 지연분을 내려야 JDBC 카운트에 보인다
        return jdbc.queryForObject("SELECT count(*) FROM cycle_position WHERE strategy_cycle_id = ?", Integer.class, cycleId);
    }

    CyclePosition latest() {
        return cyclePositionPort.findLatestByCycleIds(List.of(cycleId)).get(cycleId);
    }

    @Test
    void 같은_주문_두번_settle해도_원장은_1회_반영() {
        Order order = placedBuy();

        assertThat(recorder.recordFill(ctx, order, new BigDecimal("0.001"), PRICE)).isTrue();
        assertThat(recorder.recordFill(ctx, order, new BigDecimal("0.001"), PRICE)).isFalse();

        // 원장 행이 없던 사이클 — 0 원장에서 시작해 1행만 append
        assertThat(positionCount()).isEqualTo(1);
        assertThat(latest()).satisfies(p -> {
            assertThat(p.holdings()).isEqualByComparingTo("0.001");
            assertThat(p.avgPrice()).isEqualByComparingTo(PRICE);
            assertThat(p.usdDeposit()).isEqualByComparingTo("0");
            assertThat(p.closingPrice()).isEqualByComparingTo(PRICE);
        });
        assertThat(orderPort.findById(order.id())).get().satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(o.filledQuantity()).isEqualByComparingTo("0.001");
        });
    }

    @Test
    void 부분체결_후_전량체결은_증분만_반영() {
        Order order = placedBuy();
        // 0.0004 @ 1억 → 누적 0.001 @ 평균 1.06억 (증분 0.0006 × 1.1억)
        BigDecimal avgAll = new BigDecimal("106000000");

        assertThat(recorder.recordFill(ctx, order, new BigDecimal("0.0004"), PRICE)).isTrue();
        assertThat(recorder.recordFill(ctx, order, new BigDecimal("0.001"), avgAll)).isTrue();

        assertThat(positionCount()).isEqualTo(2);
        CyclePosition latest = latest();
        assertThat(latest.holdings()).isEqualByComparingTo("0.001"); // 0.0004 + 0.0006, 이중 반영 없음
        // 평단 = (0.0004×1억 + 0.0006×1.1억) / 0.001 = 1.06억 — 누적 금액 기준 증분
        assertThat(latest.avgPrice()).isEqualByComparingTo(avgAll);
    }

    @Test
    void 원가는_순수량_곱하기_체결평균가_수수료_제외() {
        Order order = placedBuy();
        BigDecimal netVolume = new BigDecimal("0.00015985"); // 수수료 차감 후 순수량
        BigDecimal fillAvg = new BigDecimal("65000");

        assertThat(recorder.recordFill(ctx, order, netVolume, fillAvg)).isTrue();

        // 평단은 체결 평균가 그대로 — 수수료 포함 실지출(10.40/순수량 ≈ 65061)로 부풀지 않는다
        assertThat(latest()).satisfies(p -> {
            assertThat(p.holdings()).isEqualByComparingTo("0.00015985");
            assertThat(p.avgPrice()).isEqualByComparingTo("65000");
        });
        assertThat(orderPort.findById(order.id())).get().satisfies(o -> {
            assertThat(o.filledQuantity()).isEqualByComparingTo("0.00015985");
            assertThat(o.filledPrice()).isEqualByComparingTo("65000");
        });
    }
}
