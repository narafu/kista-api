package com.kista.domain.model.broker;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;

public record DailyTransaction(
        String tradeDate,                    // 매매일자 (trad_dt)
        String settlementDate,               // 결제일자 (sttl_dt) — Toss 미제공 시 null
        Order.OrderDirection direction,      // 매도/매수 방향 (sll_buy_dvsn_cd: 01=매도, 02=매수)
        Ticker ticker,                       // 종목코드 (pdno)
        String symbolName,                   // 종목명 (ovrs_item_name)
        int quantity,                        // 체결수량 (ccld_qty)
        BigDecimal price,                    // 해외주식체결단가 (ovrs_stck_ccld_unpr)
        BigDecimal tradeAmountUsd,           // 거래외화금액 (tr_frcr_amt2)
        BigDecimal settlementAmountKrw,      // 원화정산금액 (wcrc_excc_amt) — Toss 미제공 시 null
        BigDecimal exchangeRate,             // 등록환율 (erlm_exrt) — Toss 미제공 시 null
        String currency                      // 통화코드 (crcy_cd)
) {}
