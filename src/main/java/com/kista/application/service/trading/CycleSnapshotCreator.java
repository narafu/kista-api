package com.kista.application.service.trading;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyCycleVrPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
                                           BigDecimal newValue, int gradient, BigDecimal poolLimit) {
        // 새 사이클 생성 — 시드(startAmount)는 롤오버 후 예수금
        StrategyCycle newCycle = strategyCyclePort.save(
                StrategyCycle.start(strategyId, strategyVersionId, postBalance.usdDeposit()));
        // holdings 승계 스냅샷: 이전 사이클 보유량·평단가·예수금·종가 그대로 기록
        cyclePositionPort.save(CyclePosition.tradeSnapshot(newCycle.id(), postBalance, closingPrice));
        // VR 사이클 상세 저장 — V′·gradient·poolLimit 스냅샷
        strategyCycleVrPort.save(new StrategyCycleVrDetail(newCycle.id(), newValue, gradient, poolLimit));
        return newCycle;
    }
}
