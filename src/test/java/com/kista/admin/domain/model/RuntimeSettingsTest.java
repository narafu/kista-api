package com.kista.admin.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 증권사 등록 허용·전략 생성 정책은 sharedkernel.TradingPolicySettings(trading-core 소유)로 이동했다 —
// 그 검증은 shared 모듈의 TradingPolicySettingsTest가 담당하고 여기서는 root 소유 필드만 본다.
class RuntimeSettingsTest {

    @Test
    void defaultsPreserveCurrentRuntimeBehavior() {
        RuntimeSettings settings = RuntimeSettings.defaults();

        assertThat(settings.approvalRequired()).isTrue();
        assertThat(settings.benchmarks()).isEqualTo(BenchmarkSettings.defaults());
    }

    @Test
    void nullBenchmarksFallBackToDefaults() {
        // benchmarks 도입 이전 저장 행(필드 없음)의 역직렬화와 같은 경로 — 기본값으로 보충된다.
        RuntimeSettings settings = new RuntimeSettings(false, null);

        assertThat(settings.approvalRequired()).isFalse();
        assertThat(settings.benchmarks()).isEqualTo(BenchmarkSettings.defaults());
    }

    @Test
    void withBenchmarksReplacesOnlyBenchmarks() {
        BenchmarkSettings custom = new BenchmarkSettings(new BenchmarkFieldSettings<>(List.of("VOO"), "VOO"));

        RuntimeSettings replaced = new RuntimeSettings(false, null).withBenchmarks(custom);

        assertThat(replaced.approvalRequired()).isFalse();
        assertThat(replaced.benchmarks()).isEqualTo(custom);
    }

    @Test
    void bundleDefaultsCombineRootAndTradingDefaults() {
        RuntimeSettingsBundle bundle = RuntimeSettingsBundle.defaults();

        assertThat(bundle.runtime()).isEqualTo(RuntimeSettings.defaults());
        assertThat(bundle.tradingPolicy()).isEqualTo(com.kista.sharedkernel.TradingPolicySettings.defaults());
    }
}
