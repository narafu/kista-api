package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossCandle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TossCandleResponse(
    LocalDate date,    // 기준일
    BigDecimal open,   // 시가
    BigDecimal high,   // 고가
    BigDecimal low,    // 저가
    BigDecimal close,  // 종가
    long volume        // 거래량
) {
    public static TossCandleResponse from(TossCandle c) {
        return new TossCandleResponse(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume());
    }

    public static List<TossCandleResponse> fromList(List<TossCandle> candles) {
        return candles.stream().map(TossCandleResponse::from).toList();
    }
}
