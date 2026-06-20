package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossStockInfo;

import java.math.BigDecimal;

public record TossStockInfoResponse(
    String     symbol,      // 종목 코드 (예: SOXL)
    String     name,        // 종목명
    String     exchange,    // 거래소
    String     currency,    // 통화
    BigDecimal lastPrice,   // 현재가
    BigDecimal prevClose    // 전일종가
) {
    public static TossStockInfoResponse from(TossStockInfo info) {
        return new TossStockInfoResponse(
                info.symbol(), info.name(), info.exchange(),
                info.currency(), info.lastPrice(), info.prevClose());
    }
}
