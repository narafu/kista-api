package com.kista.trading.adapter.in.web;

import com.kista.contract.trading.OrderResponse;
import com.kista.contract.trading.ReorderRequest;
import com.kista.contract.trading.ReorderResponse;
import com.kista.contract.trading.ReorderTimingAvailabilityResponse;
import com.kista.contract.trading.StrategyResponse;
import com.kista.contract.trading.StrategySummaryResponse;
import com.kista.contract.trading.TradeCorrectionRequest;
import com.kista.contract.trading.TradeCorrectionResponse;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;

// 내부 API(/api/internal/trading/**) 도메인 ↔ contract 매핑 — 도메인 record를 wire에 직접 태우지 않는다.
// contract는 도메인 타입을 import할 수 없으므로 매핑은 소유 모듈의 inbound adapter가 담당한다.
final class TradingContractMapper {

    private TradingContractMapper() {}

    static OrderResponse toResponse(Order o) {
        return new OrderResponse(o.id(), o.accountId(), o.strategyCycleId(), o.tradeDate(), o.ticker(),
                o.orderType(), o.timing(), o.direction(), o.orderLeg(), o.quantity(), o.price(),
                o.status(), o.externalOrderId(), o.filledQuantity(), o.filledPrice());
    }

    static StrategyResponse toResponse(Strategy s) {
        return new StrategyResponse(s.id(), s.accountId(), s.type(), s.status(), s.ticker(), s.cycleSeedType());
    }

    static StrategySummaryResponse toResponse(StrategySummary s) {
        return new StrategySummaryResponse(s.strategyId(), s.strategyType());
    }

    static ReorderTimingAvailabilityResponse toResponse(DstInfo.ReorderTimingAvailability a) {
        return new ReorderTimingAvailabilityResponse(a.atOpen(), a.atClose(), a.immediate());
    }

    static ReorderResponse toResponse(ReorderResult r) {
        return new ReorderResponse(r.userId(), r.accountId(), r.strategyId(), r.sourceOrderId(),
                r.originalStatus(), r.resultingStatus(), r.newOrderExternalId(),
                r.oldPrice(), r.oldQuantity(), r.newDirection());
    }

    static TradeCorrectionResponse toResponse(ManualTradeCorrectionResult r) {
        return new TradeCorrectionResponse(r.userId(), r.accountId(), r.strategyId(), r.processedCount(),
                r.finalHoldings(), r.finalAvgPrice(), r.finalUsdDeposit(), r.strategyStatus(),
                r.cycleEnded(), r.cycleEndDate());
    }

    static ReorderCommand toCommand(ReorderRequest r) {
        return new ReorderCommand(r.userId(), r.accountId(), r.strategyId(), r.orderId(), r.timing(),
                r.tradeDate(), r.direction(), r.quantity(), r.price(), r.memo());
    }

    static ManualTradeCorrectionCommand toCommand(TradeCorrectionRequest r) {
        return new ManualTradeCorrectionCommand(r.userId(), r.accountId(), r.strategyId(),
                r.fills().stream()
                        .map(f -> new ManualTradeCorrectionCommand.Fill(f.tradeDate(), f.direction(), f.quantity(),
                                f.price(), f.externalOrderId(), f.memo()))
                        .toList());
    }
}
