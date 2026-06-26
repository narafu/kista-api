package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.EnumMeta;
import com.kista.adapter.in.web.dto.MetaBundle;
import com.kista.adapter.in.web.dto.StrategyTypeMeta;
import com.kista.adapter.in.web.dto.TickerMeta;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
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
                getStrategyStatusList(), getCycleSeedTypeList()
        );
        return ResponseEntity.ok().cacheControl(CACHE).body(bundle);
    }

    private List<StrategyTypeMeta> getStrategyTypeList() {
        return Arrays.stream(Strategy.Type.values())
                .map(t -> StrategyTypeMeta.from(t, cycleStrategies.of(t)))
                .toList();
    }

    private List<TickerMeta> getTickerList() {
        return Arrays.stream(Strategy.Ticker.values())
                .map(TickerMeta::from)
                .toList();
    }

    private List<EnumMeta> getBrokerList() {
        return Arrays.stream(Account.Broker.values())
                .map(b -> new EnumMeta(b.name(), b.getLabel(), b.getShortLabel()))
                .toList();
    }

    private List<EnumMeta> getStrategyStatusList() {
        return Arrays.stream(Strategy.Status.values())
                .map(s -> new EnumMeta(s.name(), s.getLabel(), null))
                .toList();
    }

    private List<EnumMeta> getCycleSeedTypeList() {
        return Arrays.stream(Strategy.CycleSeedType.values())
                .map(t -> new EnumMeta(t.name(), t.getLabel(), null))
                .toList();
    }
}
