package com.kista.api.finance.domain.model;

import java.util.List;
import java.util.UUID;

// 추이 버킷 하나 — period는 월간 "yyyy-MM", 연간 "yyyy"
public record FlowTrendPoint(String period, long amount, List<RootAmount> byCategory) {

    // 루트(L1) 카테고리별 합계
    public record RootAmount(UUID categoryId, long amount) {}
}
