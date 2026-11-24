package com.kista.api.web;

import com.kista.api.benchmark.domain.model.BenchmarkAssetType;
import com.kista.api.benchmark.domain.model.EtfBenchmarkSymbol;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceGroupInvitation;
import com.kista.api.finance.domain.model.Market;
import com.kista.api.web.dto.EnumMeta;
import com.kista.shared.kernel.ApiKeyExpirySource;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.DcaExecutionStatus;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.MarketSession;
import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.NotificationType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 메타 번들에 싣는 enum 등록부 — "enum 클래스 → JSON 필드 이름 → 라벨" 단일 출처.
// 새 enum이 web DTO에 나가면 여기 한 줄을 더한다(MetaEnumCoverageTest가 강제). 필드 이름은 MetaBundle 컴포넌트 이름과 같아야 한다.
public final class MetaEnumRegistry {

    // enum 하나의 등록 정보 — 라벨은 enum의 getLabel(), 설명은 enum에 설명 원천이 있을 때만(없으면 null)
    public record Spec<E extends Enum<E>>(String field, Class<E> type, Function<E, String> label,
                                          @Nullable Function<E, String> description) {
        public Spec(String field, Class<E> type, Function<E, String> label) {
            this(field, type, label, null);
        }

        List<EnumMeta> toMeta() {
            return Arrays.stream(type.getEnumConstants())
                    .map(v -> new EnumMeta(v.name(), label.apply(v), description == null ? null : description.apply(v)))
                    .toList();
        }
    }

    // 라벨만 있는 단순 enum 등록 (JSON 필드 이름 = 번들 필드)
    public static final List<Spec<?>> SPECS = List.of(
            new Spec<>("strategyStatuses", StrategyStatus.class, StrategyStatus::getLabel),
            new Spec<>("cycleSeedTypes", StrategyCycleSeedType.class, StrategyCycleSeedType::getLabel),
            new Spec<>("assetClasses", AssetClass.class, AssetClass::getLabel),
            new Spec<>("markets", Market.class, Market::getLabel),
            new Spec<>("financeAccountTypes", FinanceAccount.Type.class, FinanceAccount.Type::getLabel),
            new Spec<>("financeCategoryTypes", FinanceCategory.Type.class, FinanceCategory.Type::getLabel),
            new Spec<>("orderTypes", OrderType.class, OrderType::getLabel),
            new Spec<>("orderDirections", OrderDirection.class, OrderDirection::getLabel),
            new Spec<>("orderStatuses", OrderStatus.class, OrderStatus::getLabel),
            new Spec<>("orderTimings", OrderTiming.class, OrderTiming::getLabel),
            new Spec<>("dcaFrequencies", DcaFrequency.class, DcaFrequency::getLabel),
            new Spec<>("dcaOperators", DcaOperator.class, DcaOperator::getLabel),
            new Spec<>("dcaSkipReasons", DcaSkipReason.class, DcaSkipReason::getLabel),
            new Spec<>("dcaExecutionStatuses", DcaExecutionStatus.class, DcaExecutionStatus::getLabel),
            new Spec<>("dcaSizingTypes", DcaSizingType.class, DcaSizingType::getLabel),
            new Spec<>("dcaShortfallPolicies", DcaShortfallPolicy.class, DcaShortfallPolicy::getLabel),
            new Spec<>("userStatuses", UserStatus.class, UserStatus::getLabel),
            new Spec<>("userRoles", UserRole.class, UserRole::getLabel),
            new Spec<>("recurringModes", RecurringMode.class, RecurringMode::getLabel),
            new Spec<>("currencies", Currency.class, Currency::getLabel),
            new Spec<>("apiKeyExpirySources", ApiKeyExpirySource.class, ApiKeyExpirySource::getLabel),
            new Spec<>("notificationChannels", NotificationChannel.class, NotificationChannel::getLabel),
            new Spec<>("notificationTypes", NotificationType.class, NotificationType::getLabel),
            new Spec<>("marketSessions", MarketSession.class, MarketSession::getLabel),
            new Spec<>("benchmarkAssetTypes", BenchmarkAssetType.class, BenchmarkAssetType::getLabel),
            new Spec<>("etfBenchmarkSymbols", EtfBenchmarkSymbol.class, EtfBenchmarkSymbol::name, EtfBenchmarkSymbol::description), // 라벨=심볼, 설명=ETF 정식 명칭
            new Spec<>("financeInvitationStatuses", FinanceGroupInvitation.Status.class, FinanceGroupInvitation.Status::getLabel),
            new Spec<>("nextOrdersSkipReasons", NextOrdersPreview.SkipReason.class, NextOrdersPreview.SkipReason::getLabel)
    );

    // 전용 DTO(StrategyTypeMeta·TickerMeta·BrokerMeta·DcaIndicatorMeta)로 싣는 enum — 필드 구조가 달라 SPECS 밖
    private static final Set<Class<? extends Enum<?>>> DEDICATED =
            Set.of(StrategyType.class, StrategyTicker.class, Broker.class, DcaIndicator.class);

    private MetaEnumRegistry() {}

    // 필드 이름 → EnumMeta 목록 (등록 순서 유지)
    public static Map<String, List<EnumMeta>> toMetaByField() {
        Map<String, List<EnumMeta>> result = new LinkedHashMap<>();
        SPECS.forEach(spec -> result.put(spec.field(), spec.toMeta()));
        return result;
    }

    // 번들에 실리는 모든 enum 클래스 — 가드 테스트(MetaEnumCoverageTest)가 읽는다
    public static Set<Class<?>> carriedEnums() {
        return Stream.concat(SPECS.stream().map(Spec::type), DEDICATED.stream())
                .collect(Collectors.toSet());
    }
}
