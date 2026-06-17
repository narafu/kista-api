package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
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

// 전략이 계산한 템플릿(Order)을 PLANNED 상태 + 신규 PK(null) + 호출 계좌 FK로 변환해 일괄 저장하는지 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrderPlanner 단위 테스트")
class TradingOrderPlannerTest {

    @Mock OrderPort orderPort;
    @Captor ArgumentCaptor<List<Order>> ordersCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    private Order template(Order.OrderDirection direction, String price, int quantity) {
        // 전략이 만든 템플릿은 id/accountId/strategyCycleId/status/externalOrderId가 비어있음 (계좌 귀속 전)
        return new Order(null, null, null, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, direction, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    @Test
    @DisplayName("템플릿을 PLANNED 상태 + 신규 PK + 계좌 FK로 변환해 일괄 저장")
    void savePlannedOrders_convertsTemplatesAndSavesAll() {
        Order buyTemplate = template(Order.OrderDirection.BUY, "50.00", 10);
        Order sellTemplate = template(Order.OrderDirection.SELL, "60.00", 5);

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
        assertThat(savedBuy.direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(savedBuy.quantity()).isEqualTo(10);
        assertThat(savedBuy.price()).isEqualByComparingTo("50.00");

        Order savedSell = saved.get(1);
        assertThat(savedSell.accountId()).isEqualTo(ACCOUNT.id());
        assertThat(savedSell.direction()).isEqualTo(Order.OrderDirection.SELL);
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
