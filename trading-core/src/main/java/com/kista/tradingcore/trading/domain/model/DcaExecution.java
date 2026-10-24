package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.DcaExecutionStatus;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.Quantities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// DCA 규칙 회차 판정 결과 — (strategyId, ruleKey, tradeDate) 1행. 재개·중복 진입 시 이력·알림 중복을 막는 키
public record DcaExecution(
        UUID id,                    // PK (null이면 생성)
        UUID strategyId,            // FK → strategy.id
        String ruleKey,             // 판정한 규칙 키 (strategy_dca_rule.rule_key)
        OrderDirection direction,   // 기록 시점 규칙 방향 (규칙 삭제 후에도 보존)
        LocalDate tradeDate,        // KST 거래일
        DcaExecutionStatus status,  // ORDERED | SKIPPED
        DcaSkipReason skipReason,   // SKIPPED일 때만
        UUID orderId,               // ORDERED일 때 orders.id
        BigDecimal quantity,        // 계획 수량 (축소 반영 — 코인은 소수)
        BigDecimal referencePrice,  // 판정에 쓴 기준가 (데이터 실패 시 null)
        String detail,              // 사람용 사유 상세
        Instant createdAt           // 생성 시각
) {
    public DcaExecution {
        java.util.Objects.requireNonNull(direction, "direction"); // dca_execution.direction NOT NULL
        quantity = Quantities.normalize(quantity); // DB scale 8 → 정수 주식 수량 형태 유지
    }

    public static DcaExecution ordered(UUID strategyId, String ruleKey, OrderDirection direction, LocalDate tradeDate, UUID orderId,
                                       BigDecimal quantity, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, ruleKey, direction, tradeDate, DcaExecutionStatus.ORDERED, null, orderId, quantity, referencePrice, null, null);
    }

    public static DcaExecution skipped(UUID strategyId, String ruleKey, OrderDirection direction, LocalDate tradeDate,
                                       DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, ruleKey, direction, tradeDate, DcaExecutionStatus.SKIPPED, reason, null, null, referencePrice, detail, null);
    }
}
