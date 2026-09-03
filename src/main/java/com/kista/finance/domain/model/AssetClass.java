package com.kista.finance.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// AssetSnapshot 전용이 아니라 향후 카테고리·통계에서도 쓰이는 횡단 값이라 독립 파일로 둔다.
@Getter
@RequiredArgsConstructor
public enum AssetClass {
    CASH("현금성"),
    EQUITY("주식"),
    FIXED_INCOME("채권"),
    COMMODITY("원자재"),
    CRYPTO("크립토"),
    REAL_ESTATE("부동산");

    private final String label;
}
