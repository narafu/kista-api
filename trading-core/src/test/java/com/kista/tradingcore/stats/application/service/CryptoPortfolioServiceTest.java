package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CryptoPortfolioServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock AccountPort accountPort;
    @Mock CryptoExchanges exchanges;
    @Mock CryptoExchangePort upbit;
    CryptoPortfolioService service;

    @BeforeEach
    void setUp() {
        service = new CryptoPortfolioService(accountPort, exchanges);
    }

    @Test
    void portfolioEvaluatesHoldingsInKrw() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("1000000"), new BigDecimal("5000"),
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), new BigDecimal("0.0005"), new BigDecimal("140000000")))));
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("150000000")));

        CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);

        assertThat(p.krwCash()).isEqualByComparingTo("1005000");
        assertThat(p.holdings()).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isEqualByComparingTo("0.0015");
            assertThat(h.evalAmountKrw()).isEqualByComparingTo("225000");
        });
        assertThat(p.totalAssetKrw()).isEqualByComparingTo("1230000");
    }

    @Test
    void emptyHoldingsSkipPriceCall() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(BigDecimal.TEN, BigDecimal.ZERO, Map.of()));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings()).isEmpty();
        verify(upbit, never()).getCurrentPrices(any());
    }

    @Test
    void stockAccountIsRejected() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> service.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchanges);
    }
}
