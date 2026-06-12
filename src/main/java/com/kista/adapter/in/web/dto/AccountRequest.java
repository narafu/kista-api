package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank String nickname,
        @Schema(description = "계좌번호 — KIS 8자리 또는 Toss 11자리", example = "74420614")
        @NotBlank @Pattern(regexp = "\\d{8}|\\d{11}", message = "계좌번호는 8자리(KIS) 또는 11자리(Toss)여야 합니다")
        String accountNo,
        @Schema(description = "API 앱 키 (KIS App Key / Toss Client ID)", example = "PSxxxxxxxxxx")
        String kisAppKey,
        @Schema(description = "API 앱 시크릿 (KIS App Secret / Toss Client Secret)")
        String kisSecretKey,
        @Schema(description = "증권사 — null이면 KIS 기본값 적용", example = "KIS", allowableValues = {"KIS", "TOSS"})
        Account.Broker broker
) {
    public RegisterAccountCommand toRegisterCommand() {
        return new RegisterAccountCommand(
                nickname, accountNo, kisAppKey, kisSecretKey, null, broker
        );
    }

    public UpdateAccountCommand toUpdateCommand() {
        return new UpdateAccountCommand(nickname);
    }
}
