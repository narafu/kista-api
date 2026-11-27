package com.kista.api.admin.application.service;

import com.kista.api.admin.application.port.output.TradingPolicyPort;
import com.kista.api.admin.domain.model.RuntimeSettingsBundle;
import com.kista.api.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.shared.kernel.TradingPolicySettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

// 공개 런타임 설정(메타 번들 GET /api/meta, 로그인 전 화면) 조회 — 관리자 조회(AdminSettingsService)와 달리
// trading-core 정책 조회가 실패하면(연결 거부·타임아웃·5xx, 배포 전환기 등) 500으로 무너뜨리지 않고 기본 정책으로
// 강등한다. 정책 집행은 어차피 trading-core가 하므로 UI가 잠시 기본값을 보여도 등록이 실제 정책을 우회하지 못한다.
@Slf4j
@Service
@NamedInterface("api") // 공개 런타임 설정 — 메타 번들(api.web MetaController)이 같은 경로로 읽는다
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final RuntimeSettingsService runtimeSettingsService; // root 소유 설정 (DB, 항상 가용)
    private final TradingPolicyPort tradingPolicyPort; // trading-core 소유 정책 (내부 HTTP, 장애 시 기본값)

    // 공개 런타임 설정 조회 — root 설정 + trading-core 정책 묶음
    public RuntimeSettingsBundle getSettings() {
        return new RuntimeSettingsBundle(runtimeSettingsService.load(), tradingPolicyOrDefaults());
    }

    private TradingPolicySettings tradingPolicyOrDefaults() {
        try {
            return tradingPolicyPort.load();
        } catch (TradingPolicyUnavailableException e) {
            log.warn("공개 런타임 설정: trading-core 정책 조회 실패로 기본 정책 응답 — {}", e.getMessage());
            return TradingPolicySettings.defaults();
        }
    }
}
