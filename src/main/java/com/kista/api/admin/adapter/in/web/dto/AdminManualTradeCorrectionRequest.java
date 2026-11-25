package com.kista.api.admin.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.kernel.OrderDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 요청 DTO
public record AdminManualTradeCorrectionRequest(
        @Schema(description = "대상 사용자 ID")
        @NotNull(message = "대상 사용자는 필수입니다.") UUID userId,
        @Schema(description = "대상 계좌 ID")
        @NotNull(message = "대상 계좌는 필수입니다.") UUID accountId,
        @Schema(description = "대상 전략 ID")
        @NotNull(message = "대상 전략은 필수입니다.") UUID strategyId,
        @Schema(description = "반영할 체결 명세 목록 (배열 순서대로 반영)")
        @NotEmpty(message = "체결 내역을 하나 이상 입력해야 합니다.") List<@Valid FillRequest> fills
) {
    public TradeCorrectionRequest toCommand() {
        return new TradeCorrectionRequest(
                userId, accountId, strategyId,
                fills.stream().map(FillRequest::toCommand).toList()
        );
    }

    // 개별 체결 명세
    public record FillRequest(
            @Schema(description = "체결 거래일 (KST)")
            @NotNull(message = "체결 거래일은 필수입니다.") LocalDate tradeDate,
            @Schema(description = "매매 방향", example = "BUY")
            @NotNull(message = "매매 방향은 필수입니다.") String direction,
            @Schema(description = "체결 수량 (정수)")
            @NotNull(message = "체결 수량은 필수입니다.") @Positive(message = "체결 수량은 0보다 커야 합니다.") BigDecimal quantity,
            @Schema(description = "체결 가격")
            @NotNull(message = "체결 가격은 필수입니다.") @Positive(message = "체결 가격은 0보다 커야 합니다.") BigDecimal price,
            @Schema(description = "브로커 측 원본 주문 ID (선택)")
            @Nullable String externalOrderId,
            @Schema(description = "메모 (선택)")
            @Nullable String memo
    ) {
        public TradeCorrectionRequest.Fill toCommand() {
            return new TradeCorrectionRequest.Fill(
                    tradeDate,
                    OrderDirection.valueOf(direction),
                    quantity,
                    price,
                    externalOrderId,
                    memo
            );
        }
    }
}
