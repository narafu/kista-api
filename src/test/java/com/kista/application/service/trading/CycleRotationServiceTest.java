package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.*;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 사이클 종료 후 재등록(MAINTAIN/MAX) 정책 검증 — 최소금액 가드(InfiniteCycleOrderStrategy.MIN_DEPOSIT_MULTIPLIER=44) 포함
@ExtendWith(MockitoExtension.class)
@DisplayName("CycleRotationService 단위 테스트")
class CycleRotationServiceTest {

    @Mock KisMarginPort kisMarginPort;
    @Mock TradingCyclePort cyclePort;
    @Mock TradingCycleHistoryPort cycleHistoryPort;
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
        service = new CycleRotationService(kisMarginPort, cyclePort, cycleHistoryPort,
                notifyPort, userNotificationPort, cycleStrategies);
    }

    private TradingCycle cycle(TradingCycle.CycleSeedType seedType, BigDecimal initialUsdDeposit) {
        return new TradingCycle(UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.INFINITE,
                TradingCycle.Status.ACTIVE, Ticker.SOXL, initialUsdDeposit, seedType);
    }

    private MarginItem usdMargin(String purchasable) {
        return new MarginItem(Currency.USD, new BigDecimal(purchasable), new BigDecimal(purchasable),
                new BigDecimal(purchasable), new BigDecimal("1300.00"));
    }

    @Test
    @DisplayName("MAINTAIN — 기존 initialUsdDeposit 유지하여 재등록")
    void maintain_keepsExistingDeposit() {
        // minRequired = 22 × 44 = 968 — 기존 1000 통과
        TradingCycle cycle = cycle(TradingCycle.CycleSeedType.MAINTAIN, new BigDecimal("1000.00"));

        service.rotate(cycle, ACCOUNT, USER, PRICE, null);

        ArgumentCaptor<TradingCycle> cycleCaptor = ArgumentCaptor.forClass(TradingCycle.class);
        verify(cyclePort).save(cycleCaptor.capture());
        TradingCycle saved = cycleCaptor.getValue();
        assertThatCycleRotated(saved, cycle, new BigDecimal("1000.00"));

        verify(cycleHistoryPort).save(argThat(h ->
                h.tradingCycleId().equals(cycle.id())
                        && h.usdDeposit().compareTo(new BigDecimal("1000.00")) == 0
                        && h.holdings() == 0
                        && h.avgPrice() == null));
        verify(userNotificationPort).notifyStrategyChanged(USER, ACCOUNT, saved, "재등록");
        verify(kisMarginPort, never()).getMargin(any());
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    @DisplayName("MAINTAIN — 최소금액 미달 시 재등록 취소 + 잔고부족 알림")
    void maintain_belowMinRequired_cancelsAndNotifies() {
        // minRequired = 22 × 44 = 968 — 기존 500은 미달
        TradingCycle cycle = cycle(TradingCycle.CycleSeedType.MAINTAIN, new BigDecimal("500.00"));

        service.rotate(cycle, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT),
                argThat(b -> b.usdDeposit().compareTo(new BigDecimal("500.00")) == 0), eq(Ticker.SOXL));
        verify(cyclePort, never()).save(any());
        verify(cycleHistoryPort, never()).save(any());
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — KIS USD 잔고로 nextDeposit 갱신하여 재등록")
    void max_resolvesDepositFromKisMargin() {
        // KIS USD purchasableAmount=2000 — minRequired(968) 통과
        TradingCycle cycle = cycle(TradingCycle.CycleSeedType.MAX, new BigDecimal("1000.00"));
        when(kisMarginPort.getMargin(ACCOUNT)).thenReturn(List.of(
                new MarginItem(Currency.KRW, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                usdMargin("2000.00")));

        service.rotate(cycle, ACCOUNT, USER, PRICE, null);

        ArgumentCaptor<TradingCycle> cycleCaptor = ArgumentCaptor.forClass(TradingCycle.class);
        verify(cyclePort).save(cycleCaptor.capture());
        assertThatCycleRotated(cycleCaptor.getValue(), cycle, new BigDecimal("2000.00"));
        verify(cycleHistoryPort).save(argThat(h ->
                h.usdDeposit().compareTo(new BigDecimal("2000.00")) == 0));
        verify(userNotificationPort).notifyStrategyChanged(eq(USER), eq(ACCOUNT), any(), eq("재등록"));
    }

    @Test
    @DisplayName("MAX — KIS 잔고 조회 실패 시 재등록 중단 + 관리자 오류 알림")
    void max_kisLookupFails_abortsAndNotifiesError() {
        TradingCycle cycle = cycle(TradingCycle.CycleSeedType.MAX, new BigDecimal("1000.00"));
        RuntimeException kisError = new RuntimeException("KIS 잔고 조회 실패");
        when(kisMarginPort.getMargin(ACCOUNT)).thenThrow(kisError);

        service.rotate(cycle, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(kisError);
        verify(cyclePort, never()).save(any());
        verify(cycleHistoryPort, never()).save(any());
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — USD 잔고 행이 없으면 재등록 중단 + 오류 알림")
    void max_noUsdMarginRow_abortsAndNotifiesError() {
        TradingCycle cycle = cycle(TradingCycle.CycleSeedType.MAX, new BigDecimal("1000.00"));
        when(kisMarginPort.getMargin(ACCOUNT)).thenReturn(List.of(
                new MarginItem(Currency.KRW, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

        service.rotate(cycle, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(cyclePort, never()).save(any());
        verify(cycleHistoryPort, never()).save(any());
    }

    private void assertThatCycleRotated(TradingCycle saved, TradingCycle original, BigDecimal expectedDeposit) {
        org.assertj.core.api.Assertions.assertThat(saved.id()).isEqualTo(original.id());
        org.assertj.core.api.Assertions.assertThat(saved.accountId()).isEqualTo(original.accountId());
        org.assertj.core.api.Assertions.assertThat(saved.type()).isEqualTo(original.type());
        org.assertj.core.api.Assertions.assertThat(saved.status()).isEqualTo(TradingCycle.Status.ACTIVE);
        org.assertj.core.api.Assertions.assertThat(saved.ticker()).isEqualTo(original.ticker());
        org.assertj.core.api.Assertions.assertThat(saved.cycleSeedType()).isEqualTo(original.cycleSeedType());
        org.assertj.core.api.Assertions.assertThat(saved.initialUsdDeposit()).isEqualByComparingTo(expectedDeposit);
    }

}
