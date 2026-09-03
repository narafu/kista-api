package com.kista.privacy.domain.model;

import java.math.BigDecimal;

// FIDA 수신 계획 주문 1건 — trading.domain.model.Order 전체(15필드) 대신 FIDA가 실제로 보내고 privacy가
// 실제로 읽는 4필드만 담는다. Jackson 역직렬화 시 추가 필드는 무시된다(Spring Boot 기본 FAIL_ON_UNKNOWN=false).
public record FidaPlannedOrder(
        PrivacyOrderDirection direction, // 매수/매도
        PrivacyOrderType orderType,      // LOC / MOC / LIMIT
        Integer quantity,                // 주문 수량 (nullable — SELL "잔량 전부" 의미)
        BigDecimal price                 // 주문 가격
) {
}
