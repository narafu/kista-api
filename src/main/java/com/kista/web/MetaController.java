package com.kista.web;

import com.kista.web.dto.EnumMeta;
import com.kista.web.dto.MetaBundle;
import com.kista.web.dto.StrategyTypeMeta;
import com.kista.web.dto.TickerMeta;
import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.Market;
import com.kista.sharedkernel.Broker;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@Tag(name = "메타", description = "UI 렌더링용 enum 메타데이터 (라벨, 설명, 유효값 목록)")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    private static final CacheControl CACHE = CacheControl.maxAge(1, TimeUnit.HOURS); // 1시간 캐시

    private final CycleOrderStrategies cycleStrategies;

    @Operation(summary = "전체 메타 번들 조회")
    @GetMapping
    public ResponseEntity<MetaBundle> getBundle() {
        MetaBundle bundle = new MetaBundle(
                getStrategyTypeList(), getTickerList(), getBrokerList(),
                getStrategyStatusList(), getCycleSeedTypeList(),
                getAssetClassList(), getMarketList(), getFinanceAccountTypeList(), getFinanceCategoryTypeList()
        );
        return ResponseEntity.ok().cacheControl(CACHE).body(bundle);
    }

    private List<StrategyTypeMeta> getStrategyTypeList() {
        return Arrays.stream(StrategyType.values())
                .map(t -> StrategyTypeMeta.from(t, cycleStrategies.of(t)))
                .toList();
    }

    private List<TickerMeta> getTickerList() {
        return Arrays.stream(StrategyTicker.values())
                .map(TickerMeta::from)
                .toList();
    }

    private List<EnumMeta> getBrokerList() {
        return Arrays.stream(Broker.values())
                .map(b -> new EnumMeta(b.name(), b.getLabel(), b.getShortLabel()))
                .toList();
    }

    private List<EnumMeta> getStrategyStatusList() {
        return toEnumMeta(StrategyStatus.values(), StrategyStatus::getLabel);
    }

    private List<EnumMeta> getCycleSeedTypeList() {
        return toEnumMeta(StrategyCycleSeedType.values(), StrategyCycleSeedType::getLabel);
    }

    private List<EnumMeta> getAssetClassList() {
        return toEnumMeta(AssetClass.values(), AssetClass::getLabel);
    }

    private List<EnumMeta> getMarketList() {
        return toEnumMeta(Market.values(), Market::getLabel);
    }

    private List<EnumMeta> getFinanceAccountTypeList() {
        return toEnumMeta(FinanceAccount.Type.values(), FinanceAccount.Type::getLabel);
    }

    private List<EnumMeta> getFinanceCategoryTypeList() {
        return toEnumMeta(FinanceCategory.Type.values(), FinanceCategory.Type::getLabel);
    }

    private static <E extends Enum<E>> List<EnumMeta> toEnumMeta(E[] values, Function<E, String> label) {
        return Arrays.stream(values)
                .map(v -> new EnumMeta(v.name(), label.apply(v), null))
                .toList();
    }
}
