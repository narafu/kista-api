package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyStatus;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
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
    public boolean pause(UUID strategyId) {
        // 상태만 조건부 갱신 — 스냅샷 덮어쓰기·보관 되돌림 방지(삭제 경로 등은 false여도 무시). 보관돼 갱신 안 됐으면 false
        return strategyPort.updateStatusUnlessArchived(strategyId, StrategyStatus.PAUSED);
    }

    @Transactional
    public void archive(UUID strategyId) {
        // PAUSED일 때만 조건부로 확정 — 마지막 점검 뒤 재개된 전략을 ARCHIVED로 덮어쓰지 않는다
        if (!strategyPort.archiveIfPaused(strategyId)) {
            throw new IllegalStateException("전략 상태가 바뀌어 보관하지 못했습니다. 다시 시도하시기 바랍니다.");
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
