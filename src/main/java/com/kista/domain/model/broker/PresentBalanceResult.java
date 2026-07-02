package com.kista.domain.model.broker;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
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
}
