package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.contract.trading.ReorderBuyBudgetResponse;
import com.kista.shared.contract.trading.ReorderRequest;
import com.kista.shared.contract.trading.ReorderResponse;
import com.kista.shared.contract.trading.ReorderTimingAvailabilityResponse;
import com.kista.shared.contract.trading.StrategyResponse;
import com.kista.shared.contract.trading.StrategySummaryResponse;
import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.ReorderBuyBudget;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.kista.tradingcore.trading.domain.model.ReorderCommand;
import com.kista.tradingcore.trading.domain.model.ReorderResult;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategySummary;

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

    static ReorderBuyBudgetResponse toResponse(ReorderBuyBudget b) {
        Map<UUID, BigDecimal> cycleRemaining = new LinkedHashMap<>();
        b.cycles().forEach((id, c) -> cycleRemaining.put(id, c.remaining()));
        return new ReorderBuyBudgetResponse(cycleRemaining, b.sourceRefunds());
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
