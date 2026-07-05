package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.util.UUID;

// VR 전략 버전 상세 — strategy_vr_version 테이블과 매핑
public record StrategyVrDetail(
        UUID strategyVersionId, // FK → strategy_version.id
        int intervalWeeks,      // 리밸런싱 주기 (주 단위, 1 이상)
        BigDecimal bandWidth,   // 매수·매도 사다리 밴드 폭 (% 단위, 예: 15.00)
        int recurringAmount     // 주기당 추가 예수금 (USD, 음수=인출, 0=없음)
) {

    // gradient(G): 실력공식 경사 계수 — 인출(음수) 시 고난도(G=20), 그 외 기본(G=10)
    public int gradient() {
        return recurringAmount < 0 ? 20 : 10;
    }

    // poolLimitRate: 사용 가능한 pool 상한 비율 — 입금/출금 방향에 따라 3단계
    // 입금(+): 높은 한도(75%), 없음(0): 기본(50%), 인출(-): 낮은 한도(25%)
    public BigDecimal poolLimitRate() {
        if (recurringAmount > 0) return new BigDecimal("0.75");
        if (recurringAmount == 0) return new BigDecimal("0.50");
        return new BigDecimal("0.25");
    }
}
