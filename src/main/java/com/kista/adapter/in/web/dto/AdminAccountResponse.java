package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.AccountNumberMasker;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.strategy.Strategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

// 관리자 계좌 목록 응답 DTO — 소유자 닉네임 + 마스킹된 계좌번호 포함
public record AdminAccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "소유자 사용자 ID")
        UUID userId,
        @Schema(description = "소유자 닉네임")
        String ownerNickname,   // User.nickname
        @Schema(description = "마스킹된 계좌번호", example = "****1234")
        String accountNoMasked, // "****1234"
        @Schema(description = "브로커 코드", example = "KIS")
        String broker,          // Broker.name()
        @Schema(description = "계좌에 등록된 전략 목록")
        List<AdminStrategyResponse> strategies
) {
    public static AdminAccountResponse from(Account a, AdminUserView user, List<Strategy> strategies) {
        String nickname = user != null ? user.nickname() : "(알 수 없음)";
        return new AdminAccountResponse(
                a.id(), a.userId(), nickname, AccountNumberMasker.mask(a.accountNo()),
                a.broker() != null ? a.broker().name() : null,
                strategies.stream().map(AdminStrategyResponse::from).toList());
    }
}
