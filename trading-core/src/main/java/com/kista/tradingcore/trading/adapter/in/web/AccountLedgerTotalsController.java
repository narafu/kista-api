package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.tradingcore.trading.adapter.in.web.dto.AccountLedgerTotalsResponse;
import com.kista.tradingcore.trading.application.usecase.AccountLedgerTotalsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "계좌 원장 합산", description = "계좌 안 전략들의 원장 예수금·평가금·미배정 현금 합산")
@RestController
@RequiredArgsConstructor
public class AccountLedgerTotalsController {

    private final AccountLedgerTotalsUseCase accountLedgerTotals; // 원장 합산 조회

    @Operation(summary = "계좌 원장 합산 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (시세·잔고 조회 실패 필드는 null)"),
            @ApiResponse(responseCode = "403", description = "본인 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌 없음")
    })
    @GetMapping("/api/accounts/{accountId}/ledger-totals")
    public AccountLedgerTotalsResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal UUID userId) {
        return AccountLedgerTotalsResponse.from(accountLedgerTotals.get(accountId, userId));
    }
}
