package com.kista.application.service.trading;

import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// 사이클 포지션 스냅샷 저장 + 리버스모드 판정 + 사이클 종료·rotation 처리 (TradingReporter에서 분리)
@Component
@RequiredArgsConstructor
@Slf4j
class CyclePositionPersistor {

    private final CyclePositionPort cyclePositionPort;                          // 포지션 스냅샷 저장소
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort; // INFINITE 리버스모드 상세
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;        // 전략 버전별 divisionCount 조회
    private final StrategyCyclePort strategyCyclePort;                          // 사이클 종료 기록
    private final CycleRotationService cycleRotationService;                    // 사이클 종료 후 연속 정책 실행
    private final UserNotificationPort userNotificationPort;                    // 사이클 완료 알림

    // execute() 종료 시 포지션 1건 적재, holdings==0이면 사이클 rotation 정책 처리
    void saveCyclePosition(LocalDate today, AccountBalance balance, BatchContext ctx,
                           BigDecimal price, PrivacyTradeBase privacyBase) {
        Strategy strategy = ctx.strategy();
        StrategyCycle currentCycle = ctx.currentCycle();
        // INFINITE 전략: cycle_position 최신 행을 기반으로 상태 머신으로 새 모드 결정
        boolean newReverseMode = false;
        if (strategy.isInfinite()) {
            newReverseMode = computeNewReverseMode(currentCycle, strategy, balance, price);
        }

        // 저장 전 이전 포지션 확인 — 0회차 매수 실패(holdings=0)와 진짜 청산(이전 holdings>0→현재 0) 구분
        List<CyclePosition> prevPositions = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 1);
        boolean prevHadHoldings = !prevPositions.isEmpty() && prevPositions.get(0).holdings() > 0;

        CyclePosition position = CyclePosition.tradeSnapshot(currentCycle.id(), balance, price);
        CyclePosition savedPosition = cyclePositionPort.save(position);
        if (strategy.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(savedPosition.id(), newReverseMode));
        }
        log.info("[strategyId={}] 사이클 포지션 저장 완료 (isReverseMode={})", strategy.id(), newReverseMode);

        // holdings==0이면서 이전에 보유 이력이 있을 때만 사이클 종료 처리
        // (0회차 매수 실패 케이스: startSnapshot→tradeSnapshot 모두 holdings=0이므로 여기서 걸림)
        if (position.holdings() == 0 && prevHadHoldings) {
            // 사이클 종료 기록 — 종료금액=청산 후 통합주문가능금액, 종료일자=KST 매매일
            strategyCyclePort.markEnded(currentCycle.id(), balance.usdDeposit(), today);
            log.info("[strategyId={}] 사이클 종료 — 연속 정책 실행: {}", strategy.id(), strategy.cycleSeedType());
            userNotificationPort.notifyCycleCompleted(ctx.user(), ctx.account(), strategy);
            cycleRotationService.rotate(strategy, currentCycle, ctx.account(), ctx.user(), price, privacyBase);
        }
    }

    // 체결 후 포지션 기반 리버스모드 상태 머신
    // 직전 행 is_reverse_mode → 진입/유지/종료 판정
    private boolean computeNewReverseMode(StrategyCycle currentCycle, Strategy strategy,
                                          AccountBalance balance, BigDecimal closingPrice) {
        List<CyclePositionInfiniteDetail> recent = cyclePositionInfiniteDetailPort.findLatestByCycleId(currentCycle.id(), 1);
        boolean prevReverseMode = !recent.isEmpty() && recent.get(0).isReverseMode();
        int divisionCount = strategyInfiniteDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                .map(StrategyInfiniteDetail::divisionCount)
                .orElse(Strategy.DEFAULT_DIVISION_COUNT);

        // closingPrice를 prevClosePrice로 사용 (holdings>0이면 averagePrice로 자동 대체됨)
        InfinitePosition ip = new InfinitePosition(balance, strategy.ticker(), closingPrice, divisionCount);
        boolean nextReverseMode = ip.nextReverseMode(prevReverseMode);

        if (!prevReverseMode && nextReverseMode) {
            log.info("[strategyId={}] 소진 발동 → 리버스모드 진입 (unitAmount={}, usdDeposit={})",
                    strategy.id(), ip.unitAmount(), balance.usdDeposit());
        } else if (prevReverseMode && !nextReverseMode) {
            log.info("[strategyId={}] 리버스모드 종료 → 일반모드 복귀 (closingPrice={}, avgPrice={})",
                    strategy.id(), closingPrice, balance.avgPrice());
        }
        return nextReverseMode;
    }
}
