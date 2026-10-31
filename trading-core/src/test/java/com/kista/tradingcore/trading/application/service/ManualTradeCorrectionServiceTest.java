package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.CycleEndedEvent;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrCycleOrderStrategy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class ManualTradeCorrectionServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @Spy CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null),
            new DcaCycleOrderStrategy(),
            new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null)));
    @InjectMocks ManualTradeCorrectionService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();

    @Test
    void correctManualFills_liquidatingSell_savesFilledOrdersAndPausesStrategy() {
        Account account = new Account(ACCOUNT_ID, USER_ID, "KIS", "12345678", "app", "secret", null, Broker.KIS, null, null);
        Strategy strategy = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.MAX);
        StrategyCycle cycle = new StrategyCycle(CYCLE_ID, STRATEGY_ID, VERSION_ID, new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), BigDecimal.valueOf(2), Instant.now(), null);
        ManualTradeCorrectionCommand command = new ManualTradeCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID,
                List.of(new ManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), OrderDirection.SELL, BigDecimal.valueOf(2),
                        new BigDecimal("267.37"), "MANUAL-1", "manual correction")));

        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(strategyPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle);
        when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));

        ManualTradeCorrectionResult result = service.correctManualFills(command);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.finalHoldings()).isZero();
        assertThat(result.strategyStatus()).isEqualTo(StrategyStatus.PAUSED);
        verify(orderPort).saveAll(any());
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("7200.05"), LocalDate.of(2026, 7, 1));
        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == StrategyStatus.PAUSED));
        // 청산 발생 → 사이클 종료 이벤트 발행
        verify(eventPublisher).publishEvent(any(CycleEndedEvent.class));

        ArgumentCaptor<CyclePosition> captor = ArgumentCaptor.forClass(CyclePosition.class);
        verify(cyclePositionPort).save(captor.capture());
        assertThat(captor.getValue().holdings()).isZero();
        assertThat(captor.getValue().usdDeposit()).isEqualByComparingTo("7200.05");
    }

    @Test
    void correctManualFills_sellQuantityGreaterThanHoldings_throws() {
        Account account = new Account(ACCOUNT_ID, USER_ID, "KIS", "12345678", "app", "secret", null, Broker.KIS, null, null);
        Strategy strategy = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(CYCLE_ID, STRATEGY_ID, VERSION_ID, new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), BigDecimal.valueOf(2), Instant.now(), null);
        ManualTradeCorrectionCommand command = new ManualTradeCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID,
                List.of(new ManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), OrderDirection.SELL, BigDecimal.valueOf(3),
                        new BigDecimal("267.37"), null, null)));

        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle);
        when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));

        assertThatThrownBy(() -> service.correctManualFills(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELL quantity");

        verify(orderPort, never()).saveAll(any());
        verify(cyclePositionPort, never()).save(any());
        // 청산 미발생 — 사이클 종료 이벤트 미발행
        verify(eventPublisher, never()).publishEvent(any(CycleEndedEvent.class));
    }

    @Test
    void correctManualFills_vrLiquidation_keepsCycleOpenAndActive() {
        // VR은 청산에도 사이클 유지 — 닫으면 재개가 재오픈하지 않아 좀비 사이클로 영구 정지된다
        Strategy vr = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        stubSelection(vr);

        ManualTradeCorrectionResult result = service.correctManualFills(command(sell(2, "VR-1")));

        assertThat(result.finalHoldings()).isZero();
        assertThat(result.cycleEnded()).isFalse();
        assertThat(result.strategyStatus()).isEqualTo(StrategyStatus.ACTIVE);
        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(strategyPort, never()).save(any());
        verify(cyclePositionPort).save(any());
        verify(orderPort).saveAll(any());
    }

    @Test
    void DCA_보정은_원장에_반영되고_사이클은_유지() {
        // DCA는 영구 사이클·시드 비점유 — 청산해도 사이클 유지, usd_deposit은 0
        Strategy dca = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        stubSelection(dca);

        ManualTradeCorrectionResult result = service.correctManualFills(command(sell(2, "DCA-1")));

        assertThat(result.finalHoldings()).isZero();
        assertThat(result.cycleEnded()).isFalse();
        assertThat(result.finalUsdDeposit()).isZero();
        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        ArgumentCaptor<CyclePosition> captor = ArgumentCaptor.forClass(CyclePosition.class);
        verify(cyclePositionPort).save(captor.capture());
        assertThat(captor.getValue().usdDeposit()).isZero();
        verify(orderPort).saveAll(any());
    }

    @Test
    void 크립토_소수_보정() {
        Strategy dca = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        stubSelection(dca);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID)); // 수량 단위는 계좌 시장 기준
        ManualTradeCorrectionCommand.Fill buy = new ManualTradeCorrectionCommand.Fill(LocalDate.of(2026, 7, 1),
                OrderDirection.BUY, new BigDecimal("0.00123456"), new BigDecimal("100000000"), "C-1", null);

        ManualTradeCorrectionResult result = service.correctManualFills(command(buy));

        // 기존 보유 2 + 0.00123456
        assertThat(result.finalHoldings()).isEqualByComparingTo("2.00123456");
        assertThat(result.finalUsdDeposit()).isZero();
        verify(orderPort).saveAll(any());
    }

    @Test
    void 주식_소수_보정은_400() {
        stubSelection(privacy());
        ManualTradeCorrectionCommand.Fill buy = new ManualTradeCorrectionCommand.Fill(LocalDate.of(2026, 7, 1),
                OrderDirection.BUY, new BigDecimal("1.5"), new BigDecimal("267.37"), "S-1", null);

        assertThatThrownBy(() -> service.correctManualFills(command(buy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정수");
        verify(cyclePositionPort, never()).save(any());
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void correctManualFills_alreadyAppliedExternalOrderId_rejected() {
        // 타임아웃 후 재요청 — 같은 증권사 주문번호가 이미 반영돼 있으면 이중 반영하지 않는다
        stubSelection(privacy());
        when(orderPort.existsByStrategyCycleIdAndTradeDateAndExternalOrderId(CYCLE_ID, LocalDate.of(2026, 7, 1), "DUP-1")).thenReturn(true);

        assertThatThrownBy(() -> service.correctManualFills(command(sell(1, "DUP-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 반영된");
        verify(cyclePositionPort, never()).save(any());
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void correctManualFills_duplicateExternalOrderIdInSameRequest_rejected() {
        stubSelection(privacy());

        assertThatThrownBy(() -> service.correctManualFills(command(sell(1, "SAME"), sell(1, "SAME"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복된");
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    void correctManualFills_sameExternalOrderIdOnDifferentTradeDates_allowedUnderCycleLock() {
        // 증권사 주문번호는 일자별 채번 — 다른 거래일의 같은 번호는 별개 체결, 판정 전 사이클 행을 잠근다
        stubSelection(privacy());
        ManualTradeCorrectionCommand.Fill day1 = sell(1, "ODNO-1");
        ManualTradeCorrectionCommand.Fill day2 = new ManualTradeCorrectionCommand.Fill(LocalDate.of(2026, 7, 2),
                OrderDirection.SELL, BigDecimal.ONE, new BigDecimal("267.37"), "ODNO-1", null);
        when(strategyPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualTradeCorrectionResult result = service.correctManualFills(command(day1, day2));

        assertThat(result.processedCount()).isEqualTo(2);
        verify(strategyCyclePort).lockForUpdate(CYCLE_ID);
    }

    private Account account() {
        return new Account(ACCOUNT_ID, USER_ID, "KIS", "12345678", "app", "secret", null, Broker.KIS, null, null);
    }

    private Strategy privacy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    // 보유 2주 사이클까지 선택 체인 stub — 중복 검사에서 거절되는 경로는 포지션 조회 전에 끝나므로 lenient
    private void stubSelection(Strategy strategy) {
        StrategyCycle cycle = new StrategyCycle(CYCLE_ID, STRATEGY_ID, VERSION_ID, new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), BigDecimal.valueOf(2), Instant.now(), null);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle);
        lenient().when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));
    }

    private static ManualTradeCorrectionCommand.Fill sell(int quantity, String externalOrderId) {
        return new ManualTradeCorrectionCommand.Fill(LocalDate.of(2026, 7, 1), OrderDirection.SELL, BigDecimal.valueOf(quantity),
                new BigDecimal("267.37"), externalOrderId, null);
    }

    private static ManualTradeCorrectionCommand command(ManualTradeCorrectionCommand.Fill... fills) {
        return new ManualTradeCorrectionCommand(USER_ID, ACCOUNT_ID, STRATEGY_ID, List.of(fills));
    }
}
