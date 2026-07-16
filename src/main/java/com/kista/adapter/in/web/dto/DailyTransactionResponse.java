package com.kista.adapter.in.web.dto;

import com.kista.domain.model.broker.DailyTransaction;
import com.kista.domain.model.broker.DailyTransactionResult;
import com.kista.domain.model.broker.DailyTransactionSummary;
import com.kista.domain.model.order.Order.OrderDirection;
import com.kista.domain.model.strategy.Strategy.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;


public record DailyTransactionResponse(
        @Schema(description = "일별 체결 내역 목록")
        List<ItemDto> items,
        @Schema(description = "기간 합계 요약")
        SummaryDto summary
) {
    public record ItemDto(
            @Schema(description = "매매일 (KST 기준)")
            String tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            OrderDirection direction,
            @Schema(description = "종목코드")
            Ticker ticker,
            @Schema(description = "종목명")
            String symbolName,
            @Schema(description = "체결수량")
            int quantity,
            @Schema(description = "해외주식체결단가")
            BigDecimal price,
            @Schema(description = "거래외화금액")
            BigDecimal tradeAmountUsd,
            @Schema(description = "통화코드", example = "USD")
            String currency
    ) {
        static ItemDto from(DailyTransaction t) {
            return new ItemDto(
                    t.tradeDate(), t.direction(), t.ticker(),
                    t.symbolName(), t.quantity(), t.price(), t.tradeAmountUsd(),
                    t.currency()
            );
        }
    }

    public record SummaryDto(
            @Schema(description = "외화매수금액합계")
            BigDecimal buyAmountFcr,
            @Schema(description = "외화매도금액합계")
            BigDecimal sellAmountFcr,
            @Schema(description = "국내수수료합계")
            BigDecimal domesticFee,
            @Schema(description = "해외수수료합계")
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
