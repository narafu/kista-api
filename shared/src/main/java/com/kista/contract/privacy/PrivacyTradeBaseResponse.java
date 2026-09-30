package com.kista.contract.privacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// PRIVACY 기준 매매표 마스터 + 주문 명세 — trading-core 내부 API(/api/internal/privacy/trade-bases) 응답
public record PrivacyTradeBaseResponse(
        UUID id,                             // 마스터 ID
        LocalDate releaseDate,               // DB release_date 원본 (KST 변환 없음)
        String ticker,                       // 종목 (SOXL)
        BigDecimal currentCycleStart,        // 기준가
        BigDecimal currentCycleRealizedPnl,  // 사이클 실현 수익($)
        BigDecimal avgPrice,                 // 평단가 (nullable)
        int holdings,                        // 보유 수량
        List<OrderLine> orders               // 주문 명세
) {
    public record OrderLine(
            UUID id,             // 주문 명세 ID
            String direction,    // BUY / SELL
            String orderType,    // LOC / MOC / LIMIT
            BigDecimal price,    // 주문 가격
            Integer quantity     // 주문 수량 (nullable)
    ) {}
}
