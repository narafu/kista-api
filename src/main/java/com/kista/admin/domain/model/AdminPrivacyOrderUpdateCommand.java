package com.kista.admin.domain.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// privacy.domain.model.PrivacyOrderUpdateCommand의 admin own-type 1:1 복제 — Gradle 컴파일 경계
// (:trading-core→:api 역방향 의존 금지)로 원본 타입을 import할 수 없다.
public record AdminPrivacyOrderUpdateCommand(
        @NotNull @Positive BigDecimal price,
        @Nullable Integer quantity  // BUY 주문은 null 불가 — 어댑터에서 대상 주문 direction 확인 후 검증
) {
    public AdminPrivacyOrderUpdateCommand {
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다: " + quantity);
        }
    }
}
