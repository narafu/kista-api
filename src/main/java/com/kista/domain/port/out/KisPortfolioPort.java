package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.PresentBalanceResult;

// KIS 전용 체결기준현재잔고 조회 — CTRP6504R (예수금·환율 미제공 → BrokerStatisticsRouter에서 보정)
public interface KisPortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
