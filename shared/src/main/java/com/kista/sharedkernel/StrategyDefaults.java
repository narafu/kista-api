package com.kista.sharedkernel;

// Strategy 애그리게이트가 소유하던 기본값 상수 — PRIVACY/VR처럼 분할 수 설정이 없는 전략 타입의 고정 분할 수,
// admin(RuntimeSettings)·trading(리졸버/포지션 계산)·stats(BacktestEngine)가 공통으로 참조해 sharedkernel로 추출했다.
public final class StrategyDefaults {

    // INFINITE에선 미입력 시 채우는 기본값(admin 설정으로 변경 가능), PRIVACY/VR에선 설정 항목이 없는 고정 분할 수
    public static final int DEFAULT_DIVISION_COUNT = 20;

    private StrategyDefaults() {}
}
