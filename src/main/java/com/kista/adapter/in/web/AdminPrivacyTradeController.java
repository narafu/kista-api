package com.kista.adapter.in.web;

import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.port.in.AdminQueryUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/privacy-trade-bases")
@RequiredArgsConstructor
public class AdminPrivacyTradeController {

    private final AdminQueryUseCase adminQuery;

    // PRIVACY 기준 매매표(master) + 주문 명세(detail) 목록 — days 미전달 시 전체
    @GetMapping
    public List<AdminPrivacyBaseResponse> listBases(@RequestParam(required = false) Integer days) {
        return adminQuery.listPrivacyBases(days).stream()
                .map(AdminPrivacyBaseResponse::from)
                .toList();
    }

    // 응답 DTO — 마스터 + 주문 명세
    record AdminPrivacyBaseResponse(
            UUID id,
            LocalDate tradeDate,
            String ticker,
            BigDecimal currentCycleStart,
            BigDecimal currentCycleRealizedPnl,
            BigDecimal avgPrice,
            int holdings,
            List<OrderLine> orders
    ) {
        static AdminPrivacyBaseResponse from(PrivacyTradeBaseView v) {
            List<OrderLine> orders = v.orders().stream().map(OrderLine::from).toList();
            return new AdminPrivacyBaseResponse(v.id(), v.tradeDate(), v.ticker(),
                    v.currentCycleStart(), v.currentCycleRealizedPnl(), v.avgPrice(), v.holdings(), orders);
        }

        record OrderLine(UUID id, String direction, String orderType, BigDecimal price, Integer quantity) {
            static OrderLine from(PrivacyTradeBaseView.OrderLine o) {
                return new OrderLine(o.id(), o.direction(), o.orderType(), o.price(), o.quantity());
            }
        }
    }
}
