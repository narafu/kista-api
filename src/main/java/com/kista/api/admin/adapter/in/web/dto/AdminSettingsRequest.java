package com.kista.api.admin.adapter.in.web.dto;

import com.kista.shared.kernel.Broker;
import com.kista.api.admin.domain.model.BenchmarkFieldSettings;
import com.kista.api.admin.domain.model.BenchmarkSettings;
import com.kista.api.admin.domain.model.RuntimeSettings;
import com.kista.api.admin.domain.model.RuntimeSettingsBundle;
import com.kista.shared.kernel.BrokerSettings;
import com.kista.shared.kernel.TradingPolicySettings;
import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyFieldSettings;
import com.kista.api.benchmark.domain.model.EtfBenchmarkSymbol;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
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
        @Schema(description = "증권사별 신규 등록/연결 테스트 활성화 설정 (key=Broker, 생략한 증권사는 현재 값 유지)")
        @NotNull Map<Broker, @Valid BrokerRequest> brokers,
        @Schema(description = "전략별 신규 생성 정책 설정 (key=StrategyType)")
        @NotNull Map<StrategyType, @Valid StrategyRequest> strategies,
        @Schema(description = "ETF 벤치마크 비교 자산 설정 (생략 시 기존 값 유지)")
        @Valid BenchmarkRequest benchmarks
) {
    // currentBrokers: 현재 저장된 증권사 정책 — 요청에 없는 증권사 키는 현재 값을 유지하고(구 UI 호환), allowedIp는 항상 현재 값을 유지한다
    public RuntimeSettingsBundle toDomain(Map<Broker, BrokerSettings> currentBrokers) {
        // 모든 enum 키와 전략별 필수 필드를 먼저 변환·검증한 뒤 도메인 설정을 생성한다 — brokers/strategies는
        // trading-core 소유 정책(TradingPolicySettings), auth/benchmarks는 root 소유(RuntimeSettings)로 갈라 담는다.
        Map<Broker, BrokerSettings> brokerSettings = new EnumMap<>(currentBrokers);
        brokers.forEach((key, value) -> brokerSettings.put(key,
                new BrokerSettings(require(value, "broker").enabled(), allowedIpOf(currentBrokers, key))));
        Map<StrategyType, StrategyCreationSettings> strategySettings = new EnumMap<>(StrategyType.class);
        strategies.forEach((key, value) -> strategySettings.put(key,
                require(value, "strategy").toDomain(key)));
        BenchmarkSettings benchmarkSettings = benchmarks != null ? benchmarks.toDomain() : null;
        return new RuntimeSettingsBundle(new RuntimeSettings(auth.approvalRequired(), benchmarkSettings),
                new TradingPolicySettings(brokerSettings, strategySettings));
    }

    private static String allowedIpOf(Map<Broker, BrokerSettings> currentBrokers, Broker broker) {
        BrokerSettings current = currentBrokers.get(broker);
        return current != null ? current.allowedIp() : null;
    }

    private static <T> T require(T value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " settings are required");
        return value;
    }

    // 필드 허용값이 모두 양수인지 검증 — divisionCount/bandWidth/intervalWeeks 3곳 공통
    private static void requirePositive(List<? extends Number> allowedValues, String label) {
        if (allowedValues.stream().anyMatch(v -> v.doubleValue() <= 0)) {
            throw new IllegalArgumentException(label + " 허용값은 0보다 커야 합니다");
        }
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
        StrategyCreationSettings toDomain(StrategyType type) {
            FieldRequests value = require(fields, type.name() + " fields");
            return switch (type) {
                case INFINITE -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), value.divisionCountValue(), null, null, null);
                case PRIVACY -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, null, null, null);
                case VR -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, value.recurringModeValue(),
                        value.bandWidthValue(), value.intervalWeeksValue());
                case DCA -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, null, null, null);
            };
        }
    }

    public record FieldRequests(
            @Schema(description = "종목 생성 필드 설정")
            @Valid FieldRequest<StrategyTicker> ticker,
            @Schema(description = "무한매수 분할 수 필드 설정 (INFINITE 전용)")
            @Valid FieldRequest<Integer> divisionCount,
            @Schema(description = "VR 정기 입출금 방향 필드 설정 (VR 전용)")
            @Valid FieldRequest<RecurringMode> recurringMode,
            @Schema(description = "VR 밴드 폭 필드 설정 (%, VR 전용)")
            @Valid FieldRequest<BigDecimal> bandWidth,
            @Schema(description = "VR 롤오버 주기 필드 설정 (주 단위, VR 전용)")
            @Valid FieldRequest<Integer> intervalWeeks
    ) { // 전략별 관리자 생성 필드 입력
        StrategyFieldSettings<StrategyTicker> tickerValue() { return require(ticker, "ticker").toDomain(); }

        StrategyFieldSettings<Integer> divisionCountValue() {
            FieldRequest<Integer> value = require(divisionCount, "divisionCount");
            requirePositive(value.allowedValues(), "무한매수 분할 수(divisionCount)");
            return value.toDomain();
        }

        StrategyFieldSettings<RecurringMode> recurringModeValue() { return require(recurringMode, "recurringMode").toDomain(); }

        StrategyFieldSettings<BigDecimal> bandWidthValue() {
            FieldRequest<BigDecimal> value = require(bandWidth, "bandWidth");
            requirePositive(value.allowedValues(), "VR 밴드 폭(bandWidth)");
            return value.toDomain();
        }

        StrategyFieldSettings<Integer> intervalWeeksValue() {
            FieldRequest<Integer> value = require(intervalWeeks, "intervalWeeks");
            requirePositive(value.allowedValues(), "VR 리밸런싱 주기(intervalWeeks)");
            return value.toDomain();
        }
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

    public record BenchmarkRequest(
            @Schema(description = "ETF 벤치마크 비교 자산 설정")
            @NotNull @Valid BenchmarkFieldRequest<EtfBenchmarkSymbol> etf
    ) { // 벤치마크 비교 자산 관리자 입력 — EtfBenchmarkSymbol enum 직결로 시세 동기화 대상 밖 심볼 저장을 막는다
        BenchmarkSettings toDomain() {
            return new BenchmarkSettings(new BenchmarkFieldSettings<>(
                    etf.allowedValues().stream().map(Enum::name).toList(), etf.defaultValue().name()));
        }
    }

    public record BenchmarkFieldRequest<T>(
            @Schema(description = "허용 값 목록")
            @NotNull List<@NotNull T> allowedValues,
            @Schema(description = "비교 기본값")
            @NotNull T defaultValue
    ) { // 개별 벤치마크 필드 관리자 입력
    }
}
