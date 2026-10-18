package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.stats.adapter.in.web.dto.CryptoPortfolioResponse;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "통계", description = "계좌별 손익·체결·잔고·증거금·현재가 조회 (KIS/Toss 증권사 자동 분기)")
@RestController
@RequiredArgsConstructor
public class CryptoPortfolioController {

    private final CryptoPortfolioUseCase cryptoPortfolio; // 코인 계좌 포트폴리오 조회

    @Operation(summary = "코인 계좌 포트폴리오 조회", description = "업비트 실잔고 기준 원화 예수금과 코인별 보유·평단·현재가·평가액(KRW). 주식 계좌는 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "코인 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "422", description = "거래소 API 키 오류·IP 미허용·권한 부족"),
            @ApiResponse(responseCode = "503", description = "거래소 API 호출 실패")
    })
    @GetMapping("/api/accounts/{accountId}/crypto-portfolio")
    public CryptoPortfolioResponse getCryptoPortfolio(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return CryptoPortfolioResponse.from(cryptoPortfolio.getCryptoPortfolio(accountId, userId));
    }
}
