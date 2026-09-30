package com.kista.tradingstats.adapter.in.web;

import com.kista.contract.stats.InvestmentPointsResponse;
import com.kista.contract.stats.PortfolioCurrentResponse;
import com.kista.contract.stats.PortfolioOrderResponse;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.tradingstats.domain.model.InvestmentPoint;
import com.kista.tradingstats.domain.model.InvestmentPointsResult;

// 내부 API(/api/internal/trading/stats/**) 도메인 → contract 매핑 — 도메인 record를 wire에 직접 태우지 않는다
final class StatsContractMapper {

    private StatsContractMapper() {}

    static InvestmentPointsResponse toResponse(InvestmentPointsResult r) {
        return new InvestmentPointsResponse(
                r.points().stream().map(StatsContractMapper::toDto).toList(),
                r.effectiveFrom(), r.effectiveTo(),
                r.selectedStrategy() == null ? null : toDto(r.selectedStrategy()));
    }

    private static InvestmentPointsResponse.InvestmentPointDto toDto(InvestmentPoint p) {
        return new InvestmentPointsResponse.InvestmentPointDto(p.baseDate(), p.investmentIndexUsd(), p.periodReturn());
    }

    // Strategy 6필드 중 벤치마크 비교 화면이 쓰는 id/type/ticker만 싣는다
    private static InvestmentPointsResponse.StrategyRefDto toDto(Strategy s) {
        return new InvestmentPointsResponse.StrategyRefDto(s.id(), s.type(), s.ticker());
    }

    static PortfolioCurrentResponse toResponse(CyclePositionHistoryEntry e) {
        return new PortfolioCurrentResponse(e.ticker(), e.holdings(), e.avgPrice(), e.usdDeposit(), e.closingPrice());
    }

    static PortfolioOrderResponse toResponse(Order o) {
        return new PortfolioOrderResponse(o.tradeDate(), o.ticker(), o.direction(), o.orderType(), o.quantity(), o.price());
    }
}
