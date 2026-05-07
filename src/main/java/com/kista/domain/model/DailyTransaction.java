package com.kista.domain.model;

import java.math.BigDecimal;

public record DailyTransaction(
        String tradeDate,                    // 매매일자 (trad_dt)
        String settlementDate,               // 결제일자 (sttl_dt)
        Order.OrderDirection direction,      // 매도/매수 방향 (sll_buy_dvsn_cd: 01=매도, 02=매수)
        String symbol,                       // 종목코드 (pdno)
        String symbolName,                   // 종목명 (ovrs_item_name)
        int qty,                             // 체결수량 (ccld_qty)
        BigDecimal price,                    // 해외주식체결단가 (ovrs_stck_ccld_unpr)
        BigDecimal tradeAmountUsd,           // 거래외화금액 (tr_frcr_amt2)
        BigDecimal settlementAmountKrw,      // 원화정산금액 (wcrc_excc_amt)
        BigDecimal exchangeRate,             // 등록환율 (erlm_exrt)
        String currency                      // 통화코드 (crcy_cd)
) {}
