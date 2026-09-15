package com.kista.admin.adapter.in.web;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.port.BrokerEnabledPort;
import com.kista.sharedkernel.port.StrategyCreationPolicyPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// trading-core의 StrategyCreationPolicyHttpAdapter/BrokerEnabledHttpAdapter가 소비하는 내부 전용
// 읽기 엔드포인트 — X-Internal-Token 인증. RuntimeSettingsService(admin)가 두 포트 모두 구현하며,
// 이 계획의 다른 내부 API는 전부 root->trading-core였으나 이 두 라우트는 ErrorLogInternalController와
// 같은 반대 방향(trading-core->root) — admin_runtime_settings가 root 소유 상태이기 때문
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/runtime-settings")
@RequiredArgsConstructor
public class RuntimeSettingsInternalController {

    private final StrategyCreationPolicyPort strategyCreationPolicyPort;
    private final BrokerEnabledPort brokerEnabledPort;

    @Operation(summary = "전략 타입별 생성 정책 조회", description = "trading-core StrategyCreationService 전용. 정책 미설정 시 204. X-Internal-Token 필수.")
    @GetMapping("/strategy-creation-policy/{type}")
    public ResponseEntity<StrategyCreationSettings> strategyCreationPolicy(@PathVariable StrategyType type) {
        return strategyCreationPolicyPort.find(type)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "증권사 신규 계좌 등록 활성화 여부 조회", description = "trading-core AccountService 전용. X-Internal-Token 필수.")
    @GetMapping("/broker-enabled/{broker}")
    public boolean brokerEnabled(@PathVariable Broker broker) {
        return brokerEnabledPort.enabled(broker);
    }
}
