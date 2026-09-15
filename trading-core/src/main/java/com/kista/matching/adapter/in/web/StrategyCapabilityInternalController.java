package com.kista.matching.adapter.in.web;

import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.sharedkernel.StrategyType;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// MetaController(root)의 matching.CycleOrderStrategy 직접 참조를 없애기 위한 내부 전용 엔드포인트
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/matching")
@RequiredArgsConstructor
public class StrategyCapabilityInternalController {

    private final CycleOrderStrategies cycleStrategies;

    @GetMapping("/strategy-capabilities/{type}")
    public StrategyCapabilityResponse get(@PathVariable StrategyType type) {
        return StrategyCapabilityResponse.from(cycleStrategies.of(type));
    }
}
