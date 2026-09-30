package com.kista.sharedkernel;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 매매 프로세스가 소유하는 런타임 정책 묶음 — 증권사별 신규 등록 허용 + 전략 타입별 생성 정책.
// 저장·집행은 trading-core(TradingPolicyService)가, 편집은 root admin이 내부 API(PUT)로 위임한다.
// root↔trading-core 사이 내부 API body이기도 해서 JDK + sharedkernel 타입만 참조한다(outbound-zero).
public record TradingPolicySettings(
        Map<Broker, BrokerSettings> brokers, // 증권사별 신규 등록 설정 (모든 Broker 키 필수)
        Map<StrategyType, StrategyCreationSettings> strategies // 전략별 신규 생성 설정 (모든 StrategyType 키 필수)
) {
    public TradingPolicySettings {
        brokers = immutableEnumMap(Broker.class, brokers, "broker");
        strategies = immutableEnumMap(StrategyType.class, strategies, "strategy");
    }

    // 현재 운영 동작을 보존하는 기본값 — 저장 행이 없을 때와 신규 enum 상수 백필에 쓰인다
    public static TradingPolicySettings defaults() {
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(Broker.class);
        for (Broker broker : Broker.values()) {
            brokers.put(broker, new BrokerSettings(true));
        }
        Map<StrategyType, StrategyCreationSettings> strategies = new EnumMap<>(StrategyType.class);
        strategies.put(StrategyType.INFINITE, new StrategyCreationSettings(true,
                field(true, List.of(StrategyTicker.values()), StrategyTicker.SOXL),
                field(true, List.of(20, 30, 40), StrategyDefaults.DEFAULT_DIVISION_COUNT), null, null, null));
        strategies.put(StrategyType.PRIVACY, new StrategyCreationSettings(true,
                field(false, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL), null, null, null, null));
        strategies.put(StrategyType.VR, new StrategyCreationSettings(true,
                field(false, List.of(StrategyTicker.TQQQ), StrategyTicker.TQQQ), null,
                field(true, List.of(RecurringMode.values()), RecurringMode.HOLD),
                field(true, List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(15), BigDecimal.valueOf(20)), BigDecimal.valueOf(15)),
                field(true, List.of(1, 2, 4), 2)));
        return new TradingPolicySettings(brokers, strategies);
    }

    // 증권사 하나의 허용 여부 — AccountService가 소비
    public boolean brokerEnabled(Broker broker) {
        return brokers.get(broker).enabled();
    }

    // 전략 타입 하나의 생성 정책 — StrategyCreationService가 소비
    public StrategyCreationSettings strategy(StrategyType type) {
        return strategies.get(type);
    }

    private static <T> StrategyFieldSettings<T> field(boolean customizable, List<T> values, T defaultValue) {
        return new StrategyFieldSettings<>(customizable, values, defaultValue);
    }

    // 알 수 없는 문자열 키는 JSON 역직렬화에서 거부되고, 누락된 enum 키·null 값은 여기서 거부한다
    private static <K extends Enum<K>, V> Map<K, V> immutableEnumMap(Class<K> keyType, Map<K, V> values, String label) {
        Objects.requireNonNull(values, label + " settings");
        EnumMap<K, V> copy = new EnumMap<>(keyType);
        copy.putAll(values);
        if (copy.size() != keyType.getEnumConstants().length || copy.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " settings must contain every known key");
        }
        return Map.copyOf(copy);
    }
}
