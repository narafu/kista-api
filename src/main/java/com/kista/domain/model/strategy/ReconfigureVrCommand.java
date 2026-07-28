package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// VR 전략 운영 중 재설정 인바운드 파라미터 — 모두 nullable(미지정=현재 활성 버전 값 상속/미주입)
public record ReconfigureVrCommand(
        BigDecimal bandWidth,             // 밴드 폭 (%) — 미지정 시 현재값 유지
        Integer intervalWeeks,            // 리밸런싱 주기 (주) — 미지정 시 현재값 유지
        Integer recurringAmount,          // 주기당 추가 예수금 (USD, 음수=인출) — 미지정 시 현재값 유지
        Integer initialGradient,          // 램프: 경과 0주 gradient — 미지정 시 현재값 유지
        Integer gGraceWeeks,              // 램프: gradient 유예 주수 — 미지정 시 현재값 유지
        Integer gStepWeeks,               // 램프: gradient 단계 주기 — 미지정 시 현재값 유지
        Integer gMax,                     // 램프: gradient 상한 — 미지정 시 현재값 유지
        BigDecimal initialPoolLimitRate,  // 램프: 경과 0주 poolLimitRate — 미지정 시 현재값 유지
        Integer pGraceWeeks,              // 램프: poolLimitRate 유예 주수 — 미지정 시 현재값 유지
        Integer pStepWeeks,               // 램프: poolLimitRate 단계 주기 — 미지정 시 현재값 유지
        BigDecimal poolLimitFloor,        // 램프: poolLimitRate 하한 — 미지정 시 현재값 유지
        Integer injectShares,             // 자본 주입: 매수단가 편입할 주식 수 (null=주입 없음)
        BigDecimal injectSharePrice,      // 자본 주입: 위 주식의 매수단가 (injectShares>0이면 필수)
        BigDecimal injectDeposit,         // 자본 주입: 추가 예수금 (USD, null/0=주입 없음)
        Integer withdrawShares,           // 자본 인출: 보유 수량에서 차감할 주식 수 (null=인출 없음, 보유 수량 초과 불가)
        BigDecimal withdrawDeposit        // 자본 인출: 예수금 차감액 (USD, null/0=인출 없음, 보유 예수금 초과 불가)
) {}
