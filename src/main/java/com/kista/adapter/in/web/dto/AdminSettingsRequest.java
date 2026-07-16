package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.settings.StrategyFieldSettings;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.Strategy.Type;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 관리자 전체 런타임 설정 갱신 요청
public record AdminSettingsRequest(
        @Schema(description = "가입 승인 정책 설정")
        @NotNull @Valid AuthRequest auth,
        @Schema(description = "증권사별 신규 등록/연결 테스트 활성화 설정 (key=Broker)")
        @NotNull Map<Broker, @Valid BrokerRequest> brokers,
        @Schema(description = "전략별 신규 생성 정책 설정 (key=Type)")
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

    public record AuthRequest(
            @Schema(description = "신규 가입 승인 필요 여부")
            @NotNull Boolean approvalRequired) { // 가입 승인 관리자 입력
    }

    public record BrokerRequest(
            @Schema(description = "증권사 신규 계좌 등록/연결 테스트 허용 여부")
            @NotNull Boolean enabled) { // 증권사 활성화 관리자 입력
    }

    public record StrategyRequest(
            @Schema(description = "신규 전략 생성 허용 여부")
            @NotNull Boolean enabled,
            @Schema(description = "전략별 생성 필드 설정")
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
            @Schema(description = "종목 생성 필드 설정")
            @Valid FieldRequest<Ticker> ticker,
            @Schema(description = "무한매수 분할 수 필드 설정 (INFINITE 전용)")
            @Valid FieldRequest<Integer> divisionCount,
            @Schema(description = "VR 정기 입출금 방향 필드 설정 (VR 전용)")
            @Valid FieldRequest<RecurringMode> recurringMode,
            @Schema(description = "VR 밴드 폭 필드 설정 (%, VR 전용)")
            @Valid FieldRequest<BigDecimal> bandWidth,
            @Schema(description = "VR 롤오버 주기 필드 설정 (주 단위, VR 전용)")
            @Valid FieldRequest<Integer> intervalWeeks
    ) { // 전략별 관리자 생성 필드 입력
        StrategyFieldSettings<Ticker> tickerValue() { return require(ticker, "ticker").toDomain(); }
        StrategyFieldSettings<Integer> divisionCountValue() { return require(divisionCount, "divisionCount").toDomain(); }
        StrategyFieldSettings<RecurringMode> recurringModeValue() { return require(recurringMode, "recurringMode").toDomain(); }
        StrategyFieldSettings<BigDecimal> bandWidthValue() { return require(bandWidth, "bandWidth").toDomain(); }
        StrategyFieldSettings<Integer> intervalWeeksValue() { return require(intervalWeeks, "intervalWeeks").toDomain(); }
    }

    public record FieldRequest<T>(
            @Schema(description = "사용자 입력 허용 여부")
            @NotNull Boolean customizable,
            @Schema(description = "허용 값 목록")
            @NotNull List<@NotNull T> allowedValues,
            @Schema(description = "신규 생성 기본값")
            @NotNull T defaultValue
    ) { // 개별 생성 필드 관리자 입력
        StrategyFieldSettings<T> toDomain() {
            return new StrategyFieldSettings<>(customizable, allowedValues, defaultValue);
        }
    }
}
