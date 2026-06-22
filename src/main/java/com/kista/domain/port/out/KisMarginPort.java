package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// KIS 전용 증거금 조회 — TTTC2101R (USD·KRW 두 통화의 증거금 정보 반환)
public interface KisMarginPort {
    // USD·KRW 두 통화의 증거금 정보 반환 (TTTC2101R)
    List<MarginItem> getMargin(Account account);
    // USD 매수가능금액 단건 추출
    BigDecimal getUsdBuyableAmount(Account account);
}
