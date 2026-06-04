package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase.BatchContext;
import com.kista.domain.port.in.ManualExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class ManualTradingService implements ManualExecuteTradingUseCase {

    private final TradingCyclePort cyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final UserPort userPort;
    private final NotifyPort notifyPort;
    private final TradingService tradingService; // package-private 헬퍼 접근용

    @Override
    public List<Order> execute(UUID cycleId, UUID requesterId) {
        // 동기 검증: 소유권·타입·상태
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId); // SecurityException → 403
        if (cycle.type() != TradingCycle.Type.INFINITE)
            throw new IllegalArgumentException("INFINITE 사이클만 수동 실행 가능합니다");
        if (cycle.status() != TradingCycle.Status.ACTIVE)
            throw new IllegalArgumentException("ACTIVE 상태의 사이클만 수동 실행 가능합니다");

        // 주문 가능 시간대 확인 — BLOCKED(DST 05:00~17:00, 비DST 06:00~18:00)면 즉시 거부
        DstInfo dst = DstInfo.immediate();
        if (dst.currentSession() == DstInfo.MarketSession.BLOCKED) {
            String range = dst.isDst() ? "05:00~17:00" : "06:00~18:00";
            throw new IllegalStateException("주문 불가 시간대입니다 (KST " + range + ")");
        }

        // 스케줄러와 동일 today 계산: KST 04:00 이후면 +1일(= 다음 US 거래일)
        LocalDate today = LocalTime.now().isBefore(LocalTime.of(4, 0))
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);

        // 이중 실행 방지
        if (!orderPort.findPlacedByAccountAndDate(account.id(), today).isEmpty())
            throw new IllegalStateException("오늘 이미 실행된 사이클입니다");
        User user = userPort.findById(account.userId())
                .orElseThrow(() -> new NoSuchElementException("사용자 없음: " + account.userId()));

        // 일반 LOC 직접 접수 (planAndSaveOrders/B 동기, recordAndNotifyExecutions 비동기)
        Map<Ticker, BigDecimal> startPrices = tradingService.fetchPricesComplete(List.of(cycle.ticker()), account);
        TradingService.CycleState state = tradingService.planAndSaveOrders(
                new BatchContext(cycle, account, user), startPrices, null, today);
        if (state == null) return List.of(); // 휴장 또는 잔고 부족

        // KIS 접수 실패 시 PLANNED 주문 정리 — 재시도 때 중복 누적 방지
        List<Order> placed;
        try {
            placed = state.isManualCorrection()
                    ? tradingService.executeCorrectionOrders(today, state)
                    : tradingService.executePlannedOrders(today, account);
        } catch (Exception e) {
            try {
                orderPort.deletePlannedByAccountAndDate(account.id(), today);
            } catch (Exception cleanup) {
                log.warn("PLANNED 주문 정리 실패 (원본 오류: {}): {}", e.getMessage(), cleanup.getMessage());
            }
            throw e;
        }

        TradingService.CyclePlacedState placedState = new TradingService.CyclePlacedState(state, placed);
        Thread.startVirtualThread(() -> {
            try {
                tradingService.waitForPostClose(dst);
                Map<Ticker, BigDecimal> closingPrices = tradingService.fetchPricesComplete(List.of(cycle.ticker()), account);
                tradingService.recordAndNotifyExecutions(placedState, closingPrices, today);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("수동 실행 recordAndNotifyExecutions 인터럽트: cycleId={}", cycleId);
            } catch (Exception e) {
                log.error("수동 실행 recordAndNotifyExecutions 오류: cycleId={}", cycleId, e);
                notifyPort.notifyError(e);
            }
        });

        return placed;
    }
}
