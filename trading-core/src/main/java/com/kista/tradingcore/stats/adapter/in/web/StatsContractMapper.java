package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.contract.stats.InvestmentPointsResponse;
import com.kista.shared.contract.stats.PortfolioCurrentResponse;
import com.kista.shared.contract.stats.PortfolioOrderResponse;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.stats.domain.model.InvestmentPoint;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;
import com.kista.tradingcore.stats.domain.model.PortfolioOrder;
import com.kista.tradingcore.stats.domain.model.PortfolioPosition;

// 내부 API(/api/internal/trading/stats/**) 도메인 → contract 매핑 — 도메인 record를 wire에 직접 태우지 않는다
final class StatsContractMapper {

    private StatsContractMapper() {}

    static InvestmentPointsResponse toResponse(InvestmentPointsResult r) {
        return new InvestmentPointsResponse(
                r.points().stream().map(StatsContractMapper::toDto).toList(),
                r.effectiveFrom(), r.effectiveTo(), r.currency(),
                r.selectedStrategy() == null ? null : toDto(r.selectedStrategy(), r.currency()));
    }

    private static InvestmentPointsResponse.InvestmentPointDto toDto(InvestmentPoint p) {
        return new InvestmentPointsResponse.InvestmentPointDto(p.baseDate(), p.investmentIndex(), p.periodReturn());
    }

    // Strategy 6필드 중 벤치마크 비교 화면이 쓰는 id/type/ticker만 싣는다
    private static InvestmentPointsResponse.StrategyRefDto toDto(Strategy s, Currency currency) {
        return new InvestmentPointsResponse.StrategyRefDto(s.id(), s.type(), s.ticker(), currency);
    }

    static PortfolioCurrentResponse toResponse(PortfolioPosition p) {
        CyclePositionHistoryEntry e = p.position();
        return new PortfolioCurrentResponse(e.strategyType(), e.ticker(), p.currency(),
                e.holdings(), e.avgPrice(), e.deposit(), e.closingPrice());
    }

    static PortfolioOrderResponse toResponse(PortfolioOrder p) {
        Order o = p.order();
        return new PortfolioOrderResponse(o.tradeDate(), o.ticker(), o.direction(), o.orderType(), o.quantity(), o.price(), p.currency());
    }
}
