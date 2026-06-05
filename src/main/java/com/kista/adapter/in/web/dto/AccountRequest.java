package com.kista.adapter.in.web.dto;

import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
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
    public RegisterAccountUseCase.Command toRegisterCommand() {
        return new RegisterAccountUseCase.Command(
                nickname, accountNo, kisAppKey, kisSecretKey, null
        );
    }

    public UpdateAccountUseCase.Command toUpdateCommand() {
        return new UpdateAccountUseCase.Command(nickname);
    }
}
