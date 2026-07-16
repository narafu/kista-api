package com.kista.domain.model.settings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record StrategyFieldSettings<T>(
        boolean customizable, // 사용자 입력 허용 여부
        List<T> allowedValues, // 허용 값 목록
        T defaultValue // 신규 생성 기본값
) {
    public StrategyFieldSettings {
        allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        Objects.requireNonNull(defaultValue, "defaultValue");

        // 기본값은 반드시 허용 목록에 포함되어야 한다.
        if (allowedValues.isEmpty() || allowedValues.stream().noneMatch(v -> valuesEqual(v, defaultValue))) {
            throw new IllegalArgumentException("default value must be included in allowed values");
        }
        // 고정 필드는 기본값 하나만 허용한다.
        if (!customizable && allowedValues.size() != 1) {
            throw new IllegalArgumentException("non-customizable field must have exactly one allowed value");
        }
    }

    public T resolve(T requestedValue) {
        // 생략 값에는 기본값을 적용하고 고정 필드의 명시적 변경은 거부한다.
        if (requestedValue == null) {
            return defaultValue;
        }
        if (!customizable && !valuesEqual(defaultValue, requestedValue)) {
            throw new IllegalArgumentException("non-customizable field only accepts its default value");
        }
        if (allowedValues.stream().noneMatch(v -> valuesEqual(v, requestedValue))) {
            throw new IllegalArgumentException("value is not allowed: " + requestedValue);
        }
        return requestedValue;
    }

    // BigDecimal은 scale이 다르면 equals()가 값이 같아도 false를 반환하므로 compareTo로 비교한다 (예: 15 vs 15.00).
    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof BigDecimal ba && b instanceof BigDecimal bb) {
            return ba.compareTo(bb) == 0;
        }
        return Objects.equals(a, b);
    }
}
