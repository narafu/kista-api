package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// sumFilledBuyAmountByCycleId SQL 필터(direction/status/cycle) 실효성 검증 — 실 DB 왕복 테스트
@Import(OrderPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderPersistenceAdapter adapter;

    private UUID userId;
    private UUID accountId;
    private UUID cycleId;    // 합산 대상 사이클
    private UUID otherCycleId; // 제외 대상 타 사이클

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        otherCycleId = UUID.randomUUID();

        // FK 선행 행: user → account → strategy → strategy_version → strategy_cycle
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "VR", "SOXL", "ACTIVE", "NONE");
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                strategyVersionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, strategyVersionId, new BigDecimal("1000.00"), LocalDate.now());
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                otherCycleId, strategyId, strategyVersionId, new BigDecimal("2000.00"), LocalDate.now());
    }

    @Test
    void saveAll_persistsAndLoadsOrderLeg() {
        PlannedOrder order = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                        OrderDirection.BUY, 1, new BigDecimal("22.00"))
                .withLeg("INFINITE_EARLY_AVG_BUY");

        adapter.saveAll(List.of(Order.fromPlanned(order, accountId, cycleId)));

        List<Order> result = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now());

        assertThat(result).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.orderLeg()).isEqualTo("INFINITE_EARLY_AVG_BUY");
                    // PK는 JPA가 시간순 UUID v7로 생성한다
                    assertThat(saved.id().version()).isEqualTo(7);
                });
    }

    @Test
    void cancelPlanned_thenMarkPlaced_conflictsInsteadOfReviving() {
        // 행 잠금 조회(FOR UPDATE) 경로 — 사용자 취소 뒤 늦은 접수 기록이 CANCELLED를 PLACED로 되돌리지 않는다
        PlannedOrder order = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                        OrderDirection.BUY, 1, new BigDecimal("22.00"))
                .withLeg("INFINITE_EARLY_AVG_BUY");
        adapter.saveAll(List.of(Order.fromPlanned(order, accountId, cycleId)));
        UUID orderId = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst().id();

        adapter.cancelPlanned(orderId);

        assertThatThrownBy(() -> adapter.markPlaced(orderId, "ORD-LATE"))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(adapter.findById(orderId)).get()
                .satisfies(o -> assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    void cancelPlanned_seesCommittedChangeEvenIfEntityAlreadyLoadedInSameTransaction() {
        // 재캡처럼 같은 트랜잭션에서 주문을 먼저 읽어 둔 뒤 다른 경로가 취소한 경우 — 영속성 컨텍스트 스냅샷(PLANNED)이 아니라 DB 상태로 판정
        insertOrder(cycleId, "BUY", "PLANNED", null, null);
        UUID orderId = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst().id(); // 엔티티가 컨텍스트에 올라감
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", orderId);

        assertThatThrownBy(() -> adapter.cancelPlanned(orderId))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void deletePlannedByCycleAndDate_deletesOnlyPlannedAndReturnsCount() {
        // 단일 DELETE문 — PLACED(접수 완료) 행은 남고 실제 삭제 건수를 돌려준다
        insertOrder(cycleId, "BUY", "PLANNED", null, null);
        insertOrder(cycleId, "SELL", "PLANNED", null, null);
        insertOrder(cycleId, "BUY", "PLACED", null, null);

        int deleted = adapter.deletePlannedByCycleAndDate(cycleId, LocalDate.now());

        assertThat(deleted).isEqualTo(2);
        assertThat(adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now())).isEmpty();
        assertThat(adapter.findPlacedByCycleAndDate(cycleId, LocalDate.now())).hasSize(1);
    }

    @Test
    void findOpenByAccountIdReturnsPlannedAndPlacedAcrossDates() {
        LocalDate today = LocalDate.of(2026, 10, 18);
        UUID otherAccountId = UUID.randomUUID();
        insertAccount(otherAccountId, userId, false);

        insertOrderForAccount(accountId, cycleId, today, "SOXL", "BUY", "PLANNED", null, null);
        insertOrderForAccount(accountId, cycleId, today.minusDays(1), "SOXL", "BUY", "PLACED", null, null);
        insertOrderForAccount(accountId, cycleId, today, "SOXL", "BUY", "FILLED", 5, new BigDecimal("22.00"));
        insertOrderForAccount(otherAccountId, cycleId, today, "SOXL", "BUY", "PLANNED", null, null);

        List<Order> open = adapter.findOpenByAccountId(accountId);

        assertThat(open).extracting(Order::status).containsExactly(OrderStatus.PLACED, OrderStatus.PLANNED);
        assertThat(open).extracting(Order::tradeDate).containsExactly(today.minusDays(1), today);
    }

    @Test
    void savesAndReloadsFractionalQuantity() {
        Order saved = adapter.save(Order.cryptoMarket(accountId, cycleId, LocalDate.of(2026, 10, 18),
                StrategyTicker.BTC, OrderDirection.BUY, "DCA_BUY", OrderTiming.AT_CLOSE,
                new BigDecimal("0.00012345"), new BigDecimal("150000000")));

        Order reloaded = adapter.findById(saved.id()).orElseThrow();

        assertThat(reloaded.quantity()).isEqualByComparingTo("0.00012345");
        assertThat(reloaded.price()).isEqualByComparingTo("150000000");
    }

    // 주문 1건 직접 삽입 헬퍼
    private void insertOrder(UUID cId, String direction, String status,
                             Integer filledQuantity, BigDecimal filledPrice) {
        insertOrderForAccount(accountId, cId, direction, status, filledQuantity, filledPrice);
    }

    private void insertOrderForAccount(UUID accId, UUID cId, String direction, String status,
                             Integer filledQuantity, BigDecimal filledPrice) {
        insertOrderForAccount(accId, cId, LocalDate.now(), "SOXL", direction, status, filledQuantity, filledPrice);
    }

    private void insertOrderForAccount(UUID accId, UUID cId, LocalDate tradeDate, String ticker,
                                       String direction, String status, Integer filledQuantity, BigDecimal filledPrice) {
        jdbcTemplate.update(
                "INSERT INTO orders (account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status, filled_quantity, filled_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accId, cId, tradeDate, ticker, "LOC", "AT_CLOSE",
                direction, Order.UNKNOWN_LEG, new BigDecimal("22.00"), 5, status, filledQuantity, filledPrice);
    }

    private void insertAccount(UUID accId, UUID ownerUserId, boolean deleted) {
        String accountNo = accId.toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?)",
                accId, ownerUserId, "테스트계좌-" + accId, "KIS", accountNo, "01", "key", "secret",
                deleted ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    @Test
    void sumFilledBuyAmountByCycleId_sumsOnlyFilledAndPartiallyFilledBuy() {
        // 대상 사이클: FILLED BUY 2건 + PARTIALLY_FILLED BUY 1건 + FILLED SELL 1건 + PLANNED BUY 1건
        insertOrder(cycleId, "BUY",  "FILLED",           5, new BigDecimal("10.00")); // 50.00
        insertOrder(cycleId, "BUY",  "FILLED",           3, new BigDecimal("20.00")); // 60.00
        insertOrder(cycleId, "BUY",  "PARTIALLY_FILLED", 2, new BigDecimal("15.00")); // 30.00
        insertOrder(cycleId, "SELL", "FILLED",           4, new BigDecimal("25.00")); // 제외 (SELL)
        insertOrder(cycleId, "BUY",  "PLANNED",          null, null);                 // 제외 (status 불일치)

        // 타 사이클: FILLED BUY 1건 — cycle_id 필터로 제외
        insertOrder(otherCycleId, "BUY", "FILLED", 10, new BigDecimal("30.00")); // 제외 (다른 사이클)

        // 기대값: 50.00 + 60.00 + 30.00 = 140.00
        BigDecimal result = adapter.sumFilledBuyAmountByCycleId(cycleId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("140.00"));
    }

    @Test
    void sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker_sumsOnlyMatchingReservations() {
        LocalDate databaseTradeDate = LocalDate.of(2026, 7, 14);
        LocalDate domainTradeDate = databaseTradeDate; // DB와 도메인이 같은 KST 일자
        UUID otherAccountId = UUID.randomUUID();
        insertAccount(otherAccountId, userId, false);

        // 대상 계좌·거래일·종목의 PLANNED/PLACED SELL 수량만 예약분으로 합산한다.
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "SOXL", "SELL", "PLANNED", null, null); // 5
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "SOXL", "SELL", "PLACED", null, null);  // 5

        // 각 필터를 실제 행으로 교차 검증한다.
        insertOrderForAccount(otherAccountId, cycleId, databaseTradeDate, "SOXL", "SELL", "PLANNED", null, null); // 다른 계좌
        insertOrderForAccount(accountId, cycleId, databaseTradeDate.plusDays(1), "SOXL", "SELL", "PLACED", null, null); // 다른 날짜
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "TQQQ", "SELL", "PLANNED", null, null); // 다른 종목
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "SOXL", "SELL", "FILLED", null, null); // 체결 완료
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "SOXL", "SELL", "CANCELLED", null, null); // 취소됨
        insertOrderForAccount(accountId, cycleId, databaseTradeDate, "SOXL", "BUY", "PLANNED", null, null); // 다른 방향

        int result = adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                accountId, domainTradeDate, StrategyTicker.SOXL);

        assertThat(result).isEqualTo(10);
    }

    @Test
    void sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker_returnsZeroWhenNoReservationMatches() {
        // SQL COALESCE로 매칭 주문이 없어도 0을 반환한다
        int result = adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                accountId, LocalDate.now().plusDays(1), StrategyTicker.TQQQ);

        assertThat(result).isZero();
    }

    @Test
    void findFilledByUser_aggregatesOwnedAccountsExcludingOtherUsersAndDeleted() {
        UUID secondAccountId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID otherUserAccountId = UUID.randomUUID();
        UUID deletedAccountId = UUID.randomUUID();

        insertAccount(secondAccountId, userId, false);       // 동일 유저 2번째 계좌 — 포함
        insertAccount(otherUserAccountId, otherUserId, false); // 타 유저 계좌 — 제외
        insertAccount(deletedAccountId, userId, true);        // 소프트 삭제 계좌 — 제외

        insertOrderForAccount(accountId, cycleId, "BUY", "FILLED", 5, new BigDecimal("10.00"));           // 포함 (50.00)
        insertOrderForAccount(secondAccountId, cycleId, "SELL", "PARTIALLY_FILLED", 2, new BigDecimal("15.00")); // 포함 (30.00)
        insertOrderForAccount(accountId, cycleId, "BUY", "PLANNED", null, null);                          // 제외 (status 불일치)
        insertOrderForAccount(otherUserAccountId, cycleId, "BUY", "FILLED", 10, new BigDecimal("30.00")); // 제외 (다른 유저)
        insertOrderForAccount(deletedAccountId, cycleId, "BUY", "FILLED", 1, new BigDecimal("5.00"));     // 제외 (삭제된 계좌)

        var result = adapter.findFilledByUser(userId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(o -> o.accountId()).containsExactlyInAnyOrder(accountId, secondAccountId);
    }

    // ── 접수 선점(SUBMITTING) — spec 2026-10-16 §3.1·§3.4 ──

    // 지정 상태 주문 1건을 id와 함께 삽입
    private UUID insertWithId(String direction, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                id, accountId, cycleId, LocalDate.now(), "SOXL", "LOC", "AT_CLOSE",
                direction, Order.UNKNOWN_LEG, new BigDecimal("22.00"), 5, status);
        return id;
    }

    private OrderStatus statusOf(UUID id) {
        return adapter.findById(id).orElseThrow().status();
    }

    @Test
    void markSubmitting_plannedToSubmitting() {
        UUID id = insertWithId("BUY", "PLANNED");

        adapter.markSubmitting(id);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.SUBMITTING);
    }

    @Test
    void markSubmitting_nonPlanned_conflicts() {
        UUID placed = insertWithId("BUY", "PLACED");

        assertThatThrownBy(() -> adapter.markSubmitting(placed)).isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> adapter.markSubmitting(UUID.randomUUID())).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void markPlaced_requiresSubmitting() {
        UUID planned = insertWithId("BUY", "PLANNED");
        assertThatThrownBy(() -> adapter.markPlaced(planned, "ORD-1")).isInstanceOf(OrderStateConflictException.class);

        UUID submitting = insertWithId("BUY", "SUBMITTING");
        adapter.markPlaced(submitting, "ORD-2");
        adapter.markPlaced(submitting, "ORD-2"); // 같은 접수번호 재기록은 멱등 성공

        assertThat(adapter.findById(submitting)).get().satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.PLACED);
            assertThat(o.externalOrderId()).isEqualTo("ORD-2");
        });
    }

    @Test
    void markFailed_requiresSubmitting() {
        UUID planned = insertWithId("BUY", "PLANNED");
        assertThatThrownBy(() -> adapter.markFailed(planned)).isInstanceOf(OrderStateConflictException.class);

        UUID submitting = insertWithId("BUY", "SUBMITTING");
        adapter.markFailed(submitting);

        assertThat(statusOf(submitting)).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void findSubmitting_returnsSubmittedAt() {
        UUID submitting = insertWithId("BUY", "SUBMITTING");
        insertWithId("BUY", "PLANNED");

        var result = adapter.findSubmittingByCycleAndDate(cycleId, LocalDate.now());

        assertThat(result).singleElement().satisfies(s -> {
            assertThat(s.order().id()).isEqualTo(submitting);
            assertThat(s.submittedAt()).isNotNull();
        });
    }

    @Test
    void findPlannedOrPlaced_includesSubmitting() {
        insertWithId("BUY", "PLANNED");
        insertWithId("BUY", "SUBMITTING");
        insertWithId("BUY", "PLACED");
        insertWithId("BUY", "FAILED");

        assertThat(adapter.findPlannedOrPlacedByCycleAndDate(cycleId, LocalDate.now())).hasSize(3);
        assertThat(adapter.findPlannedOrPlacedByCycleIdsAndDate(List.of(cycleId), LocalDate.now()).get(cycleId)).hasSize(3);
    }

    @Test
    void sumPlannedBuy_includesSubmitting() {
        insertWithId("BUY", "PLANNED");    // 110.00
        insertWithId("BUY", "SUBMITTING"); // 110.00 — 증권사가 이미 차감했을 수 있어도 보수적으로 포함
        insertWithId("BUY", "PLACED");     // 제외 — 증권사 주문가능금액에서 이미 차감됨

        assertThat(adapter.sumPlannedBuyByAccountAndDate(accountId, LocalDate.now()))
                .isEqualByComparingTo(new BigDecimal("220.00"));
    }

    @Test
    void sumSell_includesSubmitting() {
        insertWithId("SELL", "PLANNED");
        insertWithId("SELL", "SUBMITTING");
        insertWithId("SELL", "PLACED");
        insertWithId("SELL", "FAILED");

        assertThat(adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                accountId, LocalDate.now(), StrategyTicker.SOXL)).isEqualTo(15);
    }

    @Test
    void deletePlanned_keepsSubmitting() {
        insertWithId("BUY", "PLANNED");
        UUID submitting = insertWithId("BUY", "SUBMITTING");

        assertThat(adapter.deletePlannedByCycleAndDate(cycleId, LocalDate.now())).isEqualTo(1);
        assertThat(statusOf(submitting)).isEqualTo(OrderStatus.SUBMITTING);
    }

    @Test
    void save_returnsId() {
        PlannedOrder planned = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.BUY, 1, new BigDecimal("22.00"));

        Order saved = adapter.save(Order.fromPlanned(planned, accountId, cycleId));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.status()).isEqualTo(OrderStatus.PLANNED);
    }

    @Test
    void quantityRoundTripsAsPlainInteger() {
        PlannedOrder planned = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 10, new BigDecimal("22.00"));
        adapter.saveAll(List.of(Order.fromPlanned(planned, accountId, cycleId)));

        Order saved = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst();

        // DB는 numeric(20,8) — 읽은 뒤에도 scale 0 정수여야 한다
        assertThat(saved.quantity()).isEqualTo(BigDecimal.TEN);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'orders' AND column_name = 'quantity'
                """, String.class)).isEqualTo("20,8");
    }

    @Test
    void fractionalCryptoQuantityIsStored() {
        Order fractional = new Order(null, accountId, cycleId, LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                com.kista.shared.kernel.OrderTiming.AT_CLOSE, OrderDirection.BUY,
                new BigDecimal("0.00012345"), new BigDecimal("22.00"), OrderStatus.PLANNED, null, null, null);
        adapter.saveAll(List.of(fractional));

        assertThat(adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst().quantity())
                .isEqualByComparingTo("0.00012345");
    }

    @Test
    void sellReservationSumReadsNumericColumn() {
        PlannedOrder sell = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.SELL, 7, new BigDecimal("22.00"));
        adapter.saveAll(List.of(Order.fromPlanned(sell, accountId, cycleId)));

        // SUM(numeric)은 BigDecimal — Long 수신이면 런타임 변환 실패
        assertThat(adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(accountId, LocalDate.now(), StrategyTicker.SOXL))
                .isEqualTo(7);
    }
}
