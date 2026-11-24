package com.kista.api.benchmark.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BenchmarkAssetType {
    HOUSING("주택"), // 주택 가격 벤치마크
    ETF("ETF");      // ETF 벤치마크

    private final String label; // 한국어 표시 이름 (메타 번들)
}
