package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final BrokerPriceRouter brokerPriceRouter;
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderComputer orderComputer;
    private final CycleOrderStrategies cycleOrderStrategies;

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회 (PRIVACY에서 필요)
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        // 스케쥴러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = DstInfo.nextTradeDate();

        // 오늘 이미 등록된 주문 조회 — PLANNED(취소 가능) + PLACED(AT_OPEN 선접수됨) 모두 포함
        List<com.kista.domain.model.order.Order> todayPlannedOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);

        // 계좌 내 타 전략 당일 PLANNED BUY 합계 (이 전략 분 제외 — 예수금 부족 계산에 사용)
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        BigDecimal thisStrategyPlannedBuy = todayPlannedOrders.stream()
                .filter(o -> o.direction() == com.kista.domain.model.order.Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        // 잔고 로드 (preview 전용 — 이력 없음도 정상 skip으로 처리)
        TradingBalanceLoader.BalanceLoad load = balanceLoader.tryLoadBalance(strategy);
        if (load.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), load.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd);
        }
        AccountBalance balance = load.balance();

        // 전략별 필요 데이터 조회 — CycleOrderStrategy 다형 메서드로 전략 타입 분기 제거
        var orderStrategy = cycleOrderStrategies.of(strategy);
        BigDecimal prevClosePrice = null;
        if (orderStrategy.requiresPrevClose()) {
            try {
                prevClosePrice = brokerPriceRouter.getPriceSnapshot(strategy.ticker(), account).prevClose();
            } catch (Exception e) {
                log.warn("전일종가 조회 실패 — 미리보기 중단: ticker={}, error={}", strategy.ticker().name(), e.getMessage());
                throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
            }
        }
        PrivacyTradeBase privacyBase = orderStrategy.requiresPrivacyBase()
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        CycleOrderComputer.ComputeResult result = orderComputer.compute(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, "preview:" + strategyId);

        // 전략 차원 skip — 현재 케이스는 PRIVACY 기준매매표 미수신만 해당
        if (result.isSkipped()) {
            return new NextOrdersPreview(today, null, List.of(), SkipReason.NO_PRIVACY_BASE, todayPlannedOrders, otherStrategiesPlannedBuyUsd);
        }

        return new NextOrdersPreview(today, result.position(), result.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd);
    }
}
