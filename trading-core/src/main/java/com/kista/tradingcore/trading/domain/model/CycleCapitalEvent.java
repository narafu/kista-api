package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// 사이클 투자금 추가·인출 이력 1건 (증감은 부호 포함 delta)
public record CycleCapitalEvent(
        UUID id,
        UUID strategyCycleId,
        UUID cyclePositionId, // 변경 결과로 생성된 cycle_position
        Kind kind,
        BigDecimal depositDelta, // 예수금 증감 (USD)
        BigDecimal holdingsDelta, // 보유수량 증감
        BigDecimal principalDelta, // 원금 증감 (USD)
        Instant occurredAt
) {
    // RESET: 시드 수정·VR 시작 전 재설정의 원장 차액
    public enum Kind { INJECT, WITHDRAW, RESET }
}
