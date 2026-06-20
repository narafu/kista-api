package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossStockInfo;

// Toss /api/v1/stocks 응답 DTO — 가격 정보 없음 (현재가는 /prices 별도 조회)
public record TossStockInfoResponse(
    String symbol,       // 종목 코드 (예: SOXL)
    String name,         // 한글 종목명
    String englishName,  // 영문 종목명
    String market,       // 거래소/시장 (예: NYSE ARCA)
    String currency,     // 통화 (예: USD)
    String status        // 종목 상태 (예: NORMAL)
) {
    public static TossStockInfoResponse from(TossStockInfo info) {
        return new TossStockInfoResponse(
                info.symbol(), info.name(), info.englishName(),
                info.market(), info.currency(), info.status());
    }
}
