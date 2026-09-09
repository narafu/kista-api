package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.port.output.OrderPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

// 커널이 산출한 계획 주문(PlannedOrder)을 PLANNED 상태 + 신규 PK(null) + 호출 계좌 FK로 승격해 일괄 저장하는지 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrderPlanner 단위 테스트")
class TradingOrderPlannerTest {

    @Mock OrderPort orderPort;
    @Captor ArgumentCaptor<List<Order>> ordersCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    private PlannedOrder template(OrderDirection direction, String price, int quantity) {
        // 전략이 만든 계획 주문 — 계좌 귀속 전이라 계좌·사이클·생명주기 상태를 갖지 않는다
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, OrderType.LOC, direction, quantity, new BigDecimal(price));
    }

    @Test
    @DisplayName("템플릿을 PLANNED 상태 + 신규 PK + 계좌 FK로 변환해 일괄 저장")
    void savePlannedOrders_convertsTemplatesAndSavesAll() {
        PlannedOrder buyTemplate = template(OrderDirection.BUY, "50.00", 10).withLeg("INFINITE_BUY_01");
        PlannedOrder sellTemplate = template(OrderDirection.SELL, "60.00", 5);

        new TradingOrderPlanner(orderPort).savePlannedOrders(List.of(buyTemplate, sellTemplate), ACCOUNT, STRATEGY_CYCLE_ID);

        verify(orderPort).saveAll(ordersCaptor.capture());
        List<Order> saved = ordersCaptor.getValue();
        assertThat(saved).hasSize(2);

        Order savedBuy = saved.get(0);
        assertThat(savedBuy.id()).isNull();
        assertThat(savedBuy.accountId()).isEqualTo(ACCOUNT.id());
        assertThat(savedBuy.strategyCycleId()).isEqualTo(STRATEGY_CYCLE_ID);
        assertThat(savedBuy.status()).isEqualTo(Order.OrderStatus.PLANNED);
        assertThat(savedBuy.externalOrderId()).isNull();
        assertThat(savedBuy.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(savedBuy.orderLeg()).isEqualTo("INFINITE_BUY_01");
        assertThat(savedBuy.quantity()).isEqualTo(10);
        assertThat(savedBuy.price()).isEqualByComparingTo("50.00");

        Order savedSell = saved.get(1);
        assertThat(savedSell.accountId()).isEqualTo(ACCOUNT.id());
        assertThat(savedSell.direction()).isEqualTo(OrderDirection.SELL);
        assertThat(savedSell.quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("템플릿이 비어있으면 빈 목록으로 저장 호출")
    void savePlannedOrders_emptyTemplates_savesEmptyList() {
        new TradingOrderPlanner(orderPort).savePlannedOrders(List.of(), ACCOUNT, STRATEGY_CYCLE_ID);

        verify(orderPort).saveAll(ordersCaptor.capture());
        assertThat(ordersCaptor.getValue()).isEmpty();
    }
}
