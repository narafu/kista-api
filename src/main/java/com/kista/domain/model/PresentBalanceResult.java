package com.kista.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record PresentBalanceResult(
        List<Item> items,              // output1: 종목별 잔고
        BigDecimal totalAssetUsd,      // output3: tot_asst_amt (총자산)
        BigDecimal totalEvalProfit,    // output3: tot_evlu_pfls_amt (총평가손익)
        BigDecimal totalReturnRate     // output3: evlu_erng_rt1 (총수익률 %)
) {
    public record Item(
            String symbol,             // pdno: 종목코드
            int qty,                   // cblc_qty13: 잔고수량
            BigDecimal avgPrice,       // avg_unpr3: 평균단가
            BigDecimal currentPrice,   // ovrs_now_pric1: 현재가
            BigDecimal evalAmountUsd,  // frcr_evlu_amt2: 외화평가금액
            BigDecimal profitLossUsd,  // evlu_pfls_amt2: 평가손익
            BigDecimal profitRate,     // evlu_pfls_rt1: 평가손익율 %
            String exchangeCode        // ovrs_excg_cd: 해외거래소코드
    ) {}
}
