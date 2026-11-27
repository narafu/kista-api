package com.kista.api.web;

import com.kista.api.admin.application.service.RuntimeConfigService;
import com.kista.api.admin.domain.model.RuntimeSettingsBundle;
import com.kista.api.web.dto.BenchmarkEtfMeta;
import com.kista.api.web.dto.BrokerMeta;
import com.kista.api.web.dto.DcaIndicatorMeta;
import com.kista.api.web.dto.MetaBundle;
import com.kista.api.web.dto.StrategyTypeMeta;
import com.kista.api.web.dto.TickerMeta;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.TradingPolicySettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "메타", description = "UI 렌더링용 enum 메타데이터 (라벨, 설명, 유효값 목록)와 등록 기본값·허용 범위")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    private final RuntimeConfigService runtimeConfigService; // 런타임 설정 — /api/runtime-config와 같은 경로(기본 정책 강등 포함)

    @Operation(summary = "전체 메타 번들 조회")
    @GetMapping
    public ResponseEntity<MetaBundle> getBundle() {
        RuntimeSettingsBundle settings = runtimeConfigService.getSettings();
        TradingPolicySettings policy = settings.tradingPolicy();

        // 번들 필드 이름 → 값 (필드 이름은 MetaBundle 컴포넌트 이름 — enum 등록부가 단일 출처)
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("strategyTypes", Arrays.stream(StrategyType.values())
                .map(t -> StrategyTypeMeta.from(t, policy.strategy(t))).toList());
        values.put("tickers", Arrays.stream(StrategyTicker.values()).map(TickerMeta::from).toList());
        values.put("brokers", Arrays.stream(Broker.values())
                .map(b -> BrokerMeta.from(b, policy.brokerEnabled(b))).toList());
        values.putAll(MetaEnumRegistry.toMetaByField());
        values.put("dcaIndicators", Arrays.stream(DcaIndicator.values()).map(DcaIndicatorMeta::from).toList());
        values.put("approvalRequired", settings.runtime().approvalRequired());
        values.put("benchmarkEtf", new BenchmarkEtfMeta(
                settings.runtime().benchmarks().etf().allowedValues(), settings.runtime().benchmarks().etf().defaultValue()));

        // 런타임 값이 섞여 있어 서버·브라우저 모두 캐시하지 않는다 (runtime-config와 같은 방식)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MetaBundle.assemble(values));
    }
}
