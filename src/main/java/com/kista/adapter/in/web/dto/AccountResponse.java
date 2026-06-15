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
        @Schema(description = "증권사", example = "KIS")
        String broker
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.id(),
                a.nickname(),
                a.accountNo(),
                a.broker() != null ? a.broker().name() : null
        );
    }
}
