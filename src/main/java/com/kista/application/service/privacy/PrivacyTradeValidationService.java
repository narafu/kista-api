package com.kista.application.service.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// PRIVACY 기준 매매표 방어 규칙 — FIDA 저장 시 경고/차단, 장전 전 자동 가드에 공통 사용
@Component
public class PrivacyTradeValidationService {

    public PrivacyTradeValidationReport inspect(FidaOrderCommand command) {
        return inspect(command.holdings(), command.orders().stream()
                .map(o -> new OrderLine(o.direction(), o.quantity()))
                .toList());
    }

    public PrivacyTradeValidationReport inspect(PrivacyTradeBase base) {
        return inspect(base.holdings(), base.trades().stream()
                .map(t -> new OrderLine(t.direction(), t.quantity()))
                .toList());
    }

    private PrivacyTradeValidationReport inspect(int holdings, List<OrderLine> orders) {
        List<PrivacyTradeValidationReport.Issue> issues = new ArrayList<>();
        List<OrderLine> sellOrders = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.SELL)
                .toList();
        long nullSellCount = sellOrders.stream().filter(o -> o.quantity() == null).count();
        int explicitSellQuantity = sellOrders.stream()
                .filter(o -> o.quantity() != null)
                .mapToInt(OrderLine::quantity)
                .sum();

        // SELL null quantity는 "남은 전부 매도" 의미이므로 최대 1건만 허용
        if (nullSellCount > 1) {
            issues.add(new PrivacyTradeValidationReport.Issue(
                    PrivacyTradeValidationReport.Severity.BLOCKING,
                    "MULTIPLE_NULL_SELLS",
                    "quantity=null SELL 주문은 1건만 허용됩니다"));
        }

        // 보유 수량이 있는데 매도 계획이 전혀 없으면 재발 위험이 높으므로 저장 시 경고, 장전 시 차단 대상
        if (holdings > 0 && sellOrders.isEmpty()) {
            issues.add(new PrivacyTradeValidationReport.Issue(
                    PrivacyTradeValidationReport.Severity.WARNING,
                    "MISSING_SELL",
                    "holdings > 0 인데 SELL 주문이 없습니다"));
        }

        // 보유가 없는데 SELL이 있으면 구조적으로 잘못된 기준표
        if (holdings == 0 && !sellOrders.isEmpty()) {
            issues.add(new PrivacyTradeValidationReport.Issue(
                    PrivacyTradeValidationReport.Severity.BLOCKING,
                    "SELL_WITHOUT_HOLDINGS",
                    "holdings = 0 인데 SELL 주문이 존재합니다"));
        }

        // 명시 SELL 합이 현재 보유를 초과하면 실제 주문 시 초과 매도로 이어질 수 있으므로 차단
        if (explicitSellQuantity > holdings) {
            issues.add(new PrivacyTradeValidationReport.Issue(
                    PrivacyTradeValidationReport.Severity.BLOCKING,
                    "EXPLICIT_SELL_EXCEEDS_HOLDINGS",
                    "명시 SELL 수량 합이 현재 holdings를 초과합니다"));
        }

        return new PrivacyTradeValidationReport(issues);
    }

    // 검증에 필요한 최소 주문 필드만 추출한 내부 표현
    private record OrderLine(
            Order.OrderDirection direction,
            Integer quantity
    ) {
    }
}
