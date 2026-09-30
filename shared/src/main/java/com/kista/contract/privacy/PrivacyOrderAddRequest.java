package com.kista.contract.privacy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// 관리자 PRIVACY 주문 명세 수동 추가 — POST /api/internal/privacy/trade-bases/{baseId}/orders body
public record PrivacyOrderAddRequest(
        @NotNull OrderDirection direction,  // 매매 방향
        @NotNull OrderType orderType,       // 주문 유형
        @NotNull @Positive BigDecimal price,// 주문 가격
        @Positive Integer quantity          // 주문 수량 (nullable, 값이 있으면 양수) — BUY는 null 불가
) {
    // BUY 주문은 수량이 확정돼야 한다 — SELL만 null("잔량 전부") 허용
    @JsonIgnore
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return direction != OrderDirection.BUY || quantity != null;
    }
}
