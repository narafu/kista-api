package com.kista.privacy.domain.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// 관리자 PRIVACY 주문 명세 수동 보정 — 가격·수량만 교체(방향/유형은 불변)
public record PrivacyOrderUpdateCommand(
        @NotNull @Positive BigDecimal price,
        @Nullable Integer quantity  // BUY 주문은 null 불가 — 어댑터에서 대상 주문 direction 확인 후 검증
) {
    public PrivacyOrderUpdateCommand {
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다: " + quantity);
        }
    }
}
