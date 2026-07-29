package com.kista.application.service.trading;

import com.kista.application.service.strategy.VrStrategyLifecycle;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVersionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 사이클 재등록 시 StrategyCycle + 초기 CyclePosition 스냅샷을 원자적으로 저장
// CycleRotationService에서 @Transactional self-invocation 우회를 위해 분리
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
class CycleSnapshotCreator {

    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCycleVrPort strategyCycleVrPort; // VR 사이클 상세 저장
    private final StrategyVersionPort strategyVersionPort; // VR 재설정 시 버전 교체
    private final VrStrategyLifecycle vrStrategyLifecycle;  // VR 재설정 시 새 버전 상세 저장

    // 새 StrategyCycle + 시작 스냅샷을 하나의 트랜잭션으로 저장 — 중간 실패 시 고아 사이클 방지
    @Transactional
    StrategyCycle createCycleAndSnapshot(UUID strategyId, UUID versionId, BigDecimal seed, BigDecimal price) {
        StrategyCycle newCycle = strategyCyclePort.save(StrategyCycle.start(strategyId, versionId, seed));
        cyclePositionPort.save(CyclePosition.cycleStartSnapshot(newCycle.id(), seed, price));
        return newCycle;
    }

    // VR 롤오버 전용: StrategyCycle + holdings 승계 스냅샷 + VR 사이클 상세를 원자적으로 저장
    // holdings 승계: 이전 사이클의 보유량·평단가·예수금을 새 사이클 첫 스냅샷으로 이어받음
    @Transactional
    StrategyCycle createVrCycleAndSnapshot(UUID strategyId, UUID strategyVersionId,
                                           AccountBalance postBalance, BigDecimal closingPrice,
                                           BigDecimal newValue, int gradient, BigDecimal poolLimitRate) {
        // 새 사이클 생성 — 시드(startAmount)는 롤오버 후 예수금과 보유 주식 평가액의 합계
        BigDecimal startAmount = postBalance.usdDeposit()
                .add(closingPrice.multiply(BigDecimal.valueOf(postBalance.holdings())))
                .setScale(2, RoundingMode.HALF_UP);
        StrategyCycle newCycle = strategyCyclePort.save(
                StrategyCycle.start(strategyId, strategyVersionId, startAmount));
        // holdings 승계 스냅샷: 이전 사이클 보유량·평단가·예수금·종가 그대로 기록
        cyclePositionPort.save(CyclePosition.tradeSnapshot(newCycle.id(), postBalance, closingPrice));
        // VR 사이클 상세 저장 — V′·gradient·poolLimitRate 스냅샷
        strategyCycleVrPort.save(new StrategyCycleVrDetail(newCycle.id(), newValue, gradient, poolLimitRate));
        return newCycle;
    }

    // VR 운영 중 재설정 전용: 활성 버전 소프트삭제 + 신규 버전/VR상세 저장 + 기존 사이클 종료 + 새 사이클/스냅샷 생성을 하나의 트랜잭션으로 처리
    // 중간 실패 시 "활성 버전 없음" 또는 "사이클 종료됐지만 신규 사이클 없음" 등 고아 상태 방지 — VrReconfigureService에서 호출
    @Transactional
    StrategyCycle reconfigureVrCycle(UUID strategyId, UUID currentCycleId, LocalDate today,
                                      Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount,
                                      int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                      BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks, BigDecimal poolLimitFloor,
                                      AccountBalance postBalance, BigDecimal closingPrice, BigDecimal newValue, long weeks) {
        // nextVersionNo는 반드시 소프트 삭제보다 먼저 계산한다 — StrategyVersionEntity에 걸린
        // @SQLRestriction("deleted_at IS NULL")이 커스텀 @Query(findMaxVersionNoByStrategyId)에도
        // 자동 적용되므로, 활성 버전을 먼저 소프트 삭제하면 MAX(versionNo)가 그 버전을 제외해버려
        // 방금 삭제한 버전과 동일한 번호를 다시 계산 → uq_strategy_version_strategy_version_no 위반
        int nextVersionNo = strategyVersionPort.nextVersionNo(strategyId);
        // 기존 활성 버전 소프트 삭제 후 새 버전 발급 (버전 이력은 계속 누적)
        strategyVersionPort.softDeleteActiveByStrategyId(strategyId, Instant.now());
        StrategyVersion newVersion = strategyVersionPort.save(
                new StrategyVersion(null, strategyId, nextVersionNo, null, null));
        // 새 램프 파라미터로 VR 버전 상세 저장
        StrategyVrDetail newDetail = vrStrategyLifecycle.saveVersionDetail(newVersion.id(), intervalWeeks, bandWidth, recurringAmount,
                initialGradient, gGraceWeeks, gStepWeeks, gMax, initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor);
        // 현재 사이클 강제 종료 — 종료금액=주입 반영 후 예수금, 종료일자=오늘(KST)
        strategyCyclePort.markEnded(currentCycleId, postBalance.usdDeposit(), today);
        // 새 사이클 + holdings 승계 스냅샷 원자 생성 — 램프 시계(weeks) 기준 gradient/poolLimitRate 재계산 스냅샷
        return createVrCycleAndSnapshot(strategyId, newVersion.id(), postBalance, closingPrice, newValue,
                newDetail.gradientAt(weeks), newDetail.poolLimitRateAt(weeks));
    }
}
