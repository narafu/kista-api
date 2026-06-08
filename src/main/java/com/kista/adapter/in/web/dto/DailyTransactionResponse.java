package com.kista.adapter.in.web.dto;

import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.order.Order.OrderDirection;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.List;

public record DailyTransactionResponse(
        List<ItemDto> items,
        SummaryDto summary
) {
    public record ItemDto(
            String tradeDate,
            String settlementDate,
            OrderDirection direction,
            Ticker ticker,
            String symbolName,
            int quantity,
            BigDecimal price,
            BigDecimal tradeAmountUsd,
            BigDecimal settlementAmountKrw,
            BigDecimal exchangeRate,
            String currency
    ) {
        static ItemDto from(DailyTransaction t) {
            return new ItemDto(
                    t.tradeDate(), t.settlementDate(), t.direction(), t.ticker(),
                    t.symbolName(), t.quantity(), t.price(), t.tradeAmountUsd(),
                    t.settlementAmountKrw(), t.exchangeRate(), t.currency()
            );
        }
    }

    public record SummaryDto(
            BigDecimal buyAmountFcr,
            BigDecimal sellAmountFcr,
            BigDecimal domesticFee,
            BigDecimal overseasFee
    ) {
        static SummaryDto from(DailyTransactionSummary s) {
            return new SummaryDto(s.buyAmountFcr(), s.sellAmountFcr(), s.domesticFee(), s.overseasFee());
        }
    }

    public static DailyTransactionResponse from(DailyTransactionResult result) {
        return new DailyTransactionResponse(
                result.items().stream().map(ItemDto::from).toList(),
                SummaryDto.from(result.summary())
        );
    }
}
