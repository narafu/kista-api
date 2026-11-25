package com.kista.api.admin.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
        @Nullable BigDecimal avgPrice,
        @Schema(description = "보유 수량")
        int holdings,
        @Schema(description = "계획 주문 명세 목록")
        List<OrderLine> orders,
        @Schema(description = "추정 현금 (USD) = 사이클 시작금액 + 사이클 실현손익 − 평단가×보유수량. 보유 중인데 평단가가 없으면 null")
        @Nullable BigDecimal cash,
        @Schema(description = "직전 발표 대비 사이클 시작금액이 달라진 날(새 사이클 경계). 목록 조회에서만 값이 있고 단건 응답은 null")
        @Nullable Boolean cycleBoundary,
        @Schema(description = "종목 이력 기준 연초 재설정일(그 해 첫 사이클 경계이면서 시작금액 1만 달러). 목록 조회에서만 값이 있고 단건 응답은 null")
        @Nullable Boolean yearlyReset
) {
    // 단건 응답(생성·수정) — 직전 행을 모르므로 cycleBoundary·yearlyReset은 null
    public static AdminPrivacyBaseResponse from(PrivacyTradeBaseResponse v) {
        return of(v, null, null);
    }

    // 목록 응답 — 종목별 이력(발표일 순)으로 경계·연초 재설정을 판정하고 입력 순서를 유지한다
    public static List<AdminPrivacyBaseResponse> fromList(List<PrivacyTradeBaseResponse> views) {
        Map<UUID, PrivacySeriesFlags> flags = PrivacySeriesFlags.of(views);
        return views.stream()
                .map(v -> of(v, flags.get(v.id()).cycleBoundary(), flags.get(v.id()).yearlyReset()))
                .toList();
    }

    private static AdminPrivacyBaseResponse of(PrivacyTradeBaseResponse v, @Nullable Boolean cycleBoundary, @Nullable Boolean yearlyReset) {
        List<OrderLine> orders = v.orders().stream().map(OrderLine::from).toList();
        return new AdminPrivacyBaseResponse(v.id(), v.releaseDate(), v.ticker(),
                v.currentCycleStart(), v.currentCycleRealizedPnl(), v.avgPrice(), v.holdings(), orders,
                PrivacySeriesFlags.cash(v), cycleBoundary, yearlyReset);
    }

    // 주문 명세 행 — shared 계약 PrivacyTradeBaseResponse.OrderLine과 스키마명이 충돌해 별도 이름을 쓴다
    @Schema(name = "AdminPrivacyBaseOrderLine")
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
            @Nullable Integer quantity) {
        public static OrderLine from(PrivacyTradeBaseResponse.OrderLine o) {
            return new OrderLine(o.id(), o.direction(), o.orderType(), o.price(), o.quantity());
        }
    }
}
