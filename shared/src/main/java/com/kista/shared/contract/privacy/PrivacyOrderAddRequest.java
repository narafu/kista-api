package com.kista.shared.contract.privacy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

// 관리자 PRIVACY 주문 명세 수동 추가 — POST /api/internal/privacy/trade-bases/{baseId}/orders body
public record PrivacyOrderAddRequest(
        @NotNull(message = "매매 방향은 필수입니다.") OrderDirection direction,  // 매매 방향
        @NotNull(message = "주문 유형은 필수입니다.") OrderType orderType,       // 주문 유형
        @NotNull(message = "주문 가격은 필수입니다.") @Positive(message = "주문 가격은 0보다 커야 합니다.") BigDecimal price,// 주문 가격
        @Positive(message = "주문 수량은 0보다 커야 합니다.") @Nullable Integer quantity          // 주문 수량 (nullable, 값이 있으면 양수) — BUY는 null 불가
) {
    // BUY 주문은 수량이 확정돼야 한다 — SELL만 null("잔량 전부") 허용
    @JsonIgnore
    @AssertTrue(message = "매수 주문은 수량이 필요합니다.")
    public boolean isBuyQuantityValid() {
        return direction != OrderDirection.BUY || quantity != null;
    }
}
