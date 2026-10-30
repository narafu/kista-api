package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.trading.domain.creation.VrRampInput;

import java.math.BigDecimal;

// VR 전략 운영 중 재설정 인바운드 파라미터 — 모두 nullable(미지정=현재 활성 버전 값 상속/미주입)
public record ReconfigureVrCommand(
        BigDecimal bandWidth,             // 밴드 폭 (%) — 미지정 시 현재값 유지
        Integer intervalWeeks,            // 리밸런싱 주기 (주) — 미지정 시 현재값 유지
        Integer recurringAmount,          // 주기당 추가 예수금 (USD, 음수=인출) — 미지정 시 현재값 유지
        VrRampInput vrRamp,               // 램프 8파라미터 — 미지정 필드는 현재값 유지 (null이면 NONE)
        Integer injectShares,             // 자본 주입: 매수단가 편입할 주식 수 (null=주입 없음)
        BigDecimal injectSharePrice,      // 자본 주입: 위 주식의 매수단가 (injectShares>0이면 필수)
        BigDecimal injectDeposit,         // 자본 주입: 추가 예수금 (USD, null/0=주입 없음)
        Integer withdrawShares,           // 자본 인출: 보유 수량에서 차감할 주식 수 (null=인출 없음, 보유 수량 초과 불가)
        BigDecimal withdrawDeposit        // 자본 인출: 예수금 차감액 (USD, null/0=인출 없음, 보유 예수금 초과 불가)
) {
    public ReconfigureVrCommand {
        if (vrRamp == null) {
            vrRamp = VrRampInput.NONE; // 램프 전부 현재값 유지
        }
    }
}
