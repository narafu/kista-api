package com.kista.privacy.adapter.in.web;

import com.kista.contract.privacy.FidaOrderRequest;
import com.kista.contract.privacy.FidaOrderResponse;
import com.kista.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.contract.privacy.PrivacyTradeBaseResponse;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.FidaPlannedOrder;
import com.kista.privacy.domain.model.PrivacyBaseUpdateCommand;
import com.kista.privacy.domain.model.PrivacyOrderAddCommand;
import com.kista.privacy.domain.model.PrivacyOrderUpdateCommand;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;

import java.util.List;
import java.util.UUID;

// 내부 API(/api/internal/privacy/**, /api/internal/fida-orders) 도메인 ↔ contract 매핑 —
// contract는 도메인 타입을 import할 수 없어 매핑은 privacy inbound adapter가 담당한다.
// 도메인 command의 생성자 검증(IllegalArgumentException)은 여기서 그대로 발동해 400으로 매핑된다.
final class PrivacyContractMapper {

    private PrivacyContractMapper() {}

    static PrivacyTradeBaseResponse toResponse(PrivacyTradeBaseView v) {
        List<PrivacyTradeBaseResponse.OrderLine> orders = v.orders().stream()
                .map(o -> new PrivacyTradeBaseResponse.OrderLine(o.id(), o.direction(), o.orderType(), o.price(), o.quantity()))
                .toList();
        return new PrivacyTradeBaseResponse(v.id(), v.releaseDate(), v.ticker(), v.currentCycleStart(),
                v.currentCycleRealizedPnl(), v.avgPrice(), v.holdings(), orders);
    }

    // FIDA 수신 요청 echo 응답 — 저장된 마스터 ID + 요청 값 그대로
    static FidaOrderResponse toResponse(UUID id, FidaOrderCommand c) {
        List<FidaOrderResponse.OrderItem> orders = c.orders() == null ? List.of() : c.orders().stream()
                .map(o -> new FidaOrderResponse.OrderItem(o.direction().name(), o.orderType().name(), o.quantity(), o.price()))
                .toList();
        return new FidaOrderResponse(id, c.releaseDate(), c.ticker(), c.currentCycleStart(),
                c.currentCycleRealizedPnl(), c.avgPrice(), c.holdings(), orders);
    }

    static FidaOrderCommand toCommand(FidaOrderRequest r) {
        List<FidaPlannedOrder> orders = r.orders() == null ? null : r.orders().stream()
                .map(o -> new FidaPlannedOrder(o.direction(), o.orderType(), o.quantity(), o.price()))
                .toList();
        return new FidaOrderCommand(r.releaseDate(), r.ticker(), r.currentCycleStart(),
                r.currentCycleRealizedPnl(), r.avgPrice(), r.holdings(), orders);
    }

    static PrivacyBaseUpdateCommand toCommand(PrivacyBaseUpdateRequest r) {
        return new PrivacyBaseUpdateCommand(r.currentCycleStart(), r.currentCycleRealizedPnl(), r.avgPrice(), r.holdings());
    }

    static PrivacyOrderUpdateCommand toCommand(PrivacyOrderUpdateRequest r) {
        return new PrivacyOrderUpdateCommand(r.price(), r.quantity());
    }

    static PrivacyOrderAddCommand toCommand(PrivacyOrderAddRequest r) {
        return new PrivacyOrderAddCommand(r.direction(), r.orderType(), r.price(), r.quantity());
    }
}
