package com.kista.domain.model.privacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 조회 전용 — 마스터(기준 매매표) + 주문 명세 묶음
public record PrivacyTradeBaseView(
        UUID id,
        LocalDate releaseDate,               // DB release_date 원본 (KST 변환 없음)
        String ticker,                       // 종목 (SOXL)
        BigDecimal currentCycleStart,        // 기준가
        BigDecimal currentCycleRealizedPnl,  // 사이클 실현 수익($)
        BigDecimal avgPrice,                 // 평단가 (nullable)
        int holdings,                        // 보유 수량
        List<OrderLine> orders
) {
    public record OrderLine(
            UUID id,
            String direction,    // BUY / SELL
            String orderType,    // LOC / MOC / LIMIT
            BigDecimal price,    // 주문 가격
            Integer quantity     // 주문 수량 (nullable)
    ) {
    }
}
