package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 원장을 지키는 유일 제약 2개를 실 DB로 검증 — 같은 슬롯의 살아 있는 주문 하나(uq_orders_live_slot), 전략당 열린 사이클 하나(uq_strategy_cycle_open)
// 정상 경로(취소·종료 뒤 같은 트랜잭션에서 다시 저장)는 em.flush()로 커밋 시점의 SQL 순서를 그대로 내려 확인한다
@Tag("integration")
@Import({OrderPersistenceAdapter.class, StrategyCyclePersistenceAdapter.class})
@Execution(ExecutionMode.SAME_THREAD)
class LedgerUniqueConstraintIT extends DataJpaTestBase {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 16);
    private static final String LEG = "PRIVACY_BUY_01";

    @Autowired JdbcTemplate jdbc;
    @Autowired TestEntityManager em;
    @Autowired OrderPersistenceAdapter orderAdapter;
    @Autowired StrategyCyclePersistenceAdapter cycleAdapter;

    UUID accountId;
    UUID strategyId;
    UUID versionId;
    UUID cycleId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        strategyId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        // FK 선행 행: accounts → strategy → strategy_version → strategy_cycle(열린 사이클 1개)
        jdbc.update("INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", accountId.toString().substring(0, 8), "01", "key", "secret");
        jdbc.update("INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "PRIVACY", "SOXL", "ACTIVE", "NONE");
        jdbc.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        insertCycle(cycleId, null, false);
    }

    // ── orders: 같은 (사이클, 거래일, timing, direction, leg)에 살아 있는 주문은 하나 ──

    @Test
    void 같은_슬롯에_PLANNED를_두번_저장하면_두번째가_실패한다() {
        orderAdapter.saveAll(List.of(planned(LEG)));
        em.flush();

        orderAdapter.saveAll(List.of(planned(LEG)));

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("uq_orders_live_slot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNED", "SUBMITTING", "PLACED", "PARTIALLY_FILLED", "FILLED"})
    void 살아있는_상태끼리는_같은_슬롯을_공유하지_못한다(String live) {
        insertOrder(live, "AT_CLOSE", "BUY", LEG);

        assertThatThrownBy(() -> insertOrder("PLANNED", "AT_CLOSE", "BUY", LEG)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 취소_실패_행이_있는_슬롯에는_새_PLANNED가_들어간다() {
        insertOrder("CANCELLED", "AT_CLOSE", "BUY", LEG);
        insertOrder("CANCELLED", "AT_CLOSE", "BUY", LEG);
        insertOrder("FAILED", "AT_CLOSE", "BUY", LEG);

        orderAdapter.saveAll(List.of(planned(LEG)));

        assertThatCode(() -> em.flush()).doesNotThrowAnyException();
    }

    @Test
    void 죽은_행을_살리는_전이는_같은_슬롯에_살아있는_주문이_있으면_실패한다() {
        UUID cancelled = insertOrder("CANCELLED", "AT_CLOSE", "BUY", LEG);
        insertOrder("PLACED", "AT_CLOSE", "BUY", LEG);

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'FILLED' WHERE id = ?", cancelled))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 슬롯이_다르면_같은_leg라도_함께_살아있다() {
        insertOrder("PLACED", "AT_CLOSE", "BUY", "VR_BUY_01");

        // VR bootstrap(AT_CLOSE)과 사다리(AT_OPEN)는 같은 leg 이름을 쓴다 — 슬롯 판정(TradingOrderSlots)은 timing·direction도 본다
        assertThatCode(() -> {
            insertOrder("PLANNED", "AT_OPEN", "BUY", "VR_BUY_01");
            insertOrder("PLANNED", "AT_CLOSE", "SELL", "VR_BUY_01");
            insertOrder("PLANNED", "AT_CLOSE", "BUY", "VR_BUY_02");
            jdbc.update("INSERT INTO orders (account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status) "
                    + "VALUES (?, ?, ?, 'SOXL', 'LOC', 'AT_CLOSE', 'BUY', 'VR_BUY_01', 22.00, 5, 'PLANNED')", accountId, cycleId, TRADE_DATE.plusDays(1));
        }).doesNotThrowAnyException();
    }

    @Test
    void leg가_없는_과거_주문과_수동_체결_보정은_제약_밖이다() {
        insertOrder("FILLED", "AT_CLOSE", "BUY", Order.UNKNOWN_LEG);

        assertThatCode(() -> insertOrder("FILLED", "AT_CLOSE", "BUY", Order.UNKNOWN_LEG)).doesNotThrowAnyException();
    }

    @Test
    void PLANNED를_지우고_다시_넣기는_한_트랜잭션에서_통과한다() {
        orderAdapter.saveAll(List.of(planned(LEG)));
        em.flush();

        assertThat(orderAdapter.deletePlannedByCycleAndDate(cycleId, TRADE_DATE)).isEqualTo(1);
        orderAdapter.saveAll(List.of(planned(LEG)));

        assertThatCode(() -> em.flush()).doesNotThrowAnyException();
    }

    @Test
    void PLANNED를_취소하고_같은_슬롯에_다시_넣기는_한_트랜잭션에서_통과한다() {
        // PRIVACY 개별 가격 보정(BuyOrderPriceCapper.applyIndividualCap) — 취소 UPDATE가 새 행 INSERT보다 먼저 나가야 한다
        Order original = orderAdapter.save(planned(LEG));
        em.flush();

        orderAdapter.cancelPlanned(original.id());
        orderAdapter.saveAll(List.of(planned(LEG)));

        assertThatCode(() -> em.flush()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE strategy_cycle_id = ? AND status = 'PLANNED'", Integer.class, cycleId))
                .isEqualTo(1);
    }

    // ── strategy_cycle: 전략당 열린 사이클은 하나 ──

    @Test
    void 열린_사이클이_있는_전략에_두번째_열린_사이클_저장은_실패한다() {
        cycleAdapter.save(openCycle());

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("uq_strategy_cycle_open");
    }

    @Test
    void 사이클을_종료하고_새_사이클을_여는_회전은_한_트랜잭션에서_통과한다() {
        // VR 롤오버·재설정(CycleSnapshotCreator.rolloverVrCycle·reconfigureVrCycle) — 종료 UPDATE가 새 사이클 INSERT보다 먼저 나가야 한다
        cycleAdapter.markEnded(cycleId, new BigDecimal("1100.00"), TRADE_DATE);
        cycleAdapter.save(openCycle());

        assertThatCode(() -> em.flush()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_cycle WHERE strategy_id = ? AND end_date IS NULL", Integer.class, strategyId))
                .isEqualTo(1);
    }

    @Test
    void 종료된_사이클과_삭제된_사이클은_제약_밖이다() {
        assertThatCode(() -> {
            insertCycle(UUID.randomUUID(), TRADE_DATE.minusDays(30), false);
            insertCycle(UUID.randomUUID(), TRADE_DATE.minusDays(60), false);
            insertCycle(UUID.randomUUID(), null, true);
        }).doesNotThrowAnyException();
    }

    private Order planned(String leg) {
        return new Order(null, accountId, cycleId, TRADE_DATE, StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, leg, new BigDecimal("5"), new BigDecimal("22.00"), OrderStatus.PLANNED, null, null, null);
    }

    private StrategyCycle openCycle() {
        return new StrategyCycle(null, strategyId, versionId, new BigDecimal("1000.00"), null, TRADE_DATE, null, null, null);
    }

    private UUID insertOrder(String status, String timing, String direction, String leg) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status) "
                + "VALUES (?, ?, ?, ?, 'SOXL', 'LOC', ?, ?, ?, 22.00, 5, ?)", id, accountId, cycleId, TRADE_DATE, timing, direction, leg, status);
        return id;
    }

    private void insertCycle(UUID id, LocalDate endDate, boolean deleted) {
        jdbc.update("INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, end_date, created_at, deleted_at) "
                        + "VALUES (?, ?, ?, 1000.00, 0, ?, ?, now(), CASE WHEN ? THEN now() END)",
                id, strategyId, versionId, TRADE_DATE.minusDays(90), endDate, deleted);
    }
}
