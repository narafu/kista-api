package com.kista.adapter.in.web.dto;

import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 PRIVACY 기준 매매표 응답 DTO — 마스터 + 주문 명세
public record AdminPrivacyBaseResponse(
        @Schema(description = "기준 매매표 마스터 ID")
        UUID id,
        @Schema(description = "발행일 원본 (DB release_date 그대로, KST 변환 없음)")
        LocalDate releaseDate,
        @Schema(description = "거래 종목", example = "SOXL")
        String ticker,
        @Schema(description = "기준가")
        BigDecimal currentCycleStart,
        @Schema(description = "사이클 실현 손익 (USD)")
        BigDecimal currentCycleRealizedPnl,
        @Schema(description = "평단가 (nullable)")
        BigDecimal avgPrice,
        @Schema(description = "보유 수량")
        int holdings,
        @Schema(description = "계획 주문 명세 목록")
        List<OrderLine> orders
) {
    public static AdminPrivacyBaseResponse from(PrivacyTradeBaseView v) {
        List<OrderLine> orders = v.orders().stream().map(OrderLine::from).toList();
        return new AdminPrivacyBaseResponse(v.id(), v.releaseDate(), v.ticker(),
                v.currentCycleStart(), v.currentCycleRealizedPnl(), v.avgPrice(), v.holdings(), orders);
    }

    // 주문 명세 행
    public record OrderLine(
            @Schema(description = "주문 명세 ID")
            UUID id,
            @Schema(description = "매매 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 가격")
            BigDecimal price,
            @Schema(description = "주문 수량 (nullable)")
            Integer quantity) {
        public static OrderLine from(PrivacyTradeBaseView.OrderLine o) {
            return new OrderLine(o.id(), o.direction(), o.orderType(), o.price(), o.quantity());
        }
    }
}
