package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyCycle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface StrategyCyclePort {
    StrategyCycle save(StrategyCycle cycle);

    // 전략의 현재 사이클 — deleted_at IS NULL 중 createdAt 최신 1건
    Optional<StrategyCycle> findLatestByStrategyId(UUID strategyId);

    // 사이클 종료 기록 — holdings=0 청산 시 종료금액·종료일자 저장
    void markEnded(UUID cycleId, BigDecimal endAmount, LocalDate endDate);

    // 전략 삭제 시 사이클 일괄 소프트 삭제
    void deleteByStrategyId(UUID strategyId);

    // 계좌·사용자 삭제 cascade 용 (전략 삭제와 함께 수행)
    void deleteByAccountId(UUID accountId);
    void deleteByUserId(UUID userId);
}
