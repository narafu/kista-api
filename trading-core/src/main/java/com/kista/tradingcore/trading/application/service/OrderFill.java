package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.domain.model.Execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 주문 1건의 체결 합산(수량·가중평균가) — 마감 리포트와 취소 직후 체결 기록이 같은 함수를 쓰는 SSOT
record OrderFill(BigDecimal quantity, BigDecimal avgPrice) {

    // externalOrderId별 합산 — 번호 없는 체결은 제외
    static Map<String, OrderFill> byExternalOrderId(List<Execution> executions) {
        return executions.stream()
                .filter(e -> e.externalOrderId() != null && !e.externalOrderId().isBlank())
                .collect(Collectors.groupingBy(Execution::externalOrderId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> of(e.getValue())));
    }

    // 수량 Σ(체결수량), 가중평균가 Σ(체결금액) ÷ Σ(체결수량) — scale 2, HALF_UP (수량 0이면 평균가 0)
    static OrderFill of(List<Execution> matched) {
        int quantity = matched.stream().mapToInt(Execution::quantity).sum();
        BigDecimal avg = quantity <= 0 ? BigDecimal.ZERO
                : matched.stream().map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        return new OrderFill(BigDecimal.valueOf(quantity), avg);
    }
}
