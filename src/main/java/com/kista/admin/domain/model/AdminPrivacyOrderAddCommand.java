package com.kista.admin.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// privacy.domain.model.PrivacyOrderAddCommand의 admin own-type 1:1 복제 — Gradle 컴파일 경계
// (:trading-core→:api 역방향 의존 금지)로 원본 타입을 import할 수 없다.
public record AdminPrivacyOrderAddCommand(
        @NotNull OrderDirection direction,
        @NotNull OrderType orderType,
        @NotNull @Positive BigDecimal price,
        @Nullable Integer quantity  // BUY 주문은 null 불가
) {
    public AdminPrivacyOrderAddCommand {
        if (direction == OrderDirection.BUY && quantity == null) {
            throw new IllegalArgumentException("BUY 주문의 quantity는 null일 수 없습니다");
        }
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다: " + quantity);
        }
    }
}
