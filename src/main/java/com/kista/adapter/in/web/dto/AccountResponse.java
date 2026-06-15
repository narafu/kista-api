package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record AccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        String nickname,
        @Schema(description = "마스킹된 계좌번호", example = "****4614")
        String accountNoMasked,
        @Schema(description = "전체 계좌번호 (본인 계좌, 토글 표시용)", example = "74420614")
        String accountNo,
        @Schema(description = "증권사", example = "KIS")
        String broker
) {
    public static AccountResponse from(Account a) {
        // accountNo가 이미 전체 표시 형식 — KIS: "74420614-01", TOSS: "131-01-001931"
        return new AccountResponse(
                a.id(),
                a.nickname(),
                maskAccountNo(a.accountNo()),
                a.accountNo(),
                a.broker() != null ? a.broker().name() : null
        );
    }

    private static String maskAccountNo(String accountNo) {
        if (accountNo == null) return "****";
        // 하이픈 앞 숫자 부분만 마스킹 (예: "74420614-01" → "****4614-01", "131-01-001931" → "****1931")
        int hyphenIdx = accountNo.indexOf('-');
        String numPart = hyphenIdx > 0 ? accountNo.substring(0, hyphenIdx) : accountNo;
        String suffix = hyphenIdx > 0 ? accountNo.substring(hyphenIdx) : "";
        if (numPart.length() <= 4) return "****" + suffix;
        return "****" + numPart.substring(numPart.length() - 4) + suffix;
    }
}
