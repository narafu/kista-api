package com.kista.trading.application.service;

import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.strategy.*; import com.kista.trading.domain.model.*;
import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.trading.application.event.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// VR 전략: N주 경과 시 V′ 계산 후 사이클 롤오버
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class VrCycleRolloverService {

    // due일 직전 거래일을 찾기 위한 최대 역탐색 일수 (연휴 대비 여유)
    private static final int MAX_LOOKBACK_DAYS = 10;

    private final StrategyCycleVrPort strategyCycleVrPort;       // VR 사이클 상세 조회·저장
    private final StrategyVrDetailPort strategyVrDetailPort;     // 전략 버전별 VR 설정 조회
    private final StrategyCyclePort strategyCyclePort;           // 사이클 종료 기록
    private final CycleSnapshotCreator cycleSnapshotCreator;     // 새 사이클 + 초기 포지션 원자 저장
    private final ApplicationEventPublisher eventPublisher;       // 새 사이클 시작 이벤트 발행 + 관리자·사용자 오류 알림 이벤트
    private final MarketCalendarPort marketCalendarPort;          // due일 직전 거래일 탐색
    private final BrokerAdapterRegistry registry;                 // due일 기준 확정 종가 조회

    // 마감 리포트(saveCyclePosition) 직후 호출 — due 도래 시 V′ 계산 후 사이클 교체
    void rollIfDue(BatchContext ctx, AccountBalance postBalance, BigDecimal closingPrice, LocalDate today) {
        StrategyCycle cycle = ctx.currentCycle();
        Strategy strategy = ctx.strategy();

        // VR 사이클 상세 + 전략 버전 VR 설정 조회 — 미존재 시 배치 격리
        StrategyCycleVrDetail cycleVr;
        StrategyVrDetail detail;
        try {
            cycleVr = strategyCycleVrPort.findByCycleId(cycle.id()).orElse(null);
            detail = strategyVrDetailPort.findByStrategyVersionId(cycle.strategyVersionId()).orElse(null);
        } catch (Exception e) {
            log.error("[strategyId={}] VR 롤오버 — 상세 조회 실패", strategy.id(), e);
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
            return;
        }
        if (cycleVr == null || detail == null) {
            log.warn("[strategyId={}] VR 롤오버 — cycleVr 또는 detail 미존재, skip", strategy.id());
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR 사이클 상세 누락 strategyId=" + strategy.id() + " cycleId=" + cycle.id())));
            return;
        }

        // due 판정: startDate + intervalWeeks ≤ today (당일 포함)
        LocalDate dueDate = cycle.startDate().plusWeeks(detail.intervalWeeks());
        if (today.isBefore(dueDate)) {
            log.debug("[strategyId={}] VR 롤오버 미도래: dueDate={}, today={}", strategy.id(), dueDate, today);
            return;
        }

        // closingPrice 없으면 다음 매매일 재시도 — 알림 후 사이클 유지 (당일 시세 파이프라인 자체가 불통이라는 신호)
        if (closingPrice == null) {
            log.warn("[strategyId={}] VR 롤오버 — 종가 없음, 다음 매매일 재시도", strategy.id());
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR 롤오버 종가 없음 strategyId=" + strategy.id())));
            return;
        }

        // 평가 기준일 = due일(휴장이면 그 직전 거래일) — 배치가 due일 이후 며칠 밀려서 실행돼도
        // "2주 마감 시점" 자체의 확정 종가로 평가해야 스케쥴이 실행일에 흔들리지 않는다
        LocalDate evaluationDate = lastTradingDayOnOrBefore(dueDate);
        BigDecimal evaluationClosingPrice;
        try {
            evaluationClosingPrice = registry.require(ctx.account(), BrokerPricePort.class)
                    .getClosingPrice(strategy.ticker(), evaluationDate, ctx.account());
        } catch (Exception e) {
            log.warn("[strategyId={}] VR 롤오버 — due일({}) 확정 종가 조회 실패, 다음 매매일 재시도",
                    strategy.id(), evaluationDate, e);
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
            return;
        }
        if (evaluationClosingPrice == null) {
            log.warn("[strategyId={}] VR 롤오버 — due일({}) 확정 종가 없음, 다음 매매일 재시도", strategy.id(), evaluationDate);
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR 롤오버 due일 확정 종가 없음 strategyId=" + strategy.id() + " evaluationDate=" + evaluationDate)));
            return;
        }

        // 램프 재계산 기준: 전략 최초 사이클 시작일로부터 경과한 주수 (스냅샷 이월 아닌 매 롤오버 시점 재계산)
        LocalDate firstStart = strategyCyclePort.findFirstByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("VR 최초 사이클 없음: strategyId=" + strategy.id()))
                .startDate();
        long weeks = ChronoUnit.WEEKS.between(firstStart, today);

        // 적립/인출 반영 pool — recurringAmount(양수=적립·음수=인출)를 실측 예수금에 실제로 더해 다음 사이클의 실 현금으로 승계
        int recurringAmount = detail.recurringAmount();
        BigDecimal adjustedPool = postBalance.usdDeposit()
                .add(BigDecimal.valueOf(recurringAmount)).setScale(2, RoundingMode.HALF_UP);
        if (adjustedPool.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("[strategyId={}] VR 롤오버 보류 — 인출 반영 후 예수금 음수 (pool={}, recurringAmount={})",
                    strategy.id(), postBalance.usdDeposit(), recurringAmount);
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR 인출 반영 후 예수금 음수 — 롤오버 보류: strategyId=" + strategy.id() + " adjustedPool=" + adjustedPool)));
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user(),
                    new IllegalStateException("VR 인출 금액이 예수금을 초과합니다 — 설정 조정 필요: strategyId=" + strategy.id())));
            return;
        }

        // V′ 계산: evaluation = holdings × due일 기준 확정 종가, gradient는 경과 주수 기준 램프 재계산값 사용
        // pool은 적립/인출 반영 전 원래 예수금 사용 — 공식은 V′=V+pool/G+(E-V)/(2√G)±적립금 (recurringAmount는 별도 항)
        BigDecimal evaluation = BigDecimal.valueOf(postBalance.holdings()).multiply(evaluationClosingPrice);
        BigDecimal newValue = VrPosition.nextValue(
                cycleVr.value(),
                postBalance.usdDeposit(),
                detail.gradientAt(weeks),
                recurringAmount,
                evaluation
        );
        log.info("[strategyId={}] VR 롤오버 V′ 계산: value={} → newValue={}", strategy.id(), cycleVr.value(), newValue);

        // V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 사용자에게 설정 조정 유도 알림
        // (과거 recurringBootstrapWithoutValue 가드는 제거됨 — nextValue() 결과를 그대로 쓴다.
        //  V=0인 채로도 롤오버가 진행되면 pool/G+recurringAmount 항으로 다음 사이클 V가 자연 성장하고,
        //  실제 매수는 항상 pool/poolLimit 실측 잔고 한도 내에서만 이뤄지므로 과다지출 위험이 없다)
        if (newValue.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[strategyId={}] VR 롤오버 보류 — V′≤0 (newValue={})", strategy.id(), newValue);
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue)));
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user(),
                    new IllegalStateException("VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id())));
            return;
        }

        // 사이클 종료 — 종료금액=마감 후 예수금+보유분 종가 평가액, 종료일자=evaluationDate(due일 기준 실제 거래일 — 배치 실행일 아님)
        BigDecimal endAmount = postBalance.usdDeposit().add(evaluation)
                .setScale(2, RoundingMode.HALF_UP);
        strategyCyclePort.markEnded(cycle.id(), endAmount, evaluationDate);
        log.info("[strategyId={}] VR 사이클 종료 완료: cycleId={}", strategy.id(), cycle.id());

        // 새 poolLimitRate — 경과 주수 기준 램프 재계산값 (달러 파생은 조회 시점에 개장 pool×rate로 수행)
        BigDecimal newPoolLimitRate = detail.poolLimitRateAt(weeks);

        // 새 사이클 + holdings 승계 스냅샷 원자 생성 — pool은 적립/인출 반영된 실 현금(adjustedPool)으로 개장
        // 시작일은 evaluationDate(항상 실제 거래일)로 고정 — dueDate(휴장일일 수 있음) 그대로 쓰면 시작일이 비거래일이 되고,
        // 배치 실행일(today)을 쓰면 실행이 며칠 밀릴 때마다 다음 due일도 함께 밀려 N주 스케줄이 누적 drift된다
        AccountBalance newCycleBalance = new AccountBalance(postBalance.holdings(), postBalance.avgPrice(), adjustedPool);
        cycleSnapshotCreator.createVrCycleAndSnapshot(
                strategy.id(),
                cycle.strategyVersionId(),
                newCycleBalance,
                evaluationClosingPrice,
                newValue,
                detail.gradientAt(weeks),  // 램프 재계산 G (스냅샷 이월 폐기)
                newPoolLimitRate,
                evaluationDate
        );
        log.info("[strategyId={}] VR 사이클 롤오버 완료: newValue={}, newPoolLimitRate={}", strategy.id(), newValue, newPoolLimitRate);

        // 사용자에게 새 사이클 시작 알림 이벤트 발행 (적립/인출 반영된 개장 예수금)
        eventPublisher.publishEvent(new NewCycleStartedEvent(ctx.user(), ctx.account(), strategy, adjustedPool));
    }

    // date가 거래일이면 그대로, 아니면 직전 거래일까지 역탐색 (휴장 due일 보정용)
    private LocalDate lastTradingDayOnOrBefore(LocalDate date) {
        LocalDate candidate = date;
        for (int i = 0; i < MAX_LOOKBACK_DAYS && !marketCalendarPort.isMarketOpen(candidate); i++) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
