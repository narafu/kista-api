package com.kista.application.service.trading;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyCyclePort;
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

    // 새 StrategyCycle + 시작 스냅샷을 하나의 트랜잭션으로 저장 — 중간 실패 시 고아 사이클 방지
    @Transactional
    StrategyCycle createCycleAndSnapshot(UUID strategyId, UUID versionId, BigDecimal seed, BigDecimal price) {
        StrategyCycle newCycle = strategyCyclePort.save(StrategyCycle.start(strategyId, versionId, seed));
        cyclePositionPort.save(CyclePosition.cycleStartSnapshot(newCycle.id(), seed, price));
        return newCycle;
    }
}
