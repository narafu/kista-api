package com.kista.domain.model.settings;

import java.util.List;
import java.util.Objects;

public record BenchmarkFieldSettings<T>(
        List<T> allowedValues, // 허용 값 목록
        T defaultValue // 비교 기본값
) {
    public BenchmarkFieldSettings {
        allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        Objects.requireNonNull(defaultValue, "defaultValue");

        // 기본값은 반드시 허용 목록에 포함되어야 한다.
        if (allowedValues.isEmpty() || allowedValues.stream().noneMatch(v -> Objects.equals(v, defaultValue))) {
            throw new IllegalArgumentException("default value must be included in allowed values");
        }
    }
}
