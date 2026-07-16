package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TossStatisticsServiceTest {

    @Mock
    private AccountPort accountPort;

    @Mock
    private BrokerAdapterRegistry registry;

    @InjectMocks
    private TossStatisticsService sut;

    private final UUID accountId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    // Toss 계좌 stub 생성 헬퍼 — 정상 경로용
    private Account tossAccount() {
        return new Account(accountId, requesterId, "테스트계좌", "1234567890", "appKey", "secretKey",
                "seq", Account.Broker.TOSS, Instant.now());
    }

    // KIS 계좌 stub 생성 헬퍼 — Toss 전용 API 미지원 시나리오용
    private Account kisAccount() {
        return new Account(accountId, requesterId, "테스트계좌", "74420614-01", "appKey", "secretKey",
                null, Account.Broker.KIS, Instant.now());
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
    void getExchangeRate_KIS_계좌면_registry에서_IllegalArgumentException_전파() {
        Account kisAccount = kisAccount();
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(kisAccount);
        // KIS 브로커는 ExchangeRatePort(Toss 전용) 미지원 — registry.require가 거절
        when(registry.require(kisAccount, ExchangeRatePort.class))
                .thenThrow(new IllegalArgumentException(
                        kisAccount.broker() + " 브로커는 ExchangeRatePort를 지원하지 않습니다"));

        assertThatThrownBy(() -> sut.getExchangeRate(accountId, requesterId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ExchangeRatePort");
    }

    @Test
    void getExchangeRate_정상_경로면_포트가_반환한_값을_그대로_반환() {
        Account tossAccount = tossAccount();
        TossExchangeRate expected = new TossExchangeRate(new BigDecimal("1380.50"), new BigDecimal("1375.00"));
        ExchangeRatePort exchangeRatePort = mock(ExchangeRatePort.class);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(tossAccount);
        when(registry.require(tossAccount, ExchangeRatePort.class)).thenReturn(exchangeRatePort);
        when(exchangeRatePort.getExchangeRate()).thenReturn(expected);

        TossExchangeRate actual = sut.getExchangeRate(accountId, requesterId);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.rate()).isEqualByComparingTo("1380.50");
        assertThat(actual.midRate()).isEqualByComparingTo("1375.00");
    }
}
