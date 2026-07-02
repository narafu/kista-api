package com.kista.application.service.trading;

import com.kista.domain.model.strategy.Strategy;
import java.math.BigDecimal;
import java.util.Optional;


// 사이클 회전 시 목표 시드 결정 정책 — 잔고검증 ON/OFF 분기를 명시적 정책으로 표현
// package-private — application/service 패키지 전용
@FunctionalInterface
interface SeedResolutionPolicy {

    // 가용 잔고를 반환. 증권사 조회 실패·오류 시 Optional.empty() (호출부는 rotate 중단)
    Optional<BigDecimal> resolveAvailableBalance(Strategy strategy, BigDecimal maintainSeed, BigDecimal maxSeed);
}
