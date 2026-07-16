package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossCandle;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TossCandleResponse(
    @Schema(description = "기준일")
    LocalDate date,    // 기준일
    @Schema(description = "시가")
    BigDecimal open,   // 시가
    @Schema(description = "고가")
    BigDecimal high,   // 고가
    @Schema(description = "저가")
    BigDecimal low,    // 저가
    @Schema(description = "종가")
    BigDecimal close,  // 종가
    @Schema(description = "거래량")
    long volume        // 거래량
) {
    public static TossCandleResponse from(TossCandle c) {
        return new TossCandleResponse(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume());
    }

    public static List<TossCandleResponse> fromList(List<TossCandle> candles) {
        return candles.stream().map(TossCandleResponse::from).toList();
    }
}
