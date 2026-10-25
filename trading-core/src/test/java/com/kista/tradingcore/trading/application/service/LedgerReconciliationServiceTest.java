package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.PortfolioPort;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kista.shared.kernel.StrategyTicker.SOXL;
import static com.kista.shared.kernel.StrategyTicker.TQQQ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerReconciliationServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock PortfolioPort portfolioPort;
    @Mock TradingErrorReportPort errorReportPort;
    @InjectMocks LedgerReconciliationService service;

    private final Map<UUID, List<Strategy>> strategiesByAccount = new HashMap<>();
    private final Map<UUID, StrategyCycle> cyclesByStrategy = new HashMap<>();
    private final Map<UUID, CyclePosition> positionsByCycle = new HashMap<>();

    @AfterEach
    void 원장을_쓰지_않는다() {
        verify(cyclePositionPort, never()).save(any());
        verify(strategyPort, never()).save(any());
    }

    private static Account account(Broker broker, String nickname) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), nickname, "1", "k", "s", null, broker, null);
    }

    // 진행 중(ended=false) 또는 종료 사이클 + 최신 포지션을 가진 전략 등록
    private void strategy(Account account, StrategyType type, StrategyTicker ticker, String holdings, boolean ended) {
        Strategy s = new Strategy(UUID.randomUUID(), account.id(), type, StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE);
        strategiesByAccount.computeIfAbsent(account.id(), k -> new ArrayList<>()).add(s);
        StrategyCycle c = new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(), BigDecimal.TEN, null,
                LocalDate.of(2026, 10, 1), ended ? LocalDate.of(2026, 10, 20) : null, null, null);
        cyclesByStrategy.put(s.id(), c);
        positionsByCycle.put(c.id(), new CyclePosition(null, c.id(), new BigDecimal("100"), null, null,
                new BigDecimal(holdings), null, null));
    }

    private void stubLedgers(Account... accounts) {
        when(accountPort.findAll()).thenReturn(Arrays.asList(accounts));
        lenient().when(strategyPort.findByAccountIds(anyCollection())).thenReturn(strategiesByAccount);
        lenient().when(strategyCyclePort.findLatestByStrategyIds(anyCollection())).thenReturn(cyclesByStrategy);
        lenient().when(cyclePositionPort.findLatestByCycleIds(anyCollection())).thenReturn(positionsByCycle);
    }

    private static PresentBalanceResult balance(StrategyTicker ticker, int holdings) {
        var item = new PresentBalanceResult.Item(ticker, holdings, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "NASD");
        return new PresentBalanceResult(List.of(item), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private List<String> reportedMessages(int expected) {
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(errorReportPort, times(expected)).reportError(captor.capture());
        return captor.getAllValues().stream().map(Exception::getMessage).toList();
    }

    @Test
    void 괴리_계좌만_계좌별_1건_알림() {
        Account kis = account(Broker.KIS, "한투계좌");
        Account toss = account(Broker.TOSS, "토스계좌");
        strategy(kis, StrategyType.INFINITE, SOXL, "10", false);
        strategy(toss, StrategyType.VR, TQQQ, "5", false);
        stubLedgers(kis, toss);
        when(portfolioPort.getPresentBalance(kis.toBrokerRef())).thenReturn(balance(SOXL, 9));
        when(portfolioPort.getPresentBalance(toss.toBrokerRef())).thenReturn(balance(TQQQ, 5));

        service.reconcileAll();

        assertThat(reportedMessages(1)).singleElement().asString()
                .contains("[원장 대사]", "한투계좌", "실보유 부족");
    }

    @Test
    void 정상이면_알림_없음() {
        Account kis = account(Broker.KIS, "한투계좌");
        strategy(kis, StrategyType.INFINITE, SOXL, "10", false);
        stubLedgers(kis);
        when(portfolioPort.getPresentBalance(kis.toBrokerRef())).thenReturn(balance(SOXL, 10));

        service.reconcileAll();

        verifyNoInteractions(errorReportPort);
    }

    @Test
    void MOCK은_브로커를_조회하지_않는다() {
        Account mock = account(Broker.MOCK, "TEST");
        strategy(mock, StrategyType.INFINITE, SOXL, "10", false);
        stubLedgers(mock);

        service.reconcileAll();

        verify(portfolioPort, never()).getPresentBalance(any());
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void 코인_계좌는_건너뛴다() {
        Account upbit = TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID());
        when(accountPort.findAll()).thenReturn(List.of(upbit));
        when(strategyPort.findByAccountIds(anyCollection())).thenAnswer(inv -> {
            assertThat(inv.<java.util.Collection<UUID>>getArgument(0)).doesNotContain(upbit.id());
            return Map.of();
        });

        service.reconcileAll();

        verify(portfolioPort, never()).getPresentBalance(any());
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void 종료_사이클과_포지션_없는_전략은_합산에서_빠진다() {
        Account kis = account(Broker.KIS, "한투계좌");
        strategy(kis, StrategyType.INFINITE, SOXL, "100", true);   // 종료 사이클
        strategy(kis, StrategyType.DCA, SOXL, "10", false);
        strategy(kis, StrategyType.VR, TQQQ, "7", false);
        UUID vrCycle = cyclesByStrategy.get(strategiesByAccount.get(kis.id()).get(2).id()).id();
        positionsByCycle.remove(vrCycle);                            // 포지션 없음
        stubLedgers(kis);
        when(portfolioPort.getPresentBalance(kis.toBrokerRef())).thenReturn(balance(SOXL, 10));

        service.reconcileAll();

        verifyNoInteractions(errorReportPort);
    }

    @Test
    void 조회_실패_계좌는_건너뛰고_다른_계좌는_계속() {
        Account a = account(Broker.KIS, "실패계좌");
        Account b = account(Broker.TOSS, "괴리계좌");
        strategy(a, StrategyType.INFINITE, SOXL, "10", false);
        strategy(b, StrategyType.INFINITE, SOXL, "10", false);
        stubLedgers(a, b);
        when(portfolioPort.getPresentBalance(a.toBrokerRef())).thenThrow(new IllegalStateException("timeout"));
        when(portfolioPort.getPresentBalance(b.toBrokerRef())).thenReturn(balance(SOXL, 3));

        service.reconcileAll();

        List<String> messages = reportedMessages(2);
        assertThat(messages).anyMatch(m -> m.contains("[원장 대사] 브로커 잔고 조회 실패") && m.contains("실패계좌"));
        assertThat(messages).anyMatch(m -> m.contains("실보유 부족") && m.contains("괴리계좌"));
    }

    @Test
    void 전략_없는_계좌는_브로커를_조회하지_않는다() {
        Account kis = account(Broker.KIS, "빈계좌");
        stubLedgers(kis);

        service.reconcileAll();

        verify(portfolioPort, never()).getPresentBalance(any());
    }
}
