package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

// Toss 전용 포트폴리오 조회 — 보유종목+예수금+환율 포함 완전 응답 반환
public interface TossPortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
