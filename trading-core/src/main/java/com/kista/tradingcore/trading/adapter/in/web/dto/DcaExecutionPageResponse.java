package com.kista.tradingcore.trading.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaExecutionPage;
import com.kista.tradingcore.trading.domain.model.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// DCA 회차 이력 페이지 응답 (spec 7.3)
public record DcaExecutionPageResponse(List<Item> content, int page, int size, long totalElements) {

    @Schema(name = "DcaExecutionItem")
    public record Item(LocalDate kstTradeDate, LocalDate usSessionDate, String status, String skipReason, String detail,
                       BigDecimal referencePrice, BigDecimal quantity,
                       OrderSummary order) {}

    public record OrderSummary(UUID id, String status, BigDecimal filledQuantity, BigDecimal filledPrice) {}

    public static DcaExecutionPageResponse from(DcaExecutionPage p) {
        return new DcaExecutionPageResponse(p.content().stream().map(DcaExecutionPageResponse::item).toList(),
                p.page(), p.size(), p.totalElements());
    }

    private static Item item(DcaExecutionPage.Row r) {
        DcaExecution e = r.execution();
        Order o = r.order();
        return new Item(e.tradeDate(), r.usSessionDate(), e.status().name(),
                e.skipReason() == null ? null : e.skipReason().name(), e.detail(), e.referencePrice(), e.quantity(),
                o == null ? null : new OrderSummary(o.id(), o.status().name(), o.filledQuantity(), o.filledPrice()));
    }
}
