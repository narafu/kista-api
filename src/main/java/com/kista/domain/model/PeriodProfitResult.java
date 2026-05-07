package com.kista.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record PeriodProfitResult(
        List<Item> items,                  // output1: 종목별 손익 목록
        BigDecimal totalRealizedProfit,    // output2: ovrs_rlzt_pfls_tot_amt (총실현손익)
        BigDecimal totalReturnRate         // output2: tot_pftrt (총수익률 %)
) {
    public record Item(
            String tradeDate,              // trad_day: 매매일
            String symbol,                 // ovrs_pdno: 해외상품번호
            int qty,                       // slcl_qty: 매도청산수량
            BigDecimal avgBuyPrice,        // pchs_avg_pric: 매입평균가격
            BigDecimal avgSellPrice,       // avg_sll_unpr: 평균매도단가
            BigDecimal realizedProfit,     // ovrs_rlzt_pfls_amt: 실현손익
            BigDecimal returnRate,         // pftrt: 수익률 %
            String exchangeCode            // ovrs_excg_cd: 해외거래소코드
    ) {}
}
