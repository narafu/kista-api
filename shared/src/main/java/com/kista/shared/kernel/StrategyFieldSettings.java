package com.kista.shared.kernel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

// 전략 생성 필드 하나의 허용값/기본값 정책 — admin RuntimeSettings + trading StrategyCreationResolvers 공용
public record StrategyFieldSettings<T>(
        boolean customizable, // 사용자 입력 허용 여부
        List<T> allowedValues, // 허용 값 목록
        T defaultValue // 신규 생성 기본값
) {
    public StrategyFieldSettings {
        allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (allowedValues.isEmpty() || allowedValues.stream().noneMatch(v -> valuesEqual(v, defaultValue))) {
            throw new IllegalArgumentException("기본값은 허용 값 목록에 포함되어야 합니다.");
        }
        if (!customizable && allowedValues.size() != 1) {
            throw new IllegalArgumentException("변경할 수 없는 필드는 허용 값이 정확히 하나여야 합니다.");
        }
    }

    public T resolve(T requestedValue) {
        if (requestedValue == null) {
            return defaultValue;
        }
        if (!customizable && !valuesEqual(defaultValue, requestedValue)) {
            throw new IllegalArgumentException("변경할 수 없는 필드는 기본값만 허용합니다.");
        }
        if (allowedValues.stream().noneMatch(v -> valuesEqual(v, requestedValue))) {
            throw new IllegalArgumentException("허용되지 않는 값(" + requestedValue + ")입니다.");
        }
        return requestedValue;
    }

    // BigDecimal은 scale이 다르면 equals()가 false를 반환하므로(10 vs 10.0) compareTo로 값만 비교
    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof BigDecimal ba && b instanceof BigDecimal bb) {
            return ba.compareTo(bb) == 0;
        }
        return Objects.equals(a, b);
    }
}
