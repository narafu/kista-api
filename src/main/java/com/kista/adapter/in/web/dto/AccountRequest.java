package com.kista.adapter.in.web.dto;

import com.kista.domain.model.StrategyType;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountRequest(
        @NotBlank String nickname,
        String accountNo,        // 등록 시 필수, 수정 시 무시
        String kisAppKey,        // 등록 시 필수
        String kisSecretKey,     // 등록 시 필수
        String kisAccountType,   // 기본값 "01"
        @NotNull StrategyType strategyType, // 등록 시 필수 (INFINITE or PRIVACY)
        String telegramBotToken, // optional
        String telegramChatId,   // optional
        String symbol,           // 거래 종목 (기본값 "SOXL")
        String exchangeCode      // 해외거래소 코드 (기본값 "AMS")
) {
    public RegisterAccountUseCase.Command toRegisterCommand() {
        return new RegisterAccountUseCase.Command(
                nickname, accountNo, kisAppKey, kisSecretKey,
                kisAccountType, strategyType, telegramBotToken, telegramChatId,
                symbol, exchangeCode
        );
    }

    public UpdateAccountUseCase.Command toUpdateCommand() {
        return new UpdateAccountUseCase.Command(
                nickname, kisAppKey, kisSecretKey, telegramBotToken, telegramChatId,
                symbol, exchangeCode
        );
    }
}
