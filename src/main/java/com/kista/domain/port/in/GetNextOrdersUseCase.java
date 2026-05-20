package com.kista.domain.port.in;

import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GetNextOrdersUseCase {

    Result preview(UUID accountId, UUID requesterId);

    // 도메인 모델만 포함 — ArchUnit 규칙 준수
    record Result(
            LocalDate tradeDate,
            InfinitePosition position,
            List<Order> orders
    ) {}
}
