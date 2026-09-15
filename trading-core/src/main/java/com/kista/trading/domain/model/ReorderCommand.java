package com.kista.trading.domain.model;

import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// AdminReorderCommand와 구조적으로 동일 — trading-core는 :api에 대한 Gradle 의존이 없어
// com.kista.admin.domain.model.AdminReorderCommand를 참조할 수 없으므로 own-type으로 신설한다
// (constraints.md 게이트 (a) 순환 불가피). 호출자(admin)가 자기 타입에서 필드 그대로 매핑한다.
// direction/tradeDate는 미지정 시 원본 주문 값으로 대체되므로 @NotNull 대상이 아니다.
public record ReorderCommand(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotNull UUID orderId,
        @NotNull OrderTiming timing,   // 재주문 접수 시점 (AT_OPEN/AT_CLOSE/IMMEDIATE)
        LocalDate tradeDate,   // KST 거래일 (생략 시 원본 주문 값 사용)
        OrderDirection direction,   // 생략 시 원본 주문 값 사용
        @NotNull @Positive Integer quantity,
        @NotNull @Positive BigDecimal price,
        String memo
) {}
