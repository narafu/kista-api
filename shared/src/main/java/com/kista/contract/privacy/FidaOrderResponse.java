package com.kista.contract.privacy;

import com.kista.sharedkernel.StrategyTicker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// FIDA 주문 수신 응답 — 저장된 마스터 ID + 요청 echo. 외부 FIDA 프로젝트도 소비하므로 필드명·shape 유지
public record FidaOrderResponse(
        @Schema(description = "생성된 기준 매매표 마스터 레코드 ID")
        UUID id,
        @Schema(description = "발행일 (요청받은 FIDA 원본 값 그대로 echo, KST — 거래일 아님)")
        LocalDate releaseDate,
        @Schema(description = "거래 종목", example = "SOXL")
        StrategyTicker ticker,
        @Schema(description = "기준가")
        BigDecimal currentCycleStart,
        @Schema(description = "사이클 실현 손익 (USD)")
        BigDecimal currentCycleRealizedPnl,
        @Schema(description = "평단가 (nullable)")
        BigDecimal avgPrice,
        @Schema(description = "보유 수량")
        int holdings,
        @Schema(description = "저장된 계획 주문 목록")
        List<OrderItem> orders
) {
    public record OrderItem(
            @Schema(description = "매매 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 수량 (nullable, SELL 방향은 null 허용 — 남은 전부 매도)")
            Integer quantity,
            @Schema(description = "주문 가격")
            BigDecimal price
    ) {}
}
