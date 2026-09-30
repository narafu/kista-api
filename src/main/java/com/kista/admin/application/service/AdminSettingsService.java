package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingPolicyPort;
import com.kista.admin.application.usecase.AdminSettingsUseCase;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.admin.domain.model.RuntimeSettingsBundle;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingPolicySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// 관리자 설정 화면의 조합 서비스 — root 소유 설정(RuntimeSettingsService, 트랜잭션)과 trading-core 소유 정책
// (TradingPolicyPort, 내부 HTTP)을 한 묶음으로 읽고 쓴다. 관리자 조회는 엄격하다(trading-core 장애가 그대로 오류로
// 드러나야 관리자가 기본값을 실제 설정으로 오인하지 않는다) — 공개 조회의 기본값 강등은 RuntimeConfigService 담당.
// 트랜잭션을 걸지 않는다: HTTP 호출을 DB 트랜잭션 안에 두지 않기 위해서다. 갱신 순서는 trading 정책 → root 설정이며
// 후자가 실패하면 정책만 바뀐 부분 반영이 남는다 — PUT은 멱등이라 관리자가 재시도하면 수렴한다.
@Service
@RequiredArgsConstructor
class AdminSettingsService implements AdminSettingsUseCase {

    private final RuntimeSettingsService runtimeSettingsService; // root 소유 설정 트랜잭션 경계
    private final TradingPolicyPort tradingPolicyPort; // trading-core 소유 정책 위임
    private final AuditLogPort auditLogPort; // 관리자 설정 변경 감사 로그 포트

    @Override
    public RuntimeSettingsBundle getSettings() {
        return new RuntimeSettingsBundle(runtimeSettingsService.load(), tradingPolicyPort.load());
    }

    @Override
    public RuntimeSettingsBundle updateSettings(UUID adminId, RuntimeSettingsBundle settings, boolean benchmarksProvided) {
        // 정책 소유자(trading-core)에 먼저 반영 — 거절(400)되면 root 설정은 손대지 않는다
        TradingPolicySettings previousPolicy = tradingPolicyPort.load();
        TradingPolicySettings savedPolicy = tradingPolicyPort.replace(settings.tradingPolicy());
        RuntimeSettingsService.Updated runtime = runtimeSettingsService.update(settings.runtime(), benchmarksProvided);
        auditLogPort.log(adminId, "RUNTIME_SETTINGS_UPDATE", "RUNTIME_SETTINGS", null,
                diff(runtime.previous(), runtime.saved(), previousPolicy, savedPolicy));
        return new RuntimeSettingsBundle(runtime.saved(), savedPolicy);
    }

    // approvalRequired 외에 브로커·전략 활성화 상태 변경도 감사 로그에서 추적 가능하도록 diff만 담는다.
    private static Map<String, Object> diff(RuntimeSettings previous, RuntimeSettings saved,
                                            TradingPolicySettings previousPolicy, TradingPolicySettings savedPolicy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequired", saved.approvalRequired());
        Map<String, Boolean> brokerChanges = new LinkedHashMap<>();
        for (Broker broker : Broker.values()) {
            boolean before = previousPolicy.brokerEnabled(broker);
            boolean after = savedPolicy.brokerEnabled(broker);
            if (before != after) brokerChanges.put(broker.name(), after);
        }
        if (!brokerChanges.isEmpty()) payload.put("brokers", brokerChanges);
        Map<String, Boolean> strategyChanges = new LinkedHashMap<>();
        for (StrategyType type : StrategyType.values()) {
            boolean before = previousPolicy.strategy(type).enabled();
            boolean after = savedPolicy.strategy(type).enabled();
            if (before != after) strategyChanges.put(type.name(), after);
        }
        if (!strategyChanges.isEmpty()) payload.put("strategies", strategyChanges);
        return payload;
    }
}
