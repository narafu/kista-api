package com.kista.privacy.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// 관리자 PRIVACY 주문 명세 수동 추가 — direction/orderType/price/quantity 전체 입력
public record PrivacyOrderAddCommand(
        @NotNull OrderDirection direction,
        @NotNull OrderType orderType,
        @NotNull @Positive BigDecimal price,
        @Nullable Integer quantity  // BUY 주문은 null 불가
) {
    public PrivacyOrderAddCommand {
        if (direction == OrderDirection.BUY && quantity == null) {
            throw new IllegalArgumentException("BUY 주문의 quantity는 null일 수 없습니다");
        }
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다: " + quantity);
        }
    }
}
