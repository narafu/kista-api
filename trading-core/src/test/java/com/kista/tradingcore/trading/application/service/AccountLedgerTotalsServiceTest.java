package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculator;
import com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculator.SeedHolder;
import com.kista.tradingcore.trading.domain.model.AccountLedgerTotals;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountLedgerTotalsServiceTest {

    AccountPort accountPort = mock(AccountPort.class);
    UnassignedCashCalculator calculator = mock(UnassignedCashCalculator.class);
    BrokerPricePort brokerPricePort = mock(BrokerPricePort.class);
    CryptoExchanges cryptoExchanges = mock(CryptoExchanges.class);
    AccountLedgerTotalsService service = new AccountLedgerTotalsService(accountPort, calculator, brokerPricePort, cryptoExchanges);

    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    private static SeedHolder holder(UUID accountId, StrategyTicker ticker, String deposit, String holdings) {
        Strategy s = new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE);
        return new SeedHolder(s, new CyclePosition(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(deposit), null, null,
                new BigDecimal(holdings), null, null));
    }

    private Account kis() {
        Account account = TradingFixtures.kisAccount(accountId, userId);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        return account;
    }

    private void holders(SeedHolder... hs) {
        when(calculator.seedHolders(accountId)).thenReturn(List.of(hs));
    }

    @Test
    void sums_deposit_and_evaluation_of_non_archived_strategies() {
        kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "3"), holder(accountId, StrategyTicker.SOXL, "50", "2"),
                holder(accountId, StrategyTicker.TQQQ, "10", "0"));
        when(brokerPricePort.getPrices(any(), any())).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("10")));
        when(calculator.available(any(BrokerAccountRef.class), any(BigDecimal.class))).thenReturn(new BigDecimal("500"));

        AccountLedgerTotals totals = service.get(accountId, userId);

        assertThat(totals.currency()).isEqualTo(Currency.USD);
        assertThat(totals.deposit()).isEqualByComparingTo("160");
        assertThat(totals.evaluation()).isEqualByComparingTo("50"); // (3+2) × 10
        assertThat(totals.unassignedCash()).isEqualByComparingTo("500");
        verify(brokerPricePort).getPrices(eq(List.of(StrategyTicker.SOXL)), any()); // 보유 있는 ticker만, 중복 없이
    }

    @Test
    void evaluation_null_when_priced_ticker_missing() {
        kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "3"), holder(accountId, StrategyTicker.TQQQ, "20", "1"));
        when(brokerPricePort.getPrices(any(), any())).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("10"))); // TQQQ 가격 누락

        AccountLedgerTotals totals = service.get(accountId, userId);

        assertThat(totals.evaluation()).isNull(); // 부분합(SOXL만) 금지
        assertThat(totals.deposit()).isEqualByComparingTo("120");
    }

    @Test
    void evaluation_null_when_price_call_fails() {
        kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "3"));
        when(brokerPricePort.getPrices(any(), any())).thenThrow(new StubBrokerApiException("시세 장애"));

        assertThat(service.get(accountId, userId).evaluation()).isNull();
    }

    @Test
    void zero_holdings_skips_price_call() {
        kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "0"));

        assertThat(service.get(accountId, userId).evaluation()).isEqualByComparingTo("0");
        verifyNoInteractions(brokerPricePort);
    }

    @Test
    void unassigned_null_when_balance_lookup_fails() {
        kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "0"));
        when(calculator.available(any(BrokerAccountRef.class), any(BigDecimal.class))).thenThrow(new StubBrokerApiException("잔고 장애"));

        AccountLedgerTotals totals = service.get(accountId, userId);
        assertThat(totals.unassignedCash()).isNull();
        assertThat(totals.deposit()).isEqualByComparingTo("100"); // 나머지 필드는 정상
    }

    @Test
    void unassigned_null_for_mock_without_balance_lookup() {
        Account kis = kis();
        holders(holder(accountId, StrategyTicker.SOXL, "100", "0"));
        Account mock = new Account(accountId, userId, "모의", kis.accountNo(), kis.appKey(), kis.secretKey(), null, Broker.MOCK, null, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(mock);
        assertThat(service.get(accountId, userId).unassignedCash()).isNull();
        verify(calculator, never()).available(any(BrokerAccountRef.class), any(BigDecimal.class));
    }

    @Test
    void crypto_account_uses_exchange_prices_and_quote_currency() {
        Account upbit = TradingFixtures.upbitAccount(accountId, userId);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(upbit);
        holders(holder(accountId, StrategyTicker.BTC, "300000", "0.5"));
        CryptoExchangePort exchange = mock(CryptoExchangePort.class);
        when(cryptoExchanges.of(Broker.UPBIT)).thenReturn(exchange);
        when(exchange.getCurrentPrices(any())).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("100000000")));

        AccountLedgerTotals totals = service.get(accountId, userId);

        assertThat(totals.currency()).isEqualTo(Currency.KRW);
        assertThat(totals.evaluation()).isEqualByComparingTo("50000000");
        verifyNoInteractions(brokerPricePort);
    }

    @Test
    void rejects_non_owner() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenThrow(new SecurityException("소유권 불일치"));

        assertThatThrownBy(() -> service.get(accountId, userId)).isInstanceOf(SecurityException.class);
    }
}
