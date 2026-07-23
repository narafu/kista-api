package com.kista.domain.model.settings;

import java.util.List;
import java.util.Objects;

public record BenchmarkSettings(
        BenchmarkFieldSettings<String> etf // ETF 벤치마크 비교 자산 설정
) {
    public BenchmarkSettings {
        Objects.requireNonNull(etf, "etf");
    }

    public static BenchmarkSettings defaults() {
        return new BenchmarkSettings(new BenchmarkFieldSettings<>(
                List.of("SPY", "QQQ", "QLD", "IBIT", "ETHA"), "SPY"));
    }
}
