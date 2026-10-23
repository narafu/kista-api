package com.kista.tradingcore.broker.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CryptoExchangesTest {

    @Test
    void routesBySupportedBrokerAndRejectsStockBrokers() {
        CryptoExchangePort upbit = mock(CryptoExchangePort.class);
        when(upbit.supports()).thenReturn(Broker.UPBIT);
        CryptoExchangePort binance = mock(CryptoExchangePort.class);
        when(binance.supports()).thenReturn(Broker.BINANCE);
        CryptoExchanges exchanges = new CryptoExchanges(List.of(upbit, binance));

        assertThat(exchanges.of(Broker.UPBIT)).isSameAs(upbit);
        assertThat(exchanges.of(Broker.BINANCE)).isSameAs(binance);
        assertThatThrownBy(() -> exchanges.of(Broker.KIS)).isInstanceOf(IllegalArgumentException.class);
    }
}
