package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.DailyTransaction;
import com.kista.tradingcore.broker.domain.model.DailyTransactionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;


public record DailyTransactionResponse(
        @Schema(description = "일별 체결 내역 목록")
        List<ItemDto> items
) {
    public record ItemDto(
            @Schema(description = "매매일 (KST 기준)")
            String tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            OrderDirection direction,
            @Schema(description = "종목코드")
            StrategyTicker ticker,
            @Schema(description = "종목명")
            String symbolName,
            @Schema(description = "체결수량 (코인은 소수)")
            BigDecimal quantity,
            @Schema(description = "체결단가 (계좌 통화)")
            BigDecimal price,
            @Schema(description = "거래금액 (계좌 통화)")
            BigDecimal tradeAmount,
            @Schema(description = "계좌 통화", example = "USD")
            Currency currency
    ) {
        static ItemDto from(DailyTransaction t) {
            return new ItemDto(
                    t.tradeDate(), t.direction(), t.ticker(),
                    t.symbolName(), t.quantity(), t.price(), t.tradeAmount(),
                    t.currency()
            );
        }
    }

    public static DailyTransactionResponse from(DailyTransactionResult result) {
        return new DailyTransactionResponse(result.items().stream().map(ItemDto::from).toList());
    }
}
