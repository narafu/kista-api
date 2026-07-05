package com.kista.domain.model.strategy;

import java.math.BigDecimal;

import static java.math.RoundingMode.HALF_UP;

// VR 전략 주문 계산에 필요한 입력값 묶음 — 한 사이클·하루의 포지션 스냅샷
public record VrPosition(
        AccountBalance balance,  // 현재 잔고 (보유 수량·평균가·예수금)
        BigDecimal value,        // 현재 V값 (실력 기준선)
        BigDecimal bandWidth,    // 밴드 폭 (% 단위, 예: 15.00 = 15%)
        BigDecimal poolLimit,    // 이 사이클에서 사용 가능한 pool 상한 (USD)
        BigDecimal poolUsed      // 이미 사용한 pool 누적 금액 (USD)
) {
    private static final int MONEY_SCALE = 2; // 금액·가격 반올림 자리수

    // --- 기본 조회 ---

    // 현재 보유 수량
    public int holdings() {
        return balance.holdings();
    }

    // 현재 예수금 (= usdDeposit)
    public BigDecimal pool() {
        return balance.usdDeposit();
    }

    // --- 밴드 계산 ---

    // 매수 사다리 하단 기준선 = V × (1 − bandWidth/100)
    public BigDecimal lowerBand() {
        BigDecimal rate = bandWidth.divide(BigDecimal.valueOf(100), 10, HALF_UP);
        return value.multiply(BigDecimal.ONE.subtract(rate)).setScale(MONEY_SCALE, HALF_UP);
    }

    // 매도 사다리 상단 기준선 = V × (1 + bandWidth/100)
    public BigDecimal upperBand() {
        BigDecimal rate = bandWidth.divide(BigDecimal.valueOf(100), 10, HALF_UP);
        return value.multiply(BigDecimal.ONE.add(rate)).setScale(MONEY_SCALE, HALF_UP);
    }

    // --- 사다리 단가 계산 ---

    // m번째 매수 단가 = lowerBand ÷ (holdings + m − 1), scale=2 HALF_UP
    // 주의: divisor < 1 여부는 호출측에서 확인 후 skip 처리 (holdings=0, m=1 → divisor=0)
    public BigDecimal buyPrice(int m) {
        int divisor = holdings() + m - 1;
        return lowerBand().divide(BigDecimal.valueOf(divisor), MONEY_SCALE, HALF_UP);
    }

    // s번째 매도 단가 = upperBand ÷ (holdings − s + 1), scale=2 HALF_UP
    public BigDecimal sellPrice(int s) {
        int divisor = holdings() - s + 1;
        return upperBand().divide(BigDecimal.valueOf(divisor), MONEY_SCALE, HALF_UP);
    }

    // --- V값 갱신 (실력공식) ---

    // V' = V + pool/G + recurringAmount + (평가금 − V) / (2√G)
    // 평가금 = holdings × 종가 (evaluation)
    // 최종 결과 scale=2 HALF_UP, 중간 계산은 고정밀(scale=10) 사용
    public static BigDecimal nextValue(BigDecimal value, BigDecimal pool, int gradient,
                                       int recurringAmount, BigDecimal evaluation) {
        // √G — Math.sqrt로 double 계산 후 BigDecimal 변환
        BigDecimal sqrtG = BigDecimal.valueOf(Math.sqrt(gradient));

        // pool/G
        BigDecimal poolPart = pool.divide(BigDecimal.valueOf(gradient), 10, HALF_UP);

        // recurringAmount (추가 입금/인출)
        BigDecimal recurringPart = BigDecimal.valueOf(recurringAmount);

        // (평가금 − V) / (2√G)
        BigDecimal evaluationDiff = evaluation.subtract(value);
        BigDecimal denom = BigDecimal.valueOf(2).multiply(sqrtG);
        BigDecimal adjustPart = evaluationDiff.divide(denom, 10, HALF_UP);

        return value.add(poolPart).add(recurringPart).add(adjustPart).setScale(MONEY_SCALE, HALF_UP);
    }
}
