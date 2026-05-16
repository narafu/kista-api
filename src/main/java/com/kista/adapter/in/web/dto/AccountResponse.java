package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Account;
import com.kista.domain.model.StrategyType;
import com.kista.domain.model.StrategyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record AccountResponse(
        @Schema(description = "계좌 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        String nickname,
        @Schema(description = "마스킹된 계좌번호 (마지막 4자리만 노출)", example = "****4614")
        String accountNoMasked,
        @Schema(description = "매매 전략", example = "INFINITE")
        StrategyType strategyType,
        @Schema(description = "전략 상태", example = "ACTIVE")
        StrategyStatus strategyStatus,
        @Schema(description = "텔레그램 알림 설정 여부", example = "true")
        boolean hasTelegram,
        @Schema(description = "거래 종목 코드", example = "SOXL")
        String symbol,
        @Schema(description = "해외거래소 코드", example = "AMS")
        String exchangeCode
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
