package com.kista.broker.application.service;

import com.kista.broker.application.port.output.BrokerStatisticsPort;
import com.kista.sharedkernel.Broker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerStatisticsPortsTest {

    @Test
    @DisplayName("등록된 증권사는 supports() 기준으로 조회되고 미등록 증권사는 빈 Optional")
    void findBySupports() {
        BrokerStatisticsPort toss = mock(BrokerStatisticsPort.class);
        when(toss.supports()).thenReturn(Broker.TOSS);

        BrokerStatisticsPorts sut = new BrokerStatisticsPorts(List.of(toss));

        assertThat(sut.find(Broker.TOSS)).containsSame(toss);
        assertThat(sut.find(Broker.KIS)).isEmpty();
        assertThat(sut.find(Broker.MOCK)).isEmpty();
    }
}
