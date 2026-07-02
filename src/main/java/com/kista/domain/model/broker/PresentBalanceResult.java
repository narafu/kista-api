package com.kista.domain.model.broker;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record PresentBalanceResult(
        List<Item> items,              // output1: 종목별 잔고
        BigDecimal totalAssetUsd,      // output3: tot_asst_amt (총자산 — KRW)
        BigDecimal totalEvalProfit,    // output3: tot_evlu_pfls_amt (총평가손익 — KRW)
        BigDecimal totalReturnRate,    // output3: evlu_erng_rt1 (총수익률 %)
        BigDecimal usdDepositActual,   // USD 예수금 (TOSS: 실 USD 예수금, KIS: margin 조회값)
        BigDecimal exchangeRateKrwPerUsd  // 환율 (1 USD = ? KRW, TOSS: 실값, KIS: margin 조회값)
) {
    public record Item(
            Ticker ticker,             // pdno: 종목코드
            int holdings,              // cblc_qty13: 잔고수량
            BigDecimal avgPrice,       // avg_unpr3: 평균단가
            BigDecimal currentPrice,   // ovrs_now_pric1: 현재가
            BigDecimal evalAmountUsd,  // frcr_evlu_amt2: 외화평가금액
            BigDecimal profitLossUsd,  // evlu_pfls_amt2: 평가손익
            BigDecimal profitRate,     // evlu_pfls_rt1: 평가손익율 %
            String exchangeCode        // ovrs_excg_cd: 해외거래소코드
    ) {}

    // Toss 어댑터가 추출한 원시 보유 종목 값 — KRW 환산·수익률 계산은 aggregateToss()가 담당
    public record TossHolding(
            Ticker ticker,          // 종목코드
            int holdings,           // 보유 수량
            BigDecimal avgPrice,    // 평균 매입가 (USD)
            BigDecimal currentPrice // 현재가 (USD)
    ) {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // Toss 전용 집계 팩토리 — 종목별 평가·손익·수익률 및 KRW 환산 총자산·총수익률 계산 (KIS는 API가 총계를 내려줌)
    public static PresentBalanceResult aggregateToss(
            List<TossHolding> holdings,
            BigDecimal usdDeposit,
            BigDecimal krwDeposit,
            BigDecimal rate
    ) {
        // 종목별 외화평가금액·평가손익·수익률 산출
        List<Item> items = holdings.stream()
                .map(h -> {
                    BigDecimal evalAmountUsd = h.currentPrice().multiply(BigDecimal.valueOf(h.holdings()))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profitLossUsd = h.currentPrice().subtract(h.avgPrice())
                            .multiply(BigDecimal.valueOf(h.holdings()))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profitRate = h.avgPrice().compareTo(BigDecimal.ZERO) > 0
                            ? h.currentPrice().subtract(h.avgPrice())
                              .divide(h.avgPrice(), 4, RoundingMode.HALF_UP)
                              .multiply(HUNDRED)
                              .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new Item(
                            h.ticker(), h.holdings(), h.avgPrice(), h.currentPrice(),
                            evalAmountUsd, profitLossUsd, profitRate, "AMEX"
                    );
                })
                .toList();

        // KRW 기준 합산
        BigDecimal totalEvalUsd = items.stream().map(Item::evalAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfitUsd = items.stream().map(Item::profitLossUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPurchaseUsd = items.stream()
                .map(item -> item.avgPrice().multiply(BigDecimal.valueOf(item.holdings())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean hasRate = rate.compareTo(BigDecimal.ZERO) > 0;
        // totalAssetKrw = (포지션USD + USD예수금) × 환율 + KRW예수금
        BigDecimal totalAssetKrw = hasRate
                ? totalEvalUsd.add(usdDeposit).multiply(rate).add(krwDeposit)
                  .setScale(0, RoundingMode.HALF_UP)
                : krwDeposit.setScale(0, RoundingMode.HALF_UP);
        // totalEvalProfitKrw = 평가손익USD × 환율
        BigDecimal totalEvalProfitKrw = hasRate
                ? totalProfitUsd.multiply(rate).setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // totalReturnRate = 평가손익KRW / 매입금액KRW × 100
        BigDecimal totalPurchaseKrw = hasRate
                ? totalPurchaseUsd.multiply(rate).setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalReturnRate = totalPurchaseKrw.compareTo(BigDecimal.ZERO) > 0
                ? totalEvalProfitKrw.divide(totalPurchaseKrw, 4, RoundingMode.HALF_UP)
                  .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal krwAsUsd = hasRate
                ? krwDeposit.divide(rate, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalUsdDeposit = usdDeposit.add(krwAsUsd).setScale(2, RoundingMode.HALF_UP);
        return new PresentBalanceResult(items, totalAssetKrw, totalEvalProfitKrw, totalReturnRate, totalUsdDeposit, rate);
    }
}
