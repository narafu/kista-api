package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.Quantities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// DCA 회차 판정 결과 — (strategyId, tradeDate) 1행. 재개·중복 진입 시 이력·알림 중복을 막는 키
public record DcaExecution(
        UUID id,                    // PK (null이면 생성)
        UUID strategyId,            // FK → strategy.id
        LocalDate tradeDate,        // KST 거래일
        Status status,              // ORDERED | SKIPPED
        DcaSkipReason skipReason,   // SKIPPED일 때만
        UUID orderId,               // ORDERED일 때 orders.id
        BigDecimal quantity,        // 계획 수량 (축소 반영 — 코인은 소수)
        BigDecimal referencePrice,  // 판정에 쓴 전일 종가 (데이터 실패 시 null)
        String detail,              // 사람용 사유 상세
        Instant createdAt           // 생성 시각
) {
    public enum Status { ORDERED, SKIPPED }

    public DcaExecution {
        quantity = Quantities.normalize(quantity); // DB scale 8 → 정수 주식 수량 형태 유지
    }

    public static DcaExecution ordered(UUID strategyId, LocalDate tradeDate, UUID orderId, BigDecimal quantity, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, tradeDate, Status.ORDERED, null, orderId, quantity, referencePrice, null, null);
    }

    public static DcaExecution skipped(UUID strategyId, LocalDate tradeDate, DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, tradeDate, Status.SKIPPED, reason, null, null, referencePrice, detail, null);
    }
}
