package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// Toss 전용 증거금 조회 — buying-power API
public interface TosMarginPort {
    // GET /api/v1/buying-power?currency=USD — USD 매수가능금액
    BigDecimal getUsdBuyableAmount(Account account);
    // GET /api/v1/buying-power?currency=USD+KRW — USD·KRW 통화별 매수가능금액 (UI 표시용)
    List<MarginItem> getMargin(Account account);
}
