package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.strategy.Strategy.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FidaOrderResponse(
        @Schema(description = "생성된 기준 매매표 마스터 레코드 ID")
        UUID id,                            // 생성된 master record ID
        @Schema(description = "거래일 (요청받은 FIDA 원본 값 그대로 echo, UTC — 저장 시에는 KST로 변환됨)")
        LocalDate tradeDate,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
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
    ) {
        static OrderItem from(Order order) {
            return new OrderItem(
                    order.direction().name(),
                    order.orderType().name(),
                    order.quantity(),
                    order.price()
            );
        }
    }

    public static FidaOrderResponse of(UUID id, FidaOrderCommand command) {
        return new FidaOrderResponse(
                id,
                command.tradeDate(),
                command.ticker(),
                command.currentCycleStart(),
                command.currentCycleRealizedPnl(),
                command.avgPrice(),
                command.holdings(),
                command.orders() == null ? List.of() : command.orders().stream().map(OrderItem::from).toList()
        );
    }
}
