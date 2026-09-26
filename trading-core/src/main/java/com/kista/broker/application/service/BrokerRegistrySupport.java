package com.kista.broker.application.service;

import com.kista.sharedkernel.Broker;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Broker→T 조회 맵 빌드 + 미존재 시 IllegalArgumentException 공용 헬퍼 — BrokerAdapterRegistry/BrokerConnectionTesters 공용
final class BrokerRegistrySupport {

    private BrokerRegistrySupport() {
    }

    // List<T>를 T::supports 기준 Broker→T 맵으로 변환
    static <T> Map<Broker, T> buildMap(List<T> items, Function<T, Broker> supports) {
        return items.stream().collect(Collectors.toMap(supports, Function.identity()));
    }

    // 미지원 증권사면 IllegalArgumentException → GlobalExceptionHandler 400
    static <T> T requireByBroker(Map<Broker, T> registry, Broker broker) {
        T value = registry.get(broker);
        if (value == null) {
            throw new IllegalArgumentException("지원하지 않는 증권사: " + broker);
        }
        return value;
    }
}
