package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.settings.StrategyFieldSettings;
import com.kista.domain.model.strategy.Strategy.Type;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.EnumMap;
import java.util.Map;

// 공개 및 관리자 API가 공유하는 타입 기반 런타임 설정 응답
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeSettingsResponse(
        @Schema(description = "가입·인증 공개 설정")
        AuthResponse auth,
        @Schema(description = "증권사별 공개 설정")
        Map<Broker, BrokerResponse> brokers,
        @Schema(description = "전략 타입별 생성 정책 설정")
        Map<Type, StrategyResponse> strategies
) {
    public static RuntimeSettingsResponse from(RuntimeSettings settings) {
        // 도메인 enum 키를 유지하면서 웹 응답 타입으로 변환한다.
        Map<Broker, BrokerResponse> brokers = new EnumMap<>(Broker.class);
        settings.brokers().forEach((key, value) -> brokers.put(key, new BrokerResponse(value.enabled())));
        Map<Type, StrategyResponse> strategies = new EnumMap<>(Type.class);
        settings.strategies().forEach((key, value) -> strategies.put(key, StrategyResponse.from(value)));
        return new RuntimeSettingsResponse(new AuthResponse(settings.approvalRequired()),
                Map.copyOf(brokers), Map.copyOf(strategies));
    }

    public record AuthResponse(
            @Schema(description = "가입 승인 필수 여부")
            boolean approvalRequired) { // 가입 승인 공개 설정
    }

    public record BrokerResponse(
            @Schema(description = "증권사 신규 계좌 등록 허용 여부")
            boolean enabled) { // 증권사 신규 등록 공개 설정
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StrategyResponse(
            @Schema(description = "신규 전략 생성 허용 여부")
            boolean enabled,
            @Schema(description = "전략 타입별 생성 필드 정책 (적용 안 되는 필드는 생략)")
            FieldResponses fields) { // 전략 생성 공개 설정
        static StrategyResponse from(StrategyCreationSettings settings) {
            return new StrategyResponse(settings.enabled(), new FieldResponses(
                    settings.ticker(), settings.divisionCount(), settings.recurringMode(),
                    settings.bandWidth(), settings.intervalWeeks()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldResponses(
            @Schema(description = "종목 생성 설정 (customizable/allowedValues/defaultValue)")
            StrategyFieldSettings<?> ticker,
            @Schema(description = "무한매수 분할 수 생성 설정")
            StrategyFieldSettings<?> divisionCount,
            @Schema(description = "VR 정기 입출금 방향 생성 설정")
            StrategyFieldSettings<?> recurringMode,
            @Schema(description = "VR 밴드 폭 생성 설정 (%)")
            StrategyFieldSettings<?> bandWidth,
            @Schema(description = "VR 롤오버 주기 생성 설정 (주 단위)")
            StrategyFieldSettings<?> intervalWeeks
    ) { // 전략별 생성 필드 공개 설정 — 적용 안 되는 필드는 null 대신 생략
    }
}
