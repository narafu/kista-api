package com.kista.api.web;

import com.kista.api.web.dto.BrokerMeta;
import com.kista.api.web.dto.DcaIndicatorMeta;
import com.kista.api.web.dto.EnumMeta;
import com.kista.api.web.dto.MetaBundle;
import com.kista.api.web.dto.StrategyTypeMeta;
import com.kista.api.web.dto.TickerMeta;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.Market;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@Tag(name = "메타", description = "UI 렌더링용 enum 메타데이터 (라벨, 설명, 유효값 목록)")
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private static final CacheControl CACHE = CacheControl.maxAge(1, TimeUnit.HOURS); // 1시간 캐시

    @Operation(summary = "전체 메타 번들 조회")
    @GetMapping
    public ResponseEntity<MetaBundle> getBundle() {
        MetaBundle bundle = new MetaBundle(
                getStrategyTypeList(), getTickerList(), getBrokerList(),
                toEnumMeta(StrategyStatus.values(), StrategyStatus::getLabel),
                toEnumMeta(StrategyCycleSeedType.values(), StrategyCycleSeedType::getLabel),
                toEnumMeta(AssetClass.values(), AssetClass::getLabel),
                toEnumMeta(Market.values(), Market::getLabel),
                toEnumMeta(FinanceAccount.Type.values(), FinanceAccount.Type::getLabel),
                toEnumMeta(FinanceCategory.Type.values(), FinanceCategory.Type::getLabel),
                toEnumMeta(OrderType.values(), OrderType::getLabel),
                toEnumMeta(OrderDirection.values(), OrderDirection::getLabel),
                toEnumMeta(DcaFrequency.values(), DcaFrequency::getLabel),
                toEnumMeta(DcaOperator.values(), DcaOperator::getLabel),
                toEnumMeta(DcaSkipReason.values(), DcaSkipReason::getLabel),
                toEnumMeta(DcaSizingType.values(), DcaSizingType::getLabel),
                toEnumMeta(DcaShortfallPolicy.values(), DcaShortfallPolicy::getLabel),
                Arrays.stream(DcaIndicator.values()).map(DcaIndicatorMeta::from).toList()
        );
        return ResponseEntity.ok().cacheControl(CACHE).body(bundle);
    }

    // capability는 shared.kernel 상수(StrategyType.capability()) — 다른 프로세스 호출 없음
    private List<StrategyTypeMeta> getStrategyTypeList() {
        return Arrays.stream(StrategyType.values())
                .map(StrategyTypeMeta::from)
                .toList();
    }

    private List<TickerMeta> getTickerList() {
        return Arrays.stream(StrategyTicker.values())
                .map(TickerMeta::from)
                .toList();
    }

    private List<BrokerMeta> getBrokerList() {
        return Arrays.stream(Broker.values())
                .map(BrokerMeta::from)
                .toList();
    }

    private static <E extends Enum<E>> List<EnumMeta> toEnumMeta(E[] values, Function<E, String> label) {
        return Arrays.stream(values)
                .map(v -> new EnumMeta(v.name(), label.apply(v), null))
                .toList();
    }
}
