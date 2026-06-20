package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossSellableQuantity;

public record TossSellableQuantityResponse(
    String symbol,   // 종목 코드 (예: SOXL)
    int    quantity  // 판매 가능 수량
) {
    public static TossSellableQuantityResponse from(TossSellableQuantity q) {
        return new TossSellableQuantityResponse(q.symbol(), q.quantity());
    }
}
