package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 사이클 종료 후 재등록(MAINTAIN/MAX) 정책 검증 — 최소금액 가드(InfiniteCycleOrderStrategy.MIN_DEPOSIT_MULTIPLIER=44) 포함
@ExtendWith(MockitoExtension.class)
@DisplayName("CycleRotationService 단위 테스트")
class CycleRotationServiceTest {

    @Mock BrokerMarginRouter brokerMarginRouter;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;

    CycleRotationService service;

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS);

    static final User USER = new User(ACCOUNT.userId(), "kakao-1", "홍길동",
            User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM);

    @BeforeEach
    void setUp() {
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        service = new CycleRotationService(brokerMarginRouter, strategyPort, strategyCyclePort,
                cyclePositionPort, notifyPort, userNotificationPort, cycleStrategies);
    }

    // StrategyCycle — 현재 사이클 (MAINTAIN/MAX 시드 계산 기준)
    private StrategyCycle currentCycle(UUID strategyId, BigDecimal startAmount) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, startAmount,
                null, LocalDate.now(), null, Instant.now(), null);
    }

    // 새 StrategyCycle stub 반환값 (save 후 id 포함)
    private StrategyCycle savedNewCycle(UUID strategyId, BigDecimal deposit) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, deposit, null, LocalDate.now(), null, Instant.now(), null);
    }

    private Strategy strategy(Strategy.CycleSeedType seedType) {
        return new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, seedType);
    }


    @Test
    @DisplayName("MAINTAIN — 기존 initialUsdDeposit 유지하여 재등록")
    void maintain_keepsExistingDeposit() {
        // minRequired = 22 × 44 = 968 — 기존 1000 통과
        BigDecimal deposit = new BigDecimal("1000.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        StrategyCycle newCycle = savedNewCycle(strategy.id(), deposit);
        // MAINTAIN도 실잔고 확인 — actual >= maintainSeed 이면 재등록
        when(brokerMarginRouter.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("1500.00"));
        when(strategyCyclePort.save(any())).thenReturn(newCycle);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo(deposit);
        assertThat(cycleCaptor.getValue().strategyId()).isEqualTo(strategy.id());

        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(newCycle.id())
                        && p.usdDeposit().compareTo(deposit) == 0
                        && p.holdings() == 0
                        && p.avgPrice() == null));
        verify(userNotificationPort).notifyStrategyChanged(USER, ACCOUNT, strategy, "재등록");
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    @DisplayName("MAINTAIN — 최소금액 미달 시 재등록 취소 + 잔고부족 알림")
    void maintain_belowMinRequired_cancelsAndNotifies() {
        // minRequired = 22 × 44 = 968 — 기존 500은 미달
        // actual(600) >= maintainSeed(500) → targetSeed=500, 하지만 500 < minRequired(968) → 잔고부족 알림
        BigDecimal deposit = new BigDecimal("500.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        when(brokerMarginRouter.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("600.00"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT),
                argThat(b -> b.usdDeposit().compareTo(deposit) == 0), eq(Ticker.SOXL));
        verify(strategyCyclePort, never()).save(any());
        verify(cyclePositionPort, never()).save(any());
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — 내부 원장 maxSeed 기준으로 재등록 (KIS 잔고는 검증용)")
    void max_resolvesDepositFromKisMargin() {
        // maxSeed = 마지막 CyclePosition.usdDeposit = 1500 (내부 원장)
        // KIS actual(2000) >= maxSeed(1500) → targetSeed = 1500 (NOT 2000)
        BigDecimal maintainDeposit = new BigDecimal("1000.00");
        BigDecimal maxSeedDeposit = new BigDecimal("1500.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), maintainDeposit);
        StrategyCycle newCycle = savedNewCycle(strategy.id(), maxSeedDeposit);

        // 마지막 CyclePosition이 있어야 maxSeed가 currentCycle.initialUsdDeposit fallback이 아닌 실제 값 사용
        CyclePosition lastPosition = new CyclePosition(UUID.randomUUID(), current.id(), maxSeedDeposit, null, null, 0, null, null);

        when(brokerMarginRouter.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("2000.00"));
        when(cyclePositionPort.findLatestByStrategyId(strategy.id(), 1)).thenReturn(List.of(lastPosition));
        when(strategyCyclePort.save(any())).thenReturn(newCycle);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo(maxSeedDeposit);
        verify(cyclePositionPort).save(argThat(p ->
                p.usdDeposit().compareTo(maxSeedDeposit) == 0));
        verify(userNotificationPort).notifyStrategyChanged(eq(USER), eq(ACCOUNT), eq(strategy), eq("재등록"));
    }

    @Test
    @DisplayName("MAX — KIS 잔고 조회 실패 시 재등록 중단 + 관리자 오류 알림")
    void max_kisLookupFails_abortsAndNotifiesError() {
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        RuntimeException kisError = new RuntimeException("KIS 잔고 조회 실패");
        when(brokerMarginRouter.getUsdBuyableAmount(ACCOUNT)).thenThrow(kisError);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(kisError);
        verify(strategyCyclePort, never()).save(any());
        verify(cyclePositionPort, never()).save(any());
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — USD 잔고 행이 없으면 재등록 중단 + 오류 알림")
    void max_noUsdMarginRow_abortsAndNotifiesError() {
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        // USD 잔고 없음 → router가 BigDecimal.ZERO 반환
        when(brokerMarginRouter.getUsdBuyableAmount(ACCOUNT)).thenReturn(BigDecimal.ZERO);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(strategyCyclePort, never()).save(any());
        verify(cyclePositionPort, never()).save(any());
    }

}
