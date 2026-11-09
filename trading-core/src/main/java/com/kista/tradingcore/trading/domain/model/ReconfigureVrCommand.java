package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.trading.domain.creation.VrRampInput;

import java.math.BigDecimal;

// VR 전략 운영 중 재설정 인바운드 파라미터 — 모두 nullable(미지정=현재 활성 버전 값 상속/미주입)
public record ReconfigureVrCommand(
        BigDecimal bandWidth,             // 밴드 폭 (%) — 미지정 시 현재값 유지
        Integer intervalWeeks,            // 리밸런싱 주기 (주) — 미지정 시 현재값 유지
        Integer recurringAmount,          // 주기당 추가 예수금 (USD, 음수=인출) — 미지정 시 현재값 유지
        VrRampInput vrRamp,               // 램프 8파라미터 — 미지정 필드는 현재값 유지 (null이면 NONE)
        Integer injectShares,             // 투자금 추가: 매수단가 편입할 주식 수 (null=추가 없음)
        BigDecimal injectSharePrice,      // 투자금 추가: 위 주식의 매수단가 (injectShares>0이면 필수)
        BigDecimal injectDeposit,         // 투자금 추가: 추가 예수금 (USD, null/0=추가 없음)
        Integer withdrawShares,           // 투자금 인출: 보유 수량에서 차감할 주식 수 (null=인출 없음, 보유 수량 초과 불가)
        BigDecimal withdrawDeposit        // 투자금 인출: 예수금 차감액 (USD, null/0=인출 없음, 보유 예수금 초과 불가)
) {
    public ReconfigureVrCommand {
        if (vrRamp == null) {
            vrRamp = VrRampInput.NONE; // 램프 전부 현재값 유지
        }
    }

    // 투자금 조정 입력 검증 — 음수 거부, 인출은 현재 보유 수량·예수금 이내(wholeShares: 정수 보유 수량, holdings: 문구 표시용)
    public void validateCapitalAdjustment(int wholeShares, BigDecimal holdings, BigDecimal deposit) {
        if (injectSharesOrZero() < 0) {
            throw new IllegalArgumentException("투자금 추가 주식 수는 0 이상이어야 합니다.");
        }
        if (injectDepositOrZero().signum() < 0) {
            throw new IllegalArgumentException("투자금 추가 예수금은 0 이상이어야 합니다.");
        }
        if (withdrawSharesOrZero() < 0) {
            throw new IllegalArgumentException("투자금 인출 주식 수는 0 이상이어야 합니다.");
        }
        if (withdrawSharesOrZero() > wholeShares) {
            throw new IllegalArgumentException(
                    "투자금 인출 주식 수는 보유 수량(" + holdings + ")을 초과할 수 없습니다.");
        }
        if (withdrawDepositOrZero().signum() < 0) {
            throw new IllegalArgumentException("투자금 인출 예수금은 0 이상이어야 합니다.");
        }
        if (withdrawDepositOrZero().compareTo(deposit) > 0) {
            throw new IllegalArgumentException(
                    "투자금 인출 예수금은 보유 예수금(" + deposit + ")을 초과할 수 없습니다.");
        }
    }

    // 추가 주식 수 — 미지정이면 0
    public int injectSharesOrZero() {
        return injectShares != null ? injectShares : 0;
    }

    // 추가 예수금 — 미지정이면 0
    public BigDecimal injectDepositOrZero() {
        return injectDeposit != null ? injectDeposit : BigDecimal.ZERO;
    }

    // 인출 주식 수 — 미지정이면 0
    public int withdrawSharesOrZero() {
        return withdrawShares != null ? withdrawShares : 0;
    }

    // 인출 예수금 — 미지정이면 0
    public BigDecimal withdrawDepositOrZero() {
        return withdrawDeposit != null ? withdrawDeposit : BigDecimal.ZERO;
    }

    // 순 주식 증감 — 추가 − 인출
    public int netShares() {
        return injectSharesOrZero() - withdrawSharesOrZero();
    }
}
