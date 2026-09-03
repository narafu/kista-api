package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.trading.domain.model.StrategyVrDetail;
import com.kista.trading.domain.model.VrSummary;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// VR 전략의 버전 상세·사이클 상세 저장과 요약 조립 — 레거시 StrategyService(향후 strategy-config)가 크로스모듈로 소비
public interface VrStrategyDetailUseCase {

    // 램프 8필드는 호출측(StrategyService)이 이미 null 정규화를 마친 값이라고 가정한다
    StrategyVrDetail saveVersionDetail(UUID strategyVersionId, Integer intervalWeeks,
                                        BigDecimal bandWidth, Integer recurringAmount,
                                        int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                        BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                        BigDecimal poolLimitFloor);

    // 등록 시점(경과 0주) 스냅샷 — gradientAt(0)/poolLimitRateAt(0)은 각각 initialGradient/initialPoolLimitRate와 동일
    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialValue, StrategyVrDetail vrDetail);

    // openingPosition: 호출측(StrategyService.toDetail)이 이미 조회한 개장 포지션 — 여기서 재조회하지 않는다
    // latestPosition: 라이브 pool(currentPool) 노출용 — 없으면(이력 없음) currentPool=null
    Optional<VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle,
                                     Optional<CyclePosition> openingPosition,
                                     Optional<CyclePosition> latestPosition);

    // 목록 조립(StrategyService.toDetails) 전용 배치 조회
    Map<UUID, StrategyVrDetail> findVrDetailsByVersionIds(Collection<UUID> strategyVersionIds);

    Map<UUID, StrategyCycleVrDetail> findCycleVrDetailsByCycleIds(Collection<UUID> cycleIds);

    // openingPool: 조회 대상 사이클 개장 포지션의 USD pool — poolLimit 달러 파생(openingPool × poolLimitRate)에 사용
    // currentPool: 최신 cycle_position 기준 현재 pool — null이면(이력 없음) 그대로 null 노출
    VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr,
                            BigDecimal openingPool, BigDecimal currentPool);
}
