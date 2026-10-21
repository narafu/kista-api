package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaSizingType;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// DCA 규칙 규모 요청 — 검증은 DcaSizing(IAE→400)
public record DcaSizingRequest(
        @NotNull DcaSizingType type, // AMOUNT | HOLDING_PERCENT(SELL 전용)
        @Schema(description = "AMOUNT 전용 회차 금액 (계좌 통화 — 주식 USD, 코인 KRW 원 단위 정수 5,000 이상)")
        BigDecimal amount,           // AMOUNT 전용
        @Schema(description = "HOLDING_PERCENT 전용 원장 보유 비율 (0 초과 100 이하)")
        BigDecimal percent           // HOLDING_PERCENT 전용
) {
    DcaSizing toDomain() {
        return new DcaSizing(type, amount, percent);
    }
}
