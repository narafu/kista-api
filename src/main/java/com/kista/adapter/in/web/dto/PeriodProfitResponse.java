package com.kista.adapter.in.web.dto;

import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.List;

public record PeriodProfitResponse(
        List<ItemDto> items,
        BigDecimal totalRealizedProfit,
        BigDecimal totalReturnRate
) {
    public record ItemDto(
            String tradeDate,
            Ticker ticker,
            int quantity,
            BigDecimal avgBuyPrice,
            BigDecimal avgSellPrice,
            BigDecimal realizedProfit,
            BigDecimal returnRate,
            String exchangeCode
    ) {
        static ItemDto from(PeriodProfitResult.Item item) {
            return new ItemDto(
                    item.tradeDate(), item.ticker(), item.quantity(),
                    item.avgBuyPrice(), item.avgSellPrice(),
                    item.realizedProfit(), item.returnRate(), item.exchangeCode()
            );
        }
    }

    public static PeriodProfitResponse from(PeriodProfitResult result) {
        return new PeriodProfitResponse(
                result.items().stream().map(ItemDto::from).toList(),
                result.totalRealizedProfit(),
                result.totalReturnRate()
        );
    }
}
