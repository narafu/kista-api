package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank String nickname,
        @Schema(description = "KIS 계좌번호 8자리 (등록 시 필수)", example = "74420614")
        @NotBlank @Pattern(regexp = "\\d{8}", message = "계좌번호는 8자리 숫자여야 합니다")
        String accountNo,
        @Schema(description = "KIS API 앱 키", example = "PSxxxxxxxxxx")
        String kisAppKey,
        @Schema(description = "KIS API 앱 시크릿")
        String kisSecretKey
) {
    public RegisterAccountCommand toRegisterCommand() {
        return new RegisterAccountCommand(
                nickname, accountNo, kisAppKey, kisSecretKey, null
        );
    }

    public UpdateAccountCommand toUpdateCommand() {
        return new UpdateAccountCommand(nickname);
    }
}
