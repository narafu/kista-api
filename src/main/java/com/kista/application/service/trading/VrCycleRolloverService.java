package com.kista.application.service.trading;

import com.kista.application.event.NewCycleStartedEvent;
import com.kista.domain.model.strategy.*;
import com.kista.domain.port.out.*;
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

    private final StrategyCycleVrPort strategyCycleVrPort;       // VR 사이클 상세 조회·저장
    private final StrategyVrDetailPort strategyVrDetailPort;     // 전략 버전별 VR 설정 조회
    private final StrategyCyclePort strategyCyclePort;           // 사이클 종료 기록
    private final CycleSnapshotCreator cycleSnapshotCreator;     // 새 사이클 + 초기 포지션 원자 저장
    private final NotifyPort notifyPort;                         // 관리자 알림
    private final UserNotificationPort userNotificationPort;     // 사용자 알림 (오류)
    private final ApplicationEventPublisher eventPublisher;       // 새 사이클 시작 이벤트 발행

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
            notifyPort.notifyError(e);
            return;
        }
        if (cycleVr == null || detail == null) {
            log.warn("[strategyId={}] VR 롤오버 — cycleVr 또는 detail 미존재, skip", strategy.id());
            notifyPort.notifyError(new IllegalStateException(
                    "VR 사이클 상세 누락 strategyId=" + strategy.id() + " cycleId=" + cycle.id()));
            return;
        }

        // due 판정: startDate + intervalWeeks ≤ today (당일 포함)
        LocalDate dueDate = cycle.startDate().plusWeeks(detail.intervalWeeks());
        if (today.isBefore(dueDate)) {
            log.debug("[strategyId={}] VR 롤오버 미도래: dueDate={}, today={}", strategy.id(), dueDate, today);
            return;
        }

        // closingPrice 없으면 다음 매매일 재시도 — 알림 후 사이클 유지
        if (closingPrice == null) {
            log.warn("[strategyId={}] VR 롤오버 — 종가 없음, 다음 매매일 재시도", strategy.id());
            notifyPort.notifyError(new IllegalStateException(
                    "VR 롤오버 종가 없음 strategyId=" + strategy.id()));
            return;
        }

        // 램프 재계산 기준: 전략 최초 사이클 시작일로부터 경과한 주수 (스냅샷 이월 아닌 매 롤오버 시점 재계산)
        LocalDate firstStart = strategyCyclePort.findFirstByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("VR 최초 사이클 없음: strategyId=" + strategy.id()))
                .startDate();
        long weeks = ChronoUnit.WEEKS.between(firstStart, today);

        // V′ 계산: evaluation = holdings × 종가, gradient는 경과 주수 기준 램프 재계산값 사용
        BigDecimal evaluation = BigDecimal.valueOf(postBalance.holdings()).multiply(closingPrice);
        BigDecimal newValue = VrPosition.nextValue(
                cycleVr.value(),
                postBalance.usdDeposit(),
                detail.gradientAt(weeks),
                detail.recurringAmount(),
                evaluation
        );
        log.info("[strategyId={}] VR 롤오버 V′ 계산: value={} → newValue={}", strategy.id(), cycleVr.value(), newValue);

        // V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 사용자에게 설정 조정 유도 알림
        // (과거 recurringBootstrapWithoutValue 가드는 제거됨 — nextValue() 결과를 그대로 쓴다.
        //  V=0인 채로도 롤오버가 진행되면 pool/G+recurringAmount 항으로 다음 사이클 V가 자연 성장하고,
        //  실제 매수는 항상 pool/poolLimit 실측 잔고 한도 내에서만 이뤄지므로 과다지출 위험이 없다)
        if (newValue.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[strategyId={}] VR 롤오버 보류 — V′≤0 (newValue={})", strategy.id(), newValue);
            notifyPort.notifyError(new IllegalStateException(
                    "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue));
            userNotificationPort.notifyError(ctx.user(),
                    new IllegalStateException("VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id()));
            return;
        }

        // 사이클 종료 — 종료금액=마감 후 예수금+보유분 종가 평가액, 종료일자=KST 매매일
        BigDecimal endAmount = postBalance.usdDeposit().add(evaluation)
                .setScale(2, RoundingMode.HALF_UP);
        strategyCyclePort.markEnded(cycle.id(), endAmount, today);
        log.info("[strategyId={}] VR 사이클 종료 완료: cycleId={}", strategy.id(), cycle.id());

        // 새 poolLimitRate — 경과 주수 기준 램프 재계산값 (달러 파생은 조회 시점에 개장 pool×rate로 수행)
        BigDecimal newPoolLimitRate = detail.poolLimitRateAt(weeks);

        // 새 사이클 + holdings 승계 스냅샷 원자 생성
        cycleSnapshotCreator.createVrCycleAndSnapshot(
                strategy.id(),
                cycle.strategyVersionId(),
                postBalance,
                closingPrice,
                newValue,
                detail.gradientAt(weeks),  // 램프 재계산 G (스냅샷 이월 폐기)
                newPoolLimitRate
        );
        log.info("[strategyId={}] VR 사이클 롤오버 완료: newValue={}, newPoolLimitRate={}", strategy.id(), newValue, newPoolLimitRate);

        // 사용자에게 새 사이클 시작 알림 이벤트 발행
        eventPublisher.publishEvent(new NewCycleStartedEvent(ctx.user(), ctx.account(), strategy, postBalance.usdDeposit()));
    }
}
