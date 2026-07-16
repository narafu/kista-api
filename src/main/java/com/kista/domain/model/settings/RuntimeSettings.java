package com.kista.domain.model.settings;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.Strategy.Type;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RuntimeSettings(
        boolean approvalRequired, // 신규 가입 승인 필요 여부
        Map<Broker, BrokerSettings> brokers, // 증권사별 신규 등록 설정
        Map<Type, StrategyCreationSettings> strategies // 전략별 신규 생성 설정
) {
    public RuntimeSettings {
        brokers = immutableEnumMap(Broker.class, brokers, "broker");
        strategies = immutableEnumMap(Type.class, strategies, "strategy");
    }

    public static RuntimeSettings defaults() {
        // 현재 운영 동작을 보존하는 증권사 기본값을 구성한다.
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(Broker.class);
        for (Broker broker : Broker.values()) {
            brokers.put(broker, new BrokerSettings(true));
        }

        // 전략별 현재 생성 옵션과 기본값을 구성한다.
        Map<Type, StrategyCreationSettings> strategies = new EnumMap<>(Type.class);
        strategies.put(Type.INFINITE, new StrategyCreationSettings(true,
                field(true, List.of(Ticker.values()), Ticker.SOXL),
                field(true, List.of(20, 30, 40), Strategy.DEFAULT_DIVISION_COUNT), null, null, null));
        strategies.put(Type.PRIVACY, new StrategyCreationSettings(true,
                field(false, List.of(Ticker.SOXL), Ticker.SOXL), null, null, null, null));
        strategies.put(Type.VR, new StrategyCreationSettings(true,
                field(false, List.of(Ticker.TQQQ), Ticker.TQQQ), null,
                field(true, List.of(RecurringMode.values()), RecurringMode.HOLD),
                field(true, List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(15), BigDecimal.valueOf(20)), BigDecimal.valueOf(15)),
                field(true, List.of(1, 2, 4), 2)));
        return new RuntimeSettings(true, brokers, strategies);
    }

    private static <T> StrategyFieldSettings<T> field(boolean customizable, List<T> values, T defaultValue) {
        return new StrategyFieldSettings<>(customizable, values, defaultValue);
    }

    private static <K extends Enum<K>, V> Map<K, V> immutableEnumMap(
            Class<K> keyType, Map<K, V> values, String label) {
        Objects.requireNonNull(values, label + " settings");
        EnumMap<K, V> copy = new EnumMap<>(keyType);
        copy.putAll(values);
        // 알 수 없는 문자열 키는 JSON 역직렬화에서 거부되고 누락된 enum 키도 여기서 거부한다.
        if (copy.size() != keyType.getEnumConstants().length || copy.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " settings must contain every known key");
        }
        return Map.copyOf(copy);
    }

    public record BrokerSettings(boolean enabled) { // 증권사 신규 등록 허용 값
    }
}
