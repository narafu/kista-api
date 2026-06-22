package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

// 브로커 무관 체결기준현재잔고 조회 — KIS/Toss 공통 인터페이스
public interface BrokerPortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
