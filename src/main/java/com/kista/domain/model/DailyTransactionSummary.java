package com.kista.domain.model;

import java.math.BigDecimal;

public record DailyTransactionSummary(
        BigDecimal buyAmountFcr,   // 외화매수금액합계 (frcr_buy_amt_smtl)
        BigDecimal sellAmountFcr,  // 외화매도금액합계 (frcr_sll_amt_smtl)
        BigDecimal domesticFee,    // 국내수수료합계 (dmst_fee_smtl)
        BigDecimal overseasFee     // 해외수수료합계 (ovrs_fee_smtl)
) {}
