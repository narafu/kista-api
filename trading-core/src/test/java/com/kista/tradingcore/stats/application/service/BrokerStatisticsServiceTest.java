package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.broker.application.port.output.BrokerStatisticsPort;
import com.kista.tradingcore.broker.application.service.BrokerStatisticsPorts;
import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerStatisticsServiceTest {

    @Mock
    private BrokerStatisticsPorts statisticsPorts;

    @Mock
    private BrokerStatisticsPort statisticsPort;

    @InjectMocks
    private BrokerStatisticsService sut;

    @Test
    void currentExchangeRate_TOSS_통계_포트로_계좌_없이_조회() {
        ExchangeRateQuote expected = new ExchangeRateQuote(new BigDecimal("1380.50"), new BigDecimal("1375.00"));
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.of(statisticsPort));
        when(statisticsPort.getExchangeRate()).thenReturn(expected);

        assertThat(sut.currentExchangeRate()).isSameAs(expected);
    }

    @Test
    void currentExchangeRate_TOSS_포트가_없으면_IllegalStateException() {
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.currentExchangeRate()).isInstanceOf(IllegalStateException.class);
    }
}
