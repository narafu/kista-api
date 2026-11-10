package com.kista.tradingcore.stats.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.trading.domain.model.Order;
import java.time.LocalDate;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.PortfolioPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountStatisticsServiceTest {

    @Mock AccountPort accountPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock BrokerPricePort pricePort;
    @Mock PortfolioPort portfolioPort;

    private AccountStatisticsService service;
    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccountStatisticsService(
                accountPort, cyclePositionPort, orderPort, portfolioPort, pricePort, FixedClockTestConfig.FIXED);
        // 실제 Account record — account.toBrokerRef()가 인스턴스 메서드
        Account account = new Account(accountId, userId, "테스트계좌", "74420614-01", "key", "secret", null, Broker.KIS, null, null);
        lenient().when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
    }

    @Test
    void getPrices_returns_prev_close_not_current() {
        // 전략 생성 화면 티커 목록 가격도 basePrice와 동일 소스(전일종가)를 써야 함 (현재가 API 미사용)
        when(pricePort.getPrevCloses(eq(List.of(StrategyTicker.SOXL)), any(BrokerAccountRef.class)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("89.20")));

        var result = service.getPrices(accountId, userId, List.of(StrategyTicker.SOXL));

        assertThat(result.get(StrategyTicker.SOXL)).isEqualByComparingTo("89.20");
        verify(pricePort, never()).getPrices(any(), any());
        verify(pricePort, never()).getPriceSnapshots(any(), any());
    }

    @Test
    void getPresentBalance_brokerFailure_wrappedAsIllegalStateException() {
        // BrokerCallGuard로 감싸져야 함 — 증권사 타입 외 예상 밖 예외는 사용자용 메시지로 래핑
        when(portfolioPort.getPresentBalance(any())).thenThrow(new RuntimeException("Toss 매수가능금액 응답 없음"));

        assertThatThrownBy(() -> service.getPresentBalance(accountId, userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("증권사 API 조회에 실패했습니다");
    }

    @Test
    void 일별_거래내역은_코인_소수_수량_체결을_포함한다() {
        UUID coinAccount = UUID.randomUUID();
        when(accountPort.findByUserId(userId)).thenReturn(List.of(account(coinAccount, Broker.BINANCE)));
        when(orderPort.findFilledByUser(eq(userId), any(), any())).thenReturn(List.of(
                filled(coinAccount, StrategyTicker.ETH, "0.0123", "3000.555")));

        var item = service.getDailyTransactionsForUser(userId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .items().getFirst();

        assertThat(item.quantity()).isEqualByComparingTo("0.0123");
        assertThat(item.tradeAmount()).isEqualByComparingTo("36.91");
        assertThat(item.currency()).isEqualTo(Currency.USDT);
    }

    @Test
    void KRW_체결_금액은_정수로_반올림한다() {
        UUID upbit = UUID.randomUUID();
        when(accountPort.findByUserId(userId)).thenReturn(List.of(account(upbit, Broker.UPBIT)));
        when(orderPort.findFilledByUser(eq(userId), any(), any())).thenReturn(List.of(
                filled(upbit, StrategyTicker.BTC, "0.00031", "95000000")));

        var item = service.getDailyTransactionsForUser(userId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .items().getFirst();

        assertThat(item.tradeAmount()).isEqualByComparingTo("29450");
        assertThat(item.tradeAmount().scale()).isZero();
        assertThat(item.currency()).isEqualTo(Currency.KRW);
    }

    @Test
    void 주식_체결은_USD로_표기한다() {
        when(orderPort.findFilledByAccount(eq(accountId), any(), any())).thenReturn(List.of(
                filled(accountId, StrategyTicker.SOXL, "10", "20.5")));

        var item = service.getDailyTransactions(accountId, userId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .items().getFirst();

        assertThat(item.tradeAmount()).isEqualByComparingTo("205.00");
        assertThat(item.currency()).isEqualTo(Currency.USD);
    }

    @Test
    void 계좌_맵에_없는_주문은_IllegalStateException() {
        when(accountPort.findByUserId(userId)).thenReturn(List.of());
        when(orderPort.findFilledByUser(eq(userId), any(), any())).thenReturn(List.of(
                filled(UUID.randomUUID(), StrategyTicker.SOXL, "1", "1")));

        assertThatThrownBy(() -> service.getDailyTransactionsForUser(userId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isInstanceOf(IllegalStateException.class).hasMessage("주문 계좌를 찾을 수 없습니다.");
    }

    private Account account(UUID id, Broker broker) {
        return new Account(id, userId, "계좌", "1", "key", "secret", null, broker, null, null);
    }

    private Order filled(UUID account, StrategyTicker ticker, String qty, String price) {
        return new Order(UUID.randomUUID(), account, UUID.randomUUID(), LocalDate.of(2026, 1, 5), ticker,
                OrderType.LOC, null, OrderDirection.BUY, new BigDecimal(qty), new BigDecimal(price),
                null, null, new BigDecimal(qty), new BigDecimal(price));
    }

    @Test
    void getByAccount_krwAccount_roundsDepositToScale0() {
        UUID upbitId = UUID.randomUUID();
        Account upbit = new Account(upbitId, userId, "업비트", null, "key", "secret", null, Broker.UPBIT, null, null);
        when(accountPort.requireOwnedAccount(upbitId, userId)).thenReturn(upbit);
        var entry = new com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry(
                UUID.randomUUID(), upbitId, StrategyTicker.BTC, com.kista.shared.kernel.StrategyType.DCA,
                new BigDecimal("12345.67"), new BigDecimal("0.55"), new BigDecimal("0.53"), BigDecimal.TEN, java.time.Instant.now());
        when(cyclePositionPort.findByAccountIdWithCursor(eq(upbitId), any(), any(), eq(11))).thenReturn(List.of(entry));

        var page = service.getByAccount(upbitId, userId, null, null, null, 10);

        assertThat(page.items().getFirst().deposit()).hasToString("12346");
        assertThat(page.items().getFirst().closingPrice()).hasToString("0.55");
    }
}
