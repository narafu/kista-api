package com.kista.admin.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// privacy.domain.model.PrivacyTradeBaseView의 admin own-type read model — PrivacyQueryHttpAdapter가
// trading-core 내부 API(/api/internal/privacy/trade-bases 등) 응답을 이 타입으로 역직렬화한다.
// Gradle 컴파일 경계(:trading-core→:api 역방향 의존 금지)로 원본 타입을 import할 수 없어 1:1 복제.
public record AdminPrivacyTradeBaseView(
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
