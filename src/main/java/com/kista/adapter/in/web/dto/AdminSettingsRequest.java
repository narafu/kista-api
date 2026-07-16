package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.settings.StrategyFieldSettings;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.Strategy.Type;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 관리자 전체 런타임 설정 갱신 요청
public record AdminSettingsRequest(
        @NotNull @Valid AuthRequest auth,
        @NotNull Map<Broker, @Valid BrokerRequest> brokers,
        @NotNull Map<Type, @Valid StrategyRequest> strategies
) {
    public RuntimeSettings toDomain() {
        // 모든 enum 키와 전략별 필수 필드를 먼저 변환·검증한 뒤 도메인 설정을 생성한다.
        Map<Broker, RuntimeSettings.BrokerSettings> brokerSettings = new EnumMap<>(Broker.class);
        brokers.forEach((key, value) -> brokerSettings.put(key,
                new RuntimeSettings.BrokerSettings(require(value, "broker").enabled())));
        Map<Type, StrategyCreationSettings> strategySettings = new EnumMap<>(Type.class);
        strategies.forEach((key, value) -> strategySettings.put(key,
                require(value, "strategy").toDomain(key)));
        return new RuntimeSettings(auth.approvalRequired(), brokerSettings, strategySettings);
    }

    private static <T> T require(T value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " settings are required");
        return value;
    }

    public record AuthRequest(@NotNull Boolean approvalRequired) { // 가입 승인 관리자 입력
    }

    public record BrokerRequest(@NotNull Boolean enabled) { // 증권사 활성화 관리자 입력
    }

    public record StrategyRequest(
            @NotNull Boolean enabled,
            @NotNull @Valid FieldRequests fields
    ) { // 전략 생성 관리자 입력
        StrategyCreationSettings toDomain(Type type) {
            FieldRequests value = require(fields, type.name() + " fields");
            return switch (type) {
                case INFINITE -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), value.divisionCountValue(), null, null, null);
                case PRIVACY -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, null, null, null);
                case VR -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, value.recurringModeValue(),
                        value.bandWidthValue(), value.intervalWeeksValue());
            };
        }
    }

    public record FieldRequests(
            @Valid FieldRequest<Ticker> ticker,
            @Valid FieldRequest<Integer> divisionCount,
            @Valid FieldRequest<RecurringMode> recurringMode,
            @Valid FieldRequest<BigDecimal> bandWidth,
            @Valid FieldRequest<Integer> intervalWeeks
    ) { // 전략별 관리자 생성 필드 입력
        StrategyFieldSettings<Ticker> tickerValue() { return require(ticker, "ticker").toDomain(); }
        StrategyFieldSettings<Integer> divisionCountValue() { return require(divisionCount, "divisionCount").toDomain(); }
        StrategyFieldSettings<RecurringMode> recurringModeValue() { return require(recurringMode, "recurringMode").toDomain(); }
        StrategyFieldSettings<BigDecimal> bandWidthValue() { return require(bandWidth, "bandWidth").toDomain(); }
        StrategyFieldSettings<Integer> intervalWeeksValue() { return require(intervalWeeks, "intervalWeeks").toDomain(); }
    }

    public record FieldRequest<T>(
            @NotNull Boolean customizable,
            @NotNull List<@NotNull T> allowedValues,
            @NotNull T defaultValue
    ) { // 개별 생성 필드 관리자 입력
        StrategyFieldSettings<T> toDomain() {
            return new StrategyFieldSettings<>(customizable, allowedValues, defaultValue);
        }
    }
}
