package com.kista.application.service.trading;

import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.DstInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// placeOpenOrdersNow/executeBatchNow가 각각 immediateOpen/immediateClose DstInfo로 올바른
// TradingService 메서드에 위임되는지 검증 — 매핑이 뒤바뀌면 실매매 사고로 이어짐
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingExecutionFacade 단위 테스트")
class TradingExecutionFacadeTest {

    @Mock
    private TradingService tradingService;
    @Mock
    private ManualTradingService manualTradingService;
    @Mock
    private OrderCancelService orderCancelService;
    @Mock
    private TradingPreviewService tradingPreviewService;

    private TradingExecutionFacade facade;

    @BeforeEach
    void setUp() {
        facade = new TradingExecutionFacade(tradingService, manualTradingService, orderCancelService, tradingPreviewService);
    }

    @Test
    @DisplayName("placeOpenOrdersNow는 immediateOpen DstInfo로 tradingService.placeOpenOrders에 위임한다")
    void placeOpenOrdersNow_delegatesWithImmediateOpen() throws InterruptedException {
        List<BatchContext> contexts = List.of();

        facade.placeOpenOrdersNow(contexts);

        ArgumentCaptor<DstInfo> dstCaptor = ArgumentCaptor.forClass(DstInfo.class);
        verify(tradingService).placeOpenOrders(eq(contexts), dstCaptor.capture());
        verify(tradingService, never()).executeBatch(any(), any());
        DstInfo captured = dstCaptor.getValue();
        // immediateOpen()은 marketOpen만 과거 시각(호출 직전)으로 설정 — 최근 수 초 이내여야 한다
        assertThat(captured.marketOpen()).isBeforeOrEqualTo(Instant.now());
        assertThat(captured.marketOpen()).isAfter(Instant.now().minusSeconds(10));
        // orderAt/postClose는 스케쥴 고정 시각으로 서로 40분 간격 — immediateClose()의 past 패턴과 달리 절대 같지 않다
        assertThat(captured.orderAt()).isNotEqualTo(captured.postClose());
    }

    @Test
    @DisplayName("executeBatchNow는 immediateClose DstInfo로 tradingService.executeBatch에 위임한다")
    void executeBatchNow_delegatesWithImmediateClose() throws InterruptedException {
        List<BatchContext> contexts = List.of();

        facade.executeBatchNow(contexts);

        ArgumentCaptor<DstInfo> dstCaptor = ArgumentCaptor.forClass(DstInfo.class);
        verify(tradingService).executeBatch(eq(contexts), dstCaptor.capture());
        verify(tradingService, never()).placeOpenOrders(any(), any());
        DstInfo captured = dstCaptor.getValue();
        // immediateClose()는 orderAt/postClose를 동일한 과거 Instant(past)로 설정 — immediateOpen()에서는 발생하지 않는 패턴
        assertThat(captured.orderAt()).isEqualTo(captured.postClose());
        assertThat(captured.orderAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(captured.orderAt()).isAfter(Instant.now().minusSeconds(10));
    }

    @Test
    @DisplayName("executeManually는 requesterId와 함께 ManualTradingService.execute에 위임한다")
    void executeManually_delegates() {
        UUID strategyId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        List<Order> orders = List.of();
        when(manualTradingService.execute(strategyId, requesterId)).thenReturn(orders);

        List<Order> result = facade.executeManually(strategyId, requesterId);

        assertThat(result).isSameAs(orders);
        verify(manualTradingService).execute(strategyId, requesterId);
    }

    @Test
    @DisplayName("cancelByCycle은 OrderCancelService.cancelByCycle에 위임한다")
    void cancelByCycle_delegates() {
        UUID strategyId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        CancelResult cancelResult = new CancelResult(0, 0);
        when(orderCancelService.cancelByCycle(strategyId, requesterId)).thenReturn(cancelResult);

        CancelResult result = facade.cancelByCycle(strategyId, requesterId);

        assertThat(result).isSameAs(cancelResult);
        verify(orderCancelService).cancelByCycle(strategyId, requesterId);
    }

    @Test
    @DisplayName("cancelOrder는 OrderCancelService.cancelOrder에 위임한다")
    void cancelOrder_delegates() {
        UUID orderId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        facade.cancelOrder(orderId, requesterId);

        verify(orderCancelService).cancelOrder(orderId, requesterId);
    }

    @Test
    @DisplayName("preview는 TradingPreviewService.preview에 위임한다")
    void preview_delegates() {
        UUID strategyId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        NextOrdersPreview preview = new NextOrdersPreview(LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null);
        when(tradingPreviewService.preview(strategyId, requesterId)).thenReturn(preview);

        NextOrdersPreview result = facade.preview(strategyId, requesterId);

        assertThat(result).isSameAs(preview);
        verify(tradingPreviewService).preview(strategyId, requesterId);
    }
}
