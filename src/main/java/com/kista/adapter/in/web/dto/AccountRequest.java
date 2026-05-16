package com.kista.adapter.in.web.dto;

import com.kista.domain.model.StrategyType;
import com.kista.domain.model.Ticker;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank String nickname,
        @Schema(description = "KIS 계좌번호 8자리 (등록 시 필수, 수정 시 무시)", example = "74420614")
        String accountNo,
        @Schema(description = "KIS API 앱 키", example = "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxE")
        String kisAppKey,
        @Schema(description = "KIS API 앱 시크릿", example = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
        String kisSecretKey,
        @Schema(description = "KIS 계좌상품코드 (기본값 \"01\")", example = "01")
        String kisAccountType,
        @Schema(description = "매매 전략 (등록 시 필수)", example = "INFINITE")
        @NotNull StrategyType strategyType,
        @Schema(description = "텔레그램 봇 토큰 (선택)", example = "7123456789:AAHxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
        String telegramBotToken,
        @Schema(description = "텔레그램 채팅 ID (선택)", example = "-1001234567890")
        String telegramChatId,
        @Schema(description = "거래 종목 (선택, PRIVACY=SOXL 고정, INFINITE 기본=TQQQ)", example = "TQQQ")
        Ticker ticker
) {
    public RegisterAccountUseCase.Command toRegisterCommand() {
        return new RegisterAccountUseCase.Command(
                nickname, accountNo, kisAppKey, kisSecretKey,
                kisAccountType, strategyType, telegramBotToken, telegramChatId,
                ticker
        );
    }

    public UpdateAccountUseCase.Command toUpdateCommand() {
        return new UpdateAccountUseCase.Command(
                nickname, kisAppKey, kisSecretKey, telegramBotToken, telegramChatId,
                ticker
        );
    }
}
