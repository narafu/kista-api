package com.kista.tradingcore.privacy.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

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
            throw new IllegalArgumentException("매수 주문은 수량을 지정해야 합니다.");
        }
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("수량은 양수여야 합니다.");
        }
    }
}
