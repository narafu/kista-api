package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Account;
import com.kista.domain.model.StrategyType;
import com.kista.domain.model.StrategyStatus;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        String nickname,
        String accountNoMasked,   // 마지막 4자리만 노출 (예: ****1234)
        StrategyType strategyType,
        StrategyStatus strategyStatus,
        boolean hasTelegram,
        String symbol,            // 거래 종목 코드
        String exchangeCode       // 해외거래소 코드
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.id(),
                a.nickname(),
                maskAccountNo(a.accountNo()),
                a.strategyType(),
                a.strategyStatus(),
                a.telegramChatId() != null,
                a.symbol(),
                a.exchangeCode()
        );
    }

    private static String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.length() <= 4) return "****";
        return "****" + accountNo.substring(accountNo.length() - 4);
    }
}
