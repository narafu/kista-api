package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.SellableQuantity;

public record SellableQuantityResponse(
    String symbol,   // 종목 코드 (예: SOXL)
    int    quantity  // 판매 가능 수량
) {
    public static SellableQuantityResponse from(SellableQuantity q) {
        return new SellableQuantityResponse(q.symbol(), q.quantity());
    }
}
