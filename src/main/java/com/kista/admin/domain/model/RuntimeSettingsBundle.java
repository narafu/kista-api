package com.kista.admin.domain.model;

import com.kista.sharedkernel.TradingPolicySettings;

import java.util.Objects;

// 관리자 설정 화면·공개 런타임 설정이 한 번에 다루는 묶음 — root 소유 RuntimeSettings + trading-core 소유 TradingPolicySettings.
// 저장소가 프로세스별로 갈려 있어 원자적 갱신은 보장되지 않는다(AdminSettingsService 참고) — 읽기·편집 단위로만 묶는다.
public record RuntimeSettingsBundle(
        RuntimeSettings runtime,              // root 소유: 가입 승인·벤치마크
        TradingPolicySettings tradingPolicy   // trading-core 소유: 증권사 등록 허용·전략 생성 정책
) {
    public RuntimeSettingsBundle {
        Objects.requireNonNull(runtime, "runtime settings");
        Objects.requireNonNull(tradingPolicy, "trading policy settings");
    }

    public static RuntimeSettingsBundle defaults() {
        return new RuntimeSettingsBundle(RuntimeSettings.defaults(), TradingPolicySettings.defaults());
    }
}
