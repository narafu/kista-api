package com.kista.admin.domain.model;

// root가 소유하는 런타임 설정 — 가입 승인 정책(user 불변식) + ETF 벤치마크 비교 자산(stats 소비).
// 증권사 신규 등록 허용·전략 생성 정책은 집행 주체인 trading-core가 소유한다(sharedkernel.TradingPolicySettings) —
// 관리자 화면·공개 설정 응답에서는 RuntimeSettingsBundle이 둘을 합쳐 보여준다.
public record RuntimeSettings(
        boolean approvalRequired, // 신규 가입 승인 필요 여부
        BenchmarkSettings benchmarks // ETF 벤치마크 비교 자산 설정
) {
    public RuntimeSettings {
        // benchmarks 도입 이전에 저장된 행에는 이 필드가 없으므로 역직렬화 시 기본값으로 보충한다.
        if (benchmarks == null) benchmarks = BenchmarkSettings.defaults();
    }

    // 현재 운영 동작을 보존하는 기본값 — 저장 행이 없을 때 적용
    public static RuntimeSettings defaults() {
        return new RuntimeSettings(true, BenchmarkSettings.defaults());
    }

    // benchmarks만 교체 — 요청에서 benchmarks가 생략됐을 때 기존 값을 유지하는 데 쓴다
    public RuntimeSettings withBenchmarks(BenchmarkSettings newBenchmarks) {
        return new RuntimeSettings(approvalRequired, newBenchmarks);
    }
}
