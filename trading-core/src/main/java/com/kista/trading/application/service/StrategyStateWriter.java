package com.kista.trading.application.service;

import com.kista.sharedkernel.StrategyStatus;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// StrategyService의 증권사 주문 취소 HTTP를 트랜잭션 밖으로 빼기 위한 DB 상태변경 전용 헬퍼 (OrderCancelStateWriter와 같은 패턴)
// 메서드는 반드시 public — Spring 기본 proxy 모드는 non-public @Transactional을 무시(no-op)함
@Service
@RequiredArgsConstructor
class StrategyStateWriter {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;

    @Transactional
    public void pause(UUID strategyId) {
        // 트랜잭션 안에서 다시 읽어 상태만 바꾼다 — 호출자가 앞서 읽은 스냅샷으로 덮어쓰지 않도록
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.isPaused()) {
            strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
        }
    }

    @Transactional
    public void delete(UUID strategyId) {
        // CyclePosition → StrategyCycle → Strategy 순 소프트 삭제 — 한 트랜잭션으로 원자 처리
        cyclePositionPort.deleteByStrategyId(strategyId);
        strategyCyclePort.deleteByStrategyId(strategyId);
        strategyPort.delete(strategyId);
    }
}
