package com.kista.shared.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

// 코인 거래소 실행 규칙 — 거래소 포트가 반환하고 매칭 커널이 입력으로 받는다(단일 타입)
public record CryptoTradingRules(
        BigDecimal feeRate,      // 수수료율(0.0005 = 0.05%)
        FeeSide feeSide,         // 수수료가 붙는 쪽
        BigDecimal minNotional,  // 최소 주문 금액(견적 통화)
        BigDecimal quantityStep  // 수량 최소 단위
) {
    // 수수료 부과 방식
    public enum FeeSide {
        QUOTE_SURCHARGE, // 견적 통화에 가산(업비트 KRW 시장가 매수)
        RECEIVED_ASSET   // 받는 자산에서 차감(바이낸스 현물 기본)
    }

    public CryptoTradingRules {
        Objects.requireNonNull(feeRate, "feeRate");
        Objects.requireNonNull(feeSide, "feeSide");
        Objects.requireNonNull(minNotional, "minNotional");
        Objects.requireNonNull(quantityStep, "quantityStep");
        if (quantityStep.signum() <= 0) throw new IllegalArgumentException("quantityStep은 0보다 커야 합니다");
        if (feeRate.signum() < 0) throw new IllegalArgumentException("feeRate는 0 이상이어야 합니다");
        if (minNotional.signum() < 0) throw new IllegalArgumentException("minNotional은 0 이상이어야 합니다");
    }

    // 주문 금액 대비 견적 통화 실지출
    public BigDecimal quoteSpendFor(BigDecimal amount) {
        return feeSide == FeeSide.QUOTE_SURCHARGE ? amount.multiply(BigDecimal.ONE.add(feeRate)) : amount;
    }

    // 지갑으로 낼 수 있는 최대 주문 금액(FLOOR)
    public BigDecimal affordableAmount(BigDecimal cash, int amountScale) {
        return feeSide == FeeSide.QUOTE_SURCHARGE
                ? cash.divide(BigDecimal.ONE.add(feeRate), amountScale, RoundingMode.FLOOR)
                : cash.setScale(amountScale, RoundingMode.FLOOR);
    }

    // 수량 소수 자릿수
    public int quantityScale() {
        // ponytail: 10의 거듭제곱 step만 지원(업비트 1e-8, 바이낸스 BTC 1e-5·ETH 1e-4) — 0.5 같은 step이 나오면 step 배수 내림으로 확장
        return Math.max(0, quantityStep.stripTrailingZeros().scale());
    }
}
