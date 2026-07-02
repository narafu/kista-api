package com.kista.adapter.in.web.dto;

import com.kista.domain.model.privacy.PrivacyTradeBaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 PRIVACY 기준 매매표 응답 DTO — 마스터 + 주문 명세
public record AdminPrivacyBaseResponse(
        UUID id,
        LocalDate tradeDate,
        String ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        BigDecimal avgPrice,
        int holdings,
        List<OrderLine> orders
) {
    public static AdminPrivacyBaseResponse from(PrivacyTradeBaseView v) {
        List<OrderLine> orders = v.orders().stream().map(OrderLine::from).toList();
        return new AdminPrivacyBaseResponse(v.id(), v.tradeDate(), v.ticker(),
                v.currentCycleStart(), v.currentCycleRealizedPnl(), v.avgPrice(), v.holdings(), orders);
    }

    // 주문 명세 행
    public record OrderLine(UUID id, String direction, String orderType, BigDecimal price, Integer quantity) {
        public static OrderLine from(PrivacyTradeBaseView.OrderLine o) {
            return new OrderLine(o.id(), o.direction(), o.orderType(), o.price(), o.quantity());
        }
    }
}
