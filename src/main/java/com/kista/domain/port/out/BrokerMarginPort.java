package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 브로커 무관 증거금 조회 — KIS: TTTC2101R / Toss: buying-power API
public interface BrokerMarginPort {
    // 통화별 증거금 전체 조회 (통계·UI 표시용)
    List<MarginItem> getMargin(Account account);
    // USD 매수가능금액 단건 조회 (거래 계산·시드 검증용 — 구현체별 효율적 방법 사용)
    BigDecimal getUsdBuyableAmount(Account account);
}
