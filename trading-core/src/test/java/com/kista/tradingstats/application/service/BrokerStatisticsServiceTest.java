package com.kista.tradingstats.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.BrokerStatisticsPort;
import com.kista.broker.application.service.BrokerStatisticsPorts;
import com.kista.broker.domain.model.BrokerAccountInfo;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.ExchangeRateQuote;
import com.kista.sharedkernel.Broker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerStatisticsServiceTest {

    @Mock
    private AccountPort accountPort;

    @Mock
    private BrokerStatisticsPorts statisticsPorts;

    @Mock
    private BrokerStatisticsPort statisticsPort;

    @InjectMocks
    private BrokerStatisticsService sut;

    private final UUID accountId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    // Toss 계좌 stub 생성 헬퍼 — 정상 경로용
    private Account tossAccount() {
        return new Account(accountId, requesterId, "테스트계좌", "1234567890", "appKey", "secretKey",
                "seq", Broker.TOSS, Instant.now());
    }

    // KIS 계좌 stub 생성 헬퍼 — 통계 capability 미지원 시나리오용
    private Account kisAccount() {
        return new Account(accountId, requesterId, "테스트계좌", "74420614-01", "appKey", "secretKey",
                null, Broker.KIS, Instant.now());
    }

    @Test
    void getExchangeRate_소유권이_없으면_SecurityException_전파() {
        // requireOwnedAccount는 default 메서드 — findByIdOrThrow stub으로는 연결되지 않으므로 직접 stub
        when(accountPort.requireOwnedAccount(accountId, requesterId))
                .thenThrow(new SecurityException("계좌 소유자가 아닙니다"));

        assertThatThrownBy(() -> sut.getExchangeRate(accountId, requesterId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("계좌 소유자가 아닙니다");
    }

    @Test
    void getExchangeRate_KIS_계좌면_IllegalArgumentException_전파() {
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(kisAccount());
        // KIS 브로커는 통계 포트 미등록 — 서비스 가드가 거절
        when(statisticsPorts.find(Broker.KIS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.getExchangeRate(accountId, requesterId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KIS 브로커는 ExchangeRatePort를 지원하지 않습니다");
    }

    @Test
    void getExchangeRate_정상_경로면_포트가_반환한_값을_그대로_반환() {
        ExchangeRateQuote expected = new ExchangeRateQuote(new BigDecimal("1380.50"), new BigDecimal("1375.00"));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(tossAccount());
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.of(statisticsPort));
        when(statisticsPort.getExchangeRate()).thenReturn(expected);

        ExchangeRateQuote actual = sut.getExchangeRate(accountId, requesterId);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.rate()).isEqualByComparingTo("1380.50");
        assertThat(actual.midRate()).isEqualByComparingTo("1375.00");
    }

    @Test
    void currentExchangeRate_TOSS_통계_포트로_계좌_없이_조회() {
        ExchangeRateQuote expected = new ExchangeRateQuote(new BigDecimal("1380.50"), new BigDecimal("1375.00"));
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.of(statisticsPort));
        when(statisticsPort.getExchangeRate()).thenReturn(expected);

        assertThat(sut.currentExchangeRate()).isSameAs(expected);
        verifyNoInteractions(accountPort); // 계좌 소유권 검증 없음
    }

    @Test
    void currentExchangeRate_TOSS_포트가_없으면_IllegalStateException() {
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.currentExchangeRate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getAccountList_계좌_참조로_변환해_포트에_위임() {
        Account tossAccount = tossAccount();
        BrokerAccountRef ref = tossAccount.toBrokerRef();
        List<BrokerAccountInfo> expected = List.of(new BrokerAccountInfo(1, "123-456-7890"));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(tossAccount);
        when(statisticsPorts.find(Broker.TOSS)).thenReturn(Optional.of(statisticsPort));
        when(statisticsPort.getAccountList(ref)).thenReturn(expected);

        assertThat(sut.getAccountList(accountId, requesterId)).isSameAs(expected);
    }
}
