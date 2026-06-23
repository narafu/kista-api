package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

// 체결기준현재잔고 조회 — KIS: CTRP6504R+TTTC2101R 보정 포함 / Toss: 보유종목+예수금 직접 산출
public interface PortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
