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

    // PRIVACY 기준 매매표(master) + 주문 명세(detail) 목록 — range: ALL | 30 | 90
    @GetMapping
    public List<AdminPrivacyBaseResponse> listBases(@RequestParam(defaultValue = "ALL") String range) {
        Integer days = parseRange(range);
        return adminQuery.listPrivacyBases(days).stream()
                .map(AdminPrivacyBaseResponse::from)
                .toList();
    }

    // 조회 범위 파싱 — ALL→전체(null), 그 외 허용값 외 입력은 IllegalArgumentException → 400
    private Integer parseRange(String range) {
        return switch (range) {
            case "ALL" -> null;
            case "30" -> 30;
            case "90" -> 90;
            default -> throw new IllegalArgumentException("range는 ALL, 30, 90만 허용됩니다: " + range);
        };
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
