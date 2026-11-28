package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class MeritzBrokerAdapterTest {

    @Mock MeritzTradingApi tradingApi;
    @Mock MeritzOrderApi orderApi;
    @Mock MeritzPriceApi priceApi;

    @Test
    void supports_meritz_and_delegates() {
        MeritzBrokerAdapter adapter = new MeritzBrokerAdapter(tradingApi, orderApi, priceApi);
        BrokerAccountRef account = mock(BrokerAccountRef.class);
        OrderInstruction order = mock(OrderInstruction.class);
        CancelInstruction cancel = mock(CancelInstruction.class);
        when(priceApi.getPrices(List.of(StrategyTicker.SOXL), account)).thenReturn(Map.of(StrategyTicker.SOXL, BigDecimal.TEN));

        assertThat(adapter.supports()).isEqualTo(Broker.MERITZ);
        assertThat(adapter.currentPrices(account, List.of(StrategyTicker.SOXL)).get(StrategyTicker.SOXL)).isEqualByComparingTo("10");
        adapter.place(order, account);
        adapter.cancel(cancel, account);
        verify(orderApi).place(order, account);
        verify(orderApi).cancel(cancel, account);
    }
}
