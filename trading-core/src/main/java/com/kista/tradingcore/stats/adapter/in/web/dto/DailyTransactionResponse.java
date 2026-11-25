package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.DailyTransaction;
import com.kista.tradingcore.broker.domain.model.DailyTransactionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;


public record DailyTransactionResponse(
        @Schema(description = "일별 체결 내역 목록")
        List<ItemDto> items,
        @Schema(description = "날짜별(통화별) 체결 합계 — 날짜 오름차순, 통화 선언 순서(KRW, USD)")
        List<DayDto> byDate
) {
    // 하루의 체결 건수와 통화별 합계 (통화가 다른 금액은 합치지 않는다)
    public record DayDto(
            @Schema(description = "매매일 (KST 기준)")
            String date,
            @Schema(description = "그날 전체 체결 건수")
            int tradeCount,
            List<CurrencyDto> byCurrency
    ) {}

    public record CurrencyDto(
            Currency currency,
            @Schema(description = "매수 금액 합계 (통화 자릿수)")
            BigDecimal buyAmount,
            @Schema(description = "매도 금액 합계 (통화 자릿수)")
            BigDecimal sellAmount,
            int buyCount,
            int sellCount
    ) {}

    public record ItemDto(
            @Schema(description = "매매일 (KST 기준)")
            String tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            OrderDirection direction,
            @Schema(description = "종목코드")
            StrategyTicker ticker,
            @Schema(description = "종목명")
            String symbolName,
            @Schema(description = "체결수량")
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
        return new DailyTransactionResponse(result.items().stream().map(ItemDto::from).toList(),
                summarize(result.items()));
    }

    // 체결 목록 → 날짜별·통화별 매수/매도 합계 (kista-ui summarizeDailyTrades와 같은 값)
    static List<DayDto> summarize(List<DailyTransaction> items) {
        return items.stream()
                .collect(Collectors.groupingBy(DailyTransaction::tradeDate, TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(day -> new DayDto(day.getKey(), day.getValue().size(),
                        day.getValue().stream()
                                .collect(Collectors.groupingBy(DailyTransaction::currency, TreeMap::new, Collectors.toList()))
                                .entrySet().stream()
                                .map(c -> currencySummary(c.getKey(), c.getValue()))
                                .toList()))
                .toList();
    }

    private static CurrencyDto currencySummary(Currency currency, List<DailyTransaction> trades) {
        // SELL이 아니면 매수로 센다 (UI와 동일)
        Map<Boolean, List<DailyTransaction>> bySell = trades.stream()
                .collect(Collectors.partitioningBy(t -> t.direction() == OrderDirection.SELL));
        return new CurrencyDto(currency, sum(currency, bySell.get(false)), sum(currency, bySell.get(true)),
                bySell.get(false).size(), bySell.get(true).size());
    }

    private static BigDecimal sum(Currency currency, List<DailyTransaction> trades) {
        return currency.round(trades.stream().map(DailyTransaction::tradeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
